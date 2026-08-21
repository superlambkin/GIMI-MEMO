// app/src/main/java/com/gijimemo/ui/import_review/BatchImportViewModel.kt
package com.gijimemo.ui.import_review

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gijimemo.data.model.LlmCallMode
import com.gijimemo.data.model.Session
import com.gijimemo.data.model.SessionStatus
import com.gijimemo.data.repository.SessionRepository
import com.gijimemo.data.repository.SettingsRepository
import com.gijimemo.llm.LlmProvider
import com.gijimemo.ui.processing.CloudWhisperTranscriber
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/** 一括インポートのフェーズ */
enum class BatchImportPhase { PREPARING, TRANSCRIBING, DONE, ERROR }

/** 各ファイルの転写状態 */
enum class BatchFileStatus { PENDING, TRANSCRIBING, DONE, FAILED }

data class BatchFileItem(
    val sessionId: String,
    val fileName: String,
    val status: BatchFileStatus = BatchFileStatus.PENDING,
    val transcript: String = "",
    val error: String? = null
)

data class BatchImportState(
    val phase: BatchImportPhase = BatchImportPhase.PREPARING,
    val files: List<BatchFileItem> = emptyList(),
    val currentIndex: Int = 0,
    val currentDetail: String = "",
    val error: String? = null,
    /** 完了時に作成された結合 Session の id。画面側の LaunchedEffect が遷移に使用する。 */
    val combinedSessionId: String? = null
)

/**
 * v0.9.0: 複数 MP3 の一括文字起こし。
 * - 時系列（元ファイル更新日時）順に 1 ファイルずつ転写
 * - 各ファイルの転写結果を個別 Session に保存（ホームに残る）
 * - 全ファイルの転写を「【n. ファイル名】」ヘッダ付きで結合した結合 Session も作成
 * - 一部失敗は警告ヘッダ付きで結合、全失敗はエラー
 */
