package com.gijimemo.ui.recording

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gijimemo.audio.AudioRecorder
import com.gijimemo.audio.RecordingState
import com.gijimemo.data.model.Session
import com.gijimemo.data.model.SessionStatus
import com.gijimemo.data.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class RecordingViewModel @Inject constructor(
    private val recorder: AudioRecorder,
    private val repo: SessionRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val state: StateFlow<RecordingState> = recorder.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, RecordingState.Idle)

    private val _sessionId = MutableStateFlow<String?>(null)

    fun startRecording() {
        viewModelScope.launch {
            val id = UUID.randomUUID().toString()
            _sessionId.value = id
            val outFile = File(context.filesDir, "audio/$id.mp3")
            outFile.parentFile?.mkdirs()
            recorder.start(outFile.absolutePath)
        }
    }

    fun pauseRecording() = viewModelScope.launch { recorder.pause() }
    fun resumeRecording() = viewModelScope.launch { recorder.resume() }

    suspend fun stopRecording(title: String, durationMs: Long): Session? {
        val id = _sessionId.value ?: return null
        val path = recorder.stop()
        val file = File(path)
        val session = Session(
            id = id,
            title = title.ifBlank { "会议 ${System.currentTimeMillis()}" },
            createdAt = System.currentTimeMillis(),
            durationMs = durationMs,
            audioFilePath = path,
            audioSizeBytes = file.length(),
            status = SessionStatus.STOPPED
        )
        repo.save(session)
        return session
    }
}
