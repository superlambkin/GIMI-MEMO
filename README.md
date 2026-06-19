# GijiMemo（ギジメモ）— AI 議事録自動作成アプリ

音声録音 / インポート → AI文字起こし → 要約 → Word/MD/TXT出力 → メール共有まで、一貫して行う Android アプリ。

![version](https://img.shields.io/badge/version-0.5.0-blue)
![platform](https://img.shields.io/badge/platform-Android-lightgrey)
![kotlin](https://img.shields.io/badge/kotlin-1.9.24-purple)

---

## 🎯 特徴

| 機能 | 説明 |
|------|------|
| **AI文字起こし** | OpenAI Whisper API で高精度音声認識（日本語・中国語対応） |
| **自動要約** | 6種類のテンプレートから選択し AI が構造化議事録を生成 |
| **Word文書出力** | Apache POI で見やすい Word（.docx）を自動作成 |
| **メール共有** | docx + md + txt をメーラーでワンタップ送信 |
| **画面オフ対策** | ForegroundService + WakeLock で長時間処理も安定 |
| **日本語音声読み上げ** | Android TTS で内容を音声再生、位置ハイライト表示 |
| **無印良品風UI** | オフホワイト × Muji Red のミニマルデザイン |
| **分割文字起こし** | 25MB超えのファイルも自動分割して処理 |
| **履歴予測** | 過去の処理時間を記録し次回の所要時間を予測表示 |

## 📋 6つの要約テンプレート

| 種類 | 出力構成 |
|------|---------|
| 📝 **議事録** | 会議概要 / 議題と討論 / 決定事項 / アクション / 所感 |
| 🎤 **講演会** | 講演会概要 / 講演内容 / 要点まとめ / 感想・考察 |
| 📚 **授業** | 授業概要 / 授業内容 / 板書・資料 / 質疑応答 / 感想・考察 |
| 🎙 **取材** | 取材概要 / Q&A / ポイント整理 / 感想・考察 |
| 💬 **雑談** | 話題一覧 / 会話内容 / 気づき・発見 / 感想・考察 |
| 🔍 **DR** | DR概要 / 指摘事項 / 要対策項目 / 決定事項 / 所感 |

## 🚀 処理フロー

```
録音 / MP3・M4A インポート
    ↓
ForegroundService 起動（画面オフ対策 + WakeLock）
    ↓
ファイルサイズ 25MB以下？──→ YES → Whisper API 直接送信
    ↓ NO
M4A直接分割（MediaExtractor+MediaMuxer、デコード不要で高速）
  └ MP3等はWAVデコードフォールバック
    ↓
分割チャンクを順次 Whisper API で文字起こし → 結果結合
    ↓
AI要約（選択テンプレートに従い構造化＋感想・考察含む）
    ↓
Word(.docx) / Markdown(.md) / TXT(.txt) 自動生成
    ↓
メール共有 / 画面表示 / TXT保存（Downloadフォルダ）
```

## ⚡ パフォーマンス（38分音声ファイルの実測）

| 方式 | 分割/デコード | API xチャンク | 合計 |
|------|:----------:|:----------:|:----:|
| 旧：WAVデコード方式 | 216秒 | 116秒 | **332秒** |
| 新：M4A直接分割方式 | 47秒 | 145秒 | **192秒（42%改善）** |
| 💎 自前録音M4A想定 | 3秒 | 120秒 | **123秒（63%改善）** |

## 🛠 技術スタック

| カテゴリ | 技術 |
|---------|------|
| 言語 | **Kotlin** |
| UI | **Jetpack Compose + Material3** |
| DI | **Hilt** |
| DB | **Room** |
| 設定 | **DataStore Preferences** |
| 暗号化 | **EncryptedSharedPreferences** |
| LLM通信 | **OkHttp + Moshi**（OpenAI互換API） |
| 文書生成 | **Apache POI**（Word .docx） |
| 音声認識 | OpenAI Whisper API / whisper.cpp |
| 音声再生 | MediaPlayer + MediaCodec |
| 読み上げ | Android TextToSpeech |
| 非同期 | Coroutines + Flow |

## 📁 モジュール構成

```
:app                  UI画面（Jetpack Compose Navigation）
:core-audio          録音（MediaRecorder）
:core-llm            LLMクライアント（OpenAI互換）
:core-document       Word/MD/TXT文書生成
:core-share          メール共有（Intent）
:core-data           Room + DataStore + EncryptedPrefs
:core-whisper        whisper.cpp JNI（端末内音声認識）
```

## 🔧 開発セットアップ

### 前提条件

- **JDK 21**（Gradle 8.5 は Java 26+ 非対応）
  ```bash
  export JAVA_HOME="/c/Program Files/Java/jdk-21.0.11"
  ```
- **Android SDK**（ANDROID_HOME 必須）
  ```bash
  export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
  ```

### ビルド

```bash
./gradlew assembleDebug       # Debug APK
./gradlew assembleRelease     # Release APK
./gradlew test                # 単体テスト
./gradlew :app:installDebug   # 端末インストール
```

## 📱 対応環境

| 項目 | 対応 |
|------|------|
| Android | **API 26+**（Android 8.0〜） |
| アーキテクチャ | ARM64 / x86_64 |
| 権限 | 録音 / 通知 / ForegroundService / WakeLock |

## ⚙️ 初回設定

1. **設定 → API Key 一括管理** → OpenAI 他プロバイダの API Key を入力
2. **設定 → サービス** → 要約に使う LLM プロバイダを選択
3. **設定 → 読み上げ設定** → 話速・ピッチ・エンジンを調整（任意）
4. **設定 → 文字起こし設定** → 分割サイズ・デコード有効/無効を調整（任意）

## 🖼️ スクリーンショット

| 録音画面 | 文字起こし中 | 要約結果 |
|:--------:|:----------:|:--------:|
| 波形可視化＋時間表示 | 円グラフ＋予測表示 | Word書式＋文字数 |

> スクリーンショットは `docs/` またはプロジェクトルートの画像ファイルを参照

---

*GijiMemo - 会議を記録し、知識を活かす*
