package com.gijimemo.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gijimemo.data.model.SessionStatus
import com.gijimemo.ui.settings.SettingsViewModel

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
    var showResummaryDialog by remember { mutableStateOf(false) }
    var resummaryType by remember { mutableStateOf("minutes") }
    val resummaryDefaultMaxChars = if (state.markdown.length > 0) {
        val tenth = state.markdown.length / 10 / 100 * 100
        if (state.markdown.length <= 500) state.markdown.length.coerceAtLeast(100)
        else tenth.coerceAtLeast(100)
    } else 100
    var resummaryMaxChars by remember { mutableStateOf(resummaryDefaultMaxChars.toString()) }

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
                            // 直接使用 Session.audioSizeBytes（录音结束时已记录），避免对 content URI 重新打开文件
                            if (session.audioSizeBytes > 0L) {
                                Text(
                                    text = "音声: ${session.audioSizeBytes / 1024} KB",
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

            // 音声再生 + シーク
            val isPlaying by viewModel.playbackState.collectAsStateWithLifecycle()
            val playPos by viewModel.playbackPosition.collectAsStateWithLifecycle()
            val playDur by viewModel.playbackDuration.collectAsStateWithLifecycle()
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("音声再生", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = { viewModel.playAudio() }, modifier = Modifier.heightIn(min = 36.dp)) {
                            Text(if (isPlaying) "⏸" else "▶", fontSize = 14.sp)
                        }
                        OutlinedButton(onClick = { viewModel.stopAudio() }, modifier = Modifier.heightIn(min = 36.dp)) {
                            Text("⏹", fontSize = 14.sp)
                        }
                    }
                    if (playDur > 0L) {
                        Slider(
                            value = if (playDur > 0L) playPos.toFloat() / playDur else 0f,
                            onValueChange = { viewModel.seekAudio((it * playDur).toInt()) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 20.dp)
                        )
                        val posS = playPos / 1000; val durS = playDur / 1000
                        Text(
                            "%02d:%02d / %02d:%02d".format(posS / 60, posS % 60, durS / 60, durS % 60),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 转写内容
            if (state.markdown.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { viewModel.saveDocuments() },
                        modifier = Modifier.heightIn(min = 28.dp)
                    ) { Text("保存", fontSize = 11.sp) }
                    Spacer(Modifier.weight(1f))
                    val ms = state.session?.processingDurationMs ?: 0L
                    val charCount = state.markdown.length
                    Text(
                        text = "${charCount}文字",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (ms > 0L) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "(${formatProcessingDurationDetail(ms)})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // 操作ボタン行（縮小/拡大/再生）
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { viewModel.decreaseFont() },
                        modifier = Modifier.heightIn(min = 28.dp)) { Text("縮小", fontSize = 10.sp) }
                    OutlinedButton(onClick = { viewModel.increaseFont() },
                        modifier = Modifier.heightIn(min = 28.dp)) { Text("拡大", fontSize = 10.sp) }
                    Spacer(Modifier.weight(1f))
                    val isChinese = state.detectedLanguage?.contains("中文") == true
                    val isSpeaking = viewModel.isSpeaking || viewModel.isCloudSpeaking
                    val isTranslating = state.isTranslating
                    val cleanForTts = state.markdown.replace(Regex("[#*_`>\\[\\]|\\-]"), "").trim()
                    if (isChinese) {
                        OutlinedButton(
                            onClick = { if (isTranslating) Unit else viewModel.translateToJapanese() },
                            modifier = Modifier.heightIn(min = 32.dp),
                            enabled = !isTranslating
                        ) { Text(if (isTranslating) "..." else "日文", fontSize = 11.sp) }
                    } else {
                        OutlinedButton(
                            onClick = { if (isSpeaking) viewModel.stopSpeaking() else viewModel.speak(cleanForTts) },
                            modifier = Modifier.heightIn(min = 32.dp)
                        ) { Text(if (isSpeaking) "■停止" else "▶再生", fontSize = 11.sp) }
                    }
                }
                // TTS状態メッセージ
                val ttsMsg = viewModel.ttsMessage
                if (ttsMsg != null) {
                    Text(
                        text = ttsMsg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                // Markdown全記号除去＋TTSハイライト＋可変フォント
                val curParaIdx = viewModel.currentParagraphIndex
                val paragraphs = state.markdown.split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }
                val clean = { s: String -> s.replace(Regex("[#*_`>\\[\\]|\\-]"), "").trim() }
                val bodyFontSize = viewModel.fontSizeSp
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    paragraphs.forEachIndexed { idx, para ->
                        val bg = if (idx == curParaIdx && viewModel.isSpeaking)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        else androidx.compose.ui.graphics.Color.Transparent
                        Text(
                            text = clean(para),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = bodyFontSize.sp,
                                lineHeight = (bodyFontSize * 1.6f).sp
                            ),
                            modifier = Modifier.fillMaxWidth()
                                .let { m -> if (idx == curParaIdx && viewModel.isSpeaking) m.background(bg) else m }
                        )
                        Spacer(Modifier.height((bodyFontSize * 0.5f).dp))
                    }
                }
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

            Spacer(modifier = Modifier.height(12.dp))

            // 受信者選択
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

            // 再要約（独立行）
            if (state.markdown.isNotBlank()) {
                OutlinedButton(
                    onClick = { showResummaryDialog = true },
                    enabled = !viewModel.isResummarizing,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
                ) {
                    Text(if (viewModel.isResummarizing) "再要約中..." else "TXTから再要約", fontSize = 13.sp)
                }
                Spacer(Modifier.height(12.dp))
            }

            // 下段ボタン行: 共有 / 戻る / 削除（同色背景）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { viewModel.share(recipient) },
                    enabled = state.session != null && recipient.isNotBlank() && state.markdown.isNotBlank(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) {
                    Text("共有", fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) {
                    Text("戻る", fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) {
                    Text("削除", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                }
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
        // テンプレート種別を検出してタイトル自動生成
        val suggestedTitle = run {
            val md = state.markdown
            val lines = md.lines()
            // テンプレート種別を先頭見出しで判定
            val templateType = when {
                lines.any { it.contains("講演会概要") } -> "lecture"
                lines.any { it.contains("授業概要") } -> "class"
                lines.any { it.contains("取材概要") } -> "interview"
                lines.any { it.contains("話題一覧") } -> "chat"
                lines.any { it.contains("DR概要") } -> "dr"
                else -> "minutes"
            }
            // 種別に応じたタイトル抽出
            val titleFromContent = when (templateType) {
                "lecture" -> Regex("""\*\*タイトル\*\*[：:]\s*(.+)""").find(md)?.groupValues?.get(1)?.trim()
                "class" -> Regex("""\*\*科目名\*\*[：:]\s*(.+)""").find(md)?.groupValues?.get(1)?.trim()
                    ?: Regex("""授業概要""").find(md)?.let { "授業" }
                "interview" -> Regex("""\*\*テーマ\*\*[：:]\s*(.+)""").find(md)?.groupValues?.get(1)?.trim()
                    ?: Regex("""取材概要""").find(md)?.let { "取材" }
                "chat" -> Regex("""[#]+\s*(.+)""").find(md)?.groupValues?.get(1)?.trim()?.take(30)
                "dr" -> Regex("""\*\*プロジェクト名\*\*[：:]\s*(.+)""").find(md)?.groupValues?.get(1)?.trim()
                    ?: Regex("""DR概要""").find(md)?.let { "DR" }
                else -> Regex("""\s*(?:議題|テーマ)[：:;]\s*(.+)""").find(md)?.groupValues?.get(1)?.trim()
                    ?: Regex("""\*\*タイトル\*\*[：:]\s*(.+)""").find(md)?.groupValues?.get(1)?.trim()
            }
            val heading = titleFromContent ?: lines.firstOrNull { it.startsWith("# ") }
                ?.removePrefix("# ")?.trim()?.take(50) ?: "会議"
            val date = state.session?.let {
                java.text.SimpleDateFormat("MM/dd", java.util.Locale.getDefault())
                    .format(java.util.Date(it.createdAt))
            } ?: ""
            "$heading $date"
        }
        var newTitle by remember { mutableStateOf(suggestedTitle) }
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

    // 再要約設定ダイアログ
    if (showResummaryDialog) {
        ResummaryOptionsDialog(
            initialType = resummaryType,
            initialMaxChars = resummaryMaxChars,
            markdownLength = state.markdown.length,
            onConfirm = { type, chars ->
                resummaryType = type
                resummaryMaxChars = chars
                showResummaryDialog = false
                val maxInt = chars.toIntOrNull() ?: resummaryDefaultMaxChars
                viewModel.resummarizeWithOptions(type, maxInt)
            },
            onDismiss = { showResummaryDialog = false }
        )
    }
}

// ─── 再要約設定ダイアログ ────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResummaryOptionsDialog(
    initialType: String,
    initialMaxChars: String,
    markdownLength: Int,
    onConfirm: (type: String, maxChars: String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedType by remember { mutableStateOf(initialType) }
    var maxCharsText by remember { mutableStateOf(initialMaxChars) }
    var typeExpanded by remember { mutableStateOf(false) }

    val typeOptions = listOf(
        "minutes" to "議事録",
        "lecture" to "講演会",
        "class" to "授業",
        "interview" to "取材",
        "chat" to "雑談",
        "dr" to "DR"
    )
    val typeLabel = typeOptions.first { it.first == selectedType }.second

    val defaultChars = ((markdownLength / 10) / 100 * 100).coerceAtLeast(100)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("要約設定（再要約）") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "原文文字数: ${markdownLength} 文字",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 種類選択
                ExposedDropdownMenuBox(
                    expanded = typeExpanded,
                    onExpandedChange = { typeExpanded = it }
                ) {
                    OutlinedTextField(
                        value = typeLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("種類") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = typeExpanded,
                        onDismissRequest = { typeExpanded = false }
                    ) {
                        typeOptions.forEach { (key, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    selectedType = key
                                    typeExpanded = false
                                }
                            )
                        }
                    }
                }

                // 最大文字数スライダー（100文字単位）。最大値は原文の2倍以上に設定可能
                val maxVal = (maxOf(markdownLength * 2, 200) / 100 * 100).coerceIn(200, 50000)
                val sliderVal = (maxCharsText.toIntOrNull() ?: defaultChars).toFloat()
                Slider(
                    value = sliderVal,
                    onValueChange = { v ->
                        val snapped = (v / 100f).roundToInt() * 100
                        maxCharsText = snapped.coerceIn(100, maxVal).toString()
                    },
                    valueRange = 100f..maxVal.toFloat(),
                    steps = ((maxVal - 100) / 100).coerceIn(1, 500),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("100", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${maxVal}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "${maxCharsText} 文字（原文の1/10: $defaultChars）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selectedType, maxCharsText) }) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        }
    )
}

private fun SessionStatus.label(): String = when (this) {
    SessionStatus.RECORDING -> "録音中"
    SessionStatus.STOPPED -> "停止済み"
    SessionStatus.TRANSCRIBING -> "文字起こし中"
    SessionStatus.READY -> "完了"
    SessionStatus.SHARED -> "共有済み"
    SessionStatus.ERROR -> "失敗"
}

/** ms → "X分Y秒" / "Y秒" 表示。SessionDetail のヘッダ右側用。 */
private fun formatProcessingDurationDetail(ms: Long): String {
    val totalSec = (ms / 1000).toInt()
    val min = totalSec / 60
    val sec = totalSec % 60
    return if (min > 0) "${min}分${sec}秒" else "${sec}秒"
}
