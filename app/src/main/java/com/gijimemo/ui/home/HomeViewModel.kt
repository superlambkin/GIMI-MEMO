package com.gijimemo.ui.home

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gijimemo.data.model.Session
import com.gijimemo.data.model.SessionStatus
import com.gijimemo.data.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repo: SessionRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {
    val sessions: StateFlow<List<Session>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 从用户选择的 Uri 复制音频文件到内部存储，创建 Session 后通过 [onImported] 回调返回新 sessionId。
     * 失败时 log 错误并回调 null。
     */
    fun importAudioFile(uri: Uri, onImported: (sessionId: String?) -> Unit) {
        viewModelScope.launch {
            try {
                val id = UUID.randomUUID().toString()
                val outFile = File(context.filesDir, "audio/$id.mp3")
                outFile.parentFile?.mkdirs()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        outFile.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("无法打开输入流")
                }
                val durationMs = withContext(Dispatchers.IO) {
                    MediaMetadataRetriever().runCatching {
                        setDataSource(outFile.absolutePath)
                        extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.toLongOrNull() ?: 0L
                    }.getOrNull() ?: 0L
                }
                val title = "インポート ${SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN).format(Date())}"
                val session = Session(
                    id = id,
                    title = title,
                    createdAt = System.currentTimeMillis(),
                    durationMs = durationMs,
                    audioFilePath = outFile.absolutePath,
                    audioSizeBytes = outFile.length(),
                    status = SessionStatus.STOPPED
                )
                repo.save(session)
                onImported(id)
            } catch (e: Exception) {
                Log.e("HomeViewModel", "importAudioFile failed", e)
                onImported(null)
            }
        }
    }
}
