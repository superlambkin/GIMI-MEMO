package com.gijimemo.ui.recording

import android.content.ContentValues
import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gijimemo.audio.AudioProcessingConfig
import com.gijimemo.audio.AudioRecorder
import com.gijimemo.audio.RecordingState
import com.gijimemo.data.model.Session
import com.gijimemo.data.model.SessionStatus
import com.gijimemo.data.repository.SessionRepository
import com.gijimemo.data.repository.SettingsRepository
import com.gijimemo.llm.OnDeviceWhisperClient
import com.gijimemo.llm.TranscriptDelta
import com.gijimemo.ui.processing.TranscriptionService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject

/** 再生状態 */
enum class PlaybackState {
    /** 停止中（未再生 or 再生完了） */
    Idle,
    /** 再生中 */
    Playing,
    /** 一時停止中 */
    Paused,
}

@HiltViewModel
class RecordingViewModel @Inject constructor(
    private val recorder: AudioRecorder,
    private val repo: SessionRepository,
    private val settings: SettingsRepository,
    @ApplicationContext private val context: Context,
    // OnDeviceWhisperClient は LlmModule で @Provides されており、
    // core-whisper を持つビルドでは必ず Hilt から供給される。
    // テストや他構成で欠ける可能性に備えて nullable + デフォルト null とせず、
    // ここは必須依存として注入する (録音中の preload は no-op フォールバックで安全)。
    private val onDeviceWhisper: OnDeviceWhisperClient
) : ViewModel() {

    init {
        // Idle 状態でも録音設定（SR/BR）を表示するため、起動時に読み込む
        viewModelScope.launch {
            try {
                _recordingConfig.value = AudioProcessingConfig(
                    sampleRate = settings.recordingSampleRate.first(),
                    bitRate = settings.recordingBitRate.first(),
                    noiseSuppressor = settings.enableNoiseSuppressor.first(),
                    automaticGainControl = settings.enableAutomaticGainControl.first(),
                    voiceActivityDetection = settings.enableVoiceActivityDetection.first()
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load recording config: ${e.message}")
            }
        }
    }

    val state: StateFlow<RecordingState> = recorder.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, RecordingState.Idle)

    /** 録音中の実時間振幅 (0-32767)。UI の波形表示用。 */
    val amplitude: kotlinx.coroutines.flow.Flow<Int> = recorder.amplitude

    /**
     * 直近に録音したファイルの位置标识。
     * - API 29+：MediaStore content URI 字符串（`content://...`）
     * - API 26-28：公共目录绝对路径（`/storage/emulated/0/Music/GijiMemo/...`）
     */
    var audioFilePath: String? = null
        private set

    private val _sessionId = MutableStateFlow<String?>(null)

    private val _lastSavedSession = MutableStateFlow<Session?>(null)
    /** 直近に stopRecording() で保存に成功した Session。Stopped UI で表示。 */
    val lastSavedSession: StateFlow<Session?> = _lastSavedSession.asStateFlow()

    /** 新規録音開始時にクリア。Stopped 状態の「文字起こし」ボタンを非活性化。 */
    fun resetLastSavedSession() {
        _lastSavedSession.value = null
    }

    /**
     * 設定画面の「オンデバイスWhisper」と同期する StateFlow。
     * 録音画面にラジオボタンで露出させ、ユーザーが録音前に切替可能にする。
     */
    val useOnDeviceAsr: StateFlow<Boolean> = settings.useOnDeviceAsr
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 設定画面に通知するため、SettingsRepository 経由で永続化する。 */
    fun setUseOnDeviceAsr(enabled: Boolean) {
        viewModelScope.launch { settings.setUseOnDeviceAsr(enabled) }
    }

    // ─── 録音タイマー ───────────────────────────────────────
    // ViewModel 側に「録音開始 wall-clock ミリ秒」を保持し、UI は
    // currentTimeMillis - startTime を 200ms ごとに再計算して表示する。
    // startTime は startRecording() で set、stopRecording() / discardRecording()
    // で reset。ViewModel 側に置くと Compose Navigation の NavBackStackEntry
    // 再利用問題と無関係に正確に動く。
    private val _recordingStartMs = MutableStateFlow<Long?>(null)
    val recordingStartMs: StateFlow<Long?> = _recordingStartMs.asStateFlow()

    // 録音中画面の上部に表示する現在の録音パラメータ（サンプリングレート / ビットレート）。
    // startRecording() で設定、stop/discard で null に戻す。UI は RecordingState.Recording
    // の間のみ表示するため、record 開始前は null でも問題なし。
    private val _recordingConfig = MutableStateFlow<AudioProcessingConfig?>(null)
    val recordingConfig: StateFlow<AudioProcessingConfig?> = _recordingConfig.asStateFlow()

    // v0.7.x: ストリーミング文字起こしの中間結果。3秒窓ごとに onDeviceWhisper.transcribeStream
    // が emit する TranscriptDelta を逐次追記する。停止 / 破棄時にクリア。
    private val _partialTranscript = MutableStateFlow("")
    val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()
    private var streamingJob: Job? = null

    private val _playbackState = MutableStateFlow(PlaybackState.Idle)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _playbackPosition = MutableStateFlow(0L)
    val playbackPosition: StateFlow<Long> = _playbackPosition.asStateFlow()
    private val _playbackDuration = MutableStateFlow(0L)
    val playbackDuration: StateFlow<Long> = _playbackDuration.asStateFlow()
    private var positionJob: kotlinx.coroutines.Job? = null

    private var player: MediaPlayer? = null

    // 录音中需要保持 PFD 不关闭，录音完成 + 更新 IS_PENDING 后再关闭
    private var pendingPfd: ParcelFileDescriptor? = null
    private var pendingUri: Uri? = null

    override fun onCleared() {
        super.onCleared()
        streamingJob?.cancel()
        streamingJob = null
        releasePlayer()
        closePendingFd()
    }

    companion object {
        private const val TAG = "RecordingViewModel"
    }

    fun startRecording() {
        // 前回の再生をクリア
        releasePlayer()
        _playbackState.value = PlaybackState.Idle
        audioFilePath = null
        closePendingFd()

        // タイマー開始: 录音開始時刻を記録
        _recordingStartMs.value = System.currentTimeMillis()

        // 録音開始と同時に、Whisper モデルをバックグラウンドで preload する。
        // ユーザーが録音を停止して「文字起こし」を押す頃には既にロード済みになり、
        // 30〜90 秒のモデル読み込み待ちが削減される。
        viewModelScope.launch(Dispatchers.IO) {
            try { onDeviceWhisper.preloadModel() } catch (_: Exception) { /* 起動経路には影響させない */ }
        }

        // 画面消灯・プロセスキルイープからの保護: Service 起動 + WakeLock 取得
        TranscriptionService.start(context)
        // 部分転写をクリア（前セッションの残骸を表示しないため）
        _partialTranscript.value = ""

        // ストリーミング文字起こし: AudioRecorder.pcmChunkFlow を購読し、
        // 3秒窓 + 0.5秒オーバーラップで逐次推論。結果は _partialTranscript に追記。
        // 設定で「オンデバイスWhisper」が OFF の場合は起動しない（UI 仕様に合わせる）。
        streamingJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val useOnDevice = settings.useOnDeviceAsr.first()
                if (!useOnDevice) {
                    Log.d(TAG, "streaming skipped: useOnDeviceAsr=false")
                    return@launch
                }
                onDeviceWhisper.transcribeStream(
                    pcmFlow = recorder.pcmChunkFlow,
                    language = "", // 言語ヒントは現状設定 UI なし → 自動検出
                    sampleRate = 16000,
                    windowMs = 3000,
                    overlapMs = 500,
                ).collect { delta: TranscriptDelta ->
                    _partialTranscript.update { it + delta.text + " " }
                }
            } catch (e: Exception) {
                // 部分転写エラーは致命的ではない、ログのみ
                Log.w(TAG, "Streaming transcribe failed: ${e.message}", e)
            }
        }

        viewModelScope.launch {
            val id = UUID.randomUUID().toString()
            _sessionId.value = id
            // 設定から録音パラメータ + 音声処理設定を読む
            val config = AudioProcessingConfig(
                sampleRate = settings.recordingSampleRate.first(),
                bitRate = settings.recordingBitRate.first(),
                noiseSuppressor = settings.enableNoiseSuppressor.first(),
                automaticGainControl = settings.enableAutomaticGainControl.first(),
                voiceActivityDetection = settings.enableVoiceActivityDetection.first()
            )
            // 録音中の画面上部で SR/BR 表示するため StateFlow に流す
            _recordingConfig.value = config
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startRecordingApi29(id, config)
                } else {
                    startRecordingLegacy(id, config)
                }
            } catch (e: Exception) {
                Log.e("GijiMemo", "录音启动失败: ${e.message}", e)
                closePendingFd()
                _recordingStartMs.value = null
            }
        }
    }

    /** API 29+：MediaStore + Scoped Storage 写入 Music/GijiMemo/ */
    private suspend fun startRecordingApi29(sessionId: String, config: AudioProcessingConfig) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$sessionId.m4a")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/GijiMemo")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore.insert returned null")
        val pfd = resolver.openFileDescriptor(uri, "w")
            ?: throw IOException("openFileDescriptor returned null")
        pendingPfd = pfd
        pendingUri = uri
        recorder.startWithFileDescriptor(pfd.fileDescriptor, config)
    }

    /** API 26-28：降级到 Environment.getExternalStoragePublicDirectory */
    private suspend fun startRecordingLegacy(sessionId: String, config: AudioProcessingConfig) = withContext(Dispatchers.IO) {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "GijiMemo"
        )
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("Failed to create directory: ${dir.absolutePath}")
        }
        val outFile = File(dir, "$sessionId.m4a")
        recorder.start(outFile.absolutePath, config)
    }

    fun pauseRecording() = viewModelScope.launch { recorder.pause() }
    fun resumeRecording() = viewModelScope.launch { recorder.resume() }

    suspend fun stopRecording(title: String, durationMs: Long): Session? {
        val id = _sessionId.value ?: return null
        // ストリーミング推論を停止（PCM 供給源が recorder.stop() で止まるため）
        streamingJob?.cancel()
        streamingJob = null
        try {
            recorder.stop()
        } catch (e: Exception) {
            Log.e("GijiMemo", "停止录音失败: ${e.message}", e)
            closePendingFd()
            _recordingStartMs.value = null
            return null
        }
        val location = finalizeAudioLocation(id) ?: run {
            _recordingStartMs.value = null
            return null
        }
        // タイマー停止
        _recordingStartMs.value = null
        _recordingConfig.value = null
        audioFilePath = location
        val sizeBytes = queryAudioSize(location)
        val session = Session(
            id = id,
            title = title.ifBlank { "会议 ${System.currentTimeMillis()}" },
            createdAt = System.currentTimeMillis(),
            durationMs = durationMs,
            audioFilePath = location,
            audioSizeBytes = sizeBytes,
            status = SessionStatus.STOPPED
        )
        repo.save(session)
        // UI 侧（Stopped 状態）用: stopRecording 成功結果を ViewModel 内で保持
        _lastSavedSession.value = session
        // Phase 6: ストリーミング推論 Service（録音中に動いているケース）を停止。
        // 未起動時の stop() は no-op で冪等。確定転写は既存フローの
        // StoppedPlaybackAndTranscribe → Processing 起動で実行される。
        TranscriptionService.stop(context)
        return session
    }

    /**
     * Discard the current recording: stop the recorder if still running and
     * delete the partial audio file. No Session is created. Safe to call
     * from any state (Idle / Recording / Paused / Stopped).
     */
    fun discardRecording() {
        viewModelScope.launch {
            // ストリーミング推論を停止して部分転写をクリア
            streamingJob?.cancel()
            streamingJob = null
            _partialTranscript.value = ""
            try {
                recorder.stop()
            } catch (e: Exception) {
                Log.w("GijiMemo", "discard: recorder.stop 异常 (可忽略): ${e.message}")
            }
            val sessionId = _sessionId.value
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val uri = pendingUri
                if (uri != null) {
                    try {
                        context.contentResolver.delete(uri, null, null)
                    } catch (e: Exception) {
                        Log.w("GijiMemo", "discard: 刪除 MediaStore 行失败: ${e.message}")
                    }
                }
                closePendingFd()
            } else if (sessionId != null) {
                val file = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                    "GijiMemo/$sessionId.m4a"
                )
                if (file.exists()) {
                    file.delete()
                }
                closePendingFd()
            }
            audioFilePath = null
            _sessionId.value = null
            _lastSavedSession.value = null
            _recordingStartMs.value = null
            _recordingConfig.value = null
            // Phase 6: 破棄時も念のため推論 Service を停止（冪等）。
            TranscriptionService.stop(context)
            Log.d(TAG, "Recording discarded (sessionId=$sessionId)")
        }
    }

    /**
     * 录音结束后处理 MediaStore pending 状态 + 决定存储的位置标识。
     * - API 29+：清除 IS_PENDING，返回 content URI
     * - API 26-28：返回文件绝对路径
     */
    private suspend fun finalizeAudioLocation(sessionId: String): String? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = pendingUri
            if (uri == null) {
                Log.e("GijiMemo", "API29+ stop 时 pendingUri 为空")
                return@withContext null
            }
            val resolver = context.contentResolver
            try {
                val values = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                Log.e("GijiMemo", "清除 IS_PENDING 失败: ${e.message}", e)
            } finally {
                closePendingFd()
            }
            uri.toString()
        } else {
            closePendingFd()
            val file = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "GijiMemo/$sessionId.m4a"
            )
            if (file.exists()) file.absolutePath else null
        }
    }

    /** 从 URI 或文件路径查询文件大小（录音结束后） */
    private fun queryAudioSize(location: String): Long {
        return try {
            if (location.startsWith("content://")) {
                context.contentResolver.query(Uri.parse(location), null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (idx >= 0) c.getLong(idx) else 0L
                    } else 0L
                } ?: 0L
            } else {
                File(location).length()
            }
        } catch (e: Exception) {
            Log.w("GijiMemo", "查询音频大小失败: ${e.message}")
            0L
        }
    }

    private fun closePendingFd() {
        try { pendingPfd?.close() } catch (_: Exception) {}
        pendingPfd = null
        pendingUri = null
    }

    // ─── 再生制御 ───────────────────────────────────────────────

    fun startPlayback() {
        // 优先从 lastSavedSession 读取（StateFlow 持有的最新值，UI 显示什么就播什么）。
        // audioFilePath 私有变量是辅助缓存，单纯依赖它在 Compose 重组下偶发为 null。
        val location = _lastSavedSession.value?.audioFilePath
            ?: audioFilePath
            ?: run {
                Log.w(TAG, "startPlayback: 无可播放位置（lastSavedSession 与 audioFilePath 均为 null）")
                return
            }
        if (location.isBlank()) return
        Log.d(TAG, "startPlayback: location=$location")
        releasePlayer()
        _playbackPosition.value = 0L; _playbackDuration.value = 0L
        try {
            val mp = MediaPlayer().apply {
                if (location.startsWith("content://") || location.startsWith("file://")) {
                    setDataSource(context, Uri.parse(location))
                } else {
                    setDataSource(location)
                }
                setOnCompletionListener {
                    _playbackState.value = PlaybackState.Idle
                    positionJob?.cancel()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e("GijiMemo", "MediaPlayer error: what=$what extra=$extra location=$location")
                    _playbackState.value = PlaybackState.Idle
                    positionJob?.cancel()
                    true
                }
                prepare()
                start()
            }
            player = mp
            _playbackDuration.value = mp.duration.toLong()
            positionJob = viewModelScope.launch {
                while (true) {
                    player?.let { if (it.isPlaying) _playbackPosition.value = it.currentPosition.toLong() }
                    kotlinx.coroutines.delay(250)
                }
            }
            _playbackState.value = PlaybackState.Playing
        } catch (e: Exception) {
            Log.e("GijiMemo", "再生開始失敗 (location=$location): ${e.message}", e)
            _playbackState.value = PlaybackState.Idle
        }
    }

    fun pausePlayback() {
        player?.let {
            if (it.isPlaying) {
                it.pause()
                _playbackState.value = PlaybackState.Paused
            }
        }
    }

    fun resumePlayback() {
        player?.let {
            if (!it.isPlaying && _playbackState.value == PlaybackState.Paused) {
                it.start()
                _playbackState.value = PlaybackState.Playing
            }
        }
    }

    fun stopPlayback() {
        positionJob?.cancel()
        releasePlayer()
        _playbackState.value = PlaybackState.Idle
        _playbackPosition.value = 0L
    }

    fun seekAudio(positionMs: Int) {
        player?.let {
            if (positionMs in 0..it.duration) {
                it.seekTo(positionMs)
                _playbackPosition.value = positionMs.toLong()
            }
        }
    }

    private fun releasePlayer() {
        player?.apply {
            if (isPlaying) stop()
            release()
        }
        player = null
    }

    // ─── 保存 ───────────────────────────────────────────────

    /** 録音ファイルを Download/GIMI_MEMO/ にコピー保存 */
    fun saveAudioToDownloads() {
        val location = _lastSavedSession.value?.audioFilePath ?: return
        if (location.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val session = _lastSavedSession.value ?: return@launch
                val fileName = "${session.id}.m4a"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/GIMI_MEMO")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = context.contentResolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                    )
                    if (uri != null) {
                        context.contentResolver.openOutputStream(uri)?.use { os ->
                            if (location.startsWith("content://")) {
                                context.contentResolver.openInputStream(Uri.parse(location))?.use { ins ->
                                    ins.copyTo(os)
                                }
                            } else {
                                File(location).inputStream().use { ins -> ins.copyTo(os) }
                            }
                        }
                        values.clear()
                        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        context.contentResolver.update(uri, values, null, null)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val dir = File(
                        Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS
                        ), "GIMI_MEMO"
                    )
                    dir.mkdirs()
                    val src = File(location)
                    if (src.exists()) {
                        src.copyTo(File(dir, fileName), overwrite = true)
                    }
                }
                Log.i(TAG, "Audio saved to Download/GIMI_MEMO/$fileName")
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "音声ファイルを保存しました", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "saveAudioToDownloads failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "保存失敗: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
