package com.gijimemo.ui.preview

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gijimemo.data.model.Session
import com.gijimemo.data.model.SessionStatus
import com.gijimemo.data.repository.SessionRepository
import com.gijimemo.data.repository.SettingsRepository
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
import javax.inject.Inject

@HiltViewModel
class PreviewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: SessionRepository,
    private val settings: SettingsRepository,
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
    private var ttsEngineSetting: String? = null
    private var ttsSpeakText: String = ""
    private var ttsStartMs: Long = 0L
    var ttsProgress by mutableStateOf(0f); private set  // 0.0〜1.0
    var ttsPositionSec by mutableIntStateOf(0); private set
    var ttsDurationSec by mutableIntStateOf(0); private set
    private var ttsJob: kotlinx.coroutines.Job? = null

    private val googleTtsPackage = "com.google.android.tts"
    private fun isPackageInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, PackageManager.GET_META_DATA)
        true
    } catch (_: PackageManager.NameNotFoundException) { false }

    init {
        viewModelScope.launch {
            ttsEngineSetting = settings.ttsEngine.first()
            if (tts == null) initTts()
        }
    }

    private fun initTts() {
        if (tts != null && ttsReady) return
        tts?.shutdown()

        // Mate 60 Pro (Huawei) では PackageManager で Google TTS を検出しても
        // TextToSpeech(context, listener, packageName) が失敗する。
        // フルサービスクラス名を試行し、それでもダメならデフォルトエンジン。
        val engineCandidates = listOfNotNull(
            ttsEngineSetting,
            googleTtsPackage,
            "com.google.android.apps.speech.tts.googletts.service.GoogleTtsService"
        ).distinct()

        var currentError: Exception? = null
        for (candidate in engineCandidates) {
            try {
                @Suppress("DEPRECATION")
                tts = TextToSpeech(context, { status -> onTtsInit(status) }, candidate)
                Log.i("PreviewVM", "initTts: succeeded with engine=$candidate")
                currentError = null
                break
            } catch (e: Exception) {
                Log.w("PreviewVM", "initTts: engine='$candidate' failed: ${e.message}")
                currentError = e
            }
        }

        // 全エンジン候補が失敗 → デフォルトエンジン
        if (currentError != null) {
            Log.w("PreviewVM", "initTts: all engine candidates failed, using default")
            @Suppress("DEPRECATION")
            tts = TextToSpeech(context) { status -> onTtsInit(status) }
        }
    }

    private fun isHuaweiBuild(): Boolean {
        return Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true)
                || Build.BRAND.equals("HUAWEI", ignoreCase = true)
    }

    private fun onTtsInit(status: Int) {
        ttsReady = (status == TextToSpeech.SUCCESS)
        if (ttsReady) {
            setupTts()
        } else {
            Log.w("PreviewVM", "TTS init failed: status=$status engine=$ttsEngineSetting")
        }
    }

    private fun setupTts() {
        // 日本語設定: isLanguageAvailable の結果によらず常に setLanguage を試す
        tts?.setLanguage(java.util.Locale.JAPANESE)
        Log.i("PreviewVM", "setupTts: setLanguage(Japanese) done")
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onDone(uid: String?) { if (uid == "tts") isSpeaking = false }
            override fun onError(uid: String?) { if (uid == "tts") isSpeaking = false }
            override fun onStart(uid: String?) {}
        })
        pendingSpeak?.let { text ->
            speakImmediate(text)
            pendingSpeak = null
        }
    }

    private fun speakImmediate(text: String) {
        ttsSpeakText = text
        ttsStartMs = System.currentTimeMillis()
        // TTS 話速は日本語で約 6文字/秒。実際の話速は割愛（標準APIなし）.
        ttsDurationSec = (text.length / 6).coerceIn(1, 3600)
        ttsProgress = 0f
        ttsPositionSec = 0

        val params = java.util.HashMap<String, String>()
        params[android.speech.tts.TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID] = "tts"
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params)
        isSpeaking = true

        ttsJob?.cancel()
        ttsJob = kotlinx.coroutines.MainScope().launch(kotlinx.coroutines.Dispatchers.Main) {
            while (isSpeaking) {
                val elapsed = System.currentTimeMillis() - ttsStartMs
                ttsPositionSec = (elapsed / 1000).toInt()
                ttsProgress = (elapsed.toFloat() / (ttsDurationSec * 1000f)).coerceIn(0f, 1f)
                kotlinx.coroutines.delay(250)
            }
            ttsProgress = 1f
            ttsPositionSec = ttsDurationSec
        }
    }

    fun speak(text: String) {
        if (isSpeaking) { stopSpeaking(); return }
        if (!ttsReady) { pendingSpeak = text; initTts(); return }
        speakImmediate(text)
    }

    fun stopSpeaking() {
        tts?.stop()
        isSpeaking = false
        pendingSpeak = null
        ttsJob?.cancel()
        ttsProgress = 0f
        ttsPositionSec = 0
    }

    /** TTS 再生位置をシーク（文字位置ベースで再発話） */
    fun seekTts(progress: Float) {
        if (!isSpeaking || ttsSpeakText.isEmpty()) return
        tts?.stop()
        val charPos = (ttsSpeakText.length * progress.coerceIn(0f, 1f)).toInt()
        val remainingText = ttsSpeakText.substring(charPos.coerceAtMost(ttsSpeakText.length - 1))
        if (remainingText.isNotEmpty()) {
            // 再開時に累積時間を調整
            ttsStartMs = System.currentTimeMillis() - (ttsDurationSec * 1000f * progress).toLong()
            val params = java.util.HashMap<String, String>()
            params[android.speech.tts.TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID] = "tts"
            tts?.speak(remainingText, TextToSpeech.QUEUE_FLUSH, params)
        }
    }
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
            // TXTは自動生成しない（保存ボタンで原文ファイルから個別に作成）

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

    /** ファイル（docx/md/txt）を Download/GIMI_MEMO/ に保存 */
    fun saveDocuments() {
        val docsDir = File(context.filesDir, "docs")
        val sessionId = _state.value.session?.id ?: return
        val originalFile = File(docsDir, "${sessionId}_original.txt")
        val datePart = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.getDefault())
            .format(java.util.Date(_state.value.session?.createdAt ?: System.currentTimeMillis()))
        val txtFile = File(docsDir, "${datePart}_原文.txt")

        // TXT: 原文ファイルがあれば作成、なければ rawTranscript→session→スキップ
        if (!txtFile.exists() && originalFile.exists()) {
            txtGen.generate(originalFile.readText(), txtFile)
        }
        if (!txtFile.exists() && _state.value.session?.rawTranscript != null) {
            txtGen.generate(_state.value.session!!.rawTranscript!!, txtFile)
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
                        savedCount++
                    }
                }
                if (txtFile.exists() && !originalFile.exists() && _state.value.session?.rawTranscript == null) {
                    // TXTファイルを生成できなかった場合は注意表示
                }
                val msg = savedCount.toString() + "ファイル保存しました" +
                    (if (!txtFile.exists()) "（TXTは原文なし）" else "")
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("PreviewVM", "saveDocuments failed: ${e.message}")
            }
        }
    }

    private fun mimeType(file: File): String = when {
        file.name.endsWith(".docx") -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        file.name.endsWith(".md") -> "text/markdown"
        file.name.endsWith(".txt") -> "text/plain"
        else -> "application/octet-stream"
    }

    fun share(recipient: String) {
        val s = _state.value.session ?: return

        // v0.9.1: 原文 TXT は ensureDocuments では生成されないため、共有時に
        // 未生成ならここで作成してから添付する（従来は存在しないファイルを
        // filter { it.exists() } で除外し、TXT が添付されなかった）。
        val docsDir = File(context.filesDir, "docs")
        val originalFile = File(docsDir, "${s.id}_original.txt")
        val datePartFileName = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.getDefault())
            .format(java.util.Date(s.createdAt))
        val txtFile = File(docsDir, "${datePartFileName}_原文.txt")
        if (!txtFile.exists() && originalFile.exists()) {
            txtGen.generate(originalFile.readText(), txtFile)
        }
        val raw = s.rawTranscript
        if (!txtFile.exists() && raw != null) {
            txtGen.generate(raw, txtFile)
        }

        val files = listOfNotNull(
            _state.value.docxPath?.let { File(it) },
            _state.value.mdPath?.let { File(it) },
            if (txtFile.exists()) txtFile else null
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
