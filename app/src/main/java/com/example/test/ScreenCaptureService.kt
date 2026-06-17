
package com.example.test

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import java.io.File
import java.io.FileOutputStream

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screen_capture_channel"
        @JvmStatic
        var latestImagePath: String? = null
            private set
        @JvmStatic
        var latestImageSaveTime: Long = 0
            private set
        @JvmStatic
        var imageVersion: Long = 0
            private set
    }

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var isRunning = false

    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // 缓存 mask，避免每次从 assets 重新加载
    private var cachedMaskRegion: Bitmap? = null
    private var cachedMaskHeight: Int = 0

    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "MediaProjection stopped")
            stopCapture()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate called")

        handlerThread = HandlerThread("ScreenCaptureBackground")
        handlerThread?.start()
        backgroundHandler = Handler(handlerThread!!.looper)

        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called, intent: $intent")

        var resultCode = -100 // 使用一个不常用的默认值
        var data: Intent? = null

        val extras = intent?.extras
        if (extras != null) {
            // 直接从 extras Bundle 中获取，因为日志显示它们就在这里
            resultCode = extras.getInt("resultCode", -100)
            data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras.getParcelable("data", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                extras.getParcelable("data")
            }
        }

        // 如果 Bundle 里没有，再尝试从 Intent 顶级获取
        if (resultCode == -100) {
            resultCode = intent?.getIntExtra("resultCode", -100) ?: -100
        }
        if (data == null) {
            data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra("data", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra("data")
            }
        }

        Log.d(TAG, "Final extracted resultCode: $resultCode, data: $data")

        // 截屏权限成功的 resultCode 通常是 -1 (Activity.RESULT_OK)
        if (resultCode != -100 && data != null) {
            Log.d(TAG, "Starting screen capture process")
            startScreenCapture(resultCode, data)
        } else {
            Log.w(TAG, "Failed to get resultCode or data. Keys: ${extras?.keySet()}")
        }

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "屏幕截取",
                NotificationManager.IMPORTANCE_LOW
            )

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("GoClick 正在运行")
            .setContentText("正在截取屏幕...")
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .build()
    }

    private fun startScreenCapture(resultCode: Int, data: Intent) {
        if (isRunning) return

        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mediaProjectionManager.getMediaProjection(resultCode, data)
        if (mp == null) {
            Log.e(TAG, "Failed to get MediaProjection")
            stopSelf()
            return
        }

        isRunning = true
        mediaProjection = mp
        mediaProjection?.registerCallback(mediaProjectionCallback, backgroundHandler)

        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        imageReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            2
        )

        mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            screenWidth,
            screenHeight,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )

        backgroundHandler?.postDelayed({ captureFrame() }, 1000)
    }

    private fun captureFrame() {
        if (!isRunning) return

        try {
            val image: Image? = imageReader?.acquireLatestImage()
            if (image == null) {
                scheduleNextCapture()
                return
            }

            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val width = image.width
            val height = image.height

            // Calculate actual width including padding
            val fullWidth = rowStride / pixelStride

            val bitmap = Bitmap.createBitmap(fullWidth, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            // Crop to actual screen dimensions
            val croppedBitmap = if (fullWidth > width) {
                Bitmap.createBitmap(bitmap, 0, 0, width, height)
            } else {
                bitmap
            }

            // 用 mask.jpg 上方 5% 区域遮罩截图 - 优化版
            try {
                // 缓存 mask 区域，只加载一次
                if (cachedMaskRegion == null) {
                    val maskBitmap = BitmapFactory.decodeStream(assets.open("mask.jpg"))
                    cachedMaskHeight = (maskBitmap.height * 0.05).toInt()
                    cachedMaskRegion = Bitmap.createBitmap(maskBitmap, 0, 0, maskBitmap.width, cachedMaskHeight)
                    maskBitmap.recycle()
                }

                // 直接在 croppedBitmap 上绘制，避免创建新 Bitmap
                val canvas = Canvas(croppedBitmap)
                // 将 mask 缩放到截图宽度并绘制在上方
                val scaledMask = Bitmap.createScaledBitmap(cachedMaskRegion!!, croppedBitmap.width, cachedMaskHeight, true)
                canvas.drawBitmap(scaledMask, 0f, 0f, null)
                scaledMask.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply mask", e)
            }

            val imageFile = File(filesDir, "latest_screenshot.jpg")
            val fos = FileOutputStream(imageFile)
            croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
            fos.close()

            latestImagePath = imageFile.absolutePath
            latestImageSaveTime = System.currentTimeMillis()
            imageVersion++
            Log.d(TAG, "Screenshot saved: $latestImagePath, version: $imageVersion")

            if (croppedBitmap != bitmap) {
                bitmap.recycle()
            }
            croppedBitmap.recycle()

        } catch (e: Exception) {
            Log.e(TAG, "Capture failed", e)
        }

        scheduleNextCapture()
    }

    private fun scheduleNextCapture() {
        if (isRunning) {
            backgroundHandler?.postDelayed({ captureFrame() }, 3000)
        }
    }

    fun stopCapture() {
        if (!isRunning) return
        isRunning = false
        backgroundHandler?.removeCallbacksAndMessages(null)

        mediaProjection?.unregisterCallback(mediaProjectionCallback)
        mediaProjection?.stop()
        mediaProjection = null

        imageReader?.close()
        imageReader = null

        // 清理 mask 缓存
        cachedMaskRegion?.recycle()
        cachedMaskRegion = null

        handlerThread?.quitSafely()
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

