
<p align="center">
  <img src="app/src/main/res/drawable/ic_launcher_foreground.png" width="100" alt="GijiMemo Logo"/>
</p>

<h1 align="center">GijiMemo（ギジメモ）</h1>

<p align="center">
  <b>AI Meeting Minutes Generator — AI 議事録自動作成 — AI 会议纪要生成</b>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/version-0.7.6-blue" alt="version"/>
  <img src="https://img.shields.io/badge/platform-Android-lightgrey" alt="platform"/>
  <img src="https://img.shields.io/badge/kotlin-1.9.24-purple" alt="kotlin"/>
  <img src="https://img.shields.io/badge/license-MIT-green" alt="license"/>
</p>

---

# 🇯🇵 日本語

## 📋 概要

音声録音 / ファイルインポート → **AI文字起こし** → **自動要約** → **Word/MD/TXT出力** → **メール共有** まで一貫して行う Android アプリ。

会議、講演会、授業、取材、雑談、デザインレビューなど、様々なシーンに対応する **6種類の要約テンプレート** を搭載。

## ✨ 主な機能

| 機能 | 詳細 |
|------|------|
| 🎤 **AI文字起こし** | OpenAI Whisper API で高精度音声認識、日本語・中国語対応 |
| 📝 **6種の要約テンプレート** | 議事録 / 講演会 / 授業 / 取材 / 雑談 / DR |
| 📄 **Word文書生成** | Apache POI で見やすい .docx を自動作成 |
| 📧 **メール共有** | docx + md + txt をワンタップでメーラー送信 |
| 🔒 **画面オフ対策** | ForegroundService + WakeLock で安定稼働 |
| 🔊 **日本語音声読み上げ** | TTS で内容読み上げ＋位置ハイライト |
| 🎨 **無印良品風UI** | オフホワイト × Muji Red のミニマルデザイン |
| ⚡ **高速分割処理** | 25MB超えファイルも M4A 直接分割で効率処理 |
| 📊 **時間予測** | 過去の実績から所要時間を予測表示 |

## 🚀 処理フロー

```
録音 / MP3・M4A インポート
    ↓
ForegroundService 起動（画面オフ対策）
    ↓
25MB以下？──→ YES → Whisper API 直接送信
    ↓ NO
M4A直接分割（デコード不要、高速）
    ↓
Whisper API で文字起こし → 結果結合
    ↓
AI要約（テンプレート選択 → 構造化出力）
    ↓
Word(.docx) / Markdown(.md) / TXT(.txt) 生成
    ↓
メール共有 / 画面表示 / TXT保存
```

## ⚙️ 初回設定

1. **設定 → API Key 一括管理** → OpenAI の API Key を入力
2. **設定 → サービス** → 要約用 LLM プロバイダを選択
3. 録音または音声ファイルをインポート → 文字起こし開始

---

# 🇬🇧 English

## 📋 Overview

**GijiMemo** is an Android app that automates meeting minutes creation from start to finish: **voice recording / file import → AI transcription → smart summarization → Word/MD/TXT export → email sharing**.

Comes with **6 summary templates** for meetings, lectures, classes, interviews, casual chats, and design reviews.

## ✨ Key Features

| Feature | Description |
|---------|-------------|
| 🎤 **AI Transcription** | OpenAI Whisper API — high accuracy, supports Japanese & Chinese |
| 📝 **6 Summary Templates** | Meeting / Lecture / Class / Interview / Chat / Design Review |
| 📄 **Word Export** | Apache POI generates clean .docx automatically |
| 📧 **Email Sharing** | One-tap send with docx + md + txt attachments |
| 🔒 **Screen-off Resistant** | ForegroundService + WakeLock for long processing |
| 🔊 **Japanese TTS** | Text-to-speech with real-time paragraph highlighting |
| 🎨 **Muji-style UI** | Minimalist design with off-white × Muji Red |
| ⚡ **Chunked Processing** | Files over 25MB auto-split via M4A direct splitting |
| 📊 **Time Prediction** | Estimated duration based on historical performance |

## 🚀 Processing Flow

```
Recording / MP3・M4A Import
    ↓
ForegroundService starts (screen-off protection)
    ↓
Under 25MB? ──→ YES → Send directly to Whisper API
    ↓ NO
M4A direct split (no decode, high speed)
    ↓
Whisper API transcription → Results merged
    ↓
AI summarization (template-based structured output)
    ↓
Word(.docx) / Markdown(.md) / TXT(.txt) generation
    ↓
Email sharing / On-screen display / TXT save
```

## ⚙️ First-time Setup

1. **Settings → API Key Management** → Enter your OpenAI API Key
2. **Settings → Service** → Select LLM provider for summarization
3. Record or import an audio file → Start transcription

