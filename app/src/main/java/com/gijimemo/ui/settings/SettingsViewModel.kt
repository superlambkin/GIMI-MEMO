package com.gijimemo.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gijimemo.data.model.LlmCallMode
import com.gijimemo.data.model.LlmProviderConfig
import com.gijimemo.data.repository.SettingsRepository
import com.gijimemo.llm.LlmClient
import com.gijimemo.llm.LlmProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val llmProvider: LlmProvider
) : ViewModel() {

    val providers: List<LlmProviderConfig> = settings.defaultProviders()

    val callMode: StateFlow<LlmCallMode> = settings.defaultCallMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LlmCallMode.MULTIMODAL)

    val chunkMinutes: StateFlow<Int> = settings.defaultChunkMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 25)

    val recipient: StateFlow<String> = settings.defaultRecipient
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val recipients: StateFlow<List<String>> = settings.recipients
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val promptTemplate: StateFlow<String> = settings.defaultPromptTemplate
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    private val _selectedProviderName = MutableStateFlow<String?>(null)
    val selectedProviderName: StateFlow<String?> = _selectedProviderName.asStateFlow()

    /** 当前选中 provider 的模型（per-provider，存到 "default_model_<providerName>" key） */
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

    /** API 接続テストの状態: Idle / Running / Success(text) / Error(message) */
    sealed class ApiTestState {
        object Idle : ApiTestState()
        object Running : ApiTestState()
        data class Success(val response: String) : ApiTestState()
        data class Error(val message: String) : ApiTestState()
    }

    private val _apiTestState = MutableStateFlow<ApiTestState>(ApiTestState.Idle)
    val apiTestState: StateFlow<ApiTestState> = _apiTestState.asStateFlow()

    fun dismissApiTest() {
        _apiTestState.value = ApiTestState.Idle
    }

    init {
        viewModelScope.launch {
            _selectedProviderName.value = settings.selectedProvider().name
        }
    }

    fun getApiKey(ref: String): String? = settings.getApiKey(ref)

    fun selectProvider(name: String) {
        viewModelScope.launch {
            settings.setDefaultProvider(name)
            _selectedProviderName.value = name
        }
    }

    fun setCallMode(v: LlmCallMode) = viewModelScope.launch { settings.setDefaultCallMode(v) }
    fun setChunkMinutes(v: Int) = viewModelScope.launch { settings.setDefaultChunkMinutes(v) }
    fun setRecipient(v: String) = viewModelScope.launch { settings.setDefaultRecipient(v) }
    fun setPromptTemplate(v: String) = viewModelScope.launch { settings.setDefaultPromptTemplate(v) }
    fun setApiKey(ref: String, key: String) = settings.setApiKey(ref, key)

    fun addRecipient(email: String) = viewModelScope.launch { settings.addRecipient(email) }
    fun removeRecipient(email: String) = viewModelScope.launch { settings.removeRecipient(email) }

    fun setModel(model: String) {
        val name = _selectedProviderName.value ?: return
        viewModelScope.launch { settings.setModelForProvider(name, model) }
    }

    /** 当前 provider 支持的模型列表（用于 UI dropdown） */
    fun supportedModels(): List<String> {
        val name = _selectedProviderName.value ?: return emptyList()
        return providers.firstOrNull { it.name == name }?.supportedModels ?: emptyList()
    }

    /**
     * 接続テスト: 選択中の provider / API Key / model で短い chat completion を呼び出し結果を返す。
     * ApiTestState が Idle→Running→Success/Error に遷移する。
     */
    fun testApi() {
        val name = _selectedProviderName.value ?: return
        val config = providers.firstOrNull { it.name == name } ?: return
        val apiKey = settings.getApiKey(config.apiKeyRef)?.trim().orEmpty()
        if (apiKey.isEmpty()) {
            _apiTestState.value = ApiTestState.Error("API Key が未設定です")
            return
        }
        val model = runBlocking { settings.modelForProvider(name).first() }?.takeIf { it.isNotBlank() } ?: config.defaultModel
        _apiTestState.value = ApiTestState.Running
        viewModelScope.launch {
            try {
                val client: LlmClient = llmProvider.createClient(config, apiKey, model)
                val response = client.testConnection()
                _apiTestState.value = ApiTestState.Success(response.take(300))
            } catch (e: Exception) {
                _apiTestState.value = ApiTestState.Error(e.message ?: e::class.java.simpleName)
            }
        }
    }
}
