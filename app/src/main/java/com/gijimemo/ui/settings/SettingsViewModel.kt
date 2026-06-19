package com.gijimemo.ui.settings

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gijimemo.data.model.LlmCallMode
import com.gijimemo.data.model.LlmProviderConfig
import com.gijimemo.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) : ViewModel() {

    val providers: List<LlmProviderConfig> = settings.defaultProviders()

    val callMode: StateFlow<LlmCallMode> = settings.defaultCallMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LlmCallMode.MULTIMODAL)

    val chunkMinutes: StateFlow<Int> = settings.defaultChunkMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 10)

    val recipients: StateFlow<List<String>> = settings.recipients
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val promptTemplate: StateFlow<String> = settings.defaultPromptTemplate
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val useOnDeviceAsr: StateFlow<Boolean> = settings.useOnDeviceAsr
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val decodeEnabled: StateFlow<Boolean> = settings.decodeEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val themeMode: StateFlow<Int> = settings.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    fun setThemeMode(v: Int) = viewModelScope.launch { settings.setThemeMode(v) }

    val ttsSpeechRate: StateFlow<Float> = settings.ttsSpeechRate
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1.0f)
    val ttsPitch: StateFlow<Float> = settings.ttsPitch
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1.0f)

    fun setTtsSpeechRate(v: Float) = viewModelScope.launch { settings.setTtsSpeechRate(v) }
    fun setTtsPitch(v: Float) = viewModelScope.launch { settings.setTtsPitch(v) }
    val ttsEngine: StateFlow<String?> = settings.ttsEngine
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    fun setTtsEngine(v: String?) = viewModelScope.launch { settings.setTtsEngine(v) }

    /** 利用可能なTTSエンジン一覧（遅延初期化） */
    private var _availableEngines: List<EngineInfo>? = null
    val availableEngines: List<EngineInfo> get() {
        if (_availableEngines == null) {
            _availableEngines = runCatching {
                @Suppress("DEPRECATION")
                val tts = TextToSpeech(context, null)
                val list = tts.engines.map { EngineInfo(it.name, it.label?.toString() ?: it.name) }
                tts.shutdown(); list
            }.getOrDefault(emptyList())
        }
        return _availableEngines!!
    }

    data class EngineInfo(val packageName: String, val label: String)

    /** 試聴再生（初期化完了後に発声） */
    private var trialTts: TextToSpeech? = null
    fun trialPlay(rate: Float, pitch: Float, engine: String?) {
        trialTts?.stop(); trialTts?.shutdown()
        val listener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                trialTts?.language = java.util.Locale.JAPANESE
                trialTts?.setSpeechRate(rate)
                trialTts?.speak("これはテスト音声です。話速とピッチを確認してください。",
                    TextToSpeech.QUEUE_FLUSH, null, "trial")
            }
        }
        @Suppress("DEPRECATION")
        trialTts = if (engine != null) TextToSpeech(context, listener, engine)
        else TextToSpeech(context, listener)
    }
    override fun onCleared() { super.onCleared(); trialTts?.stop(); trialTts?.shutdown() }

    private val _selectedProviderName = MutableStateFlow<String?>(null)
    val selectedProviderName: StateFlow<String?> = _selectedProviderName.asStateFlow()

    val currentModel: StateFlow<String> = _selectedProviderName
        .flatMapLatest { name ->
            if (name == null) {
                flowOf("")
            } else {
                val default = providers.firstOrNull { it.name == name }?.defaultModel ?: ""
                settings.modelForProvider(name).map { saved -> saved ?: default }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    init {
        viewModelScope.launch {
            _selectedProviderName.value = settings.selectedProvider().name
        }
    }

    fun selectProvider(name: String) {
        viewModelScope.launch {
            settings.setDefaultProvider(name)
            _selectedProviderName.value = name
        }
    }

    fun setCallMode(v: LlmCallMode) = viewModelScope.launch { settings.setDefaultCallMode(v) }
    fun setUseOnDeviceAsr(v: Boolean) = viewModelScope.launch { settings.setUseOnDeviceAsr(v) }
    fun setDecodeEnabled(v: Boolean) = viewModelScope.launch { settings.setDecodeEnabled(v) }
    fun setChunkMinutes(v: Int) = viewModelScope.launch { settings.setDefaultChunkMinutes(v) }
    fun setPromptTemplate(v: String) = viewModelScope.launch { settings.setDefaultPromptTemplate(v) }

    /** 種類別テンプレートを保存 */
    fun setPromptTemplate(type: String, value: String) {
        viewModelScope.launch { settings.setTemplateForType(type, value) }
    }

    /** 種類別テンプレートを取得（同期的簡易読取） */
    fun getTemplate(type: String): String = run {
        try {
            kotlinx.coroutines.runBlocking { settings.templateForType(type).first() }
        } catch (_: Exception) { null }
    } ?: ""

    fun addRecipient(email: String) = viewModelScope.launch { settings.addRecipient(email) }
    fun removeRecipient(email: String) = viewModelScope.launch { settings.removeRecipient(email) }

    fun setModel(model: String) {
        val name = _selectedProviderName.value ?: return
        viewModelScope.launch { settings.setModelForProvider(name, model) }
    }

    fun supportedModels(): List<String> {
        val name = _selectedProviderName.value ?: return emptyList()
        return providers.firstOrNull { it.name == name }?.supportedModels ?: emptyList()
    }
}
