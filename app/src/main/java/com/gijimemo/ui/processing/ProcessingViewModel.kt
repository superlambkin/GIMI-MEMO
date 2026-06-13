package com.gijimemo.ui.processing

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gijimemo.data.model.SessionStatus
import com.gijimemo.data.repository.SessionRepository
import com.gijimemo.data.repository.SettingsRepository
import com.gijimemo.llm.LlmClient
import com.gijimemo.llm.LlmEvent
import com.gijimemo.llm.LlmProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class ProcessingViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: SessionRepository,
    private val settings: SettingsRepository,
    private val provider: LlmProvider
) : ViewModel() {

    val sessionId: String = savedStateHandle.get<String>("sessionId") ?: error("missing sessionId")

    private val _state = MutableStateFlow(ProcessingState())
    val state: StateFlow<ProcessingState> = _state.asStateFlow()

    fun start() {
        viewModelScope.launch {
            try {
                _state.value = ProcessingState(running = true)
                repo.updateStatus(sessionId, SessionStatus.TRANSCRIBING)

                val session = repo.getById(sessionId) ?: error("Session $sessionId not found")
                val audioFile = File(session.audioFilePath)
                if (!audioFile.exists()) error("Audio file not found: ${audioFile.path}")

                val providerConfig = settings.selectedProvider()
                val apiKey = settings.getApiKey(providerConfig.apiKeyRef)
                    ?: error("API Key for ${providerConfig.name} not set")
                val callMode = settings.defaultCallMode.first()
                val prompt = settings.defaultPromptTemplate.first()
                val client: LlmClient = provider.createClient(providerConfig, apiKey)

                val sb = StringBuilder()
                client.transcribeAndFormat(audioFile, prompt, callMode).collect { event ->
                    when (event) {
                        is LlmEvent.Delta -> {
                            sb.append(event.text)
                            _state.value = _state.value.copy(
                                running = true,
                                streamText = sb.toString()
                            )
                        }
                        is LlmEvent.Complete -> {
                            val fullText = event.fullText.ifEmpty { sb.toString() }
                            finalizeSession(fullText, providerConfig.name, providerConfig.defaultModel)
                            _state.value = _state.value.copy(
                                running = false,
                                streamText = fullText,
                                finished = true
                            )
                        }
                        is LlmEvent.Error -> {
                            repo.updateStatus(sessionId, SessionStatus.ERROR, event.cause.message)
                            _state.value = _state.value.copy(
                                running = false,
                                error = event.cause.message
                            )
                        }
                        is LlmEvent.Progress -> { /* 忽略 */ }
                    }
                }
            } catch (e: Exception) {
                repo.updateStatus(sessionId, SessionStatus.ERROR, e.message)
                _state.value = _state.value.copy(running = false, error = e.message)
            }
        }
    }

    private suspend fun finalizeSession(markdown: String, providerName: String, model: String) {
        repo.getById(sessionId)?.let { session ->
            val updated = session.copy(
                transcriptMd = markdown,
                llmProvider = providerName,
                llmModel = model,
                status = SessionStatus.READY
            )
            repo.save(updated)
        }
    }
}

data class ProcessingState(
    val running: Boolean = false,
    val streamText: String = "",
    val finished: Boolean = false,
    val error: String? = null
)