@HiltViewModel
class BatchImportViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: SessionRepository,
    private val settings: SettingsRepository,
    private val provider: LlmProvider,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val ids: List<String> = savedStateHandle.get<String>("ids").orEmpty()
        .split(",").map { it.trim() }.filter { it.isNotBlank() }

    private val cloudTranscriber = CloudWhisperTranscriber(settings, provider, context)

    private val _state = MutableStateFlow(BatchImportState())
    val state: StateFlow<BatchImportState> = _state.asStateFlow()

    /**
     * 一括転写を開始する。
     * 完了は state.phase == DONE と state.combinedSessionId で通知する
     * （v0.9.1: 画面遷移は Navigation のライフサイクル問題を避けるため
     * ViewModel から直接行わず、画面側の LaunchedEffect が監視する）。
     */
    fun start() {
        if (_state.value.phase == BatchImportPhase.TRANSCRIBING) return
        viewModelScope.launch {
            try {
                val sessions = ids.mapNotNull { repo.getById(it) }
                if (sessions.isEmpty()) {
                    _state.value = _state.value.copy(phase = BatchImportPhase.ERROR, error = "インポートしたファイルが見つかりません")
                    return@launch
                }
                _state.value = _state.value.copy(
                    phase = BatchImportPhase.TRANSCRIBING,
                    files = sessions.map { BatchFileItem(it.id, it.title.ifBlank { "ファイル" }) }
                )

                for ((i, s) in sessions.withIndex()) {
                    _state.update { st ->
                        st.copy(
                            currentIndex = i,
                            currentDetail = "",
                            files = st.files.mapIndexed { fi, f ->
                                if (fi == i) f.copy(status = BatchFileStatus.TRANSCRIBING) else f
                            }
                        )
                    }
                    try {
                        val t0 = System.currentTimeMillis()
                        val text = transcribeOne(s)
                        val elapsed = System.currentTimeMillis() - t0
                        // 個別 Session に転写結果を保存（ホームに残す）
                        repo.save(s.copy(
                            rawTranscript = text,
                            status = SessionStatus.READY,
                            llmCallMode = LlmCallMode.WHISPER_THEN_SUMMARY,
                            transcribeDurationMs = elapsed
                        ))
                        _state.update { st ->
                            st.copy(files = st.files.mapIndexed { fi, f ->
                                if (fi == i) f.copy(status = BatchFileStatus.DONE, transcript = text) else f
                            })
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Batch file ${s.title} failed: ${e.message}")
                        _state.update { st ->
                            st.copy(files = st.files.mapIndexed { fi, f ->
                                if (fi == i) f.copy(status = BatchFileStatus.FAILED, error = e.message) else f
                            })
                        }
                    }
                }

                // 結合 Session 作成
                val doneFiles = _state.value.files.filter { it.status == BatchFileStatus.DONE }
                val failedFiles = _state.value.files.filter { it.status == BatchFileStatus.FAILED }
                if (doneFiles.isEmpty()) {
                    _state.value = _state.value.copy(
                        phase = BatchImportPhase.ERROR,
                        error = "全ファイルの文字起こしに失敗しました。ネットワーク状態を確認して再試行してください。"
                    )
                    return@launch
                }

                val combinedText = buildString {
                    if (failedFiles.isNotEmpty()) {
                        append("【警告: 以下のファイルの文字起こしに失敗しました】\n")
                        failedFiles.forEach { append("・${it.fileName}\n") }
                        append("\n")
                    }
                    doneFiles.forEachIndexed { idx, f ->
                        val fileNo = _state.value.files.indexOfFirst { it.sessionId == f.sessionId } + 1
                        if (idx > 0) append("\n\n")
                        append("【$fileNo. ${f.fileName}】\n")
                        append(f.transcript.trim())
                    }
                }

                val combinedId = UUID.randomUUID().toString()
                val dateStr = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN).format(Date())
                val combinedSession = Session(
                    id = combinedId,
                    title = "結合文字起こし $dateStr",
                    createdAt = System.currentTimeMillis(),
                    durationMs = sessions.sumOf { it.durationMs },
                    audioFilePath = "",
                    audioSizeBytes = 0L,
                    status = SessionStatus.STOPPED,
                    rawTranscript = combinedText.trim(),
                    llmCallMode = LlmCallMode.WHISPER_THEN_SUMMARY
                )
                repo.save(combinedSession)

                _state.value = _state.value.copy(phase = BatchImportPhase.DONE, combinedSessionId = combinedId)
            } catch (e: Exception) {
                Log.e(TAG, "Batch import failed", e)
                _state.value = _state.value.copy(phase = BatchImportPhase.ERROR, error = e.message)
            }
        }
    }

    /** 1 ファイルを転写する。オンデバイス設定なら端末内 Whisper、それ以外はクラウド Whisper。 */
    private suspend fun transcribeOne(session: Session): String {
        val file = File(session.audioFilePath)
        val useOnDevice = settings.useOnDeviceAsr.first()
        return if (useOnDevice) {
            val providerConfig = settings.selectedProvider()
            val apiKey = settings.getApiKey(providerConfig.apiKeyRef)
                ?: if (providerConfig.name == "Ollama") "ollama"
                else error("API Key for ${providerConfig.name} not set")
            val model = settings.modelForProvider(providerConfig.name).first()
                ?: providerConfig.defaultModel
            val client = provider.createClient(
                config = providerConfig,
                apiKey = apiKey,
                model = model,
                useOnDeviceAsr = true
            )
            _state.update { it.copy(currentDetail = "端末内 Whisper で文字起こし中...") }
            client.transcribeOnly(file)
        } else {
            val chunkSizeMb = settings.defaultChunkMinutes.first().coerceIn(1, 24)
            cloudTranscriber.transcribeFile(file, chunkSizeMb) { p ->
                val chunkSuffix = if (p.totalChunks > 0) " (Chunk ${p.completedChunks}/${p.totalChunks})" else ""
                _state.update { it.copy(currentDetail = p.detailStatus + chunkSuffix) }
            }
        }
    }

    companion object {
        private const val TAG = "BatchImportVM"
    }
}
