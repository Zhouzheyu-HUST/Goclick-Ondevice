# GoClick - On-Device GUI Model

一个在Android设备上运行的GUI元素定位模型应用，基于Florence-2架构。

本项目来自**华中科技大学计算机学院何强老师课题组**。

## 特性

- 📱 **完全本地运行**: 所有推理都在设备上完成，无需网络连接
- 🚀 **ONNX Runtime加速**: 使用ONNX Runtime进行高效推理
- 🎯 **实时屏幕捕获**: 通过MediaProjection实时捕获屏幕并进行分析
- 💬 **自然语言交互**: 使用自然语言描述要定位的界面元素

## 模型下载

模型文件托管在Hugging Face上：

👉 **[https://huggingface.co/ThreeLucky/Goclick-Ondevice](https://huggingface.co/ThreeLucky/Goclick-Ondevice)**

## 配置说明

### 1. 下载模型文件

从Hugging Face下载以下文件并放置在 `app/src/main/assets/` 目录下：

```
app/src/main/assets/
├── vision_encoder_int8.onnx（可以选用不同的量化版本）
├── encoder_model_int8.onnx（可以选用不同的量化版本）
├── decoder_model_int8.onnx（可以选用不同的量化版本）
├── vocab.json
├── tokenizer.json
├── tokenizer_config.json
├── special_tokens_map.json
└── mask.jpg
```

### 2. 构建应用

使用Android Studio打开项目，或使用命令行构建：

```bash
./gradlew build
```

## 使用说明

1. 启动应用
2. 点击 **"加载模型"** 按钮加载模型
3. 点击 **"开始"** 按钮开始屏幕捕获和推理
4. 应用会每3秒分析一次屏幕，尝试定位用户指定的界面元素

## 技术栈

- **框架**: Android (Jetpack Compose)
- **ML引擎**: ONNX Runtime
- **模型**: Florence-2 (量化为INT8)
- **语言**: Kotlin

## 项目结构

```
app/src/main/java/com/example/test/
├── MainActivity.kt           # 主界面和控制逻辑
├── GoClickInference.kt       # 模型推理引擎
├── ScreenCaptureService.kt   # 屏幕捕获服务
└── ui/theme/                 # UI主题配置
```

## 性能

- 模型量化: INT8
- 推理模式: CPU (4线程)
- 屏幕分辨率: 自适应
- 推理间隔: 3秒 (可配置)

## 权限

应用需要以下权限：

- `FOREGROUND_SERVICE`: 前台服务
- `FOREGROUND_SERVICE_MEDIA_PROJECTION`: 屏幕捕获
- `POST_NOTIFICATIONS`: 显示通知

## License

MIT License
