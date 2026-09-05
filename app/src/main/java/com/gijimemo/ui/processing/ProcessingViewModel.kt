package com.gijimemo.ui.processing

import android.content.ContentValues
import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gijimemo.data.model.LlmCallMode
import com.gijimemo.data.model.SessionStatus
import com.gijimemo.data.prefs.SettingsDataStore
import com.gijimemo.data.repository.SessionRepository
import com.gijimemo.data.repository.SettingsRepository
import com.gijimemo.document.MarkdownGenerator
import com.gijimemo.document.TextGenerator
import com.gijimemo.document.WordDocumentGenerator
import com.gijimemo.llm.LlmClient
import com.gijimemo.llm.LlmEvent
import com.gijimemo.llm.LlmException
import com.gijimemo.llm.LlmProvider
import com.gijimemo.llm.NetworkWhisperClient
import com.gijimemo.share.EmailShareService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** 処理フェーズ */
enum class ProcessingPhase {
    IDLE,
    TRANSCRIBING,
    TRANSCRIBED,
    SUMMARIZING,
    COMPLETED,
    ERROR
}

data class ProcessingState(
    val phase: ProcessingPhase = ProcessingPhase.IDLE,
    val rawTranscript: String = "",
    val summaryText: String = "",
    val error: String? = null,
    val totalElapsedMs: Long = 0L,
    val transcribeDurationMs: Long = 0L,
    val useOnDevice: Boolean = false,
    /** 詳細ステータス行 */
    val detailStatus: String = "",
    /** 使用中サービス名 */
    val activeProvider: String = "",
    /** 使用中モデル名 */
    val activeModel: String = "",
    /** 分割予測 */
    val totalChunks: Int = 0,
    val completedChunks: Int = 0,
    /** 1チャンクあたりの予想API時間(ms) */
    val chunkTimeEstimateMs: Long = 0L,
    /** 分割処理にかかった実際の時間(ms) */
    val splitTimeMs: Long = 0L,
    /** v0.9.1: 元の音声の長さ(ms)。セッションの durationMs から設定。 */
    val audioDurationMs: Long = 0L,
    // ── v0.7.2: パフォーマンス計測 ──
    /** モデルロードにかかった時間(ms) */
    val modelLoadMs: Long = 0L,
    /** 音声デコード (AAC→WAV) 時間(ms) */
    val decodeMs: Long = 0L,
    /** 各転写窓の処理時間リスト (ms) */
    val windowTimingsMs: List<Long> = emptyList(),
    /** whisper.cpp が使用するスレッド数 */
    val threadCount: Int = 0,
    /** GPU フラグ (実機では CMake 未統合のため false) */
    val useGpu: Boolean = false,
    /** LLM 要約 API 呼び出し時間(ms) */
    val summaryApiMs: Long = 0L
) {
    val isTranscribing: Boolean get() = phase == ProcessingPhase.TRANSCRIBING
    val isSummarizing: Boolean get() = phase == ProcessingPhase.SUMMARIZING
    val isFinished: Boolean get() = phase == ProcessingPhase.COMPLETED
    val isTranscribed: Boolean get() = phase == ProcessingPhase.TRANSCRIBED
}

