package com.gijimemo.ui.home

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE
import android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
import android.media.MediaMetadataRetriever.METADATA_KEY_SAMPLERATE
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val metaStore: ImportedMetaStore,
    private val sharedAudioStore: SharedAudioStore,
    @ApplicationContext private val context: Context
) : ViewModel() {
    val sessions: StateFlow<List<Session>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** v0.9.1: 他アプリから共有された音声 URI（未共有なら null）。 */
    val sharedAudio: StateFlow<Uri?> = sharedAudioStore.pending

    /** 共有された音声 URI を取得してクリアする。 */
    fun consumeSharedAudio(): Uri? = sharedAudioStore.consume()

    /** v0.7.2: Singleton ImportedMetaStore への薄いファサード。
     *  HomeViewModel の ViewModel インスタンスが画面ごとに違うため、
     *  Singleton に委譲して HomeScreen ↔ ImportReviewScreen 間で共有する。 */
    val lastImportedMeta: StateFlow<ImportedAudioMeta?> = metaStore.meta

    fun clearImportedMeta() = metaStore.clear()

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
            val result = importAudioFileInternal(uri)
            if (result != null) {
                metaStore.set(result.meta)
                onImported(result.session.id)
            } else {
                onImported(null)
            }
        }
    }

    /**
     * v0.9.0: 複数音声ファイルの一括インポート（一括文字起こし用）。
     * 各ファイルをインポートし、元ファイルの最終更新日時（取得不能時はインポート時刻）
     * で時系列ソートしたセッション ID リストを [onImported] で返す。
     * 同時刻はファイル名昇順 → 選択順で安定化する。
     */
    fun importAudioFiles(uris: List<Uri>, onImported: (orderedIds: List<String>) -> Unit) {
        viewModelScope.launch {
            try {
                val imported = uris.mapNotNull { uri ->
                    importAudioFileInternal(uri)?.let { r -> Triple(r.session, r.meta, uri) }
                }
                // 時系列ソート: 更新日時 昇順 → ファイル名 昇順（大文字小文字無視）→ 選択順
                val ordered = imported.sortedWith(
                    compareBy<Triple<Session, ImportedAudioMeta, Uri>> {
                        if (it.second.originalLastModifiedMs > 0L) it.second.originalLastModifiedMs
                        else it.second.importedAtMs
                    }.thenBy { it.second.fileName.lowercase() }
                )
                // 一括画面・ホーム一覧でファイル名を識別できるよう title を元ファイル名にする
                ordered.forEach { (s, meta, _) ->
                    repo.save(s.copy(title = meta.fileName))
                }
                onImported(ordered.map { it.first.id })
            } catch (e: Exception) {
                Log.e("HomeViewModel", "importAudioFiles failed", e)
                onImported(emptyList())
            }
        }
    }

    /** インポート処理の内部実装。成功時は Session とメタ情報を返す。 */
    private suspend fun importAudioFileInternal(uri: Uri): ImportedResult? {
        return try {
            val id = UUID.randomUUID().toString()
            // URI の MIME タイプから適切な拡張子を決定（実態と合わない .mp3 固定を修正）
            val mimeType = context.contentResolver.getType(uri) ?: "audio/mpeg"
            val ext = when {
                mimeType.contains("m4a") || mimeType.contains("mp4") || mimeType.contains("aac") -> "m4a"
                mimeType.contains("mpeg") || mimeType.contains("mp3") -> "mp3"
                mimeType.contains("wav") -> "wav"
                mimeType.contains("ogg") -> "ogg"
                mimeType.contains("flac") -> "flac"
                mimeType.contains("webm") -> "webm"
                else -> "mp3"
            }
            val outFile = File(context.filesDir, "audio/$id.$ext")
            outFile.parentFile?.mkdirs()
            // 元ファイルの表示名 (OpenableColumns.DISPLAY_NAME) を取得
            val originalDisplayName = withContext(Dispatchers.IO) {
                context.contentResolver.query(
                    uri,
                    arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                    null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            } ?: uri.lastPathSegment ?: outFile.name
            // 元ファイルの最終更新日時 (可能なら取得)
            val originalLastModifiedMs = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val idx = c.getColumnIndex("last_modified")
                            if (idx >= 0) c.getLong(idx) else null
                        } else null
                    }
                }.getOrNull()
            } ?: 0L
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                } ?: error("无法打开输入流")
            }
            // MediaMetadataRetriever で duration / sampleRate / bitRate を取得
            val meta = withContext(Dispatchers.IO) {
                MediaMetadataRetriever().runCatching {
                    setDataSource(outFile.absolutePath)
                    val dur = extractMetadata(METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val sr = extractMetadata(METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 0
                    val br = extractMetadata(METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
                    Triple(dur, sr, br)
                }.getOrNull() ?: Triple(0L, 0, 0)
            }
            val (durationMs, sampleRate, bitRate) = meta
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
            ImportedResult(session, ImportedAudioMeta(
                fileName = originalDisplayName,
                fileLocation = outFile.absolutePath,
                sampleRate = sampleRate,
                bitRate = bitRate,
                durationMs = durationMs,
                fileSizeBytes = outFile.length(),
                importedAtMs = System.currentTimeMillis(),
                originalLastModifiedMs = originalLastModifiedMs
            ))
        } catch (e: Exception) {
            Log.e("HomeViewModel", "importAudioFile failed", e)
            null
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

/**
 * v0.7.2: インポートした音声ファイルのメタ情報。ImportReviewScreen で表示。
 * - fileName: 元の表示ファイル名 (例: "meeting_2024.mp3")
 * - fileLocation: 内部保存先パス (例: "/data/data/.../files/audio/{uuid}.m4a")
 * - sampleRate: Hz (0 = 不明)
 * - bitRate: bps (0 = 不明)
 * - durationMs: ミリ秒
 * - fileSizeBytes: バイト
 * - importedAtMs: インポート実行時刻
 * - originalLastModifiedMs: 元ファイルの最終更新日時 (取得不可なら 0)
 */
data class ImportedAudioMeta(
    val fileName: String,
    val fileLocation: String,
    val sampleRate: Int,
    val bitRate: Int,
    val durationMs: Long,
    val fileSizeBytes: Long,
    val importedAtMs: Long,
    val originalLastModifiedMs: Long
)

/** v0.9.0: インポート内部処理の戻り値。単一インポートはメタ情報を、一括インポートはソート用に使用する。 */
data class ImportedResult(
    val session: Session,
    val meta: ImportedAudioMeta
)