---

# 🇨🇳 中文

## 📋 概述

**GijiMemo** 是一款 Android 应用，实现从 **录音/导入 → AI 转写 → 智能总结 → Word/MD/TXT 导出 → 邮件分享** 的全流程会议纪要自动化。

内置 **6 种总结模板**，适用于会议、演讲、课程、采访、讨论和设计评审等场景。

## ✨ 主要功能

| 功能 | 说明 |
|------|------|
| 🎤 **AI 语音转写** | 基于 OpenAI Whisper API，支持日语和中文 |
| 📝 **6 种总结模板** | 会议 / 演讲 / 课程 / 采访 / 讨论 / 设计评审 |
| 📄 **Word 文档生成** | 使用 Apache POI 自动生成格式清晰的 .docx |
| 📧 **邮件分享** | 一键发送含 docx + md + txt 附件的邮件 |
| 🔒 **屏幕关闭保护** | ForegroundService + WakeLock 确保稳定运行 |
| 🔊 **日语朗读** | TTS 语音朗读，实时高亮当前位置 |
| 🎨 **无印良品风格 UI** | 米白底色 × Muji 红的极简设计 |
| ⚡ **文件分割处理** | 超过 25MB 的文件自动分割后处理 |
| 📊 **时间预测** | 基于历史记录预估处理时间 |

## 🚀 处理流程

```
录音 / MP3・M4A 导入
    ↓
启动 ForegroundService（防止屏幕关闭中断）
    ↓
小于 25MB？──→ 是 → 直接发送至 Whisper API
    ↓ 否
M4A 直接分割（无需解码，高速处理）
    ↓
Whisper API 转写 → 合并结果
    ↓
AI 总结（基于模板的结构化输出）
    ↓
生成 Word(.docx) / Markdown(.md) / TXT(.txt)
    ↓
邮件分享 / 屏幕显示 / TXT 保存
```

## ⚙️ 首次设置

1. **设置 → API Key 管理** → 输入 OpenAI 的 API Key
2. **设置 → 服务** → 选择用于总结的 LLM 提供商
3. 录音或导入音频文件 → 开始转写

---

# 📊 Performance Benchmark

**38-minute audio file实测:**

| Method | Split/Decode | API × Chunks | Total |
|--------|:----------:|:-----------:|:----:|
| Old: WAV decode | 216s | 116s | **332s** |
| New: M4A direct split | 47s | 145s | **192s (42% faster)** |
| 💎 Native M4A (esti.) | 3s | 120s | **123s (63% faster)** |

# 🛠 Tech Stack

| Category | Technology |
|----------|-----------|
| Language | **Kotlin** |
| UI | **Jetpack Compose + Material3** |
| DI | **Hilt** |
| Database | **Room** |
| Preferences | **DataStore** |
| Encryption | **EncryptedSharedPreferences** |
| LLM Client | **OkHttp + Moshi** (OpenAI-compatible) |
| Document | **Apache POI** (.docx) |
| Speech API | OpenAI Whisper / whisper.cpp |
| Audio Playback | MediaPlayer + MediaCodec |
| TTS | Android TextToSpeech |
| Async | Coroutines + Flow |

# 📁 Module Structure

```
:app                  UI (Jetpack Compose Navigation)
:core-audio          Recording (MediaRecorder)
:core-llm            LLM client (OpenAI-compatible)
:core-document       Word/MD/TXT generation
:core-share          Email sharing (Intent)
:core-data           Room + DataStore + EncryptedPrefs
:core-whisper        whisper.cpp JNI binding
```

# 🔧 Development Setup

### Prerequisites

- **JDK 21** (Gradle 8.5 does not support Java 26+)
  ```bash
  export JAVA_HOME="/c/Program Files/Java/jdk-21.0.11"
  ```
- **Android SDK** (ANDROID_HOME required)
  ```bash
  export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
  ```

### Build

```bash
./gradlew assembleDebug       # Debug APK
./gradlew assembleRelease     # Release APK
./gradlew test                # Unit tests
./gradlew :app:installDebug   # Install to device
```

# 📱 Requirements

| Item | Requirement |
|------|-------------|
| Android | **API 26+** (Android 8.0〜) |
| Architecture | ARM64 / x86_64 |
| Permissions | RECORD_AUDIO, POST_NOTIFICATIONS, FOREGROUND_SERVICE, WAKE_LOCK |

---

<p align="center">
  <i>GijiMemo — Capture meetings, leverage knowledge</i><br>
  <i>GijiMemo — 会議を記録し、知識を活かす</i><br>
  <i>GijiMemo — 记录会议，活用知识</i>
</p>
