# GijiMemo テスト計画書

> 📂 パス：docs/TEST_PLAN.md
> 📍 対象：GijiMemo v0.2.0 全モジュール

---

## 1. テスト戦略

### ゴール
1. 全ユニットテストPASS (7モジュール)
2. 二段階処理フロー（文字起こし→確認→要約）の状態遷移完全確認
3. 端末Whisper + クラウドLLMの結合パス確認
4. ダミーMP3認識率 ≥80%
5. LLM要約一致率 ≥80%

### 優先度定義
| 優先度 | 意味 |
|--------|------|
| 🔴 HIGH | コア機能、ブロッカー |
| 🟡 MEDIUM | 補完的だが品質に影響 |
| 🟢 LOW | ニッチエッジケース |

---

## 2. モジュール別テストケース

### 2.1 core-llm (🔴 HIGH, 4テスト群)

#### TC-LLM-01: `OnDeviceWhisperClient` 単体テスト **(新規)**
| ID | テストケース | 入力 | 期待結果 |
|----|-----------|------|---------|
| 01a | transcribeOnly 正常 | ダミーWAV、モデル済 | 文字起こしテキスト返却 |
| 01b | transcribeOnly モデル未Download | ダミーWAV、モデル未 | Download呼び出し後文字起こし |
| 01c | summarizeOnly 正常 | 文字起こしテキスト | 要約Flow<LlmEvent>返却 |
| 01d | transcribeAndFormat 二段階統合 | 音声ファイル + プロンプト | Delta→Complete (文字起こし→要約) |
| 01e | testConnection 応答正常 | - | モデル準備OKメッセージ |

#### TC-LLM-02: `OpenAiCompatibleClient` 新機能テスト **(新規)**
| ID | テストケース | 期待結果 |
|----|-----------|---------|
| 02a | transcribeOnly で whisperTranscribe 呼出 | 文字起こしテキスト返却 |
| 02b | summarizeOnly 正常 Flow | Delta→Complete |
| 02c | executeStream IOスレッド切替確認 | Dispatchers.IOで実行 |
| 02d | executeStream 空Body | LlmException.Unknown |
| 02e | executeStream HTTPエラー (400) | LlmException.Unknown |
| 02f | MULTIMODAL + testConnection | テキスト応答返却 |

#### TC-LLM-03: `LlmProvider` 拡張テスト **(新規)**
| ID | テストケース | 期待結果 |
|----|-----------|---------|
| 03a | createClient with useOnDeviceAsr=true | OnDeviceWhisperClient返却 |
| 03b | createClient with useOnDeviceAsr=false | OpenAiCompatibleClient返却 |
| 03c | 静的createClient (テスト用) | WrappedLlmClient返却 |
| 03d | MULTIMODAL ↔ WHISPER_THEN_SUMMARY auto-fallback | 適切なClient生成 |

#### TC-LLM-04: `SseStreamParser` エッジケース **(既存拡充)**
| ID | テストケース | 期待結果 |
|----|-----------|---------|
| 04a | 空行のみ | 空リスト |
| 04b | reasoning フィールド抽出 | reasoningテキスト返却 |
| 04c | 巨大JSONペイロード | 正常パース |
| 04d | 不完全SSE (最後に改行なし) | 正常終了 |
| 04e | Unicode (日本語/絵文字) | 正しくemit |

---

### 2.2 app/processing (🔴 HIGH, 1テスト群)

#### TC-PROC-01: `ProcessingViewModel` 二段階フロー **(新規)**
| ID | テストケース | 期待結果 |
|----|-----------|---------|
| P01a | start() → IDLE→TRANSCRIBING遷移 | phase==TRANSCRIBING |
| P01b | startTranscribePhase → TRANSCRIBED | phase==TRANSCRIBED, rawTranscript!=空 |
| P01c | confirmAndSummarize() → SUMMARIZING→COMPLETED | phase==COMPLETED, summaryText!=空 |
| P01d | confirmAndSummarize on error | phase==ERROR, error!=null |
| P01e | retryTranscribe() → IDLE→TRANSCRIBING→... | フロー再実行できる |
| P01f | start() 二度呼び出し | 2回目は無視される |
| P01g | 単一(MULTIMODAL)フロー動作確認 | phase==COMPLETED (一発) |
| P01h | LlmEvent.Progress 無視される | 状態変化なし |

---

### 2.3 app/recording (🟡 MEDIUM, 1テスト群)

#### TC-REC-01: `RecordingViewModel` 再生機能テスト **(既存拡充)**
| ID | テストケース | 期待結果 |
|----|-----------|---------|
| R01a | startPlayback → Playing遷移 | playbackState==Playing |
| R01b | pausePlayback → Paused遷移 | playbackState==Paused |
| R01c | resumePlayback → Playing遷移 | playbackState==Playing |
| R01d | stopPlayback → Idle遷移 | playbackState==Idle |
| R01e | audioFilePath null時 startPlayback | 何も起きない |
| R01f | ファイル不在時 startPlayback | エラーログ、状態変化なし |
| R01g | startRecording で再生状態リセット | player解放、Idle遷移 |

