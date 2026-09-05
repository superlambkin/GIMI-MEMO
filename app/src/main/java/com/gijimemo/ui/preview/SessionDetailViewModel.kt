package com.gijimemo.ui.preview

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gijimemo.data.repository.SettingsRepository
import com.gijimemo.llm.LlmEvent
import com.gijimemo.llm.LlmProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gijimemo.data.model.Session
import com.gijimemo.data.model.SessionStatus
import com.gijimemo.data.repository.SessionRepository
import com.gijimemo.document.MarkdownGenerator
import com.gijimemo.document.TextGenerator
import com.gijimemo.document.WordDocumentGenerator
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: SessionRepository,
    private val settings: SettingsRepository,
    private val wordGen: WordDocumentGenerator,
    private val mdGen: MarkdownGenerator,
    private val txtGen: TextGenerator,
    private val emailShare: EmailShareService,
    private val llmProvider: LlmProvider,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val sessionId: String = savedStateHandle.get<String>("sessionId") ?: error("missing sessionId")

    private val tag = "SessionDetailVM"
    private val _state = MutableStateFlow(SessionDetailState())
    val state: StateFlow<SessionDetailState> = _state.asStateFlow()

    var fontSizeSp by mutableStateOf(14); private set
    fun increaseFont() { fontSizeSp = (fontSizeSp + 2).coerceAtMost(24) }
    fun decreaseFont() { fontSizeSp = (fontSizeSp - 2).coerceAtLeast(10) }

    // ─── TTS（段落追跡＋ハイライト） ──────────────────
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    var isSpeaking by mutableStateOf(false); private set
    private val paragraphs = mutableListOf<String>()
    private var pendingSpeak: String? = null // 初期化待ちのテキスト
    var currentParagraphIndex by mutableStateOf(-1); private set
    private var ttsEngineSetting: String? = null  // 設定から読み込んだTTSエンジン
    /** TTS状態メッセージ（UI表示用） */
    var ttsMessage by mutableStateOf<String?>(null); private set
    /** クラウドTTS読み上げ中 */
    var isCloudSpeaking by mutableStateOf(false); private set

    private val cloudTts = CloudTtsClient()

    init {
        viewModelScope.launch {
            ttsEngineSetting = settings.ttsEngine.first()
            if (tts == null) initTts()
        }
    }

    private val googleTtsPackage = "com.google.android.tts"
    private fun isPackageInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, PackageManager.GET_META_DATA)
        true
    } catch (_: PackageManager.NameNotFoundException) { false }

    /** TextToSpeech インスタンスを作成（Google TTS優先） */
    private fun initTts() {
        if (tts != null && ttsReady) return
        tts?.shutdown()

        // エンジン選択: ユーザー設定 → Google TTS(日本語対応確認済) → システム標準
        var engine = when {
            ttsEngineSetting != null -> ttsEngineSetting
            isPackageInstalled(googleTtsPackage) -> googleTtsPackage
            else -> null
        }
        // Huawei 端末では PackageManager が Google TTS を検出できないが、
        // 実際にはインストール済みなのでパッケージ名で直接作成を試みる
        if (engine == null) {
            Log.i(tag, "PackageManager failed to detect Google TTS, trying direct package")
            engine = googleTtsPackage
        }
        @Suppress("DEPRECATION")
        tts = try {
            TextToSpeech(context, { status -> onTtsInit(status) }, engine)
        } catch (e: Exception) {
            Log.e(tag, "TTS engine $engine failed: ${e.message}, using default")
            TextToSpeech(context) { status -> onTtsInit(status) }
        }
    }

    private fun onTtsInit(status: Int) {
        ttsReady = (status == TextToSpeech.SUCCESS)
        if (ttsReady) {
            setupTts()
        } else {
            Log.e(tag, "TTS init failed: status=$status engine=$ttsEngineSetting")
        }
    }

    private fun setupTts() {
        val langAvail = tts?.isLanguageAvailable(java.util.Locale.JAPANESE) ?: return
        if (langAvail >= TextToSpeech.LANG_AVAILABLE) {
            tts?.setLanguage(java.util.Locale.JAPANESE)
            ttsMessage = null
        } else {
            // isLanguageAvailable が false でも setLanguage を試す（Google TTS 等で実際は使える場合がある）
            val langResult = tts?.setLanguage(java.util.Locale.JAPANESE)
            if (langResult != null && langResult >= TextToSpeech.LANG_AVAILABLE) {
                Log.i(tag, "TTS Japanese available via setLanguage ($langResult)")
                ttsMessage = null
            } else {
                Log.w(tag, "TTS Japanese not available ($langAvail), using default locale")
                tts?.setLanguage(java.util.Locale.getDefault())
                ttsMessage = "日本語読み上げには Google TTS のインストールをおすすめします"
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onDone(uid: String?) { if (uid == "tts") speakNextParagraph() }
            override fun onError(uid: String?) { if (uid == "tts") { isSpeaking = false; currentParagraphIndex = -1 } }
            override fun onStart(uid: String?) {}
        })
        // 待機中の発声を実行
        pendingSpeak?.let { doSpeak(it); pendingSpeak = null }
    }

    fun speak(text: String) {
        if (isSpeaking || isCloudSpeaking) { stopSpeaking(); return }
        paragraphs.clear()
        paragraphs.addAll(text.split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() })
        currentParagraphIndex = -1
        if (!ttsReady) {
            pendingSpeak = text
            initTts()
            return
        }
        // 日本語がローカルTTSで使えるか確認、不可ならクラウドTTSへ
        val langOk = tts?.isLanguageAvailable(java.util.Locale.JAPANESE) ?: TextToSpeech.LANG_NOT_SUPPORTED
        if (langOk >= TextToSpeech.LANG_AVAILABLE) {
            speakNextParagraph()
        } else {
            speakViaCloud(text)
        }
    }

    private fun speakViaCloud(text: String) {
        isCloudSpeaking = true
        ttsMessage = "クラウドTTSで音声を取得中..."
        // Google Translate TTS は200文字制限があるため先頭150文字だけ送信
        val shortText = text.replace(Regex("\\s+"), " ").take(150).trim()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val mp3Path = cloudTts.synthesize(shortText, cacheDir = context.cacheDir)
                if (mp3Path != null) {
                    withContext(Dispatchers.Main) {
                        playCloudAudio(mp3Path)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        ttsMessage = "クラウドTTS失敗。Google TTSをインストールしてください"
                        isCloudSpeaking = false
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "CloudTTS error", e)
                withContext(Dispatchers.Main) {
                    ttsMessage = "クラウドTTSエラー: ${e.message}"
                    isCloudSpeaking = false
                }
            }
        }
    }

    private fun playCloudAudio(mp3Path: String) {
        try {
            val player = MediaPlayer().apply {
                setDataSource(mp3Path)
                setOnCompletionListener {
                    release()
                    isCloudSpeaking = false
                    ttsMessage = null
                    cloudMediaPlayer = null
                }
                setOnErrorListener { _, _, _ ->
                    isCloudSpeaking = false
                    ttsMessage = "音声再生エラー"
                    cloudMediaPlayer = null
                    true
                }
                prepare()
                start()
            }
            cloudMediaPlayer = player
            isCloudSpeaking = true
            ttsMessage = null
        } catch (e: Exception) {
            Log.e(tag, "playCloudAudio error", e)
            isCloudSpeaking = false
            ttsMessage = "音声再生失敗: ${e.message}"
        }
    }

    private var cloudMediaPlayer: MediaPlayer? = null

    private fun speakNextParagraph() {
        val idx = currentParagraphIndex + 1
        if (idx >= paragraphs.size) { stopSpeaking(); return }
        currentParagraphIndex = idx
        try {
            tts?.speak(paragraphs[idx], TextToSpeech.QUEUE_FLUSH, null, "tts")
            isSpeaking = true
        } catch (e: Exception) { Log.e(tag, "TTS fail", e) }
    }

    private fun doSpeak(text: String) {
        paragraphs.clear()
        paragraphs.addAll(text.split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() })
        currentParagraphIndex = -1
        speakNextParagraph()
    }

    fun stopSpeaking() {
        tts?.stop()
        isSpeaking = false
        cloudMediaPlayer?.apply { stop(); release() }
        cloudMediaPlayer = null
        isCloudSpeaking = false
        currentParagraphIndex = -1
        pendingSpeak = null
    }
    override fun onCleared() {
        super.onCleared()
        stopAudio()
        stopSpeaking()
        tts?.shutdown()
    }

    // ─── AI翻訳（中文→日本語） ──────────────────────────
    fun translateToJapanese() {
        val text = _state.value.markdown
        if (text.isBlank()) return
        _state.value = _state.value.copy(isTranslating = true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val config = settings.selectedProvider()
                val apiKey = settings.getApiKey(config.apiKeyRef)
                    ?: error("API Key not set")
                val model = settings.modelForProvider(config.name).first()
                    ?: config.defaultModel
                val client = llmProvider.createClient(config, apiKey, model)
                val prompt = "以下の中文の文章を日本語に翻訳してください。Markdown形式は維持し、検出言語の行も「日本語」に更新してください。"
                val sb = StringBuilder()
                client.summarizeOnly(text, prompt).collect { event ->
                    when (event) {
                        is LlmEvent.Delta -> sb.append(event.text)
                        is LlmEvent.Complete -> {
                            val translated = sb.toString().ifEmpty { event.fullText }
                            val session = _state.value.session
                            if (session == null) { _state.value = _state.value.copy(isTranslating = false); return@collect }
                            _state.value = _state.value.copy(markdown = translated, detectedLanguage = "日本語", isTranslating = false)
                            ensureDocuments(session!!, translated)
                        }
                        is LlmEvent.Error -> throw event.cause
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "翻訳失敗: ${e.message}", e)
                _state.value = _state.value.copy(isTranslating = false, error = "翻訳失敗: ${e.message}")
            }
        }
    }

    // ─── 音声再生 ───────────────────────────────────────
    private var mediaPlayer: MediaPlayer? = null
    private var positionJob: kotlinx.coroutines.Job? = null
    private val _playbackState = MutableStateFlow(false)
    val playbackState: StateFlow<Boolean> = _playbackState.asStateFlow()
    private val _playbackPosition = MutableStateFlow(0L)
    val playbackPosition: StateFlow<Long> = _playbackPosition.asStateFlow()
    private val _playbackDuration = MutableStateFlow(0L)
    val playbackDuration: StateFlow<Long> = _playbackDuration.asStateFlow()

    fun playAudio() {
        val location = _state.value.session?.audioFilePath ?: return
        if (location.isBlank()) return
        try {
            if (mediaPlayer == null) {
                mediaPlayer = MediaPlayer().apply {
                    if (location.startsWith("content://")) setDataSource(context, Uri.parse(location))
                    else setDataSource(location)
                    setOnCompletionListener { _playbackState.value = false; positionJob?.cancel() }
                    setOnErrorListener { _, _, _ -> _playbackState.value = false; positionJob?.cancel(); true }
                    prepare()
                    start()
                }
                _playbackDuration.value = mediaPlayer!!.duration.toLong()
                positionJob = viewModelScope.launch {
                    while (true) {
                        mediaPlayer?.let { if (it.isPlaying) _playbackPosition.value = it.currentPosition.toLong() }
                        kotlinx.coroutines.delay(250)
                    }
                }
            } else if (mediaPlayer!!.isPlaying) {
                mediaPlayer!!.pause(); _playbackState.value = false; return
            } else { mediaPlayer!!.start() }
            _playbackState.value = true
        } catch (e: Exception) {
            Log.e(tag, "playAudio failed: ${e.message}", e)
            _playbackState.value = false
        }
    }

    fun seekAudio(positionMs: Int) {
        mediaPlayer?.let { if (positionMs in 0..it.duration) { it.seekTo(positionMs); _playbackPosition.value = positionMs.toLong() } }
    }

    fun stopAudio() {
        positionJob?.cancel()
        mediaPlayer?.apply { if (isPlaying) stop(); release() }
        mediaPlayer = null; _playbackState.value = false; _playbackPosition.value = 0L
    }

    fun load() {
        viewModelScope.launch {
            val session = repo.getById(sessionId) ?: run {
                _state.value = _state.value.copy(error = "会话不存在")
                return@launch
            }
            val transcript = session.transcriptMd
            val detectedLang = transcript?.let { extractDetectedLanguage(it) }
            _state.value = _state.value.copy(session = session, markdown = transcript ?: "", detectedLanguage = detectedLang)
            if (!transcript.isNullOrBlank()) {
                ensureDocuments(session, transcript)
            }
        }
    }

    private fun extractDetectedLanguage(markdown: String): String? {
        val regex = Regex("""^#\s*検出言語[^\n]*\n+([^\n#]+)""", RegexOption.MULTILINE)
        return regex.find(markdown)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    // ─── 再要約 ─────────────────────────────────────────
    var isResummarizing by mutableStateOf(false); private set

    /** 音声ファイルの生成日時を取得（ミリ秒）。content:// URI は MediaStore から、ファイルパスは lastModified から取得 */
    private fun getAudioFileDateMs(): Long {
        val session = _state.value.session ?: return System.currentTimeMillis()
        val audioPath = session.audioFilePath ?: return session.createdAt
        return try {
            when {
                audioPath.startsWith("content://") -> {
                    val cursor = context.contentResolver.query(
                        Uri.parse(audioPath),
                        arrayOf(MediaStore.Audio.Media.DATE_ADDED),
                        null, null, null
                    )
                    cursor?.use { c ->
                        if (c.moveToFirst()) {
                            val seconds = c.getLong(0)
                            if (seconds > 0L) seconds * 1000L else session.createdAt
                        } else session.createdAt
                    } ?: session.createdAt
                }
                else -> {
                    val file = File(audioPath)
                    if (file.exists()) file.lastModified() else session.createdAt
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "getAudioFileDateMs fallback: ${e.message}")
            session.createdAt
        }
    }

    /** 再要約の種類に応じた出力形式指示 */
    private fun resummaryTypeInstruction(type: String, maxChars: Int): String = when (type) {
        "class" -> "\n\n授業形式で、以下の構成でまとめてください:\n# 授業概要\n# 授業内容\n# 板書・資料ポイント\n# 質疑応答\n# 感想・考察\n最大文字数: 約${maxChars}文字"
        "lecture" -> "\n\n講演会形式で、以下の構成でまとめてください:\n# 講演会概要\n# 講演内容\n# 要点まとめ\n# 感想・考察\n最大文字数: 約${maxChars}文字"
        "interview" -> "\n\n取材形式で、以下の構成でまとめてください:\n# 取材概要\n# 取材内容(Q/A形式)\n# ポイント整理\n# 感想・考察\n最大文字数: 約${maxChars}文字"
        "chat" -> "\n\n雑談形式で、以下の構成でまとめてください:\n# 話題一覧\n# 会話内容\n# 気づき・発見\n# 感想・考察\n最大文字数: 約${maxChars}文字"
        "dr" -> "\n\nデザインレビュー形式で、以下の構成でまとめてください:\n# DR概要\n# レビュー指摘事項\n# 決定事項\n# 次回課題\n# 所感\n最大文字数: 約${maxChars}文字"
        else -> "\n\n議事録形式で、以下の構成でまとめてください:\n# 会議概要\n# 議題と討論\n# 決定事項\n# アクションアイテム\n# 所感\n最大文字数: 約${maxChars}文字"
    }

    /** 種類・文字数・録音日時を指定して再要約 */
    fun resummarizeWithOptions(type: String, maxChars: Int) {
        val text = _state.value.markdown
        if (text.isBlank()) return
        isResummarizing = true
        _state.value = _state.value.copy(error = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val config = settings.selectedProvider()
                val apiKey = settings.getApiKey(config.apiKeyRef) ?: error("API Key not set")
                val model = settings.modelForProvider(config.name).first() ?: config.defaultModel
                val client = llmProvider.createClient(config, apiKey, model)

                // 種類に応じた形式指示
                val typeInstruction = resummaryTypeInstruction(type, maxChars)

                // 音声ファイルの生成日時をプロンプトに含める
                val audioDateMs = getAudioFileDateMs()
                val dateStr = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
                    .format(Date(audioDateMs))
                val datePrefix = "\n\n---\n録音日時: $dateStr\n---\n"

                val sb = StringBuilder()
                val prompt = "以下の文章を再度要約してください。Markdown形式で見やすく構造化し、感想・考察を含めてください。$typeInstruction$datePrefix"
                client.summarizeOnly(text, prompt).collect { event ->
                    when (event) {
                        is LlmEvent.Delta -> sb.append(event.text)
                        is LlmEvent.Complete -> {
                            val result = sb.toString().ifEmpty { event.fullText }
                            val session = _state.value.session ?: return@collect
                            _state.value = _state.value.copy(markdown = result)
                            ensureDocuments(session, result)
                            isResummarizing = false
                        }
                        is LlmEvent.Error -> throw event.cause
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "再要約失敗: ${e.message}", e)
                _state.value = _state.value.copy(error = "再要約失敗: ${e.message}")
                isResummarizing = false
            }
        }
    }

    private suspend fun ensureDocuments(session: Session, markdown: String) {
        withContext(Dispatchers.IO) {
            val docsDir = File(context.filesDir, "docs").apply { mkdirs() }
            val datePart = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.getDefault())
                .format(java.util.Date(session.createdAt))
            val docxFile = File(docsDir, "${datePart}_議事録.docx")
            val mdFile = File(docsDir, "${datePart}_議事録.md")
            val txtFile = File(docsDir, "${datePart}_原文.txt")

            if (!docxFile.exists() || docxFile.length() == 0L) {
                wordGen.generate(markdown, session.title, docxFile)
            }
            if (!mdFile.exists()) mdGen.generate(markdown, mdFile)
            // TXTは自動生成しない（保存ボタンで原文ファイルから個別に作成）

            val updated = session.copy(
                docxFilePath = docxFile.absolutePath,
                mdFilePath = mdFile.absolutePath,
                txtFilePath = txtFile.absolutePath,
                transcriptMd = markdown
            )
            repo.save(updated)
            _state.value = _state.value.copy(
                session = updated,
                docxPath = docxFile.absolutePath,
                mdPath = mdFile.absolutePath,
                txtPath = txtFile.absolutePath
            )
        }
    }

    fun rename(newTitle: String) {
        val s = _state.value.session ?: return
        if (newTitle.isBlank() || newTitle == s.title) return
        viewModelScope.launch {
            val updated = s.copy(title = newTitle.trim())
            repo.save(updated)
            _state.value = _state.value.copy(session = updated)
        }
    }

    /**
     * 共有用ファイル（docx/md/txt）を用意する。原文 TXT が未生成なら作成する。
     */
    private fun prepareShareFiles(s: Session, datePartFileName: String): List<File> {
        val docsDir = File(context.filesDir, "docs")
        val originalFile = File(docsDir, "${s.id}_original.txt")
        val txtFile = File(docsDir, "${datePartFileName}_原文.txt")
        if (!txtFile.exists() && originalFile.exists()) {
            txtGen.generate(originalFile.readText(), txtFile)
        }
        val raw = s.rawTranscript
        if (!txtFile.exists() && raw != null) {
            txtGen.generate(raw, txtFile)
        }
        return listOfNotNull(
            _state.value.docxPath?.let { File(it) },
            _state.value.mdPath?.let { File(it) },
            if (txtFile.exists()) txtFile else null
        ).filter { it.exists() }
    }

    /** 変換結果をメールで送信する（従来仕様）。 */
    fun share(recipient: String) {
        val s = _state.value.session ?: return
        val datePartFileName = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.getDefault())
            .format(java.util.Date(s.createdAt))
        val files = prepareShareFiles(s, datePartFileName)
        if (files.isEmpty()) return
        val datePart = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(s.createdAt))
        emailShare.shareViaEmail(
            attachments = files,
            subject = s.title,
            body = "${s.title}の議事録をお送りします。\n\n生成日時: ${datePart}\n\n添付ファイル:\n・${files.firstOrNull()?.name ?: ""} (Word)\n・${files.getOrNull(1)?.name ?: ""} (Markdown)\n・${files.getOrNull(2)?.name ?: ""} (原文TXT)\n",
            recipient = recipient
        )
        viewModelScope.launch {
            repo.updateStatus(s.id, SessionStatus.SHARED)
            _state.value.session?.let {
                _state.value = _state.value.copy(session = it.copy(status = SessionStatus.SHARED))
            }
        }
    }

    /**
     * 変換結果（要約テキスト）を他アプリ（WeChat / LINE 等）へ送信する。
     * v0.9.2: テキストのみ共有（ファイルは添付しない）。
     */
    fun shareToApps() {
        val s = _state.value.session ?: return
        val markdown = _state.value.markdown
        if (markdown.isBlank()) return
        emailShare.shareToApps(text = markdown)
        viewModelScope.launch {
            repo.updateStatus(s.id, SessionStatus.SHARED)
            _state.value.session?.let {
                _state.value = _state.value.copy(session = it.copy(status = SessionStatus.SHARED))
            }
        }
    }

    /** ファイル（docx/md/txt）を Download/GIMI_MEMO/ に保存 */
    fun saveDocuments() {
        val session = _state.value.session ?: return
        val docsDir = File(context.filesDir, "docs")
        val originalFile = File(docsDir, "${session.id}_original.txt")
        val datePart = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.getDefault())
            .format(java.util.Date(session.createdAt))
        val txtFile = File(docsDir, "${datePart}_原文.txt")

        // TXT: 原文ファイルがあれば作成
        if (!txtFile.exists() && originalFile.exists()) {
            txtGen.generate(originalFile.readText(), txtFile)
        } else if (!txtFile.exists()) {
            val dbRaw = session.rawTranscript
            if (dbRaw != null) txtGen.generate(dbRaw, txtFile)
        }

        val files = listOfNotNull(
            _state.value.docxPath?.let { File(it) },
            _state.value.mdPath?.let { File(it) },
            if (txtFile.exists()) txtFile else null
        ).filter { it.exists() }
        if (files.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var savedCount = 0
                for (file in files) {
                    val fileName = file.name
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                            put(MediaStore.MediaColumns.MIME_TYPE, mimeType(file))
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/GIMI_MEMO")
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                        val uri = context.contentResolver.insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                        )
                        if (uri != null) {
                            context.contentResolver.openOutputStream(uri)?.use { os ->
                                file.inputStream().use { ins -> ins.copyTo(os) }
                            }
                            values.clear()
                            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                            context.contentResolver.update(uri, values, null, null)
                            savedCount++
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        val dir = File(
                            Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS
                            ), "GIMI_MEMO"
                        )
                        dir.mkdirs()
                        file.copyTo(File(dir, fileName), overwrite = true)
                    }
                }
                Log.i(tag, "Saved ${files.size} docs to Download/GIMI_MEMO/")
                val msg = savedCount.toString() + "ファイル保存しました"
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(tag, "saveDocuments failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "保存失敗: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun mimeType(file: File): String = when {
        file.name.endsWith(".docx") -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        file.name.endsWith(".md") -> "text/markdown"
        file.name.endsWith(".txt") -> "text/plain"
        else -> "application/octet-stream"
    }

    fun delete(onDeleted: () -> Unit) {
        val s = _state.value.session ?: return
        viewModelScope.launch {
            // 原文ファイル削除
            File(context.filesDir, "docs/${s.id}_original.txt").let { if (it.exists()) it.delete() }
            // 删文档文件（docx/md/txt 走 File.delete）
            listOfNotNull(s.docxFilePath, s.mdFilePath, s.txtFilePath)
                .map { File(it) }
                .filter { it.exists() }
                .forEach { it.delete() }
            // 录音文件：content URI 走 ContentResolver.delete，文件路径走 File.delete
            s.audioFilePath?.let { location ->
                when {
                    location.startsWith("content://") -> {
                        runCatching {
                            context.contentResolver.delete(Uri.parse(location), null, null)
                        }
                    }
                    else -> {
                        val f = File(location)
                        if (f.exists()) f.delete() else Unit
                    }
                }
            }
            // 删 DB 记录
            repo.delete(s.id)
            onDeleted()
        }
    }

    fun formatDate(epoch: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epoch))
}

data class SessionDetailState(
    val session: Session? = null,
    val markdown: String = "",
    val docxPath: String? = null,
    val mdPath: String? = null,
    val txtPath: String? = null,
    val detectedLanguage: String? = null,
    val isTranslating: Boolean = false,
    val error: String? = null
)
