package com.example.test

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.test.ui.theme.TestTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var goClickInference: GoClickInference
    private var isRunning by mutableStateOf(false)
    private var statusText by mutableStateOf("未开始")
    private var resultText by mutableStateOf("等待开始...")
    private var isModelLoaded by mutableStateOf(false)
    private var screenWidth by mutableStateOf(0)
    private var screenHeight by mutableStateOf(0)
    private var buttonCenterX by mutableStateOf(0f)
    private var buttonCenterY by mutableStateOf(0f)

    private val mediaProjectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "需要通知权限", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        goClickInference = GoClickInference(this)

        setContent {
            TestTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        modifier = Modifier.padding(innerPadding),
                        isRunning = isRunning,
                        statusText = statusText,
                        resultText = resultText,
                        isModelLoaded = isModelLoaded,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                        onStartClick = { checkPermissionsAndStart() },
                        onStopClick = { stopCapture() },
                        onLoadModelClick = { loadModel() },
                        onButtonPositionUpdate = { x, y ->
                            buttonCenterX = x
                            buttonCenterY = y
                        }
                    )
                }
            }
        }

        checkNotificationPermission()

        // 获取屏幕尺寸
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        android.util.Log.d("MainActivity", "onActivityResult requestCode: $requestCode, resultCode: $resultCode, data: $data")

        if (requestCode == REQUEST_SCREEN_CAPTURE && data != null) {
            val extras = data.extras
            if (extras != null) {
                android.util.Log.d("MainActivity", "=== Extras contained ===")
                for (key in extras.keySet()) {
                    val value = extras.get(key)
                    android.util.Log.d("MainActivity", "Key: $key = $value")
                }
            }

            android.util.Log.d("MainActivity", "Data not null, ignoring resultCode and trying anyway")
            startCaptureService(RESULT_OK, data)
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun loadModel() {
        statusText = "正在加载模型..."
        lifecycleScope.launch {
            val success = goClickInference.initialize()
            isModelLoaded = success
            if (success) {
                statusText = "模型加载成功"
            } else {
                statusText = "模型加载失败"
            }
        }
    }

    private fun checkPermissionsAndStart() {
        if (!isModelLoaded) {
            Toast.makeText(this, "请先加载模型", Toast.LENGTH_SHORT).show()
            return
        }

        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        @Suppress("DEPRECATION")
        startActivityForResult(captureIntent, REQUEST_SCREEN_CAPTURE)
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        android.util.Log.d("MainActivity", "startCaptureService called with resultCode: $resultCode")

        val stopIntent = Intent(this, ScreenCaptureService::class.java)
        stopService(stopIntent)

        val serviceIntent = Intent(this, ScreenCaptureService::class.java)
        serviceIntent.putExtra("resultCode", resultCode)
        serviceIntent.putExtra("data", data)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        isRunning = true
        statusText = "正在运行..."

        startPredictionLoop()
    }

    private fun stopCapture() {
        val serviceIntent = Intent(this, ScreenCaptureService::class.java)
        stopService(serviceIntent)

        isRunning = false
        statusText = "已停止"
    }

    private fun startPredictionLoop() {
        lifecycleScope.launch {
            var lastProcessedVersion = -1L
            while (isRunning) {
                val imagePath = ScreenCaptureService.latestImagePath
                val currentVersion = ScreenCaptureService.imageVersion
                if (imagePath != null && currentVersion != lastProcessedVersion) {
                    lastProcessedVersion = currentVersion
                    val startTime = System.currentTimeMillis()
                    android.util.Log.d("GoClickTiming", "开始推理，图片版本: $currentVersion")

                    val result = goClickInference.predict(imagePath, "点击搜索框")

                    val endTime = System.currentTimeMillis()
                    val inferenceTime = endTime - startTime
                    android.util.Log.d("GoClickTiming", "推理完成，耗时: $inferenceTime ms")

                    if (result.success) {
                        resultText = "预测: (${result.x.toInt()}, ${result.y.toInt()})\n实际: (${buttonCenterX.toInt()}, ${buttonCenterY.toInt()})\n耗时: ${inferenceTime}ms"
                    } else {
                        resultText = "未找到: ${result.error}"
                    }
                }
                delay(3000)
            }
        }
    }

    companion object {
        private const val REQUEST_SCREEN_CAPTURE = 1001
    }
}

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    isRunning: Boolean,
    statusText: String,
    resultText: String,
    isModelLoaded: Boolean,
    screenWidth: Int,
    screenHeight: Int,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onLoadModelClick: () -> Unit,
    onButtonPositionUpdate: (Float, Float) -> Unit
) {
    val buttonCoordinates = remember { mutableStateOf<Pair<Float, Float>?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "GoClick 测试",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "屏幕尺寸",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                )
                Text(
                    text = "${screenWidth} × ${screenHeight} 像素",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                if (buttonCoordinates.value != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "开始按钮中心",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                    )
                    Text(
                        text = "(${buttonCoordinates.value!!.first.toInt()}, ${buttonCoordinates.value!!.second.toInt()})",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "状态",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "检测结果",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                )
                Text(
                    text = resultText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onLoadModelClick,
                enabled = !isModelLoaded
            ) {
                Text("加载模型")
            }

            Button(
                onClick = onStartClick,
                enabled = !isRunning && isModelLoaded,
                modifier = Modifier.onGloballyPositioned { coordinates ->
                    val positionInRoot = coordinates.positionInRoot()
                    val size = coordinates.size
                    val centerX = positionInRoot.x + size.width / 2f
                    val centerY = positionInRoot.y + size.height / 2f
                    buttonCoordinates.value = Pair(centerX, centerY)
                    onButtonPositionUpdate(centerX, centerY)
                }
            ) {
                Text("开始")
            }

            Button(
                onClick = onStopClick,
                enabled = isRunning
            ) {
                Text("停止")
            }
        }
    }
}
