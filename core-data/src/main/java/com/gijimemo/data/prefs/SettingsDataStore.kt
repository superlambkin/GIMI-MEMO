package com.gijimemo.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gijimemo.data.model.LlmCallMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsDataStore(private val context: Context) {
    private val keyProvider = stringPreferencesKey("default_provider")
    private val keyModel = stringPreferencesKey("default_model")
    private val keyCallMode = stringPreferencesKey("default_call_mode")
    private val keyChunkMinutes = intPreferencesKey("default_chunk_minutes")
    private val keyRecipients = stringPreferencesKey("recipient_list")
    private val keyFormatPriority = stringPreferencesKey("format_priority")
    private val keyPromptTemplate = stringPreferencesKey("prompt_template")
    private val keyMinutesTemplate = stringPreferencesKey("template_minutes")
    private val keyLectureTemplate = stringPreferencesKey("template_lecture")
    private val keyClassTemplate = stringPreferencesKey("template_class")
    private val keyDrTemplate = stringPreferencesKey("template_dr")
    private val keyInterviewTemplate = stringPreferencesKey("template_interview")
    private val keyChatTemplate = stringPreferencesKey("template_chat")
    private val keyMediaTemplate = stringPreferencesKey("template_media")
    private val keyCustom1Template = stringPreferencesKey("template_custom1")
    private val keyCustom2Template = stringPreferencesKey("template_custom2")
    private val keyUseOnDeviceAsr = booleanPreferencesKey("use_on_device_asr")
    private val keyWhisperModel = stringPreferencesKey("whisper_model")
    private val keyCloudAsrProvider = stringPreferencesKey("cloud_asr_provider")
    private val keyAutoProvider = booleanPreferencesKey("auto_provider")
    private val keyDecodeEnabled = booleanPreferencesKey("decode_enabled")
    private val keyPerfFactor = floatPreferencesKey("transcribe_perf_factor")
    private val keyThemeMode = intPreferencesKey("theme_mode")
    private val keyTtsRate = floatPreferencesKey("tts_speech_rate")
    private val keyTtsPitch = floatPreferencesKey("tts_pitch")
    private val keyTtsEngine = stringPreferencesKey("tts_engine")
    private val keyRecordingSampleRate = intPreferencesKey("recording_sample_rate")
    private val keyRecordingBitRate = intPreferencesKey("recording_bit_rate")
    private val keyEnableNs = booleanPreferencesKey("enable_noise_suppressor")
    private val keyEnableAgc = booleanPreferencesKey("enable_automatic_gain_control")
    private val keyEnableVad = booleanPreferencesKey("enable_voice_activity_detection")

    val defaultProvider: Flow<String> = context.dataStore.data.map { it[keyProvider] ?: "MiniMax 国内" }
    val defaultModel: Flow<String> = context.dataStore.data.map { it[keyModel] ?: "MiniMax-M3" }
    val defaultCallMode: Flow<LlmCallMode> = context.dataStore.data.map {
        LlmCallMode.valueOf(it[keyCallMode] ?: LlmCallMode.MULTIMODAL.name)
    }
    val defaultChunkMinutes: Flow<Int> = context.dataStore.data.map { it[keyChunkMinutes] ?: 25 }
    val defaultRecipient: Flow<String> = context.dataStore.data.map { it[keyRecipients] ?: "" }
    val recipients: Flow<List<String>> = context.dataStore.data.map { prefs ->
        prefs[keyRecipients]
            ?.split("\n")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
    }
    val defaultFormatPriority: Flow<String> = context.dataStore.data.map { it[keyFormatPriority] ?: "docx,md,txt" }
    val defaultPromptTemplate: Flow<String> = context.dataStore.data.map {
        it[keyPromptTemplate] ?: DEFAULT_PROMPT_TEMPLATE
    }

    suspend fun setDefaultProvider(v: String) = context.dataStore.edit { it[keyProvider] = v }
    suspend fun setDefaultModel(v: String) = context.dataStore.edit { it[keyModel] = v }
    suspend fun setDefaultCallMode(v: LlmCallMode) = context.dataStore.edit { it[keyCallMode] = v.name }
    suspend fun setDefaultChunkMinutes(v: Int) = context.dataStore.edit { it[keyChunkMinutes] = v }
    suspend fun setDefaultRecipient(v: String) = context.dataStore.edit { it[keyRecipients] = v }
    suspend fun setRecipients(list: List<String>) =
        context.dataStore.edit { it[keyRecipients] = list.joinToString("\n") }
    suspend fun addRecipient(email: String) {
        val trimmed = email.trim()
        if (trimmed.isEmpty()) return
        context.dataStore.edit { prefs ->
            val current = prefs[keyRecipients]
                ?.split("\n")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
            if (current.contains(trimmed)) return@edit
            prefs[keyRecipients] = (current + trimmed).joinToString("\n")
        }
    }
    suspend fun removeRecipient(email: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[keyRecipients]
                ?.split("\n")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
            prefs[keyRecipients] = current.filter { it != email }.joinToString("\n")
        }
    }
    suspend fun setDefaultFormatPriority(v: String) = context.dataStore.edit { it[keyFormatPriority] = v }
    suspend fun setDefaultPromptTemplate(v: String) = context.dataStore.edit { it[keyPromptTemplate] = v }

    // ─── 要約テンプレート ──────────────────────────
    fun templateForType(type: String): Flow<String?> = context.dataStore.data.map { prefs ->
        val saved = when (type) {
            "lecture" -> prefs[keyLectureTemplate]
            "class" -> prefs[keyClassTemplate]
            "dr" -> prefs[keyDrTemplate]
            "interview" -> prefs[keyInterviewTemplate]
            "chat" -> prefs[keyChatTemplate]
            "media" -> prefs[keyMediaTemplate]
            "custom1" -> prefs[keyCustom1Template]
            "custom2" -> prefs[keyCustom2Template]
            else -> prefs[keyMinutesTemplate]
        }
        if (!saved.isNullOrBlank()) saved else defaultTemplate(type)
    }

    private fun defaultTemplate(type: String): String = when (type) {
        "class" -> """以下の授業の文字起こしを要約してください。

出力形式:
# 授業概要
（科目名、講師、日時）
# 授業内容
（節見出しで区切る）
# 板書・資料ポイント
（箇条書き）
# 質疑応答
Q: A:
# 感想・考察
（授業内容に対する理解と所感）"""
        "dr" -> """以下のデザインレビュー(DR)の文字起こしを要約してください。

出力形式:
# DR概要
（プロジェクト、日時、参加者）
# レビュー指摘事項
（重要度明記）
# 要対策項目
（優先度、担当者、期限）
# 決定事項
（箇条書き）
# 所感
（DRの進め方に対する考察）"""
        "lecture" -> """以下の講演会の文字起こしを要約してください。

出力形式:
# 講演会概要
（タイトル、講師、日時）
# 講演内容
（節見出しで区切り、重要な引用は「」で囲む）
# 要点まとめ
（箇条書き）
# 感想・考察
（講演内容に対する考察と所感）"""
        "interview" -> """以下の取材の文字起こしを要約してください。

出力形式:
# 取材概要
（取材先、日時、テーマ）
# 取材内容
Q: （質問） A: （回答）
# ポイント整理
（キーフレーズを箇条書き）
# 感想・考察
（取材内容に対する分析と所感）"""
        "chat" -> """以下の雑談の文字起こしを要約してください。

出力形式:
# 話題一覧
# 会話内容
（話題ごとに節見出し）
# 気づき・発見
（箇条書き）
# 感想・考察
（会話に対する所感と今後に活かせる点）"""
        "media" -> "以下の形式で出力してください。\n\n【メディア配信用】\n# タイトル\n\n## 要約（100文字以内）\n\n## 本文\n- 箇条書きで主要ポイントを列挙\n\n## キーフレーズ\n- 印象的な引用や重要発言を抜粋\n\n## ハッシュタグ\n#キーワード1 #キーワード2"
        "custom1" -> ""
        "custom2" -> ""
        else -> """以下の会議の文字起こしを要約してください。

出力形式:
# 会議概要
（日時、参加者、議題）
# 議題と討論
（議題ごとに節見出し）
# 決定事項
（箇条書き）
# アクションアイテム
（担当者：タスク、期限）
# 所感
（会議の進め方や課題に対する考察）"""
    }

    suspend fun setTemplateForType(type: String, value: String) = context.dataStore.edit {
        val key = when (type) {
            "lecture" -> keyLectureTemplate
            "class" -> keyClassTemplate
            "dr" -> keyDrTemplate
            "interview" -> keyInterviewTemplate
            "chat" -> keyChatTemplate
            "media" -> keyMediaTemplate
            "custom1" -> keyCustom1Template
            "custom2" -> keyCustom2Template
            else -> keyMinutesTemplate
        }
        it[key] = value
    }

    // ─── オンデバイスWhisper ─────────────────────────────────

    val useOnDeviceAsr: Flow<Boolean> = context.dataStore.data.map { it[keyUseOnDeviceAsr] ?: false }

    val whisperModel: Flow<String> = context.dataStore.data.map { it[keyWhisperModel] ?: "ggml-base-q5_1.bin" }

    val cloudAsrProvider: Flow<String> = context.dataStore.data.map { it[keyCloudAsrProvider] ?: "openai" }

    suspend fun setUseOnDeviceAsr(v: Boolean) = context.dataStore.edit { it[keyUseOnDeviceAsr] = v }

    suspend fun setWhisperModel(v: String) = context.dataStore.edit { it[keyWhisperModel] = v }

    suspend fun setCloudAsrProvider(v: String) = context.dataStore.edit { it[keyCloudAsrProvider] = v }

    // ─── 自動プロバイダ選択 (API Key 存在ベース) ────────────────────
    /** true (デフォルト) なら API Key 設定済のプロバイダを優先順位に従って自動選択。
     *  false なら `defaultProvider` (手動) を使用。 */
    val autoProviderMode: Flow<Boolean> = context.dataStore.data.map { it[keyAutoProvider] ?: true }
    suspend fun setAutoProviderMode(v: Boolean) = context.dataStore.edit { it[keyAutoProvider] = v }

    // ─── デコード有効/無効（AAC→WAV変換をスキップするか） ────────────
    val decodeEnabled: Flow<Boolean> = context.dataStore.data.map { it[keyDecodeEnabled] ?: true }
    suspend fun setDecodeEnabled(v: Boolean) = context.dataStore.edit { it[keyDecodeEnabled] = v }

    // ─── 文字起こしパフォーマンス履歴 ──────────────────────
    /** パフォーマンス係数（秒/MB）— 次回の時間予測に使用 */
    val transcribePerfFactor: Flow<Float> = context.dataStore.data.map { it[keyPerfFactor] ?: 0f }
    suspend fun setTranscribePerfFactor(v: Float) = context.dataStore.edit { it[keyPerfFactor] = v }

    // ─── テーマ設定 ────────────────────────────────────────────
    val themeMode: Flow<Int> = context.dataStore.data.map { it[keyThemeMode] ?: 0 }
    suspend fun setThemeMode(v: Int) = context.dataStore.edit { it[keyThemeMode] = v }

    // ─── TTS設定 ────────────────────────────────────────────
    val ttsSpeechRate: Flow<Float> = context.dataStore.data.map { it[keyTtsRate] ?: 1.0f }
    val ttsPitch: Flow<Float> = context.dataStore.data.map { it[keyTtsPitch] ?: 1.0f }
    suspend fun setTtsSpeechRate(v: Float) = context.dataStore.edit { it[keyTtsRate] = v }
    suspend fun setTtsPitch(v: Float) = context.dataStore.edit { it[keyTtsPitch] = v }
    val ttsEngine: Flow<String?> = context.dataStore.data.map { it[keyTtsEngine] }
    suspend fun setTtsEngine(v: String?) = context.dataStore.edit {
        if (v != null) it[keyTtsEngine] = v else it.remove(keyTtsEngine)
    }

    // Per-provider model selection: key = "default_model_<providerName>"
    fun modelForProvider(providerName: String): Flow<String?> =
        context.dataStore.data.map { it[stringPreferencesKey("default_model_$providerName")] }

    suspend fun setModelForProvider(providerName: String, model: String) =
        context.dataStore.edit { it[stringPreferencesKey("default_model_$providerName")] = model }

    // ─── 録音設定 ────────────────────────────────────────────
    val recordingSampleRate: Flow<Int> = context.dataStore.data.map { it[keyRecordingSampleRate] ?: 16000 }
    // v0.7.2: 48kbps は AAC-LC 16kHz mono には低すぎ、録音音量が小さく聞こえる主因。
    // 同梱 Recorder 系アプリは 128kbps が標準。128000 に変更。
    val recordingBitRate: Flow<Int> = context.dataStore.data.map { it[keyRecordingBitRate] ?: 128000 }
    suspend fun setRecordingSampleRate(v: Int) = context.dataStore.edit { it[keyRecordingSampleRate] = v }
    suspend fun setRecordingBitRate(v: Int) = context.dataStore.edit { it[keyRecordingBitRate] = v }

    // v0.7.2: AGC は ON 推奨 (小声でも録れる)、NS は明瞭音声より環境音が多い場合のみ ON
    val enableNoiseSuppressor: Flow<Boolean> = context.dataStore.data.map { it[keyEnableNs] ?: true }
    val enableAutomaticGainControl: Flow<Boolean> = context.dataStore.data.map { it[keyEnableAgc] ?: true }
    val enableVoiceActivityDetection: Flow<Boolean> = context.dataStore.data.map { it[keyEnableVad] ?: true }
    suspend fun setEnableNoiseSuppressor(v: Boolean) = context.dataStore.edit { it[keyEnableNs] = v }
    suspend fun setEnableAutomaticGainControl(v: Boolean) = context.dataStore.edit { it[keyEnableAgc] = v }
    suspend fun setEnableVoiceActivityDetection(v: Boolean) = context.dataStore.edit { it[keyEnableVad] = v }

    companion object {
        const val DEFAULT_PROMPT_TEMPLATE = """以下の録音を文字起こしし、入力と同じ言語で Markdown 形式の議事録を出力してください。

# 検出言語
（音声から自動検出した言語を 1 行で記載。例: 日本語 / 中文 / English）

# 会議のテーマ
（内容から自動抽出）

## 参加者
（発言者を識別。可能なら言語毎にラベル付け）

## 議題と討論
- 議題 1：... 発言者 A の意見...
- 議題 2：...

## 決定事項
- 決定 1：...

## アクションアイテム
- [ ] 担当者：事項（期限）"""
    }
}