# GijiMemo コードレビュー結果

> 📂 パス：docs/CODE_REVIEW.md
> 📍 ソース：GijiMemo v0.2.0 全モジュール

---

## 評価サマリー

| 次元 | 評価 | コメント |
|------|------|---------|
| アーキテクチャ | ✅ 良好 | クリーンな7モジュール構成、依存関係明確 |
| コード品質 | ✅ 良好 | Null安全、sealed class、Flowベース設計 |
| テスタビリティ | ⚠️ 要改善 | 新規コードにテスト不足 + Windows環境課題 |
| セキュリティ | ✅ 良好 | EncryptedSharedPreferences + API Key暗号化 |
| パフォーマンス | ✅ 良好 | coroutines + IO threading 適切 |
| エラーハンドリング | ⚠️ 一部改善余地 | 一部catchが広すぎる |

---

## 1. アーキテクチャ

### 良い点
- **7モジュールの明確な責務分離**: core-audio/llm/data/document/share/whisper + app
- **依存関係の方向**: app → core-* (逆方向なし)。クリーンアーキテクチャ準拠
- **Hilt DI**: 全モジュールで適切に使用、`@Singleton` スコープ
- **Compose + ViewModel**: 画面ごとの責務明確

### 改善提案
| # | 提案 | 優先度 | 影響範囲 |
|---|------|--------|---------|
| 1 | `OnDeviceWhisperClient` の @Inject → @Provides 移行（実施済み） | 🔴 | LlmModule.kt |

---

## 2. コード品質レビュー

### 2.1 core-llm （レビュアー: 第三者視点）

**良いパターン:**
- `sealed class LlmException` — 例外の種類を明確に型付け ✅
- `sealed class LlmEvent` — Flowのイベント型を網羅 ✅
- `LlmOptions` data class — 設定値の不変性保証 ✅
- `OpenAiCompatibleClient.executeStream()` で `withContext(Dispatchers.IO)` を使用してNetworkOnMainThreadを防止 ✅

**懸念点:**
- `SseStreamParser` の非標準deltaパースが複雑 — delta構造の変化に弱い
- `OpenAiCompatibleClient` が384行と大きめ — 責務分割検討の余地
- `OkHttpClient` のlog interceptorでRequestBody全体を読む — メモリ使用量に注意

### 2.2 core-whisper

**良いパターン:**
- JNIラッパーの明確なインターフェース設計（`WhisperModel`インターフェース）✅
- `WhisperModelImpl.transcribeFile()` でWAVパースロジック分離 ✅
- `ModelManager` でダウンロード進捗コールバック ✅
- C言語JNIで `whisper_full_default_params` で言語固定 (`ja`) ✅

**懸念点:**
- `WhisperModelImpl.readWavAsFloat()` が手動パース — バイナリ安全性に注意
- `AudioDecoder.decodeToWav()` が231行と大きい — 分割推奨
- 一時WAVファイル削除の保証 （`finally` ブロック内での削除が必要）

### 2.3 app/processing

**良いパターン:**
- `sealed class ProcessingPhase` で状態を型付け ✅
- `ProcessingState` data class + プロパティ委譲 (`isTranscribing`等) ✅
- 二段階フローの状態遷移が明確（TRANSCRIBING→TRANSCRIBED→SUMMARIZING→COMPLETED）✅
- キャッシュ変数 (`cachedClient`, `cachedAudioFile`) で初期化を一度に制限 ✅

**懸念点:**
- `ProcessingViewModel` が290行と大きい — ViewModelとビジネスロジックの分離余地
- `cachedClient` の型安全でないnull許容 — `?: error()` で強制クラッシュのリスク

### 2.4 app/recording

**良いパターン:**
- `PlaybackState` enum で再生状態明確化 ✅
- `MediaPlayer` のライフサイクル管理（`onCleared()` で解放）✅
- 振幅バッファ「リングバッファ方式」で最新Nフレーム保持 ✅

**懸念点:**
- `AmplitudeVisualizer` がCanvas描画で複雑 — 描画パフォーマンスの監視が必要
- `Math.cos/sin` を毎フレーム再計算 — `remember` による最適化余地

---

## 3. セキュリティレビュー

| 項目 | 結果 | 詳細 |
|------|------|------|
| API Key 保存 | ✅ 安全 | EncryptedSharedPreferences + AES256 |
| ネットワーク | ✅ 安全 | HTTPS (baseUrl) + Bearer token |
| ファイル権限 | ✅ 適切 | FileProvider + grantUriPermission |
| Log出力 | ⚠️ 注意 | デバッグビルド限定にすべき |

---

## 4. パフォーマンスレビュー

| 懸念 | 影響 | 対策 |
|------|------|------|
| OkHttp Interceptor body抽出 | 中 | デバッグビルドのみ有効に |
| 音声ファイル Base64 変換 | 大ファイル時 | メモリ圧迫注意、分岐 |
| MediaCodecデコード | 中 | 既にIOスレッド対応済み |
| リアルタイム波形描画 | 中 | Canvas再描画頻度制御要検討 |

---

## 5. テストカバレッジ評価

| モジュール | 評価 | カバレッジ | 備考 |
|-----------|------|-----------|------|
| core-llm | ✅ | 16 tests | Sse/LLMProvider/Multipart/OpenAIClient + 新規OnDeviceClient |
| app | ⚠️ 増加中 | 3 tests (+新規7) | ProcessingVM+HomeVM+RecordingVM → 新規追加済み |
| core-data | ✅ | 8 tests | 全Repository/Prefsカバー |
| core-audio | ✅ | 2 tests | Chunker + Recorder |
| core-document | ✅ | 4 tests | 全Generator |
| core-share | ✅ | 1 test | 単一サービスのためOK |
| core-whisper | ❌ | 0 tests | JNI依存のため実機/エミュレータ必須 |

---

## 6. 重要発見事項（アクション必須）

### 🔴 Critical
1. **core-llm の Hilt クロスモジュール依存問題** → `@Inject → @Provides` 移行（実施済み）
2. **Windows環境でのファイルロック問題** → Gradle test binary出力の競合。毎回Kill必須

### 🟡 推奨
1. `OpenAiCompatibleClient`（384行）を3つに分割
2. `ProcessingViewModel`（291行）から`describeError`等のユーティリティ分離
3. core-whisper の単体テスト追加（Robolectric + mock JNI）
4. 全モジュールでビルドバリアントに応じたLog出力制御

### 🟢 おまけ
1. `WhisperModelImpl` の `transcribeWithTimestamps` が未実装（全0返却）
2. `SseStreamParser.parseFromSource` が `content` しか抽出しない（非標準delta非対応）
3. フォールバックモード（MULTIMODAL↔WHISPER_THEN_SUMMARY）のテスト不足

---

## 7. 総評

**GijiMemo v0.2.0 は堅実な設計と実装を持つアプリケーションです。**

クリーンアーキテクチャに基づく7モジュール構成、Flowベースの非同期処理、sealed classによる型安全な状態管理など、モダンなAndroid開発のベストプラクティスに従っています。

特に **二段階処理フロー**（端末Whisper文字起こし → 確認 → クラウドLLM要約）へのリファクタリングは適切であり、ユーザー体験の向上に貢献しています。

優先的に対処すべき課題は **Windowsビルド環境のファイルロック問題** と **core-whisper モジュールのテスト不足** です。
