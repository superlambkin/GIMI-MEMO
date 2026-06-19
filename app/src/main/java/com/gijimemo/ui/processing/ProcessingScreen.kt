package com.gijimemo.ui.processing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessingScreen(
    onComplete: (sessionId: String) -> Unit,
    onError: () -> Unit,
    viewModel: ProcessingViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 初回のみ処理開始
    LaunchedEffect(Unit) { viewModel.start() }

    // 完了状態での自動遷移は行わない。ユーザーが「戻る」を押した時に遷移する。

    // 編集中の文字起こしテキスト
    var editedTranscript by remember { mutableStateOf("") }
    LaunchedEffect(state.rawTranscript) {
        if (state.rawTranscript.isNotEmpty() && editedTranscript.isEmpty()) {
            editedTranscript = state.rawTranscript
        }
    }

    // 要約設定ダイアログの状態
    var showSummaryDialog by remember { mutableStateOf(false) }
    var summaryType by remember { mutableStateOf("minutes") }
    val defaultMaxChars = if (editedTranscript.length > 0) {
        val tenth = editedTranscript.length / 10 / 100 * 100
        if (editedTranscript.length <= 500) editedTranscript.length
        else tenth.coerceAtLeast(100)
    } else 100
    var summaryMaxChars by remember { mutableStateOf(defaultMaxChars.toString()) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        when (state.phase) {
            ProcessingPhase.IDLE,
            ProcessingPhase.TRANSCRIBING -> {
                TranscribingContent(
                    useOnDevice = state.useOnDevice,
                    detailStatus = state.detailStatus,
                    activeProvider = state.activeProvider,
                    activeModel = state.activeModel,
                    totalChunks = state.totalChunks,
                    completedChunks = state.completedChunks,
                    splitTimeMs = state.splitTimeMs,
                    chunkTimeEstimateMs = state.chunkTimeEstimateMs
                )
            }
            ProcessingPhase.TRANSCRIBED -> {
                val isPlaying by viewModel.playbackState.collectAsStateWithLifecycle()
                val playPos by viewModel.playbackPosition.collectAsStateWithLifecycle()
                val playDur by viewModel.playbackDuration.collectAsStateWithLifecycle()
                TranscribedContent(
                    transcript = editedTranscript,
                    onTranscriptChange = { editedTranscript = it },
                    transcribeDurationMs = state.transcribeDurationMs,
                    isPlaying = isPlaying,
                    playbackPositionMs = playPos,
                    playbackDurationMs = playDur,
                    onPlayPause = { viewModel.playAudio() },
                    onStop = { viewModel.stopAudio() },
                    onSeek = { viewModel.seekAudio(it) },
                    onSummarize = { showSummaryDialog = true },
                    onSave = { viewModel.saveTranscriptToDownloads(editedTranscript) },
                    onRetry = { viewModel.retryTranscribe() },
                    onBack = onError
                )
            }
            ProcessingPhase.SUMMARIZING -> {
                SummarizingContent(
                    summaryText = state.summaryText,
                    activeModel = state.activeModel,
                    activeProvider = state.activeProvider
                )
            }
            ProcessingPhase.COMPLETED -> {
                // 完了即時プレビューへ遷移
                LaunchedEffect(Unit) { onComplete(viewModel.sessionId) }
            }
            ProcessingPhase.ERROR -> {
                ErrorContent(
                    error = state.error,
                    onRetry = { viewModel.start() },
                    onBack = onError
                )
            }
        }
    }

    // ─── 要約設定ダイアログ ──────────────────────────────
    if (showSummaryDialog) {
        SummaryOptionsDialog(
            initialType = summaryType,
            initialMaxChars = summaryMaxChars,
            transcriptLength = editedTranscript.length,
            onConfirm = { type, chars ->
                summaryType = type
                summaryMaxChars = chars.toString()
                showSummaryDialog = false
                val maxInt = chars.toIntOrNull() ?: defaultMaxChars
                viewModel.confirmAndSummarize(editedTranscript, type, maxInt)
            },
            onDismiss = { showSummaryDialog = false }
        )
    }
}