@HiltViewModel
class ProcessingViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: SessionRepository,
    private val settings: SettingsRepository,
    private val provider: LlmProvider,
    private val wordGen: WordDocumentGenerator,
    private val mdGen: MarkdownGenerator,
    private val txtGen: TextGenerator,
    private val emailShare: EmailShareService,
    private val networkWhisperClient: NetworkWhisperClient,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val tag = "GijiMemoLLM"

    val sessionId: String = savedStateHandle.get<String>("sessionId") ?: error("missing sessionId")
    /** ユーザーが選択した文字起こし言語ヒント ("ja" / "zh" / "")。空なら自動判定。 */
    val langHint: String = (savedStateHandle.get<String>("lang") ?: "").also {
        Log.d(tag, "langHint from route: '$it'")
    }

    private val _state = MutableStateFlow(ProcessingState())
    val state: StateFlow<ProcessingState> = _state.asStateFlow()

    // キャッシュ: start() で一度だけ初期化する値
    private var cachedClient: LlmClient? = null
    private var cachedAudioFile: File? = null
    private var cachedProviderName: String = ""
    private var cachedModel: String = ""
    private var cachedPrompt: String = ""
    private var cachedUseOnDevice: Boolean = false
    /** v0.9.1: 文字起こし方式（cloud / on_device / network） */
    private var cachedAsrMode: String = SettingsDataStore.ASR_MODE_CLOUD
    /** オンデバイスWhisperのモデル名 */
    private var cachedWhisperModel: String = ""
    /** v0.7.2: GPU 使用フラグ (OpenCL 有効化フラグを State に伝播) */
    private var cachedUseGpu: Boolean = false
    /** 実行時の呼び出しモード。finalizeSession() で Session に保存。 */
    private var cachedCallMode: LlmCallMode = LlmCallMode.MULTIMODAL
    /** 文字起こしフェーズの開始時刻 (transcribe 専用時間計測用)。 */
    private var transcribeStartMs: Long = 0L
    /** 文字起こし開始時刻 (start() で記録)。完了時に合計時間として表示する。 */
    private var processingStartMs: Long = 0L
    /** 設定から読み込んだチャンクサイズ (MB) */
    private var chunkSizeMb: Int = 10
    /** v0.9.1: 元の音声の長さ(ms)。結果画面の時間内訳表示用。 */
    private var sessionAudioDurationMs: Long = 0L
    /** クラウドWhisper文字起こしエンジン（分割・並列送信・リトライ。一括インポートと共有） */
    private val cloudTranscriber = CloudWhisperTranscriber(settings, provider, context)
    /** 音声再生用 MediaPlayer */
    private var mediaPlayer: MediaPlayer? = null
    private var positionJob: kotlinx.coroutines.Job? = null
    private val _playbackState = MutableStateFlow(false)
    val playbackState: StateFlow<Boolean> = _playbackState.asStateFlow()
    private val _playbackPosition = MutableStateFlow(0L)
    val playbackPosition: StateFlow<Long> = _playbackPosition.asStateFlow()
    private val _playbackDuration = MutableStateFlow(0L)
    val playbackDuration: StateFlow<Long> = _playbackDuration.asStateFlow()

    /**
     * 录音位置标识 → 可供 [LlmClient] 使用的本地 [File]。
     * - `content://` URI：从 ContentResolver 复制到 cacheDir 临时文件
     * - 普通文件路径：直接包装为 [File]
     * - 旧数据（私有目录文件路径）：直接包装为 [File]
     *
     * 注意：调用方需保证运行在 IO 调度器上下文（生产中由 viewModelScope 切换）。
     */
    private fun resolveAudioFile(location: String): File {
        if (location.startsWith("content://")) {
            val uri = Uri.parse(location)
            // 写入 cacheDir/audio_cache/ 避免和正式录音文件冲突
            val cacheDir = File(context.cacheDir, "audio_cache").apply { mkdirs() }
            val target = File(cacheDir, "transcribe_${System.currentTimeMillis()}.m4a")
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: error("无法打开 content stream: $location")
            return target
        }
        val f = File(location)
        if (!f.exists()) error("Audio file not found: $location")
        return f
    }

    /**
     * 処理開始: 設定を読み込み、ForegroundService で画面オフ対策し、
     * ファイルサイズに応じて分割文字起こしまたは一括処理を行う。
     */
    fun start() {
        if (_state.value.phase != ProcessingPhase.IDLE && _state.value.phase != ProcessingPhase.ERROR) return
        _state.value = ProcessingState() // ERROR からも再開可能に
        processingStartMs = System.currentTimeMillis()
        // v0.9.2: 文字起こし結果の処理時間は「（日本語）を押してから結果が出るまで」とする。
        // 従来は転写フェーズ開始時点から計測していたため、画面遷移・設定読込・クライアント初期化
        // の数秒が含まれなかった。start()（＝ボタン押下直後）を起点に統一する。
        transcribeStartMs = System.currentTimeMillis()

        // 画面オフ対策: ForegroundService + WakeLock を開始
        TranscriptionService.start(context)

        viewModelScope.launch {
            try {
                val session = repo.getById(sessionId) ?: error("Session $sessionId not found")

                // TXTインポート（音声なし、rawTranscriptあり）→ 文字起こしスキップ
                if (session.rawTranscript != null && session.audioFilePath.isNullOrBlank()) {
                    setupLlmClient()
                    cachedAudioFile = null
                    _state.value = ProcessingState(
                        phase = ProcessingPhase.TRANSCRIBED,
                        rawTranscript = session.rawTranscript ?: ""
                    )
                    Log.d(tag, "TXT import: skip transcribe, rawTranscript=${session.rawTranscript?.length ?: 0}chars")
                    return@launch
                }

                val audioFile = resolveAudioFile(session.audioFilePath)
                sessionAudioDurationMs = session.durationMs

                // 常にユーザーが選択したプロバイダを使用（autoProviderMode は設定画面にUIがなく無効化済み）
                val providerConfig = settings.selectedProvider()
                val callMode = settings.defaultCallMode.first()
                cachedCallMode = callMode

                // 文字起こし（ASR）はユーザー設定に従う（LLMプロバイダのマルチモーダル対応とは無関係）
                val asrMode = settings.asrMode.first()
                val useOnDevice = asrMode == SettingsDataStore.ASR_MODE_ON_DEVICE

                cachedAudioFile = audioFile
                cachedAsrMode = asrMode
                cachedUseOnDevice = useOnDevice
                cachedClient = initializeLlmClient(useOnDevice)

                // 文字起こしは常に OpenAI Whisper API を使用する（LLM プロバイダとは独立）。
                // Whisper クライアントはキャッシュせず chunkAndTranscribe 内で最新の API Key から毎回生成する
                // （v0.9.0: 再試行時に設定変更したキーが反映されない不具合の修正）。

                // 分割サイズを設定から読み込む（1〜24MB）
                chunkSizeMb = settings.defaultChunkMinutes.first().coerceIn(1, 24)
                val decodeEnabled = settings.decodeEnabled.first()
                Log.d(tag, "Processing start: session=$sessionId provider=${providerConfig.name} " +
                        "model=$cachedModel mode=$callMode asrMode=$asrMode onDevice=$useOnDevice " +
                        "supportsMultimodal=${providerConfig.supportsMultimodal} " +
                        "audio=${audioFile.absolutePath} size=${audioFile.length()} " +
                        "chunkSize=${chunkSizeMb}MB decodeEnabled=$decodeEnabled")

                repo.updateStatus(sessionId, SessionStatus.TRANSCRIBING)

                when (asrMode) {
                    // オンデバイス Whisper → 端末内処理（分割不要）
                    SettingsDataStore.ASR_MODE_ON_DEVICE -> {
                        startTranscribePhase(audioFile)
                    }
                    // ローカルPC のネットワーク Whisper サーバ → 分割せずそのまま送信
                    SettingsDataStore.ASR_MODE_NETWORK -> {
                        networkTranscribe(audioFile)
                    }
                    // クラウド文字起こしは常に 20MB 分割 Whisper API を使用。
                    else -> {
                        Log.d(tag, "File ${audioFile.length() / 1024 / 1024}MB, chunked Whisper API")
                        chunkAndTranscribe(audioFile)
                    }
                }
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    /** Provider設定をロードして LLM クライアントを作成する。start() と TXT インポートの両方で使用 */
    private suspend fun initializeLlmClient(useOnDeviceAsr: Boolean): LlmClient {
        val providerConfig = settings.selectedProvider()
        val apiKey = settings.getApiKey(providerConfig.apiKeyRef)
            ?: if (providerConfig.name == "Ollama") "ollama" else
            error("API Key for ${providerConfig.name} not set")
        val prompt = settings.defaultPromptTemplate.first()
        val model = settings.modelForProvider(providerConfig.name).first()
            ?: providerConfig.defaultModel

        cachedPrompt = buildPromptWithLangHint(prompt, langHint)
        cachedProviderName = providerConfig.name
        cachedModel = model
        cachedWhisperModel = try { settings.whisperModel.first() } catch (_: Exception) { "default" }

        // v0.7.2: Whisper+要約経路 + オンデバイスWhisper 有効時のみ OpenCL/GPU を有効化。
        // v0.7.2: CMakeLists.txt に GGML_USE_OPENCL=1 を組み込んだので true に。
        // CPU のみの端末では whisper.cpp 内部で OpenCL 初期化失敗 → CPU フォールバック。
        val callMode = settings.defaultCallMode.first()
        val useGpu = useOnDeviceAsr && callMode == LlmCallMode.WHISPER_THEN_SUMMARY
        cachedUseGpu = useGpu
        Log.d(tag, "initializeLlmClient: useOnDeviceAsr=$useOnDeviceAsr callMode=$callMode → useGpu=$useGpu")

        return provider.createClient(
            config = providerConfig,
            apiKey = apiKey,
            model = model,
            useOnDeviceAsr = useOnDeviceAsr,
            useGpu = useGpu,
            langHint = langHint
        )
    }

    /** LLMクライアントのみを初期化（TXTインポート用）。initializeLlmClient に委譲。 */
    private suspend fun setupLlmClient() {
        cachedClient = initializeLlmClient(false)
    }

    /** TXTインポートなど音声がない場合に再生ボタンを非表示にするための判定 */
    fun hasAudioFile(): Boolean = cachedAudioFile != null

    /**
     * 文字起こしフェーズを開始する（オンデバイスWhisper または API Whisper）。
     */
    private suspend fun startTranscribePhase(audioFile: File) {
        val client = cachedClient ?: error("Client not initialized")

        val transcribingModel = if (cachedUseOnDevice) {
            "Whisper(${cachedWhisperModel})"
        } else {
            cachedModel
        }
        _state.value = ProcessingState(
            phase = ProcessingPhase.TRANSCRIBING,
            useOnDevice = cachedUseOnDevice, useGpu = cachedUseGpu,
            threadCount = 4,
            activeProvider = cachedProviderName,
            activeModel = transcribingModel,
            audioDurationMs = sessionAudioDurationMs
        )

        try {
            val transcript = client.transcribeOnly(audioFile)
            val transcribeElapsed = System.currentTimeMillis() - transcribeStartMs
            Log.d(tag, "Transcribe complete: ${transcript.length} chars in ${transcribeElapsed}ms")
            _state.value = ProcessingState(
                phase = ProcessingPhase.TRANSCRIBED,
                rawTranscript = transcript,
                transcribeDurationMs = transcribeElapsed,
                audioDurationMs = sessionAudioDurationMs
            )
        } catch (e: Exception) {
            handleError(e)
        }
    }

    /**
     * ローカルPC のネットワーク Whisper サーバで文字起こしする（v0.9.1）。
     * クラウドと違い 25MB 制限がないため分割せず、ファイル全体をそのまま送信する。
     */
    private suspend fun networkTranscribe(audioFile: File) {
        val url = settings.networkWhisperUrl.first()
        _state.value = ProcessingState(
            phase = ProcessingPhase.TRANSCRIBING,
            useOnDevice = false,
            activeProvider = "ローカルPC (Whisper)",
            activeModel = url,
            detailStatus = "ローカルPC へ送信中...",
            audioDurationMs = sessionAudioDurationMs
        )
        try {
            val transcript = networkWhisperClient.transcribe(audioFile, url, langHint.ifBlank { null }).trim()
            val transcribeElapsed = System.currentTimeMillis() - transcribeStartMs
            Log.d(tag, "Network transcribe complete: ${transcript.length} chars in ${transcribeElapsed}ms ($url)")
            _state.value = ProcessingState(
                phase = ProcessingPhase.TRANSCRIBED,
                rawTranscript = transcript,
                transcribeDurationMs = transcribeElapsed,
                audioDurationMs = sessionAudioDurationMs,
                activeProvider = "ローカルPC (Whisper)",
                activeModel = url
            )
        } catch (e: Exception) {
            handleError(e)
        }
    }

    /**
     * 文字起こしテキストを Download フォルダに TXT で保存する。
     */
    fun saveTranscriptToDownloads(text: String, fileName: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val safeName = if (fileName.isNullOrBlank()) "GijiMemo_${System.currentTimeMillis()}.txt"
                    else if (fileName.endsWith(".txt")) fileName else "$fileName.txt"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/GIMI_MEMO")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = context.contentResolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                    )
                    if (uri != null) {
                        context.contentResolver.openOutputStream(uri)?.use { os ->
                            os.write(text.toByteArray(Charsets.UTF_8))
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
                    val file = File(dir, safeName)
                    file.writeText(text, Charsets.UTF_8)
                }
                Log.d(tag, "Transcript saved to Download/GIMI_MEMO/$safeName")
                // UI スレッドで Toast 表示
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "保存しました", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to save transcript: ${e.message}")
            }
        }
    }

    /**
     * 要約結果をメールで共有する。
     * - docx: 要約結果
     * - md: 要約結果
     * - txt: 元の文字起こし結果
     * 受信者は設定画面の受信者プリセットから取得。
     */
    fun shareSummary(summaryText: String, rawTranscript: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val docsDir = File(context.cacheDir, "share_docs").apply { mkdirs() }
                // ファイル名に日時を使用
                val datePart = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.getDefault())
                    .format(java.util.Date(processingStartMs.coerceAtLeast(1L)))
                val sessionTitle = "会議 ${datePart}"

                val docxFile = File(docsDir, "${datePart}_要約.docx")
                val mdFile = File(docsDir, "${datePart}_要約.md")
                val txtFile = File(docsDir, "${datePart}_原文.txt")

                wordGen.generate(summaryText, sessionTitle, docxFile)
                mdGen.generate(summaryText, mdFile)
                txtGen.generate(rawTranscript, txtFile)

                val recipient = settings.recipients.first().firstOrNull() ?: ""

                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    val emailBody = buildString {
                        appendLine("${sessionTitle}の議事録をお送りします。")
                        appendLine()
                        appendLine("--- 要約（${summaryText.length}文字）---")
                        appendLine(summaryText.take(500))
                        if (summaryText.length > 500) appendLine("...（続きは添付ファイルをご参照ください）")
                        appendLine()
                        appendLine("--- 添付ファイル ---")
                        appendLine("・${docxFile.name} : 要約文書（Word形式）")
                        appendLine("・${mdFile.name} : 要約文書（Markdown形式）")
                        appendLine("・${txtFile.name} : 文字起こし原文")
                        appendLine()
                        appendLine("本メールは GijiMemo より自動送信されています。")
                    }
                    emailShare.shareViaEmail(
                        attachments = listOf(docxFile, mdFile, txtFile).filter { it.exists() },
                        subject = sessionTitle,
                        body = emailBody,
                        recipient = recipient
                    )
                }
            } catch (e: Exception) {
                Log.e(tag, "shareSummary failed: ${e.message}")
            }
        }
    }

    // ─── 音声再生制御 ─────────────────────────────────────

    /** 録音音声の再生を開始する。 */
    fun playAudio() {
        val file = cachedAudioFile ?: return
        try {
            if (mediaPlayer == null) {
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    setOnCompletionListener {
                        _playbackState.value = false
                        positionJob?.cancel()
                    }
                    setOnErrorListener { _, _, _ ->
                        _playbackState.value = false
                        positionJob?.cancel()
                        true
                    }
                    prepare()
                    start()
                }
                _playbackDuration.value = mediaPlayer!!.duration.toLong()
                // 再生位置を定期更新
                positionJob = viewModelScope.launch {
                    while (true) {
                        mediaPlayer?.let {
                            if (it.isPlaying) _playbackPosition.value = it.currentPosition.toLong()
                        }
                        kotlinx.coroutines.delay(250)
                    }
                }
            } else if (mediaPlayer!!.isPlaying) {
                mediaPlayer!!.pause()
                _playbackState.value = false
                return
            } else {
                mediaPlayer!!.start()
            }
            _playbackState.value = true
        } catch (e: Exception) {
            Log.e(tag, "playAudio failed: ${e.message}")
            _playbackState.value = false
        }
    }

    /** 指定位置にシークする。 */
    fun seekAudio(positionMs: Int) {
        mediaPlayer?.apply {
            if (positionMs in 0..duration) {
                seekTo(positionMs)
                _playbackPosition.value = positionMs.toLong()
            }
        }
    }

    /** 再生を停止する。 */
    fun stopAudio() {
        positionJob?.cancel()
        mediaPlayer?.apply {
            if (isPlaying) stop()
            release()
        }
        mediaPlayer = null
        _playbackState.value = false
        _playbackPosition.value = 0L
    }

    override fun onCleared() {
        super.onCleared()
        stopAudio()
    }

    /** 要約タイプに対応する追加指示文（Word出力＋感想・考察含む） */
    private fun summaryTypeInstruction(type: String, maxChars: Int): String = when (type) {
        "class" -> """

出力形式: 授業の記録。
以下の構成でWord文書として整形してください:

# 授業概要
（科目名、講師、日時、対象）

# 授業内容
（授業の流れに沿って「###」節見出しで区切る。重要な説明は「」で引用）

# 板書・資料ポイント
- 板書や配布資料の重要ポイントを箇条書き

# 質疑応答
Q: （質問） A: （回答）

# 感想・考察
（授業内容に対する理解・所感を2〜3段落で記載）

最大文字数: 約${maxChars}文字"""
        "dr" -> """

出力形式: デザインレビュー（DR）の議事録。
以下の構成でWord文書として整形してください:

# DR概要
（プロジェクト名、日時、参加者、レビュー対象）

# レビュー指摘事項
（指摘事項ごとに「###」節見出しをつけ、重要度を明記）

## 要対策項目
- [ ] 優先度: 担当者 - 課題内容（期限）

## 承認事項
- 承認された項目を箇条書き

# 決定事項
- DRでの決定事項を箇条書き

# 次回課題
- 次回DRまでの宿題・準備事項

# 所感
（DRの進め方や品質に対する考察を2〜3段落で記載）

最大文字数: 約${maxChars}文字"""
        "lecture" -> """

出力形式: 講演会の記録。
以下の構成でWord文書として整形してください:

# 講演会概要
（タイトル、講師、日時を冒頭に記載）

# 講演内容
（講演の流れに沿って、適宜「###」節見出しで区切る。重要な引用は「」で囲む）

# 要点まとめ
- 主要なポイントを箇条書きで整理

# 感想・考察
（講演内容に対する客観的な考察と所感を2〜3段落で記載）

最大文字数: 約${maxChars}文字"""
        "interview" -> """

出力形式: 取材メモ。
以下の構成でWord文書として整形してください:

# 取材概要
（取材先、日時、テーマ）

# 取材内容
Q: （質問）
A: （回答）
（Q/A を時系列で繰り返す。重要キーワードは**太字**）

# ポイント整理
- キーフレーズや注目点を箇条書き

# 感想・考察
（取材内容に対する分析と所感を2〜3段落で記載）

最大文字数: 約${maxChars}文字"""
        "chat" -> """

出力形式: 雑談の記録。
以下の構成でWord文書として整形してください:

# 話題一覧
（会話の中で出た主要トピックを列挙）

# 会話内容
（話題ごとに「###」節見出しをつけて整理。発言者は（）付きで記載）

# 気づき・発見
- 会話から得られた知見や気づきを箇条書き

# 感想・考察
（会話の内容に対する所感や今後に活かせる点を2〜3段落で記載）

最大文字数: 約${maxChars}文字"""
        else -> """

出力形式: 標準的な議事録。
以下の構成でWord文書として整形してください:

# 会議概要
（日時、参加者、議題を冒頭に記載）

# 議題と討論
（議題ごとに「###」節見出しをつけ、討論内容を記載）

## 決定事項
- 決定事項を箇条書きで整理

## アクションアイテム
- [ ] 担当者：タスク内容（期限）

# 所感
（会議の進め方や雰囲気、今後の課題に対する考察を2〜3段落で記載）

最大文字数: 約${maxChars}文字"""
    }

    /**
     * ユーザー確認後、種類と文字数を指定して要約を開始する。
     * @param text 要約する文字起こしテキスト
     * @param type 要約種類: "minutes" / "interview" / "chat"
     * @param maxChars 最大文字数（デフォルト: 文字起こしの 1/10）
     */
    fun confirmAndSummarize(text: String, type: String = "minutes", maxChars: Int = (text.length / 10).coerceAtLeast(100)) {
        stopAudio() // 要約開始前に再生を停止

        val client = cachedClient ?: run {
            _state.value = _state.value.copy(
                phase = ProcessingPhase.ERROR,
                error = "Client not initialized. Please go back and try again."
            )
            return
        }
        // 要約種類に応じて追加指示を prompt に付与
        val typeInstruction = summaryTypeInstruction(type, maxChars)
        // 録音日時情報を付加
        val dateStr = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(processingStartMs.coerceAtLeast(1L)))
        val datePrefix = "\n\n---\n録音日時: $dateStr\n---\n"
        val fullPrompt = cachedPrompt + typeInstruction + datePrefix

        Log.d(tag, "Summarize: type=$type maxChars=$maxChars date=$dateStr")

        viewModelScope.launch {
            // 原文（要約前の文字起こし）をファイル保存（同コルーチン内で同期的に実行）
            withContext(Dispatchers.IO) {
                val originalFile = File(context.filesDir, "docs/${sessionId}_original.txt")
                originalFile.parentFile?.mkdirs()
                originalFile.writeText(text)
                Log.d(tag, "Original transcript saved for session $sessionId (${text.length}chars)")
            }
            _state.value = _state.value.copy(
                phase = ProcessingPhase.SUMMARIZING,
                summaryText = "",
                activeProvider = cachedProviderName,
                activeModel = cachedModel
            )

            try {
                val sb = StringBuilder()
                client.summarizeOnly(text, fullPrompt).collect { event ->
                    when (event) {
                        is LlmEvent.Delta -> {
                            sb.append(event.text)
                            _state.value = _state.value.copy(
                                phase = ProcessingPhase.SUMMARIZING,
                                summaryText = sb.toString()
                            )
                        }
                        is LlmEvent.Complete -> {
                            val fullText = stripThinkTags(event.fullText.ifEmpty { sb.toString() })
                            val elapsed = System.currentTimeMillis() - processingStartMs
                            Log.d(tag, "Summary Complete: ${fullText.length} chars, elapsed=${elapsed}ms")
                            finalizeSession(
                                fullText,
                                cachedProviderName,
                                cachedModel,
                                rawTranscript = text,
                                transcribeDurationMs = _state.value.transcribeDurationMs
                            )
                            _state.value = _state.value.copy(
                                phase = ProcessingPhase.COMPLETED,
                                summaryText = fullText,
                                totalElapsedMs = elapsed
                            )
                        }
                        is LlmEvent.Error -> {
                            val msg = describeError(event.cause)
                            Log.e(tag, "LlmEvent.Error: $msg", event.cause)
                            repo.updateStatus(sessionId, SessionStatus.ERROR, msg)
                            _state.value = _state.value.copy(
                                phase = ProcessingPhase.ERROR,
                                error = msg
                            )
                        }
                        is LlmEvent.Progress -> { /* 無視 */ }
                    }
                }
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    /**
     * MULTIMODAL 一発処理（従来のフロー）。
     */
    private suspend fun runSinglePhase(
        audioFile: File,
        prompt: String,
        mode: LlmCallMode
    ) {
        val client = cachedClient ?: error("Client not initialized")

        _state.value = ProcessingState(phase = ProcessingPhase.TRANSCRIBING, useOnDevice = cachedUseOnDevice, useGpu = cachedUseGpu, threadCount = 4)

        val sb = StringBuilder()
        client.transcribeAndFormat(audioFile, prompt, mode).collect { event ->
            when (event) {
                is LlmEvent.Delta -> {
                    sb.append(event.text)
                    _state.value = _state.value.copy(
                        phase = ProcessingPhase.SUMMARIZING,
                        summaryText = sb.toString()
                    )
                }
                is LlmEvent.Complete -> {
                    val fullText = stripThinkTags(event.fullText.ifEmpty { sb.toString() })
                    val elapsed = System.currentTimeMillis() - processingStartMs
                    Log.d(tag, "Stream Complete: ${fullText.length} chars, model=${event.model}, elapsed=${elapsed}ms")
                    finalizeSession(fullText, cachedProviderName, cachedModel, transcribeDurationMs = 0L)
                    _state.value = _state.value.copy(
                        phase = ProcessingPhase.COMPLETED,
                        summaryText = fullText,
                        totalElapsedMs = elapsed
                    )
                }
                is LlmEvent.Error -> {
                    val msg = describeError(event.cause)
                    Log.e(tag, "LlmEvent.Error: $msg", event.cause)
                    repo.updateStatus(sessionId, SessionStatus.ERROR, msg)
                    _state.value = _state.value.copy(
                        phase = ProcessingPhase.ERROR,
                        error = msg
                    )
                }
                is LlmEvent.Progress -> { /* 無視 */ }
            }
        }
    }

    /**
     * 文字起こしをリトライする。
     * v0.7.4: クラウドWhisper（OpenAI）とオンデバイスWhisperを正しく振り分け。
     * v0.9.1: ネットワーク Whisper（ローカルPC）も振り分けに追加。
     */
    fun retryTranscribe() {
        cachedAudioFile?.let { file ->
            viewModelScope.launch {
                // v0.9.2: リトライ時はリトライ押下を起点に処理時間を再計測
                transcribeStartMs = System.currentTimeMillis()
                when (cachedAsrMode) {
                    SettingsDataStore.ASR_MODE_ON_DEVICE -> startTranscribePhase(file)
                    SettingsDataStore.ASR_MODE_NETWORK -> networkTranscribe(file)
                    else -> chunkAndTranscribe(file)
                }
            }
        }
    }

    private fun handleError(e: Throwable) {
        val msg = describeError(e)
        Log.e(tag, "Processing caught exception: $msg", e)
        viewModelScope.launch {
            repo.updateStatus(sessionId, SessionStatus.ERROR, msg)
        }
        _state.value = ProcessingState(phase = ProcessingPhase.ERROR, error = msg)
        TranscriptionService.stop(context)
    }

    private fun describeError(e: Throwable): String {
        val className = e::class.java.simpleName
        val raw = e.message
        val withCause = e.cause?.let { " (caused by ${it::class.java.simpleName}: ${it.message ?: "null"})" } ?: ""
        return if (raw.isNullOrBlank() || raw == "null") {
            "[$className] (メッセージなし)$withCause"
        } else {
            "[$className] $raw$withCause"
        }
    }

    /**
     * ユーザー指定の言語ヒント ("ja" / "zh") を prompt 先頭に付与する。
     * Whisper/multimodal モデルへの言語固定指示として効く。
     */
    private fun buildPromptWithLangHint(basePrompt: String, lang: String): String {
        val hint = when (lang) {
            "ja" -> "出力言語: 日本語。音声を必ず日本語として認識・要約してください。\n\n"
            "zh" -> "输出语言: 中文。请将语音作为中文识别并总结。\n\n"
            else -> ""
        }
        return if (hint.isEmpty()) basePrompt else hint + basePrompt
    }

    /**
     * LLM の思考タグ（`<think>...</think>`, `<thinking>...</thinking>`,
     * ` ```thinking ... ``` `）を最終要約から除去する。
     * deepseek-r1 / qwen3 系で頻出。
     */
    private fun stripThinkTags(text: String): String {
        if (text.isBlank()) return text
        var s = text
        // <think>...</think>  / <thinking>...</thinking>  (DOTALL)
        s = Regex("(?is)<think(?:ing)?>.*?</think(?:ing)?>").replace(s, "")
        // ```thinking ... ```  (任意言語)
        s = Regex("(?is)```\\s*think(?:ing)?\\b.*?```").replace(s, "")
        // 末尾に閉じタグが無い「中途半端な <think> 以降」も切る
        s = Regex("(?is)<think(?:ing)?>.*$").replace(s, "")
        return s.trim()
    }

    private suspend fun finalizeSession(
        markdown: String,
        providerName: String,
        model: String,
        rawTranscript: String? = null,
        transcribeDurationMs: Long = 0L
    ) {
        val elapsed = if (processingStartMs > 0L) {
            System.currentTimeMillis() - processingStartMs
        } else 0L
        repo.getById(sessionId)?.let { session ->
            val updated = session.copy(
                transcriptMd = markdown,
                rawTranscript = rawTranscript ?: session.rawTranscript,
                llmProvider = providerName,
                llmModel = model,
                status = SessionStatus.READY,
                processingDurationMs = elapsed,
                transcribeDurationMs = transcribeDurationMs,
                llmCallMode = cachedCallMode
            )
            repo.save(updated)
        }
        TranscriptionService.stop(context)
    }

    companion object {
        /**
         * 一時的な障害（リトライ有効）かどうか。
         * v0.9.0: 実装は [CloudWhisperTranscriber] に移動（一括インポートと共有）。
         */
        fun isRetryable(e: Throwable): Boolean = CloudWhisperTranscriber.isRetryable(e)
    }

    // ─── 分割文字起こし（M4A 直接分割版） ──────────────────

    /**
     * M4A 直接分割 → Whisper API によるクラウド文字起こし。
     * v0.9.0: 分割・並列送信・リトライの実処理は [CloudWhisperTranscriber] に委譲し、
     * ここでは初期予測・進捗表示・処理時間の記録のみ行う（一括インポートとロジックを共有）。
     */
    private suspend fun chunkAndTranscribe(source: File) {
        _state.value = ProcessingState(
            phase = ProcessingPhase.TRANSCRIBING,
            useOnDevice = false,
            activeProvider = "OpenAI (Whisper)",
            activeModel = "whisper-1",
            detailStatus = "準備中...",
            audioDurationMs = sessionAudioDurationMs
        )

        // 初期予測（ファイルサイズベース）：即座に円グラフ表示するため
        val chunkSizeBytes = (chunkSizeMb * 1024 * 1024L).coerceAtLeast(1024 * 1024)
        val fileMb = source.length() / (1024L * 1024L)
        val totalChunksEst = maxOf(1, (source.length() + chunkSizeBytes - 1) / chunkSizeBytes).toInt()
        // 履歴から時間予測係数を取得（秒/MB）
        val perfFactorSecPerMb = settings.transcribePerfFactor.first().takeIf { it > 0f } ?: 5.0f
        // 分割時間予測: ファイル形式により係数を変更（実測値ベース v0.9.0）
        //   MP3: 約 0.6s/MB、M4A/AAC: MediaMuxer 直接分割で約 0.1s/MB
        val splitFactor = when {
            source.name.lowercase().endsWith(".mp3") -> 600L
            source.name.lowercase().endsWith(".m4a") || source.name.lowercase().endsWith(".aac") -> 100L
            else -> 500L
        }
        val estimatedSplitMs = (fileMb * splitFactor).coerceIn(2000L, 120000L)
        val estimatedChunkApiMs = (chunkSizeMb.toFloat() * perfFactorSecPerMb * 1000f)
            .toLong().coerceIn(15000L, 120000L)
        _state.value = _state.value.copy(
            totalChunks = totalChunksEst,
            chunkTimeEstimateMs = estimatedChunkApiMs,
            splitTimeMs = estimatedSplitMs
        )

        // 文字起こし専用時間計測開始（起点は start() = ボタン押下時。v0.9.2）
        val t0 = System.currentTimeMillis()

        try {
            val result = cloudTranscriber.transcribeFile(source, chunkSizeMb) { p ->
                // 進行状況（分割・チャンク完了・予測補正）を State に反映
                _state.value = _state.value.copy(
                    detailStatus = p.detailStatus,
                    totalChunks = p.totalChunks,
                    completedChunks = p.completedChunks,
                    splitTimeMs = p.splitTimeMs,
                    chunkTimeEstimateMs = p.chunkTimeEstimateMs
                )
            }.trim()
            Log.d(tag, "[TIMING] TOTAL: ${System.currentTimeMillis() - t0}ms, ${result.length} chars")

            // パフォーマンス履歴を更新（秒/MB）
            val totalSec = (System.currentTimeMillis() - t0) / 1000f
            val mb = fileMb.toFloat().coerceAtLeast(1f)
            val newFactor = totalSec / mb
            viewModelScope.launch { settings.setTranscribePerfFactor(newFactor) }
            Log.d(tag, "[PERF] ${mb}MB in ${totalSec.toInt()}s → ${"%.1f".format(newFactor)}s/MB")

            val transcribeElapsed = System.currentTimeMillis() - transcribeStartMs
            _state.value = ProcessingState(
                phase = ProcessingPhase.TRANSCRIBED,
                rawTranscript = result,
                transcribeDurationMs = transcribeElapsed,
                splitTimeMs = _state.value.splitTimeMs,
                audioDurationMs = sessionAudioDurationMs,
                activeProvider = "OpenAI (Whisper)",
                activeModel = "whisper-1"
            )
        } catch (e: Exception) {
            handleError(e)
        }
    }
}