---

### 2.4 app/settings (🟡 MEDIUM, 1テスト群)

#### TC-SET-01: `SettingsViewModel` API接続テスト **(新規)**
| ID | テストケース | 期待結果 |
|----|-----------|---------|
| S01a | testApi() → Idle→Running→Success | 応答テキスト返却 |
| S01b | testApi() → API Key未設定→Error | Error("API Key が未設定です") |
| S01c | testApi() → LLM例外→Error | Errorメッセージ設定 |
| S01d | dismissApiTest() | Idleに戻る |
| S01e | selectProvider 変更 | selectedProviderName更新 |

---

### 2.5 app/home (🟡 MEDIUM, 1テスト群)

#### TC-HOME-01: `HomeViewModel` 音声インポート **(既存拡充)**
| ID | テストケース | 期待結果 |
|----|-----------|---------|
| H01a | 正常インポート | Session保存、IDコールバック |
| H01b | 不正ファイル(Uri) | エラーログ、nullコールバック |

---

### 2.6 core-audio (🟢 LOW, 1テスト群)

#### TC-AUD-01: `AudioChunker` 分割ロジック **(既存拡充)**
| ID | テストケース | 期待結果 |
|----|-----------|---------|
| A01a | chunkMinutes=0 → 非分割 | shouldChunk=false, count=1 |
| A01b | 短録音 < chunkMinutes | shouldChunk=false, count=1 |
| A01c | 長録音 > chunkMinutes | shouldChunk=true, count>1 |
| A01d | 丁度 ⌈duration/chunk⌉ 境界値 | 正しい切り上げ計算 |

---

### 2.7 core-whisper (🟡 MEDIUM, 2テスト群)

#### TC-WH-01: `WhisperModelImpl` WAVパース **(新規・要Robolectric)**
| ID | テストケース | 期待結果 |
|----|-----------|---------|
| W01a | 有効WAV読み込み (16kHz,16bit,mono) | FloatArray返却、正規化範囲内 |
| W01b | 不正フォーマット | IllegalStateException |
| W01c | ステレオ→モノ変換 | モノラルFloatArray |
| W01d | 44.1kHz→16kHz変換 | 16kHz相当のFloatArray |
| W01e | release多重呼び出し | 安全に動作 |

#### TC-WH-02: `AudioDecoder` AAC→WAV **(新規・要Robolectric + 実ファイル)**
| ID | テストケース | 期待結果 |
|----|-----------|---------|
| W02a | 音声トラック検出 | 有効インデックス |

---

## 3. 結合テスト計画

### 3.1 モジュール間結合パス
```
RecordingScreen → RecordingViewModel
                          ↓ audioFilePath
ProcessingScreen → ProcessingViewModel
                          ↓ transcribeOnly
                   OnDeviceWhisperClient / OpenAiCompatibleClient
                          ↓ transcript
                   confirmAndSummarize()
                          ↓ summarizeOnly
                   LlmClient → Flow<LlmEvent> → Save Session
                          ↓
PreviewScreen
```

### 3.2 テスト用スタブ設計
| スタブ | 用途 | 実装方法 |
|-------|------|---------|
| `FakeLlmClient` | 二段階フローテスト | 固定テキスト返却、エラーモード切替可 |
| `FakeAudioRecorder` | RecordingVMテスト | 状態遷移のみ、実際の録音不要 |
| `FakeWhisperModel` | 端末Whisperテスト | 固定文字起こし返却 |

---

## 4. テスト実施順序

```
Phase 3a: core-llm 既存テスト復旧 (file lock解決)
Phase 3b: core-llm 新規テスト実装 (TC-LLM-01〜04)
Phase 3c: app 新規テスト実装 (TC-PROC-01, TC-REC-01, TC-SET-01, TC-HOME-01)
Phase 3d: core-audio テスト拡充 (TC-AUD-01)
Phase 3e: core-whisper テスト実装 (TC-WH-01, TC-WH-02)
Phase 4:  結合テスト (全モジュール連携)
Phase 5:  ダミーMP3精度検証
```

---

## 5. 完了条件

| # | 条件 | 確認方法 |
|---|------|---------|
| 1 | 全ユニットテスト PASS | `./gradlew test --no-build-cache` exit=0 |
| 2 | 二段階フロー状態遷移確認 | ProcessingViewModelTest 全件PASS |
| 3 | 端末Whisper + クラウドLLM結合確認 | 結合テスト |
| 4 | ダミーMP3 → 文字起こし ≥80%一致 | 文字起こし結果と原文比較 |
| 5 | LLM要約 ≥80%一致 | 要約結果と期待結果比較 |
