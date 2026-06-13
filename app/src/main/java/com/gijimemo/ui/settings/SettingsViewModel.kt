package com.gijimemo.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gijimemo.data.model.LlmCallMode
import com.gijimemo.data.model.LlmProviderConfig
import com.gijimemo.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository
) : ViewModel() {

    val providers: List<LlmProviderConfig> = settings.defaultProviders()

    val callMode: StateFlow<LlmCallMode> = settings.defaultCallMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LlmCallMode.MULTIMODAL)

    val chunkMinutes: StateFlow<Int> = settings.defaultChunkMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 25)

    val recipient: StateFlow<String> = settings.defaultRecipient
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val promptTemplate: StateFlow<String> = settings.defaultPromptTemplate
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    private val _selectedProviderName = MutableStateFlow<String?>(null)
    val selectedProviderName: StateFlow<String?> = _selectedProviderName.asStateFlow()

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
}