// ─── 要約設定ダイアログ ────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SummaryOptionsDialog(
    initialType: String,
    initialMaxChars: String,
    transcriptLength: Int,
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

    val defaultChars = ((transcriptLength / 10) / 100 * 100).coerceAtLeast(100)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("要約設定") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 原文文字数表示
                Text(
                    "原文文字数: ${transcriptLength} 文字",
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

                // 最大文字数スライダー（100文字単位）
                val maxVal = (transcriptLength / 100 * 100).coerceIn(100, 50000)
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

// ─── フェーズ別 UI ──────────────────────────────────────────────

@Composable
private fun TranscribingContent(
    useOnDevice: Boolean = false,
    detailStatus: String = "",
    activeProvider: String = "",
    activeModel: String = "",
    totalChunks: Int = 0,
    completedChunks: Int = 0,
    splitTimeMs: Long = 0L,
    chunkTimeEstimateMs: Long = 0L
) {
    var elapsedSec by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(500)
            elapsedSec++
        }
    }

    // 画面オフ防止
    val view = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(Unit) {
        view.keepScreenOn = true
    }

    val phaseMessage = when {
        useOnDevice && elapsedSec < 3 -> "音声ファイルを読み込み中..."
        useOnDevice && elapsedSec < 30 -> "ローカル Whisper モデル (base, 141MB) を読み込み中..."
        useOnDevice -> "文字起こし中..."
        else -> "クラウドで文字起こし中..."
    }

    // 予測時間計算
    val apiTotalEstimateMs = totalChunks * chunkTimeEstimateMs
    val overallEstimateMs = splitTimeMs + apiTotalEstimateMs
    val elapsedMs = elapsedSec * 1000L
    val remainingMs = (overallEstimateMs - elapsedMs).coerceAtLeast(0L)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "文字起こし",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        Text(
            phaseMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))

        // 円グラフ（予測値がある場合のみ）
        if (overallEstimateMs > 0L) {
            val colSplit = MaterialTheme.colorScheme.primary
            val colDone = MaterialTheme.colorScheme.secondary
            val colBg = MaterialTheme.colorScheme.surfaceVariant
            // チャンク色：primaryからtertiaryへグラデーション
            val chunkColors = if (totalChunks > 0) (0 until totalChunks).map { i ->
                val ratio = i.toFloat() / totalChunks.coerceAtLeast(1)
                androidx.compose.ui.graphics.Color(
                    red = 0.6f + 0.3f * ratio,
                    green = 0.5f - 0.3f * ratio,
                    blue = 0.3f - 0.2f * ratio,
                    alpha = 1f
                )
            } else emptyList()

            val totalMs = splitTimeMs + apiTotalEstimateMs
            val splitAngle = (splitTimeMs.toFloat() / totalMs * 360f).coerceIn(1f, 359f)
            val chunkMsEach = if (totalChunks > 0) apiTotalEstimateMs / totalChunks else 0L
            val chunkAngleEach = if (totalChunks > 0) (chunkMsEach.toFloat() / totalMs * 360f).coerceAtLeast(1f) else 0f
            val doneRatio = (elapsedMs.toFloat() / totalMs).coerceIn(0f, 1f)

            // 現在のフェーズと進捗に基づく中央テキスト
            val currentChunk = completedChunks + 1
            val isDelayed = chunkTimeEstimateMs > 0L &&
                completedChunks < totalChunks &&
                (elapsedMs - splitTimeMs) > (completedChunks + 1) * chunkTimeEstimateMs * 1.3f
            val centerText = when {
                detailStatus.contains("分割") || (splitTimeMs > 0L && elapsedMs <= splitTimeMs) -> "分割"
                isDelayed -> "遅延"
                completedChunks < totalChunks && totalChunks > 0 -> "Chunk$currentChunk"
                totalChunks > 0 && completedChunks >= totalChunks -> "完了"
                else -> "準備"
            }

            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(120.dp)) {
                Canvas(modifier = Modifier.size(120.dp)) {
                    val strokeWd = 28f
                    // 背景
                    drawArc(color = colBg, startAngle = -90f, sweepAngle = 360f, useCenter = false,
                        topLeft = Offset(strokeWd / 2, strokeWd / 2),
                        size = Size(size.width - strokeWd, size.height - strokeWd),
                        style = Stroke(width = strokeWd))
                    // 分割スライス
                    drawArc(color = colSplit, startAngle = -90f, sweepAngle = splitAngle, useCenter = false,
                        topLeft = Offset(strokeWd / 2, strokeWd / 2),
                        size = Size(size.width - strokeWd, size.height - strokeWd),
                        style = Stroke(width = strokeWd))
                    // チャンク毎のスライス
                    val chunkSweeps = mutableListOf<Float>()
                    val remainingForChunks = 360f - splitAngle
                    for (i in 0 until totalChunks) {
                        val sweep = if (i < totalChunks - 1) chunkAngleEach
                            else remainingForChunks - (totalChunks - 1) * chunkAngleEach
                        chunkSweeps.add(sweep.coerceAtLeast(1f))
                    }
                    var startAngle = -90f + splitAngle
                    for (i in 0 until totalChunks) {
                        drawArc(color = chunkColors.getOrElse(i) { colBg },
                            startAngle = startAngle, sweepAngle = chunkSweeps[i], useCenter = false,
                            topLeft = Offset(strokeWd / 2, strokeWd / 2),
                            size = Size(size.width - strokeWd, size.height - strokeWd),
                            style = Stroke(width = strokeWd))
                        startAngle += chunkSweeps[i]
                    }
                    // 完了オーバーレイ
                    if (doneRatio > 0f) {
                        drawArc(color = colDone, startAngle = -90f, sweepAngle = 360f * doneRatio, useCenter = false,
                            topLeft = Offset(strokeWd / 2, strokeWd / 2),
                            size = Size(size.width - strokeWd, size.height - strokeWd),
                            style = Stroke(width = strokeWd + 4f))
                    }
                }
                // 中央テキスト
                Text(centerText, style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp)
            }

            Spacer(Modifier.height(8.dp))

            // 凡例（分割 ＋ 各チャンク）
            val legendItems = mutableListOf<Pair<String, androidx.compose.ui.graphics.Color>>()
            legendItems.add("分割" to colSplit)
            for (i in 0 until totalChunks) {
                legendItems.add("Ch${i + 1}" to chunkColors.getOrElse(i) { colBg })
            }
            // 最大2行になるよう折り返し
            val row1 = legendItems.take(4)
            val row2 = legendItems.drop(4)
            Text("凡例", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row1.forEach { (label, color) ->
                    Text("■$label", fontSize = 10.sp, color = color)
                }
            }
            if (row2.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row2.forEach { (label, color) ->
                        Text("■$label", fontSize = 10.sp, color = color)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))

            // 予測表示
            Text(
                "チャンク $completedChunks/$totalChunks | 予想残り ${formatMs(remainingMs)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "全体予測: ${formatMs(overallEstimateMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))
        }

        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(MaterialTheme.shapes.small),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            "経過時間: ${formatElapsed(elapsedSec)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))

        // フェーズ詳細
        if (detailStatus.isNotEmpty()) {
            Text(
                detailStatus,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }

        if (activeProvider.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "使用中: $activeProvider / $activeModel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }

        Spacer(Modifier.height(8.dp))
    }
}

