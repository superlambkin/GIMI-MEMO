package com.gijimemo.ui.processing

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
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
import com.gijimemo.data.repository.SessionRepository
import com.gijimemo.data.repository.SettingsRepository
import com.gijimemo.document.MarkdownGenerator
import com.gijimemo.document.TextGenerator
import com.gijimemo.document.WordDocumentGenerator
import com.gijimemo.llm.LlmClient
import com.gijimemo.llm.LlmEvent
import com.gijimemo.llm.LlmProvider
import com.gijimemo.share.EmailShareService
import com.gijimemo.whisper.AudioDecoder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val tag = "GijiMemoLLM"

    val sessionId: String = savedStateHandle.get<String>("sessionId") ?: error("missing sessionId")
    /** ユーザーが選択した文字起こし言語ヒント ("ja" / "zh" / "")。空なら自動判定。 */
    val langHint: String = savedStateHandle.get<String>("lang") ?: ""

    private val _state = MutableStateFlow(ProcessingState())
    val state: StateFlow<ProcessingState> = _state.asStateFlow()

    // キャッシュ: start() で一度だけ初期化する値
    private var cachedClient: LlmClient? = null
    private var cachedAudioFile: File? = null
    private var cachedProviderName: String = ""
    private var cachedModel: String = ""
    private var cachedPrompt: String = ""
    private var cachedUseOnDevice: Boolean = false
    /** v0.7.2: GPU 使用フラグ (OpenCL 有効化フラグを State に伝播) */
    private var cachedUseGpu: Boolean = false
    /** 実行時の呼び出しモード。finalizeSession() で Session に保存。 */
    private var cachedCallMode: LlmCallMode = LlmCallMode.MULTIMODAL
    /** 文字起こしフェーズの開始時刻 (transcribe 専用時間計測用)。 */
    private var transcribeStartMs: Long = 0L
    /** 文字起こし専用 OpenAI Whisper クライアント */
    private var cachedWhisperClient: LlmClient? = null
    /** 文字起こし開始時刻 (start() で記録)。完了時に合計時間として表示する。 */
    private var processingStartMs: Long = 0L
    /** 設定から読み込んだチャンクサイズ (MB) */
    private var chunkSizeMb: Int = 10
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

                // 常にユーザーが選択したプロバイダを使用（autoProviderMode は設定画面にUIがなく無効化済み）
                val providerConfig = settings.selectedProvider()
                val callMode = settings.defaultCallMode.first()
                cachedCallMode = callMode

                // 文字起こし（ASR）はユーザー設定に従う（LLMプロバイダのマルチモーダル対応とは無関係）
                val useOnDevice = settings.useOnDeviceAsr.first()

                cachedAudioFile = audioFile
                cachedUseOnDevice = useOnDevice
                cachedClient = initializeLlmClient(useOnDevice)

                // 文字起こしは常に OpenAI Whisper API を使用するため、
                // OpenAI クライアントを別途作成（設定のプロバイダとは独立）
                val openAiConfig = settings.defaultProviders().firstOrNull { it.name == "OpenAI" }
                val openAiKey = openAiConfig?.let { settings.getApiKey(it.apiKeyRef) }
                cachedWhisperClient = if (openAiConfig != null && !openAiKey.isNullOrBlank()) {
                    provider.createClient(openAiConfig, openAiKey, openAiConfig.defaultModel)
                } else {
                    null
                }

                // 分割サイズを設定から読み込む（1〜24MB）
                chunkSizeMb = settings.defaultChunkMinutes.first().coerceIn(1, 24)
                val decodeEnabled = settings.decodeEnabled.first()
                Log.d(tag, "Processing start: session=$sessionId provider=${providerConfig.name} " +
                        "model=$cachedModel mode=$callMode onDevice=$useOnDevice " +
                        "supportsMultimodal=${providerConfig.supportsMultimodal} " +
                        "audio=${audioFile.absolutePath} size=${audioFile.length()} " +
                        "chunkSize=${chunkSizeMb}MB decodeEnabled=$decodeEnabled")

                repo.updateStatus(sessionId, SessionStatus.TRANSCRIBING)

                when {
                    // オンデバイス Whisper → 端末内処理（分割不要）
                    useOnDevice -> {
                        startTranscribePhase(audioFile)
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

        _state.value = ProcessingState(phase = ProcessingPhase.TRANSCRIBING, useOnDevice = cachedUseOnDevice, useGpu = cachedUseGpu, threadCount = Runtime.getRuntime().availableProcessors() - 1)

        try {
            transcribeStartMs = System.currentTimeMillis()
            val transcript = client.transcribeOnly(audioFile)
            val transcribeElapsed = System.currentTimeMillis() - transcribeStartMs
            Log.d(tag, "Transcribe complete: ${transcript.length} chars in ${transcribeElapsed}ms")
            _state.value = ProcessingState(
                phase = ProcessingPhase.TRANSCRIBED,
                rawTranscript = transcript,
                transcribeDurationMs = transcribeElapsed
            )
        } catch (e: Exception) {
            handleError(e)
        }
    }

    /**
     * 文字起こしテキストを Download フォルダに TXT で保存する。
     */
    fun saveTranscriptToDownloads(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fileName = "GijiMemo_${System.currentTimeMillis()}.txt"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
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
                    val file = File(dir, fileName)
                    file.writeText(text, Charsets.UTF_8)
                }
                Log.d(tag, "Transcript saved to Download/GIMI_MEMO/$fileName")
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

        _state.value = ProcessingState(phase = ProcessingPhase.TRANSCRIBING, useOnDevice = cachedUseOnDevice, useGpu = cachedUseGpu, threadCount = Runtime.getRuntime().availableProcessors() - 1)

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
     */
    fun retryTranscribe() {
        cachedAudioFile?.let { file ->
            viewModelScope.launch {
                if (cachedUseOnDevice) {
                    startTranscribePhase(file)
                } else {
                    chunkAndTranscribe(file)
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
        /** WAV サンプルレート */
        private const val SAMPLE_RATE = 16000

        /**
         * 先頭 2 バイトが ADTS 同期ワード (0xFFFx) かを確認して raw AAC を検出。
         * OpenAI Whisper API は raw AAC をサポートしていないため、事前に WAV
         * 変換が必要。
         */
        private fun isRawAacFile(file: File): Boolean {
            return try {
                val bytes = file.readBytes()
                bytes.size >= 2 &&
                    (bytes[0].toInt() and 0xFF) == 0xFF &&
                    (bytes[1].toInt() and 0xF0) == 0xF0
            } catch (e: Exception) {
                false
            }
        }
    }

    // ─── M4A 直接分割（MediaExtractor + MediaMuxer） ──────

    /** AAC フレーム 1 個分のデータ */
    private data class AudioFrame(
        val data: ByteArray,
        val presentationTimeUs: Long,
        val flags: Int
    )

    /**
     * M4A/AAC ファイルを [chunkSizeBytes] 以下のチャンクに分割する。
     * MediaExtractor + MediaMuxer でデコードせずに直接分割するため、
     * PCM デコード（216秒）が不要になり約 2〜5秒で完了する。
     *
     * @return 分割後のチャンクファイルのリスト
     */
    private fun splitM4aIntoChunks(source: File, outputDir: File, chunkSizeBytes: Long): List<File> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.absolutePath)
        } catch (e: Exception) {
            Log.w(tag, "MediaExtractor cannot read $source, falling back: ${e.message}")
            return emptyList()
        }

        val trackIndex = findAudioTrackM4a(extractor)
        if (trackIndex < 0) {
            Log.w(tag, "No audio track found, falling back")
            extractor.release()
            return emptyList()
        }

        val format = extractor.getTrackFormat(trackIndex)
        extractor.selectTrack(trackIndex)

        // 全 AAC フレームを読み込む
        val frames = mutableListOf<AudioFrame>()
        var totalInputBytes = 0L
        while (true) {
            val buf = ByteBuffer.allocate(8192)
            val sampleSize = extractor.readSampleData(buf, 0)
            if (sampleSize < 0) break

            val data = ByteArray(sampleSize)
            buf.rewind()
            buf.get(data)
            frames.add(AudioFrame(data, extractor.sampleTime, extractor.sampleFlags))
            totalInputBytes += sampleSize

            if (!extractor.advance()) break
        }
        extractor.release()

        if (frames.isEmpty()) return emptyList()

        // 1 チャンクで収まる場合
        if (totalInputBytes <= chunkSizeBytes || frames.size <= 1) {
            return emptyList() // 分割不要 → 元ファイルをそのまま使う
        }

        // フレーム数ベースで分割ポイントを計算
        val framesPerChunk = maxOf(1, (frames.size.toLong() * chunkSizeBytes / totalInputBytes).toInt())
        val chunks = mutableListOf<File>()
        var idx = 0
        var chunkIdx = 0

        while (idx < frames.size) {
            val end = minOf(idx + framesPerChunk, frames.size)
            val chunkFile = File(outputDir, "chunk_${chunkIdx}_${System.nanoTime()}.m4a")
            writeM4aChunk(chunkFile, format, frames, idx, end)
            chunks.add(chunkFile)
            idx = end
            chunkIdx++
        }

        Log.d(tag, "splitM4aIntoChunks: ${source.length()}B → ${chunks.size} chunks")
        return chunks
    }

    private fun findAudioTrackM4a(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return -1
    }

    /** フレーム範囲を M4A ファイルとして書き出す。 */
    private fun writeM4aChunk(
        outputFile: File,
        format: MediaFormat,
        frames: List<AudioFrame>,
        start: Int,
        end: Int
    ) {
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val trackId = muxer.addTrack(format)
            muxer.start()
            for (i in start until end) {
                val f = frames[i]
                val buf = ByteBuffer.wrap(f.data)
                val info = MediaCodec.BufferInfo().apply {
                    size = f.data.size
                    flags = f.flags
                    presentationTimeUs = f.presentationTimeUs
                    offset = 0
                }
                muxer.writeSampleData(trackId, buf, info)
            }
        } finally {
            muxer.stop()
            muxer.release()
        }
    }

    /**
     * raw AAC (ADTS) を M4A コンテナにラップする。
     * MediaExtractor + MediaMuxer でデコードせずにコンテナ変換するため、
     * 従来の AAC→WAV デコード（実時間比 4.7倍）に比べてほぼ瞬時に完了する。
     * @return ラップ後の M4A ファイル、失敗時は null
     */
    private fun wrapAacInM4a(source: File, outputDir: File): File? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.absolutePath)
        } catch (e: Exception) {
            Log.w(tag, "wrapAacInM4a: cannot read $source: ${e.message}")
            return null
        }
        val trackIndex = findAudioTrackM4a(extractor)
        if (trackIndex < 0) { extractor.release(); return null }
        val format = extractor.getTrackFormat(trackIndex)
        extractor.selectTrack(trackIndex)

        val frames = mutableListOf<AudioFrame>()
        while (true) {
            val buf = ByteBuffer.allocate(8192)
            val sampleSize = extractor.readSampleData(buf, 0)
            if (sampleSize < 0) break
            val data = ByteArray(sampleSize)
            buf.rewind(); buf.get(data)
            frames.add(AudioFrame(data, extractor.sampleTime, extractor.sampleFlags))
            if (!extractor.advance()) break
        }
        extractor.release()
        if (frames.isEmpty()) return null

        val outFile = File(outputDir, "m4a_wrapped_${System.nanoTime()}.m4a")
        writeM4aChunk(outFile, format, frames, 0, frames.size)
        Log.d(tag, "wrapAacInM4a: ${source.length()}B → ${outFile.absolutePath} (${frames.size} frames)")
        return outFile
    }

    // ─── 分割文字起こし（M4A 直接分割版） ──────────────────

    /**
     * M4A 直接分割 → Whisper API:
     * 1. MediaExtractor で全 AAC フレームを読込（デコード不要、約 2〜5秒）
     * 2. フレームを [chunkSizeMb] 単位で MediaMuxer 出力
     * 3. 各チャンクを Whisper API (/v1/audio/transcriptions) で文字起こし
     * 4. 全チャンクの結果を結合 → TRANSCRIBED
     */
    private suspend fun chunkAndTranscribe(source: File) {
        val whisperClient = cachedWhisperClient ?: run {
            handleError(IllegalStateException("OpenAI Whisper クライアントが初期化できません。API Key を設定してください。"))
            return
        }

        val chunkSizeBytes = (chunkSizeMb * 1024 * 1024L).coerceAtLeast(1024 * 1024)
        _state.value = ProcessingState(
            phase = ProcessingPhase.TRANSCRIBING,
            useOnDevice = false,
            activeProvider = "OpenAI (Whisper)",
            activeModel = "whisper-1",
            detailStatus = "準備中..."
        )

        // 初期予測（ファイルサイズベース）：即座に円グラフ表示するため
        val fileMb = source.length() / (1024L * 1024L)
        val totalChunksEst = maxOf(1, (source.length() + chunkSizeBytes - 1) / chunkSizeBytes).toInt()
        // 履歴から時間予測係数を取得（秒/MB）
        val perfFactorSecPerMb = settings.transcribePerfFactor.first().takeIf { it > 0f } ?: 5.0f
        // 分割時間予測: ファイル形式により係数を変更（MP3: 1.5s/MB, M4A/AAC: 0.2s/MB）
        val splitFactor = when {
            source.name.lowercase().endsWith(".mp3") -> 1500L
            source.name.lowercase().endsWith(".m4a") || source.name.lowercase().endsWith(".aac") -> 200L
            else -> 800L
        }
        val estimatedSplitMs = (fileMb * splitFactor).coerceIn(2000L, 120000L)
        val estimatedChunkApiMs = (chunkSizeMb.toFloat() * perfFactorSecPerMb * 1000f)
            .toLong().coerceIn(15000L, 120000L)
        _state.value = _state.value.copy(
            totalChunks = totalChunksEst,
            chunkTimeEstimateMs = estimatedChunkApiMs,
            splitTimeMs = estimatedSplitMs
        )

        // 文字起こし専用時間計測開始
        transcribeStartMs = System.currentTimeMillis()

        val cacheDir = File(context.cacheDir, "chunk_cache").apply { mkdirs() }
        val t0 = System.currentTimeMillis()

        try {
            // 単一ファイルで収まる → 直接送信
            if (source.length() <= chunkSizeBytes) {
                Log.d(tag, "Single chunk, sending original file to Whisper API")

                // raw AAC (ADTS) → M4A コンテナにラップ（デコード不要、ほぼ瞬時）
                val fileToSend = if (isRawAacFile(source)) {
                    Log.d(tag, "Raw AAC detected, wrapping in M4A container")
                    _state.value = _state.value.copy(detailStatus = "AAC→M4A 変換中...")
                    val m4aFile = withContext(Dispatchers.IO) {
                        wrapAacInM4a(source, cacheDir)
                    }
                    m4aFile ?: source
                } else {
                    source
                }

                _state.value = _state.value.copy(detailStatus = "Whisper API 送信中...")
                val tApi = System.currentTimeMillis()
                val transcript = whisperClient.transcribeOnly(fileToSend)
                Log.d(tag, "[TIMING] Whisper API: ${System.currentTimeMillis() - tApi}ms")
                _state.value = ProcessingState(
                    phase = ProcessingPhase.TRANSCRIBED,
                    rawTranscript = transcript,
                    transcribeDurationMs = System.currentTimeMillis() - transcribeStartMs,
                    activeProvider = "OpenAI (Whisper)",
                    activeModel = "whisper-1"
                )
                Log.d(tag, "[TIMING] TOTAL: ${System.currentTimeMillis() - t0}ms")
                return
            }

            // M4A 直接分割（MediaExtractor + MediaMuxer）— デコード不要で高速
            val tSplit = System.currentTimeMillis()
            _state.value = _state.value.copy(detailStatus = "M4A 分割中...")

            val m4aChunks = withContext(Dispatchers.IO) {
                splitM4aIntoChunks(source, cacheDir, chunkSizeBytes)
            }
            val chunks = m4aChunks.toMutableList()

            if (chunks.isEmpty()) {
                // M4A 分割失敗 → デコード方式にフォールバック
                Log.d(tag, "M4A split failed, falling back to decode-based splitting")
                _state.value = _state.value.copy(detailStatus = "AAC→WAV デコード中...")
                val tDecode = System.currentTimeMillis()
                val wavPath = withContext(Dispatchers.IO) {
                    AudioDecoder.decodeToWav(source.absolutePath, cacheDir)
                }
                val wavFile = File(wavPath)
                Log.d(tag, "[TIMING] Decode fallback: ${System.currentTimeMillis() - tDecode}ms, WAV=${wavFile.length() / 1024 / 1024}MB")

                _state.value = _state.value.copy(detailStatus = "PCM 分割中...")
                val allPcm = withContext(Dispatchers.IO) { readWavPcm(wavFile) }
                wavFile.delete()
                val chunkSamples = (chunkSizeMb * 1024 * 1024 / 2).coerceAtLeast(16000)
                val totalPcmChunks = (allPcm.size + chunkSamples - 1) / chunkSamples

                val pcmChunks = mutableListOf<File>()
                for (i in 0 until totalPcmChunks) {
                    val start = i * chunkSamples
                    val end = minOf(start + chunkSamples, allPcm.size)
                    val cf = File(cacheDir, "pcm_chunk_${i}_${System.nanoTime()}.wav")
                    withContext(Dispatchers.IO) { writeWavFile(cf, allPcm.copyOfRange(start, end)) }
                    pcmChunks.add(cf)
                }
                chunks.addAll(pcmChunks)
            } else {
                val actualSplitMs = System.currentTimeMillis() - tSplit
                Log.d(tag, "[TIMING] M4A split: ${actualSplitMs}ms, ${chunks.size} chunks")
                // 実測分割時間 + 初期予測の1チャンクあたりAPI時間を維持
                _state.value = _state.value.copy(
                    totalChunks = chunks.size,
                    splitTimeMs = actualSplitMs
                    // chunkTimeEstimateMs は初期予測値(履歴ベース)をそのまま維持
                )
            }

            // 3. 全チャンクを並列 Whisper API で文字起こし（最大 3 並行）
            val fullTranscript = StringBuilder()
            val totalChunks = chunks.size
            val semaphore = java.util.concurrent.Semaphore(3)
            val results = coroutineScope {
                chunks.mapIndexed { i, chunkFile ->
                    async {
                        semaphore.acquire()
                        try {
                            val transcript = whisperClient.transcribeOnly(chunkFile)
                            i to transcript.trim()
                        } catch (e: Exception) {
                            Log.e(tag, "Chunk ${i + 1}/$totalChunks failed: ${e.message}")
                            i to ""
                        } finally {
                            chunkFile.delete()
                            semaphore.release()
                            _state.value = _state.value.copy(completedChunks = _state.value.completedChunks + 1)
                        }
                    }
                }.map { it.await() }.sortedBy { (i, _) -> i }
            }
            for ((_, text) in results) {
                if (text.isNotEmpty()) {
                    if (fullTranscript.isNotEmpty()) fullTranscript.append(" ")
                    fullTranscript.append(text)
                }
            }

            // キャッシュディレクトリをクリーンアップ
            cacheDir.listFiles()?.forEach { it.delete() }

            val result = fullTranscript.toString().trim()
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
                activeProvider = "OpenAI (Whisper)",
                activeModel = "whisper-1"
            )
        } catch (e: Exception) {
            handleError(e)
        }
    }

    // ─── WAV 読み書きヘルパー ─────────────────────────────

    /**
     * WAV ファイルから 16bit PCM データを ShortArray として読み込む。
     * 16kHz mono 16-bit PCM を想定。
     */
    private fun readWavPcm(wavFile: File): ShortArray {
        val bytes = wavFile.readBytes()
        // "data" チャンクを探す
        val dataOffset = findDataChunk(bytes)
        val dataSize = bytes.size - dataOffset
        val alignedSize = dataSize - (dataSize % 2)
        val buf = ByteBuffer.wrap(bytes, dataOffset, alignedSize)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
        val result = ShortArray(buf.remaining())
        buf.get(result)
        return result
    }

    /** WAV ファイルの "data" チャンク開始位置（PCM データのバイトオフセット）を探す。 */
    private fun findDataChunk(bytes: ByteArray): Int {
        var offset = 44
        var guard = 0
        while (offset + 8 < bytes.size && guard++ < 16) {
            val id = String(bytes, offset, 4)
            if (id == "data") return offset + 8
            val size = leIntAt(bytes, offset + 4)
            if (size <= 0 || size > bytes.size) break
            offset += 8 + size
        }
        return 44
    }

    /** リトルエンディアン 32-bit int を読み取る。 */
    private fun leIntAt(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    /**
     * PCM ShortArray を 16kHz mono 16-bit WAV ファイルとして書き出す。
     */
    private fun writeWavFile(file: File, pcmData: ShortArray) {
        val dataSize = pcmData.size * 2L
        val fileSize = 36L + dataSize

        FileOutputStream(file).use { fos ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(fileSize.toInt())
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)          // chunk size
            header.putShort(1)         // PCM
            header.putShort(1)         // mono
            header.putInt(SAMPLE_RATE)
            header.putInt(SAMPLE_RATE * 2) // byte rate
            header.putShort(2)         // block align
            header.putShort(16)        // bits per sample
            header.put("data".toByteArray())
            header.putInt(dataSize.toInt())
            fos.write(header.array())

            val pcmBytes = ByteBuffer.allocate(pcmData.size * 2)
                .order(ByteOrder.LITTLE_ENDIAN)
            pcmBytes.asShortBuffer().put(pcmData)
            fos.write(pcmBytes.array())
        }
    }
}
