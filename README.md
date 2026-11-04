# 🎙️ OfflineWhisper — 100% On-Device Offline Speech-to-Text for Android

[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-blue.svg?logo=kotlin)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-M3-4285F4.svg?logo=android)](https://developer.android.com/jetpack/compose)
[![NDK](https://img.shields.io/badge/Android%20NDK-r26b-brightgreen.svg?logo=android)](https://developer.android.com/ndk)
[![Whisper.cpp](https://img.shields.io/badge/whisper.cpp-1.7.4-orange.svg)](https://github.com/ggml-org/whisper.cpp)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Offline](https://img.shields.io/badge/Offline-100%25%20On--Device-success.svg)](https://github.com)

**OfflineWhisper** is a high-performance, privacy-first Android application built with **Kotlin** and **Jetpack Compose** that runs OpenAI's Whisper speech-to-text models **100% locally on your phone's CPU** via native C++ **whisper.cpp** (JNI/NDK).

Once a model file (`ggml-tiny.bin` or `ggml-base.bin`) is loaded, the app requires **ZERO internet connection** and functions completely in **Airplane Mode**. No audio ever leaves your device.

---

## 📱 Tailored for Budget Phones (e.g. Oppo A15s)

Designed to run smoothly on devices with moderate specifications:
- **Target Device Profile**: Oppo A15s (4GB RAM, MediaTek Helio P35 Octa-core CPU, Android 10+).
- **ARM64 FP16 Vector Acceleration**: Utilizes ARMv8.2-A FP16 arithmetic instructions when supported by the hardware for maximum transcription throughput.
- **Optimized Multi-Threading**: Defaults to 4 CPU threads on 8-core CPUs to balance fast transcription with minimal thermal throttling and battery drain.
- **Model Recommendation**:
  - ⚡ **`ggml-tiny.bin` (~39 MB)**: Ultra-fast inference with minimal memory footprint. Recommended for quick voice notes on the Oppo A15s.
  - 🎯 **`ggml-base.bin` (~142 MB)**: Higher accuracy and better recognition for complex vocabulary, multiple speakers, and varied accents.

---

## ✨ Features

- 🎙️ **Live Audio Recording**: Records crisp 16kHz mono 16-bit PCM using low-latency `AudioRecord`.
- 🌊 **Real-Time Audio Waveform**: Animated visualizer that reacts to the speaker's vocal amplitude in real time.
- ⚡ **Live Partial Transcription**: Provides streaming transcript previews while you speak, finalizing into a complete transcript when stopped.
- 📁 **Offline File Transcription**: Transcribe existing audio recordings (`.wav`, `.mp3`, `.m4a`, `.aac`, `.ogg`) stored on your device.
- 🌐 **Multilingual Recognition**: Supports automatic language detection as well as dedicated language selection for **Urdu (اردو)**, **English**, **Hindi (हिन्दी)**, **Arabic (العربية)**, **Spanish**, **French**, **German**, **Turkish**, and more.
- ⏱️ **Timestamp Toggling**: Easily enable or disable timestamp headers (`[00:01.000 --> 00:03.500]`).
- 📥 **Integrated Model Manager**:
  - One-click downloader directly from Hugging Face with live download percentage and speed metrics.
  - **Offline Storage Import**: Import any `.bin` model file directly from your phone's Downloads folder without connecting to the internet.
- 📋 **Quick Actions**: One-tap Copy to clipboard, system Share sheet to send text to WhatsApp or Notes, and Clear functionality.

---

## 🛠️ How to Build the APK

### Prerequisites
- **Android Studio Ladybug (2024.2+)** or command-line Gradle.
- **Android SDK Platform 36** (minSdk 24, targetSdk 36).
- **Android NDK 26.1.10909125** and **CMake 3.22.1+**.

### 1. Build via Terminal (Command Line)
To build a debug APK immediately:

```bash
# Clone the repository
git clone https://github.com/<your-username>/OfflineWhisper.git
cd OfflineWhisper

# Build the Debug APK
gradle :app:assembleDebug
```

The generated APK file will be located at:
```
app/build/outputs/apk/debug/app-debug.apk
```

### 2. Build via Android Studio
1. Open Android Studio and choose **Open**, then select the project folder.
2. Wait for Gradle to sync dependencies and configure CMake.
3. In the top toolbar, select the `app` configuration.
4. Click **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
5. Click **locate** in the notification window to find your APK.

---

## 📲 How to Install the APK on Your Mobile Phone

1. **Transfer the APK to your phone**:
   - Send `app-debug.apk` via USB cable, Google Drive, WhatsApp, or Telegram to your phone.
2. **Enable Unknown Sources**:
   - Go to phone **Settings > Security / Privacy > Install Unknown Apps** and allow your file manager or browser.
3. **Install and Launch**:
   - Tap `app-debug.apk` and press **Install**.
   - Launch **OfflineWhisper** from your home screen.

---

## 🤖 Models Download (Hugging Face)

If you prefer to download the model files manually on your computer and transfer them to your phone:

| Model | Size | Speed (Oppo A15s) | Best For | Direct Download Link |
|---|---|---|---|---|
| **ggml-tiny.bin** | ~39 MB | ⚡ ~0.5x realtime | Fast notes, lower RAM | [Download Tiny](https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin) |
| **ggml-base.bin** | ~142 MB | 🎯 ~1.2x realtime | High accuracy, Urdu/English | [Download Base](https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin) |
| **ggml-tiny.en.bin** | ~39 MB | ⚡ Ultra-fast | English only | [Download Tiny.en](https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin) |

> **How to Import manually in the app**:
> 1. Download the `.bin` file to your phone's storage.
> 2. In OfflineWhisper, tap the **Settings (Tune)** icon in the top right.
> 3. Tap **"Import .bin Model from Phone Storage"** and pick your file.
> 4. You can now use the app in complete **Airplane Mode**!

---

## 📖 اردو رہنمائی (Urdu Guide)

**OfflineWhisper** ایک جدید اور مکمل آف لائن اینڈرائیڈ ایپ ہے جو آپ کی آواز کو متن (Speech-to-Text) میں تبدیل کرتی ہے۔

- **انٹرنیٹ کی ضرورت نہیں**: ایک بار ماڈل ڈاؤن لوڈ یا امپورٹ کرنے کے بعد یہ ایپ بنا انٹرنیٹ کے کام کرتی ہے۔
- **Oppo A15s کے لیے بہترین**: اس فون پر کم ریم (4GB) ہونے کی وجہ سے `ggml-tiny.bin` سب سے تیز چلتا ہے، جبکہ `ggml-base.bin` اردو اور انگریزی دونوں میں زیادہ درست نتائج فراہم کرتا ہے۔
- **ماڈل کیسے شامل کریں؟**:
  1. ایپ کھولیں، اوپر سیٹنگز بٹن دبائیں۔
  2. "Download" دبائیں یا اپنے موبائل اسٹوریج سے پہلے سے ڈاؤن لوڈ کردہ `.bin` فائل امپورٹ کریں۔
  3. ریکارڈ کا بٹن دبائیں اور بولیں، لائیو ٹرانسکرپٹ سامنے آ جائے گا!

---

## 🏛️ Project Architecture

```
OfflineWhisper/
├── app/
│   ├── src/main/
│   │   ├── cpp/whisper/            # Native C++ whisper.cpp & GGML engine
│   │   │   ├── CMakeLists.txt      # CMake build script for arm64-v8a & x86_64
│   │   │   ├── jni.c               # JNI bridge functions for Android
│   │   │   ├── ggml/               # Tensor computation framework
│   │   │   └── src/                # Whisper core C++ source
│   │   ├── java/
│   │   │   ├── com/whispercpp/whisper/
│   │   │   │   ├── LibWhisper.kt         # JNI bindings and WhisperContext
│   │   │   │   └── WhisperCpuConfig.kt   # CPU thread optimization
│   │   │   └── com/example/
│   │   │       ├── MainActivity.kt       # Edge-to-edge entry point
│   │   │       ├── audio/
│   │   │       │   ├── AudioRecordEngine.kt  # 16kHz PCM audio recorder
│   │   │       │   └── AudioFileUtils.kt     # WAV & audio format decoders
│   │   │       ├── model/
│   │   │       │   ├── ModelManager.kt   # Model downloader & storage
│   │   │       │   └── WhisperEngine.kt  # Inference lifecycle & executor
│   │   │       └── ui/
│   │   │           ├── MainScreen.kt     # Main Jetpack Compose screen
│   │   │           ├── MainViewModel.kt  # Reactive state management
│   │   │           ├── components/       # Visualizers & bottom sheet
│   │   │           └── theme/            # Material 3 colors & typography
│   │   └── AndroidManifest.xml
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── build.gradle.kts
├── settings.gradle.kts
├── LICENSE                          # MIT Open Source License
└── README.md
```

---

## 📜 License

This project is open-source under the [MIT License](LICENSE).
Whisper.cpp and GGML are licensed under the MIT License by Georgi Gerganov and contributors.
OpenAI Whisper models are licensed under the MIT License.
