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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.gijimemo.ui.settings.SettingsViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    defaultRecipient: String = "",
    onBack: () -> Unit,
    viewModel: SessionDetailViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recipients by settingsViewModel.recipients.collectAsStateWithLifecycle()
    var recipient by remember { mutableStateOf(defaultRecipient) }
    var recipientExpanded by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(recipients) {
        if (recipient.isBlank() && recipients.isNotEmpty()) {
            recipient = recipients.first()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.session?.title ?: "セッション詳細") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "削除")
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
                            text = "ステータス: ${session.status.label()}",
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
                                text = "モデル: ${session.llmProvider} / ${session.llmModel ?: "?"}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (session.audioFilePath != null) {
                            val audioFile = File(session.audioFilePath)
                            if (audioFile.exists()) {
                                Text(
                                    text = "音声: ${audioFile.length() / 1024} KB",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        if (session.durationMs > 0L) {
                            val totalSec = session.durationMs / 1000
                            val min = totalSec / 60
                            val sec = totalSec % 60
                            Text(
                                text = "長さ: %02d:%02d".format(min, sec),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        OutlinedButton(
                            onClick = { showRenameDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("名前変更")
                        }
                    }
                }
            }

            // 转写内容
            if (state.markdown.isNotBlank()) {
                Text(
                    text = "文字起こし結果",
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
                    text = state.error ?: "文字起こし結果なし",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 文档导出状态
            if (state.markdown.isNotBlank()) {
                Text("ドキュメント（自動生成）", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Word: ${if (state.docxPath != null) "✓" else "..."}")
                    Text("MD: ${if (state.mdPath != null) "✓" else "..."}")
                    Text("TXT: ${if (state.txtPath != null) "✓" else "..."}")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 邮件分享 - 收件人预设下拉
            Text("受信者", style = MaterialTheme.typography.bodySmall)
            TextButton(
                onClick = { recipientExpanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (recipient.isBlank()) "受信者を選択" else recipient)
            }
            DropdownMenu(
                expanded = recipientExpanded,
                onDismissRequest = { recipientExpanded = false }
            ) {
                if (recipients.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("（設定で受信者を追加してください）") },
                        onClick = { recipientExpanded = false }
                    )
                } else {
                    recipients.forEach { email ->
                        DropdownMenuItem(
                            text = { Text(email) },
                            onClick = { recipient = email; recipientExpanded = false }
                        )
                    }
                }
            }
            OutlinedTextField(
                value = recipient,
                onValueChange = { recipient = it },
                label = { Text("または手動で入力") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { viewModel.share(recipient) },
                enabled = state.session != null && recipient.isNotBlank() && state.markdown.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Text(" メールで共有")
            }
        }
    }

    // 删除确认
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("セッションを削除しますか?") },
            text = { Text("録音、ドキュメント、セッションが削除され、復元できません。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.delete(onDeleted = onBack)
                }) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("キャンセル") }
            }
        )
    }

    // 重命名
    if (showRenameDialog) {
        var newTitle by remember { mutableStateOf(state.session?.title ?: "") }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("名前変更") },
            text = {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    label = { Text("新しいタイトル") },
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
                TextButton(onClick = { showRenameDialog = false }) { Text("キャンセル") }
            }
        )
    }
}

private fun SessionStatus.label(): String = when (this) {
    SessionStatus.RECORDING -> "録音中"
    SessionStatus.STOPPED -> "停止済み"
    SessionStatus.TRANSCRIBING -> "文字起こし中"
    SessionStatus.READY -> "完了"
    SessionStatus.SHARED -> "共有済み"
    SessionStatus.ERROR -> "失敗"
}
