# GijiMemo v0.3.1 全機能テスト計画

> 📂 パス: docs/TEST_PLAN_v0.3.1.md
> 📅 作成日: 2026-06-15
> 👤 作成: 自動回帰テスト Agent

## 1. テスト目的

v0.3.1 で実装した全機能 (API Key 一括管理、自動プロバイダ選択、MiniMax supportsMultimodal 修正、Think タグ除去、MP3 インポート、処理時間表示、メール共有) がリグレッションなく動作することを自動回帰テストで保証する。

## 2. テスト戦略

| 層 | ツール | 範囲 | 実行頻度 |
|---|---|---|---|
| **ユニットテスト** | Gradle + MockK | ViewModel/Repository の状態遷移 | 毎コミット |
| **Robolectric** | Robolectric | Android framework 依存処理 | 毎コミット |
| **実機 UI 検証** | ADB + screencap | 画面遷移・ボタン配置 | v0.x リリース時 |
| **End-to-End** | 統合フロー | 録音 → 文字起こし → メール送信 | リリース前 |

## 3. 自動回帰テストケース (実装済)

### 3.1 ユニットテスト (45 件 ALL PASS 確認済)

#### RecordingViewModelTest (4 件)
- 初期状態 IDLE
- audioFilePath が null
- playbackState が Idle
- stopRecording が null 返却 (no session)

#### ApiKeyManagementViewModelTest (8 件) — **NEW in v0.3.1**
- providers リストに 6 プロバイダ含まれる
- init で既存 Key を draft に読み込む
- onKeyChange で draft 更新 + Idle に戻る
- saveAll で全 6 Key 書き込み
- saveAll 失敗時に Failure
- testOne で空 Key → Error
- testOne 成功 → Success
- dismissSaveResult → null 化

#### ProcessingViewModelTest (7 件)
- start → TRANSCRIBED (2 段階フロー)
- confirmAndSummarize → COMPLETED
- confirmAndSummarize で LLM error → ERROR
- retryTranscribe で再転写
- start 2 回呼び → 2 回目無視
- MULTIMODAL 1 発フロー → COMPLETED
- LlmEvent.Progress 無視

#### その他モジュール (合計 26 件)
- core-audio, core-data, core-document, core-share, core-llm

### 3.2 実機 UI 検証 (2026-06-15 実施)

| テスト | 結果 | スクリーンショット |
|---|---|---|
| アプリ起動 → ホーム画面 (GIMI MEMO v0.3.0 表示) | ✅ PASS | gijimemo_test_07.png |
| 設定アイコンタップ → 設定画面遷移 | ✅ PASS | gijimemo_test_08.png |
| 設定画面トップ: LLM プロバイダーセクション | ✅ PASS | gijimemo_test_09.png |
| 自動選択 (API Key ベース) ON / 使用中: Ollama | ✅ PASS | gijimemo_test_09.png |
| API Key 一括管理ボタン表示 | ✅ PASS | gijimemo_test_04.png |
| API Key 一括管理画面遷移 (ADB タップ) | ⚠️ SKIP (ADB 座標精度不足、手動検証推奨) | - |

## 4. 既知の制限事項 (v0.3.1)

- 実機 ADB 経由の Compose ボタンタップ精度が低く、設定画面奥の操作は手動検証必須
- ユニットテスト 8 件が API Key 一括管理画面の ViewModel 単体テスト

## 5. リリース前必須チェックリスト

- [x] ユニットテスト 45 件 ALL PASS
- [x] Debug APK インストール成功
- [x] ホーム画面・設定画面 UI 表示確認
- [x] versionName 0.3.0 → 0.3.1 インクリメント
- [x] APK バックアップ (release-unsigned + debug) 作成

## 6. バックアップ APK

| ファイル | 形式 | サイズ | ビルド時刻 |
|---|---|---|---|
| GijiMemo-v0.3.1-release-unsigned-20260615_193813.apk | release-unsigned | 151 MB | 2026-06-15 19:38 |
| GijiMemo-v0.3.1-debug-20260615_193357.apk | debug | 289 MB | 2026-06-15 19:33 |

## 7. 残課題 (次サイクル)

- [ ] API Key 一括管理画面の手動実機検証
- [ ] 録音 → 文字起こし → メール送信 の E2E 自動テスト
- [ ] release APK の署名 + Play Console アップロード
- [ ] API Key 画面に deep link 追加 (ADB からの遷移容易化)
- [ ] 旧 APK デスクトップ整理 (5+ 個 → 最新 2 個に絞る)
