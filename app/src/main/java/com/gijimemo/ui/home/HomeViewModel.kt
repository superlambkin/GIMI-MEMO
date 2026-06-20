package com.gijimemo.ui.home

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import kotlinx.coroutines.flow.first
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

    /** 起動時に TRANSCRIBING でスタックしたセッションを ERROR にリセット */
    init {
        viewModelScope.launch {
            cleanupStuckSessions()
        }
    }

    /**
     * TRANSCRIBING 状態でスタックしたセッションを ERROR に変更する。
     * テストから直接呼び出し可能。
     */
    suspend fun cleanupStuckSessions() {
        try {
            val stuck = repo.observeAll().first().filter { it.status == SessionStatus.TRANSCRIBING }
            for (s in stuck) {
                repo.updateStatus(s.id, SessionStatus.ERROR, "Whisper native crash - on-device WhisperをOFFにしてください")
                Log.w("HomeViewModel", "Reset stuck session ${s.id} from TRANSCRIBING to ERROR")
            }
        } catch (e: Exception) {
            Log.e("HomeViewModel", "Failed to reset stuck sessions", e)
        }
    }

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

    /**
     * Download/GIMI_MEMO ディレクトリを作成する（ファイルピッカーの初期表示用）。
     */
    fun ensureTxtImportDir() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+: MediaStore 経由でディレクトリ作成（ダミーファイルを作って削除）
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/GIMI_MEMO")
                    put(MediaStore.MediaColumns.DISPLAY_NAME, ".gijimemo")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                )
                if (uri != null) context.contentResolver.delete(uri, null, null)
            } else {
                // Android 9以下: File API
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS
                    ), "GIMI_MEMO"
                )
                if (!dir.exists()) dir.mkdirs()
            }
        } catch (_: Exception) { /* 失敗しても許容（ピッカーはルートから開く）*/ }
    }

    /**
     * TXTファイルを読み込み、Session を作成する。
     * 音声はなし、rawTranscript に TXT の内容を格納。
     */
    fun importTxtFile(uri: Uri, onImported: (sessionId: String?) -> Unit) {
        viewModelScope.launch {
            try {
                val id = UUID.randomUUID().toString()
                val content = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        input.bufferedReader().readText()
                    } ?: error("Cannot open input stream")
                }
                val title = "TXT ${SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN).format(Date())}"
                val session = Session(
                    id = id,
                    title = title,
                    createdAt = System.currentTimeMillis(),
                    durationMs = 0L,
                    audioFilePath = "",
                    audioSizeBytes = content.toByteArray().size.toLong(),
                    status = SessionStatus.STOPPED,
                    rawTranscript = content
                )
                repo.save(session)
                onImported(id)
            } catch (e: Exception) {
                Log.e("HomeViewModel", "importTxtFile failed", e)
                onImported(null)
            }
        }
    }
}
