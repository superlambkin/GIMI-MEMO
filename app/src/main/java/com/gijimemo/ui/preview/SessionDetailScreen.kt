package com.gijimemo.ui.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gijimemo.data.model.SessionStatus
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    defaultRecipient: String = "",
    onBack: () -> Unit,
    viewModel: SessionDetailViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var recipient by remember { mutableStateOf(defaultRecipient) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.session?.title ?: "会话详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 元数据卡片
            state.session?.let { session ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = session.title,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = viewModel.formatDate(session.createdAt),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "状态: ${session.status.label()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = when (session.status) {
                                SessionStatus.READY -> MaterialTheme.colorScheme.primary
                                SessionStatus.SHARED -> MaterialTheme.colorScheme.tertiary
                                SessionStatus.ERROR -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                        )
                        if (session.llmProvider != null) {
                            Text(
                                text = "模型: ${session.llmProvider} / ${session.llmModel ?: "?"}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (session.audioFilePath != null) {
                            val audioFile = File(session.audioFilePath)
                            if (audioFile.exists()) {
                                Text(
                                    text = "音频: ${audioFile.length() / 1024} KB",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = { showRenameDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("重命名")
                        }
                    }
                }
            }

            // 转写内容
            if (state.markdown.isNotBlank()) {
                Text(
                    text = "转写结果",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = state.markdown,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                )
            } else {
                Text(
                    text = state.error ?: "暂无转写内容",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 文档导出状态
            if (state.markdown.isNotBlank()) {
                Text("文档（自动生成）", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Word: ${if (state.docxPath != null) "✓" else "..."}")
                    Text("MD: ${if (state.mdPath != null) "✓" else "..."}")
                    Text("TXT: ${if (state.txtPath != null) "✓" else "..."}")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 邮件分享
            OutlinedTextField(
                value = recipient,
                onValueChange = { recipient = it },
                label = { Text("收件人邮箱") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { viewModel.share(recipient) },
                enabled = state.session != null && recipient.isNotBlank() && state.markdown.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Text(" 分享到邮件")
            }
        }
    }

    // 删除确认
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除会话?") },
            text = { Text("此操作将删除录音、文档和会话记录，且不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.delete(onDeleted = onBack)
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            }
        )
    }

    // 重命名
    if (showRenameDialog) {
        var newTitle by remember { mutableStateOf(state.session?.title ?: "") }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    label = { Text("新标题") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.rename(newTitle)
                    showRenameDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("取消") }
            }
        )
    }
}

private fun SessionStatus.label(): String = when (this) {
    SessionStatus.RECORDING -> "录音中"
    SessionStatus.STOPPED -> "已停止"
    SessionStatus.TRANSCRIBING -> "转写中"
    SessionStatus.READY -> "已完成"
    SessionStatus.SHARED -> "已分享"
    SessionStatus.ERROR -> "失败"
}