/** ms → "X分Y秒" */
private fun formatMs(ms: Long): String {
    val totalSec = (ms / 1000).toInt()
    val min = totalSec / 60
    val sec = totalSec % 60
    return if (min > 0) "${min}分${sec}秒" else "${sec}秒"
}

/** 秒数を「X分Y秒」形式にフォーマット */
private fun formatElapsed(totalSec: Int): String {
    val min = totalSec / 60
    val sec = totalSec % 60
    return if (min > 0) "${min}分${sec}秒" else "${sec}秒"
}

// ─── 文字起こし結果表示 ─────────────────────────────────

@Composable
private fun TranscribedContent(
    transcript: String,
    onTranscriptChange: (String) -> Unit,
    transcribeDurationMs: Long,
    isPlaying: Boolean,
    playbackPositionMs: Long,
    playbackDurationMs: Long,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onSeek: (Int) -> Unit,
    onSummarize: () -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // タイトル + 処理時間
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "文字起こし結果",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (transcribeDurationMs > 0L) {
                Text(
                    "処理時間: ${formatProcessingDuration(transcribeDurationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Text(
            "内容を確認・編集してから操作を選んでください。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // 文字起こしテキスト編集エリア
        OutlinedTextField(
            value = transcript,
            onValueChange = onTranscriptChange,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium,
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedBorderColor = MaterialTheme.colorScheme.primary
            )
        )

        Spacer(modifier = Modifier.height(4.dp))

        // 再生/停止行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onPlayPause,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
            ) {
                Text(if (isPlaying) "⏸ 一時停止" else "▶ 再生", fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = onStop,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
            ) {
                Text("⏹ 停止", fontSize = 13.sp)
            }
        }

        // 再生位置スライダー
        if (playbackDurationMs > 0L) {
            val posSec = (playbackPositionMs / 1000).toInt()
            val durSec = (playbackDurationMs / 1000).toInt()
            val posMin = posSec / 60; val posSec2 = posSec % 60
            val durMin = durSec / 60; val durSec2 = durSec % 60
            Slider(
                value = if (playbackDurationMs > 0L) playbackPositionMs.toFloat() / playbackDurationMs else 0f,
                onValueChange = { onSeek((it * playbackDurationMs).toInt()) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp)
            )
            Text(
                "%02d:%02d / %02d:%02d".format(posMin, posSec2, durMin, durSec2),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ボタン 1 行目: リトライ / 戻る
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onRetry,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
            ) {
                Text("リトライ", fontSize = 14.sp)
            }
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
            ) {
                Text("戻る", fontSize = 14.sp)
            }
        }

        // ボタン 2 行目: 保存 / 要約
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onSave,
                enabled = transcript.isNotBlank(),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
            ) {
                Text("保存 (TXT)", fontSize = 14.sp)
            }
            Button(
                onClick = onSummarize,
                enabled = transcript.isNotBlank(),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
            ) {
                Text("要約", fontSize = 14.sp)
            }
        }
    }
}

