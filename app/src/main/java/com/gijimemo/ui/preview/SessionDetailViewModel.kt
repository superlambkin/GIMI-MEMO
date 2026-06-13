package com.gijimemo.ui.preview

import android.content.Context
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: SessionRepository,
    private val wordGen: WordDocumentGenerator,
    private val mdGen: MarkdownGenerator,
    private val txtGen: TextGenerator,
    private val emailShare: EmailShareService,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val sessionId: String = savedStateHandle.get<String>("sessionId") ?: error("missing sessionId")

    private val _state = MutableStateFlow(SessionDetailState())
    val state: StateFlow<SessionDetailState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            val session = repo.getById(sessionId) ?: run {
                _state.value = _state.value.copy(error = "会话不存在")
                return@launch
            }
            val transcript = session.transcriptMd
            _state.value = _state.value.copy(session = session, markdown = transcript ?: "")
            // 仅当存在转写结果时再生成文档
            if (!transcript.isNullOrBlank()) {
                ensureDocuments(session, transcript)
            }
        }
    }

    private suspend fun ensureDocuments(session: Session, markdown: String) {
        withContext(Dispatchers.IO) {
            val docsDir = File(context.filesDir, "docs").apply { mkdirs() }
            val docxFile = File(docsDir, "${session.id}.docx")
            val mdFile = File(docsDir, "${session.id}.md")
            val txtFile = File(docsDir, "${session.id}.txt")

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
        emailShare.shareViaEmail(
            attachments = files,
            subject = s.title,
            body = "请查收会议纪要。\n\n生成时间：${formatDate(System.currentTimeMillis())}\n",
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
            // 删文档文件
            listOfNotNull(s.docxFilePath, s.mdFilePath, s.txtFilePath, s.audioFilePath)
                .map { File(it) }
                .filter { it.exists() }
                .forEach { it.delete() }
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
    val error: String? = null
)
