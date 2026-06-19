package com.gijimemo.ui.preview

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class PreviewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: SessionRepository,
    private val wordGen: WordDocumentGenerator,
    private val mdGen: MarkdownGenerator,
    private val txtGen: TextGenerator,
    private val emailShare: EmailShareService,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val sessionId: String = savedStateHandle.get<String>("sessionId") ?: error("missing sessionId")

    /** 表示用フォントサイズ（sp） */
    var fontSizeSp by mutableStateOf(14)
        private set

    // ─── TTS（音声読み上げ） ──────────────────────────
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    var isSpeaking by mutableStateOf(false); private set
    private var pendingSpeak: String? = null

    init {
        tts = TextToSpeech(context) { status ->
            ttsReady = (status == TextToSpeech.SUCCESS)
            if (ttsReady) {
                tts?.language = java.util.Locale.JAPANESE
                pendingSpeak?.let { s -> tts?.speak(s, TextToSpeech.QUEUE_FLUSH, null, "tts"); isSpeaking = true; pendingSpeak = null }
            }
        }
    }

    fun speak(text: String) {
        if (isSpeaking) { stopSpeaking(); return }
        if (!ttsReady) { pendingSpeak = text; return }
        try { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts"); isSpeaking = true }
        catch (e: Exception) { Log.e("PreviewVM", "TTS fail", e) }
    }

    fun stopSpeaking() { tts?.stop(); isSpeaking = false; pendingSpeak = null }
    override fun onCleared() { super.onCleared(); tts?.stop(); tts?.shutdown(); tts = null }

    private val _state = MutableStateFlow(PreviewState())
    val state: StateFlow<PreviewState> = _state.asStateFlow()

    fun increaseFont() { fontSizeSp = (fontSizeSp + 2).coerceAtMost(24) }
    fun decreaseFont() { fontSizeSp = (fontSizeSp - 2).coerceAtLeast(10) }

    fun load() {
        viewModelScope.launch {
            val session = repo.getById(sessionId) ?: return@launch
            val markdown = session.transcriptMd ?: return@launch
            val detectedLang = extractDetectedLanguage(markdown)
            _state.value = _state.value.copy(
                session = session,
                markdown = markdown,
                detectedLanguage = detectedLang
            )
            ensureDocuments(session, markdown)
        }
    }

    /**
     * Markdown 文字列の先頭の `# 検出言語` セクションを抽出して表示用に整形。
     * 見つかれば "中文" / "日本語" / "English" 等を返す。
     */
    private fun extractDetectedLanguage(markdown: String): String? {
        val regex = Regex("""^#\s*検出言語[^\n]*\n+([^\n#]+)""", RegexOption.MULTILINE)
        val match = regex.find(markdown) ?: return null
        return match.groupValues[1].trim().takeIf { it.isNotEmpty() }
    }

    /**
     * プレビュー全文をシステムクリップボードにコピー。
     * Android 13 (Tiramisu) 以降はシステム標準のコピー通知が出るのでトースト不要。
     */
    fun copyToClipboard() {
        val text = _state.value.markdown
        if (text.isBlank()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("GijiMemo 議事録", text)
        clipboard.setPrimaryClip(clip)
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

    fun share(recipient: String) {
        val s = _state.value.session ?: return
        val files = listOfNotNull(
            _state.value.docxPath?.let { File(it) },
            _state.value.mdPath?.let { File(it) },
            _state.value.txtPath?.let { File(it) }
        ).filter { it.exists() }
        if (files.isEmpty()) return
        val datePart = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(s?.createdAt ?: System.currentTimeMillis()))
        emailShare.shareViaEmail(
            attachments = files,
            subject = s.title,
            body = "${s.title}の議事録をお送りします。\n\n生成日時: ${datePart}\n\n添付ファイル:\n・${files.firstOrNull()?.name ?: ""} (Word)\n・${files.getOrNull(1)?.name ?: ""} (Markdown)\n・${files.getOrNull(2)?.name ?: ""} (原文TXT)\n",
            recipient = recipient
        )
        viewModelScope.launch {
            repo.updateStatus(s.id, SessionStatus.SHARED)
        }
    }
}

data class PreviewState(
    val session: Session? = null,
    val markdown: String = "",
    val detectedLanguage: String? = null,
    val docxPath: String? = null,
    val mdPath: String? = null,
    val txtPath: String? = null,
    val error: String? = null
)
