package com.gijimemo.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gijimemo.data.model.LlmCallMode
import com.gijimemo.ui.settings.SettingsViewModel

@Composable
fun PreviewScreen(
    defaultRecipient: String = "",
    onBackToTranscript: () -> Unit,
    onBackToMenu: () -> Unit,
    onBackToSessionDetail: (String) -> Unit = {},
    viewModel: PreviewViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recipients by settingsViewModel.recipients.collectAsStateWithLifecycle()
    var recipient by remember { mutableStateOf(defaultRecipient) }
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(recipients) {
        if (recipient.isBlank() && recipients.isNotEmpty()) {
            recipient = recipients.first()
        }
    }

    // v0.7.4: 画面離脱時に TTS 再生を停止
    DisposableEffect(Unit) { onDispose { viewModel.stopSpeaking() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp)
            .padding(top = 32.dp) // 上部余白確保
            .verticalScroll(rememberScrollState())
    ) {
        // ─── ヘッダー: タイトル ────────────────────────
        Text(
            text = state.session?.title ?: "読み込み中",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(Modifier.height(16.dp))

        // ─── 操作ボタン ────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Spacer(Modifier.weight(1f))
            // 統一サイズの操作ボタン行
            OutlinedButton(
                onClick = { viewModel.decreaseFont() },
                modifier = Modifier.height(40.dp),
                enabled = viewModel.fontSizeSp > 10
            ) {
                Text("縮小", fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = { viewModel.increaseFont() },
                modifier = Modifier.height(40.dp),
                enabled = viewModel.fontSizeSp < 24
            ) {
                Text("拡大", fontSize = 12.sp)
            }
            Button(
                onClick = {
                    viewModel.copyToClipboard()
                    android.widget.Toast.makeText(context, "コピーしました", android.widget.Toast.LENGTH_SHORT).show()
                },
                enabled = state.markdown.isNotBlank(),
                modifier = Modifier.height(40.dp)
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.width(16.dp).height(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("COPY", fontSize = 12.sp)
            }
            Spacer(Modifier.width(4.dp))
            val isSpeaking = viewModel.isSpeaking
            OutlinedButton(
                onClick = {
                    if (isSpeaking) viewModel.stopSpeaking()
                    else viewModel.speak(state.markdown.replace(Regex("[#*_`>\\[\\]|\\-]"), "").trim())
                },
                modifier = Modifier.height(40.dp)
            ) {
                Text(if (isSpeaking) "■停止" else "▶再生", fontSize = 12.sp)
            }
        }

        // v0.7.4: TTS 再生シークバー
        if (viewModel.ttsDurationSec > 0) {
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "%02d:%02d".format(viewModel.ttsPositionSec / 60, viewModel.ttsPositionSec % 60),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(48.dp)
                )
                Slider(
                    value = viewModel.ttsProgress,
                    onValueChange = { viewModel.seekTts(it) },
                    modifier = Modifier.weight(1f).height(24.dp),
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
                Text(
                    text = "%02d:%02d".format(viewModel.ttsDurationSec / 60, viewModel.ttsDurationSec % 60),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(48.dp)
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ─── 見出し ────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "要約結果（Word書式）",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${state.markdown.length}文字",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            val session = state.session
            val transcribeMs = session?.transcribeDurationMs ?: 0L
            val totalMs = session?.processingDurationMs ?: 0L
            // 表示判定: WHISPER_THEN_SUMMARY かつ文字起こし時間記録あり → 文字起こし時間のみ表示
            // フォールバック: 合計時間 / 何もなければ非表示
            val label: String? = when {
                session?.llmCallMode == LlmCallMode.WHISPER_THEN_SUMMARY && transcribeMs > 0L ->
                    "文字起こし: ${formatProcessingDuration(transcribeMs)}"
                totalMs > 0L -> formatProcessingDuration(totalMs)
                else -> null
            }
            if (label != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "($label)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // ─── 要約本文（**除去＋可変フォント）───────────
        val bodyFontSize = viewModel.fontSizeSp
        fun stripMd(s: String) = s.replace("**", "").replace("__", "").replace("```", "")
        val lines = state.markdown.lines()
        for (line in lines) {
            when {
                line.startsWith("# ") -> {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stripMd(line.removePrefix("# ")),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                }
                line.startsWith("## ") -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stripMd(line.removePrefix("## ")),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                }
                line.startsWith("### ") -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stripMd(line.removePrefix("### ")),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = bodyFontSize.sp),
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                line.startsWith("- ") || line.startsWith("-**") -> {
                    Text(
                        text = "・${stripMd(line.removePrefix("-").removePrefix(" "))}",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = bodyFontSize.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                line.startsWith("---") || line.isBlank() -> {
                    Spacer(Modifier.height(4.dp))
                }
                else -> {
                    Text(
                        text = stripMd(line),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = bodyFontSize.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // ─── 受信者 ────────────────────────────────────
        Text("受信者", style = MaterialTheme.typography.bodySmall)
        var expanded by remember { mutableStateOf(false) }
        TextButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(if (recipient.isBlank()) "受信者を選択" else recipient)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (recipients.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("（設定で受信者を追加してください）") },
                    onClick = { expanded = false }
                )
            } else {
                recipients.forEach { email ->
                    DropdownMenuItem(
                        text = { Text(email) },
                        onClick = { recipient = email; expanded = false }
                    )
                }
            }
        }
        OutlinedTextField(
            value = recipient,
            onValueChange = { recipient = it },
            label = { Text("または手動で入力") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        )

        Spacer(Modifier.height(8.dp))

        // ─── 保存 ─────────────────────────────────────
        OutlinedButton(
            onClick = { viewModel.saveDocuments() },
            modifier = Modifier.fillMaxWidth().height(44.dp)
        ) { Text("保存（docx/md/txt）", fontSize = 13.sp) }

        Spacer(Modifier.height(8.dp))

        // ─── メールで共有 ──────────────────────────────
        Button(
            onClick = { viewModel.share(recipient) },
            enabled = state.session != null && recipient.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text("メールで共有", fontSize = 14.sp)
        }

        Spacer(Modifier.height(12.dp))

        // ─── 下段ボタン行 ──────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = { onBackToSessionDetail(viewModel.sessionId) },
                modifier = Modifier.weight(1f).height(52.dp)
            ) {
                Text("文字起こし結果", fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = onBackToMenu,
                modifier = Modifier.weight(1f).height(52.dp)
            ) {
                Text("メニューへ戻る", fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(50.dp)) // 下部余白確保
    }
}

/** ms → "X分Y秒" / "Y秒" 表示 */
private fun formatProcessingDuration(ms: Long): String {
    val totalSec = (ms / 1000).toInt()
    val min = totalSec / 60
    val sec = totalSec % 60
    return if (min > 0) "${min}分${sec}秒" else "${sec}秒"
}
