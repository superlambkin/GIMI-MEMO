package com.gijimemo.data.repository

import com.gijimemo.data.model.LlmCallMode
import com.gijimemo.data.model.LlmProviderConfig
import com.gijimemo.data.model.findByName
import com.gijimemo.data.prefs.EncryptedPrefs
import com.gijimemo.data.prefs.SettingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    private val store: SettingsDataStore,
    private val encryptedPrefs: EncryptedPrefs
) {
    fun defaultProviders(): List<LlmProviderConfig> = LlmProviderConfig.defaults()

    suspend fun selectedProvider(): LlmProviderConfig {
        val name = store.defaultProvider.first()
        return defaultProviders().findByName(name) ?: defaultProviders().first()
    }

    fun selectedProviderFlow(): Flow<LlmProviderConfig> =
        store.defaultProvider.map { name ->
            defaultProviders().findByName(name) ?: defaultProviders().first()
        }

    fun getApiKey(ref: String): String? = encryptedPrefs.getApiKey(ref)
    fun setApiKey(ref: String, key: String) = encryptedPrefs.putApiKey(ref, key)

    /** 指定 provider の API Key が設定されているか */
    fun isApiKeyConfigured(config: LlmProviderConfig): Boolean =
        !getApiKey(config.apiKeyRef).isNullOrBlank()

    // ─── 自動プロバイダ選択 (API Key 存在ベース) ────────────────────
    val autoProviderMode get() = store.autoProviderMode
    suspend fun setAutoProviderMode(v: Boolean) = store.setAutoProviderMode(v)

    /**
     * API Key 設定済プロバイダを優先順位に従って返す。
     * 優先順位:
     *   1. multimodal 対応 (音声処理) ができる → OpenAI / ClaudeProxy
     *   2. ローカル実行 (API Key 不要) → Ollama
     *   3. クラウドテキスト (OnDevice Whisper と組み合わせ) → DeepSeek / MiniMax 国内 / MiniMax 海外
     *
     * 自動モード: 1〜3 の中で **最初に見つかった設定済プロバイダ** を返す。
     * 手動モード: `defaultProvider` を尊重 (既存挙動)。
     */
    suspend fun autoSelectProvider(): LlmProviderConfig {
        val all = defaultProviders()
        val priority = listOf(
            "OpenAI",           // multimodal 対応 (gpt-4o-audio-preview) — 音声そのまま処理
            "ClaudeProxy",      // multimodal 対応 (あれば)
            "Ollama",           // ローカル LLM (API Key なくても Ollama 起動してれば使える)
            "DeepSeek",         // テキスト特化クラウド
            "MiniMax 国内",     // テキスト特化クラウド
            "MiniMax 海外"      // テキスト特化クラウド
        )
        for (name in priority) {
            val cfg = all.findByName(name) ?: continue
            if (isApiKeyConfigured(cfg) || name == "Ollama") {
                return cfg
            }
        }
        // どの Key も未設定 → 既定の MiniMax 国内を返す (呼び出し側で Key 未設定エラー)
        return all.findByName("MiniMax 国内") ?: all.first()
    }

    val defaultCallMode get() = store.defaultCallMode
    val useOnDeviceAsr get() = store.useOnDeviceAsr
    val whisperModel get() = store.whisperModel
    val decodeEnabled get() = store.decodeEnabled

    suspend fun setUseOnDeviceAsr(v: Boolean) = store.setUseOnDeviceAsr(v)
    suspend fun setDecodeEnabled(v: Boolean) = store.setDecodeEnabled(v)
    val transcribePerfFactor get() = store.transcribePerfFactor
    suspend fun setTranscribePerfFactor(v: Float) = store.setTranscribePerfFactor(v)
    val themeMode get() = store.themeMode
    suspend fun setThemeMode(v: Int) = store.setThemeMode(v)
    val ttsSpeechRate get() = store.ttsSpeechRate
    val ttsPitch get() = store.ttsPitch
    suspend fun setTtsSpeechRate(v: Float) = store.setTtsSpeechRate(v)
    suspend fun setTtsPitch(v: Float) = store.setTtsPitch(v)
    val ttsEngine get() = store.ttsEngine
    suspend fun setTtsEngine(v: String?) = store.setTtsEngine(v)
    suspend fun setWhisperModel(v: String) = store.setWhisperModel(v)
    val defaultChunkMinutes get() = store.defaultChunkMinutes
    val defaultRecipient get() = store.defaultRecipient
    val recipients get() = store.recipients
    val defaultFormatPriority get() = store.defaultFormatPriority
    val defaultPromptTemplate get() = store.defaultPromptTemplate

    fun modelForProvider(providerName: String) = store.modelForProvider(providerName)

    suspend fun setDefaultProvider(v: String) = store.setDefaultProvider(v)

    suspend fun setDefaultCallMode(v: LlmCallMode) = store.setDefaultCallMode(v)
    suspend fun setDefaultChunkMinutes(v: Int) = store.setDefaultChunkMinutes(v)
    suspend fun setDefaultRecipient(v: String) = store.setDefaultRecipient(v)
    suspend fun setRecipients(list: List<String>) = store.setRecipients(list)
    suspend fun addRecipient(email: String) = store.addRecipient(email)
    suspend fun removeRecipient(email: String) = store.removeRecipient(email)
    suspend fun setDefaultFormatPriority(v: String) = store.setDefaultFormatPriority(v)
    suspend fun setDefaultPromptTemplate(v: String) = store.setDefaultPromptTemplate(v)
    fun templateForType(type: String) = store.templateForType(type)
    suspend fun setTemplateForType(type: String, value: String) = store.setTemplateForType(type, value)
    suspend fun setModelForProvider(providerName: String, model: String) =
        store.setModelForProvider(providerName, model)

    // ─── 録音設定 ────────────────────────────────────────
    val recordingSampleRate get() = store.recordingSampleRate
    val recordingBitRate get() = store.recordingBitRate
    suspend fun setRecordingSampleRate(v: Int) = store.setRecordingSampleRate(v)
    suspend fun setRecordingBitRate(v: Int) = store.setRecordingBitRate(v)

    val enableNoiseSuppressor get() = store.enableNoiseSuppressor
    val enableAutomaticGainControl get() = store.enableAutomaticGainControl
    val enableVoiceActivityDetection get() = store.enableVoiceActivityDetection
    suspend fun setEnableNoiseSuppressor(v: Boolean) = store.setEnableNoiseSuppressor(v)
    suspend fun setEnableAutomaticGainControl(v: Boolean) = store.setEnableAutomaticGainControl(v)
    suspend fun setEnableVoiceActivityDetection(v: Boolean) = store.setEnableVoiceActivityDetection(v)
}