
package com.example.test
import ai.onnxruntime.providers.NNAPIFlags
import java.util.EnumSet
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GoClickInference(private val context: Context) {

    private var ortEnv: OrtEnvironment? = null
    private var visionSession: OrtSession? = null
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var tokenizer: HuggingFaceTokenizer? = null

    private var isInitialized = false

    private val imageSize = 768

    private val imageMean = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
    private val imageStd = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)

    private val decoderStartTokenId = 2L
    private val bosTokenId = 0L
    private val eosTokenId = 2L
    private val locTokenBase = 50269

    companion object {
        private const val TAG = "GoClickInference"
    }

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d(TAG, "正在初始化纯 CPU 极限加速架构...")

            ortEnv = OrtEnvironment.getEnvironment()

            val cpuOptions = OrtSession.SessionOptions()
            cpuOptions.setIntraOpNumThreads(4)

            val modelDir = copyAssetsToFiles()

            android.util.Log.d(TAG, "正在加载 HuggingFace Tokenizer...")
            tokenizer = HuggingFaceTokenizer.newInstance(modelDir.toPath(), emptyMap())

            android.util.Log.d(TAG, "正在加载 vision_encoder.onnx...")
            visionSession = ortEnv!!.createSession(File(modelDir, "vision_encoder_int8.onnx").absolutePath, cpuOptions)

            android.util.Log.d(TAG, "正在加载 encoder_model.onnx...")
            encoderSession = ortEnv!!.createSession(File(modelDir, "encoder_model_int8.onnx").absolutePath, cpuOptions)

            android.util.Log.d(TAG, "正在加载 decoder_model.onnx...")
            decoderSession = ortEnv!!.createSession(File(modelDir, "decoder_model_int8.onnx").absolutePath, cpuOptions)

            isInitialized = true
            android.util.Log.d(TAG, "模型部署成功！")
            true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "初始化失败", e)
            false
        }
    }

    private suspend fun copyAssetsToFiles(): File = withContext(Dispatchers.IO) {
        val filesDir = File(context.filesDir, "goclick_models")
        if (!filesDir.exists()) {
            filesDir.mkdirs()
        }

        val assetManager = context.assets
        val assetFiles = listOf(
            "vision_encoder_int8.onnx", "encoder_model_int8.onnx", "decoder_model_int8.onnx",
            "vocab.json", "tokenizer.json", "tokenizer_config.json", "special_tokens_map.json"
        )

        for (fileName in assetFiles) {
            val outFile = File(filesDir, fileName)
            if (outFile.exists()) {
                outFile.delete()
            }

            android.util.Log.d(TAG, "Copying $fileName...")
            try {
                val inputStream = assetManager.open(fileName)
                val outputStream = FileOutputStream(outFile)
                inputStream.copyTo(outputStream)
                inputStream.close()
                outputStream.close()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to copy $fileName", e)
            }
        }

        filesDir
    }

    private fun preprocessImage(bitmap: Bitmap): java.nio.FloatBuffer {
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, imageSize, imageSize, true)

        val pixels = IntArray(imageSize * imageSize)
        resizedBitmap.getPixels(pixels, 0, imageSize, 0, 0, imageSize, imageSize)

        val floatBuffer = ByteBuffer.allocateDirect(1 * 3 * imageSize * imageSize * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        val mean0 = imageMean[0]
        val mean1 = imageMean[1]
        val mean2 = imageMean[2]
        val std0 = imageStd[0]
        val std1 = imageStd[1]
        val std2 = imageStd[2]

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val value = ((pixel shr 16) and 0xFF) / 255.0f
            floatBuffer.put((value - mean0) / std0)
        }

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val value = ((pixel shr 8) and 0xFF) / 255.0f
            floatBuffer.put((value - mean1) / std1)
        }

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val value = (pixel and 0xFF) / 255.0f
            floatBuffer.put((value - mean2) / std2)
        }

        floatBuffer.rewind()
        return floatBuffer
    }

    private fun extractCoordinates(generatedIds: List<Long>, originalWidth: Int, originalHeight: Int): Pair<Float, Float>? {
        val locs = mutableListOf<Int>()
        for (id in generatedIds) {
            if (id >= locTokenBase) {
                val loc = (id - locTokenBase).toInt()
                locs.add(loc)
                if (locs.size >= 2) break
            }
        }

        if (locs.size >= 2) {
            val xBin = locs[0]
            val yBin = locs[1]
            val x = (xBin + 0.5f) * (originalWidth.toFloat() / 1000.0f)
            val y = (yBin + 0.5f) * (originalHeight.toFloat() / 1000.0f)
            return Pair(x, y)
        }
        return null
    }

    suspend fun predict(imagePath: String, query: String): PredictionResult = withContext(Dispatchers.IO) {
        if (!isInitialized || tokenizer == null) {
            return@withContext PredictionResult(false, 0f, 0f, "Model not initialized")
        }

        try {
            val bitmap = BitmapFactory.decodeFile(imagePath)
                ?: return@withContext PredictionResult(false, 0f, 0f, "Failed to load image")
            val totalStart = System.currentTimeMillis()
            val originalWidth = bitmap.width
            val originalHeight = bitmap.height

            val saveTime = ScreenCaptureService.latestImageSaveTime
            val nowTime = System.currentTimeMillis()
            android.util.Log.d("GoClickTiming", "⏱️ 从截图保存到开始Preprocessing: ${nowTime - saveTime} ms")

            android.util.Log.d(TAG, "Preprocessing image...")
            val pixelValues = preprocessImage(bitmap)
            pixelValues.rewind()

            android.util.Log.d(TAG, "开始运行 vision_encoder...")
            val vStart = System.currentTimeMillis()
            val imgShape = longArrayOf(1, 3, imageSize.toLong(), imageSize.toLong())
            val imgInput = OnnxTensor.createTensor(ortEnv, pixelValues, imgShape)
            val visionInputs = mutableMapOf<String, OnnxTensor>()
            visionInputs["pixel_values"] = imgInput
            val visionOutputs = visionSession!!.run(visionInputs)
            val imageFeatures = visionOutputs.get(0) as OnnxTensor
            val vTime = System.currentTimeMillis() - vStart
            android.util.Log.d(TAG, "⏱️ 【性能打点】1. Vision Encoder 耗时: $vTime ms")

            val prompt = "I want to $query. Please locate the target element I should interact with. (Output the center coordinates of the target)"
            android.util.Log.d(TAG, "Prompt: $prompt")

            val tokenized = tokenizer!!.encode(prompt)
            val inputIdsArray = tokenized.ids.map { it.toLong() }.toLongArray()
            val attentionMaskArray = tokenized.attentionMask.map { it.toLong() }.toLongArray()

            val inputIdsBuffer = ByteBuffer.allocateDirect(1 * inputIdsArray.size * 8)
                .order(ByteOrder.nativeOrder()).asLongBuffer()
            inputIdsBuffer.put(inputIdsArray)
            inputIdsBuffer.rewind()

            val attentionMaskBuffer = ByteBuffer.allocateDirect(1 * attentionMaskArray.size * 8)
                .order(ByteOrder.nativeOrder()).asLongBuffer()
            attentionMaskBuffer.put(attentionMaskArray)
            attentionMaskBuffer.rewind()

            android.util.Log.d(TAG, "开始运行 encoder_model...")
            val eStart = System.currentTimeMillis()
            val encoderInputs = mutableMapOf<String, OnnxTensor>()
            encoderInputs["input_ids"] = OnnxTensor.createTensor(ortEnv, inputIdsBuffer, longArrayOf(1, inputIdsArray.size.toLong()))
            encoderInputs["image_features"] = imageFeatures

            val encoderInputNames = encoderSession!!.inputNames
            if ("attention_mask" in encoderInputNames) {
                encoderInputs["attention_mask"] = OnnxTensor.createTensor(ortEnv, attentionMaskBuffer, longArrayOf(1, attentionMaskArray.size.toLong()))
            }

            val encoderOutputs = encoderSession!!.run(encoderInputs)
            val encoderHiddenStates = encoderOutputs.get(0) as OnnxTensor
            val eTime = System.currentTimeMillis() - eStart
            android.util.Log.d(TAG, "⏱️ 【性能打点】2. Encoder 耗时: $eTime ms")

            android.util.Log.d(TAG, "开始运行 decoder 自回归生成...")
            val dStart = System.currentTimeMillis()
            val generatedTokens = mutableListOf<Long>()
            generatedTokens.add(bosTokenId)

            val maxTokens = 30

            for (step in 0 until maxTokens) {
                val currentDecoderInputIds = LongArray(generatedTokens.size + 1)
                currentDecoderInputIds[0] = decoderStartTokenId
                for (i in generatedTokens.indices) {
                    currentDecoderInputIds[i + 1] = generatedTokens[i]
                }

                val decoderInputBuffer = ByteBuffer.allocateDirect(1 * currentDecoderInputIds.size * 8)
                    .order(ByteOrder.nativeOrder()).asLongBuffer()
                decoderInputBuffer.put(currentDecoderInputIds)
                decoderInputBuffer.rewind()

                val decoderInputs = mutableMapOf<String, OnnxTensor>()
                decoderInputs["decoder_input_ids"] = OnnxTensor.createTensor(ortEnv, decoderInputBuffer, longArrayOf(1, currentDecoderInputIds.size.toLong()))
                decoderInputs["encoder_hidden_states"] = encoderHiddenStates

                val decoderOutputs = decoderSession!!.run(decoderInputs)
                val logits = decoderOutputs.get(0).value as Array<Array<FloatArray>>

                val vocabSize = logits[0][0].size
                var maxVal = -Float.MAX_VALUE
                var nextTokenId = 0L
                for (i in 0 until vocabSize) {
                    if (logits[0][logits[0].size - 1][i] > maxVal) {
                        maxVal = logits[0][logits[0].size - 1][i]
                        nextTokenId = i.toLong()
                    }
                }

                if (nextTokenId == eosTokenId) {
                    break
                }

                generatedTokens.add(nextTokenId)
            }
            val dTime = System.currentTimeMillis() - dStart
            val totalInferenceTime = System.currentTimeMillis() - totalStart

            android.util.Log.d(TAG, "⏱️ 【性能打点】3. Decoder 总耗时: $dTime ms")
            android.util.Log.d(TAG, "⏱️ 【性能打点】🔥 核心推理总耗时: $totalInferenceTime ms")

            val coordinates = extractCoordinates(generatedTokens, originalWidth, originalHeight)

            if (coordinates != null) {
                android.util.Log.d(TAG, "Found coordinates: x=${coordinates.first}, y=${coordinates.second}")
                PredictionResult(true, coordinates.first, coordinates.second, null)
            } else {
                android.util.Log.w(TAG, "No coordinates found!")
                PredictionResult(false, 0f, 0f, "No coordinates found in output")
            }

        } catch (e: Exception) {
            android.util.Log.e(TAG, "Inference failed", e)
            PredictionResult(false, 0f, 0f, e.message ?: "Unknown error")
        }
    }

    data class PredictionResult(
        val success: Boolean,
        val x: Float,
        val y: Float,
        val error: String?
    )
}

