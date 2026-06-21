package com.gijimemo.ui.startup

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gijimemo.whisper.ModelManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Splash / startup gate. On first launch the bundled Whisper model must be
 * copied from APK assets to internal storage; this gate shows progress and
 * holds the main UI back until the extraction is complete (or has failed).
 *
 * On subsequent launches the extraction is skipped, [StartupState.Ready] is
 * emitted almost immediately, and the UI shows a brief "ready" state.
 */
@HiltViewModel
class StartupViewModel @Inject constructor(
    private val modelManager: ModelManager
) : ViewModel() {

    private val _state = MutableStateFlow<StartupState>(StartupState.Checking)
    val state: StateFlow<StartupState> = _state.asStateFlow()

    // v0.7.2: ハードコードではなく、availableModels の isBundled=true 先頭を動的に取得。
    // モデル切替時にここを変更する必要がない。
    private val bundledModelName: String = modelManager.availableModels
        .firstOrNull { it.isBundled }
        ?.name ?: error("No bundled model defined in ModelManager.availableModels")

    init {
        // Clean up legacy models (v0.2.0-era + v0.7.1-era) if user is upgrading.
        modelManager.cleanupLegacyModel()
        startExtraction()
    }

    private fun startExtraction() {
        viewModelScope.launch {
            try {
                // Fast path: model already extracted from a previous run.
                if (modelManager.isBundledModelExtracted(bundledModelName)) {
                    _state.update { StartupState.Ready }
                    return@launch
                }

                _state.update { StartupState.Extracting(progress = 0f) }

                modelManager.ensureBundledModel(
                    modelName = bundledModelName,
                    onProgress = { p ->
                        _state.update { StartupState.Extracting(progress = p) }
                    }
                )

                _state.update { StartupState.Ready }
            } catch (e: Exception) {
                Log.e(TAG, "Bundled model extraction failed", e)
                _state.update { StartupState.Failed(e.message ?: "Unknown error") }
            }
        }
    }

    companion object {
        private const val TAG = "StartupViewModel"
    }
}

sealed interface StartupState {
    data object Checking : StartupState
    data class Extracting(val progress: Float) : StartupState
    data object Ready : StartupState
    data class Failed(val message: String) : StartupState
}