// ─── 要約中 ────────────────────────────────────────────

@Composable
private fun SummarizingContent(
    summaryText: String,
    activeModel: String = "",
    activeProvider: String = ""
) {
    var elapsedSec by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            elapsedSec++
        }
    }
    // 予想時間: 原文1000文字あたり約15秒
    val estimateSec = ((summaryText.length.coerceAtLeast(100)) / 1000 * 15).coerceIn(10, 120)

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (summaryText.isEmpty()) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "要約を生成中...",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "経過 ${formatElapsed(elapsedSec)} / 予想 ${formatElapsed(estimateSec)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            if (activeProvider.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "使用中: $activeProvider / $activeModel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        } else {
            Text(
                "要約結果",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "${summaryText.length} 文字",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = summaryText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            )
            CircularProgressIndicator(
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp
            )
        }
    }
}

// ─── 完了 ──────────────────────────────────────────────

@Composable
private fun CompletedContent(
    summaryText: String,
    rawTranscript: String,
    totalElapsedMs: Long,
    onShareEmail: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // タイトル + 処理時間
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "✓ 文字起こし完了",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )
            if (totalElapsedMs > 0L) {
                Text(
                    "処理時間: ${formatProcessingDuration(totalElapsedMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 要約結果 + 文字数
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                "要約結果",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "${summaryText.length} 文字",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = summaryText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        )

        Spacer(Modifier.height(8.dp))

        // メニュー: メールで共有 / 戻る
        var menuExpanded by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Button(
                onClick = { menuExpanded = true },
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("メニュー ▼", fontSize = 14.sp)
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("メールで共有") },
                    onClick = { menuExpanded = false; onShareEmail() },
                    enabled = summaryText.isNotBlank()
                )
                DropdownMenuItem(
                    text = { Text("戻る") },
                    onClick = { menuExpanded = false; onBack() }
                )
            }
        }
    }
}

/** ms → "X分Y秒" / "Y秒" 形式 */
private fun formatProcessingDuration(ms: Long): String {
    val totalSec = (ms / 1000).toInt()
    val min = totalSec / 60
    val sec = totalSec % 60
    return if (min > 0) "${min}分${sec}秒" else "${sec}秒"
}

// ─── エラー ────────────────────────────────────────────

@Composable
private fun ErrorContent(
    error: String?,
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "文字起こしに失敗しました",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            error ?: "(エラー詳細なし)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRetry) { Text("再試行") }
            OutlinedButton(onClick = onBack) { Text("戻る") }
        }
    }
}
