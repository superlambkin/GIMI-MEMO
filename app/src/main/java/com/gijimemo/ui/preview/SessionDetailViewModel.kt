package com.gijimemo.ui.preview

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
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

    init {
        // 事前初期化（1回目クリック時に待ち時間ゼロにするため）
        initTts()
    }

    private fun initTts() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            ttsReady = (status == TextToSpeech.SUCCESS)
            if (ttsReady) {
                tts?.language = java.util.Locale.JAPANESE
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onDone(uid: String?) { if (uid == "tts") speakNextParagraph() }
                    override fun onError(uid: String?) {}
                    override fun onStart(uid: String?) {}
                })
                // 待機中の発声を実行
                pendingSpeak?.let { doSpeak(it); pendingSpeak = null }
            }
        }
    }

    fun speak(text: String) {
        if (isSpeaking) { stopSpeaking(); return }
        paragraphs.clear()
        paragraphs.addAll(text.split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() })
        currentParagraphIndex = -1
        if (!ttsReady) {
            pendingSpeak = text // 初期化完了後に自動発声
            return
        }
        speakNextParagraph()
    }

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

    fun stopSpeaking() { tts?.stop(); isSpeaking = false; currentParagraphIndex = -1; pendingSpeak = null }
    override fun onCleared() { super.onCleared(); tts?.stop(); tts?.shutdown(); tts = null }

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
                val prompt = "以下の中文の文章を日本語に翻訳してください。Markdown形式は維持し、検出言語の行も「日本語」に更新してください。\n\n$text"
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
            if (!txtFile.exists()) txtGen.generate(markdown, txtFile)

            val updated = session.copy(
                docxFilePath = docxFile.absolutePath,
                mdFilePath = mdFile.absolutePath,
                txtFilePath = txtFile.absolutePath
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

    fun share(recipient: String) {
        val s = _state.value.session ?: return
        val files = listOfNotNull(
            _state.value.docxPath?.let { File(it) },
            _state.value.mdPath?.let { File(it) },
            _state.value.txtPath?.let { File(it) }
        ).filter { it.exists() }
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

    fun delete(onDeleted: () -> Unit) {
        val s = _state.value.session ?: return
        viewModelScope.launch {
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
