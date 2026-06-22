package com.gijimemo.ui.recording

import android.Manifest
import android.util.Log
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.gijimemo.audio.RecordingState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
fun RecordingScreen(
    onTranscribe: (sessionId: String, lang: String) -> Unit,
    onCancel: () -> Unit,
    viewModel: RecordingViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()

    // v0.7.3: 画面離脱時に再生中の音声を停止
    DisposableEffect(Unit) { onDispose { viewModel.stopPlayback() } }

    // 画面サイズに応じた波形サイズ（横幅の約55%）
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val visualizerSize = (screenWidthDp * 0.7f).coerceIn(240f, 500f).dp

    // 振幅バッファ（リングバッファ方式で最新 N フレームを保持）
    val amplitudeBuffer = remember { AmplitudeBuffer(capacity = 64) }
    LaunchedEffect(Unit) {
        viewModel.amplitude.collect { amp ->
            val normalized = (min(amp, 32767).toFloat() / 32767f).coerceIn(0f, 1f)
            amplitudeBuffer.push(normalized)
        }
    }

    var elapsedMs by remember { mutableLongStateOf(0L) }
    val lastSavedSession by viewModel.lastSavedSession.collectAsState()
    val lastSavedSessionId: String? = lastSavedSession?.id
    val recordingStartMs by viewModel.recordingStartMs.collectAsState()
    val recordingConfig by viewModel.recordingConfig.collectAsState()
    val partialTranscript by viewModel.partialTranscript.collectAsState()
    var showCancelDialog by remember { mutableStateOf(false) }

    // ページ初期化: 経過時間表示のみリセット。
    // `lastSavedSession` のリセットは、新規録音 (Recording 状態突入時) のみで行う。
    // ここで毎回リセットすると、stopRecording() 直後の再コンポーズで
    // _lastSavedSession が null に戻り「文字起こし」ボタンが出なくなる。
    LaunchedEffect(Unit) {
        elapsedMs = 0L
    }

    LaunchedEffect(state) {
        if (state == RecordingState.Recording) {
            viewModel.resetLastSavedSession()
        }
    }

    // Timer: ViewModel の recordingStartMs をキーにした while(true) loop。
    // - 録音中: currentTimeMillis - startTime を 200ms ごとに更新
    // - 停止後 (lastSavedSession あり): 保存された durationMs を表示し続ける
    //   (00:00 にリセットしない — ユーザーは「何秒録ったか」を確認したい)
    // - 完全 Idle (lastSavedSession なし): 00:00
    LaunchedEffect(Unit) {
        while (true) {
            val start = recordingStartMs
            elapsedMs = when {
                start != null && state == RecordingState.Recording ->
                    System.currentTimeMillis() - start
                lastSavedSession != null ->
                    lastSavedSession?.durationMs ?: 0L
                else -> 0L
            }
            delay(200)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        if (!hasPermission) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "マイクの許可が必要です",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    modifier = Modifier.heightIn(min = 52.dp)
                ) { Text("許可する") }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // ─── 上部：ステータス + タイマー ────────────
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Text(
                        text = stateLabel(state),
                        style = MaterialTheme.typography.titleMedium,
                        color = stateColor(state),
                        fontWeight = FontWeight.SemiBold
                    )
                    // 録音中と録音開始画面に現在設定のサンプリングレート/ビットレートを表示
                    val cfg = recordingConfig
                    if (cfg != null && (state == RecordingState.Recording || state == RecordingState.Idle)) {
                        Spacer(Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "${cfg.sampleRate / 1000}kHz",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "·",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${cfg.bitRate / 1000}kbps",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = formatDuration(elapsedMs),
                        fontSize = 42.sp,
                        fontWeight = FontWeight.Light,
                        color = MaterialTheme.colorScheme.onSurface,
                        letterSpacing = 2.sp
                    )
                }

                // ─── 中央：波形可視化（録音中/一時停止中のみ表示） ────
                if (state == RecordingState.Recording || state == RecordingState.Paused) {
                    AmplitudeVisualizer(
                        buffer = amplitudeBuffer,
                        isActive = state == RecordingState.Recording,
                        isPaused = state == RecordingState.Paused,
                        modifier = Modifier.size(visualizerSize)
                    )
                }

                // ─── 部分転写（ストリーミング文字起こし） ───
                PartialTranscriptView(text = partialTranscript)
                // ─── 下部：操作ボタン ────────────────────

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (state) {
                        RecordingState.Idle -> {
                            // Idle 状態:
                            //  - lastSavedSessionId == null → 新規録音可能
                            //  - lastSavedSessionId != null → さっき録ったものが
                            //    「停止済み」として残っているので再生 + 文字起こし
                            if (lastSavedSessionId == null) {
                                Button(
                                    onClick = {
                                        elapsedMs = 0L
                                        viewModel.startRecording()
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 56.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    )
                                ) {
                                    Icon(Icons.Filled.Mic, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("録音開始", fontSize = 18.sp)
                                }
                            } else {
                                // 直前録音の停止済み UI を表示（Stop 済み + 録音が完成）
                                StoppedPlaybackAndTranscribe(
                                    lastSavedSessionId = lastSavedSessionId,
                                    onTranscribe = onTranscribe,
                                    onBack = onCancel,
                                    onDiscard = {
                                        viewModel.discardRecording()
                                    },
                                    viewModel = viewModel
                                )
                            }
                        }

                        RecordingState.Recording -> {
                            // 一時停止（大ボタン）
                            Button(
                                onClick = { viewModel.pauseRecording() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 56.dp)
                            ) {
                                Icon(Icons.Filled.Pause, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("一時停止", fontSize = 18.sp)
                            }
                        }

                        RecordingState.Paused -> {
                            // 再開
                            Button(
                                onClick = { viewModel.resumeRecording() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 56.dp)
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("再開", fontSize = 18.sp)
                            }
                        }

                        RecordingState.Stopped -> {
                            // No-op: stop() 実装で状態は Idle に戻すように
                            // なったため、ここには来ない（念のため placeholder）。
                        }

                        is RecordingState.Error -> {
                            Text(
                                "録音エラー、再試行してください",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }

                    // ─── 録音中の停止ボタン ──────────────
                    if (state == RecordingState.Recording || state == RecordingState.Paused) {
                        FilledTonalButton(
                            onClick = {
                                scope.launch {
                                    // stopRecording 内で lastSavedSession が
                                    // 自動的に更新される（成功時）。
                                    viewModel.stopRecording(
                                        title = "会議 ${System.currentTimeMillis()}",
                                        durationMs = elapsedMs
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Icon(Icons.Filled.Stop, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("停止して保存", fontSize = 18.sp)
                        }
                    }

                    // ─── キャンセル（常に最下部、単独行） ──
                    if (state != RecordingState.Idle) {
                        OutlinedButton(
                            onClick = { showCancelDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Icon(Icons.Filled.Cancel, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("キャンセル", fontSize = 15.sp)
                        }
                    }
                }

                // 下部余白（ナビゲーションジェスチャー対策）
                Spacer(Modifier.height(8.dp))
            }
        }

        // ─── キャンセル確認ダイアログ ──
        if (showCancelDialog) {
            AlertDialog(
                onDismissRequest = { showCancelDialog = false },
                title = { Text("録音を終了しますか？") },
                text = {
                    val durationLabel = formatDuration(elapsedMs)
                    Text("現在の録音（$durationLabel）を保存、または破棄して終了できます。")
                },
                confirmButton = {
                    // 保存して終了（メインアクション）
                    TextButton(onClick = {
                        showCancelDialog = false
                        scope.launch {
                            viewModel.stopRecording(
                                title = "会議 ${System.currentTimeMillis()}",
                                durationMs = elapsedMs
                            )
                            onCancel()
                        }
                    }) {
                        Text("保存して終了", color = MaterialTheme.colorScheme.primary)
                    }
                },
                dismissButton = {
                    Row {
                        // 破棄して終了
                        TextButton(onClick = {
                            showCancelDialog = false
                            viewModel.discardRecording()
                            onCancel()
                        }) {
                            Text(
                                "破棄",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        // 続ける
                        TextButton(onClick = { showCancelDialog = false }) {
                            Text("続ける")
                        }
                    }
                }
            )
        }
    }
}

// ─── ヘルパー関数 ─────────────────────────────────────────────

/**
 * 直前録音を停止した直後に表示する UI（再生コントロール + 文字起こし）。
 * 状態は Idle だが lastSavedSessionId がある＝録音が完了しているので、
 * 新規録音ボタンではなくこちらを出す。
 */
@Composable
private fun StoppedPlaybackAndTranscribe(
    lastSavedSessionId: String,
    onTranscribe: (String, String) -> Unit,
    onBack: () -> Unit,
    onDiscard: () -> Unit,
    viewModel: RecordingViewModel
) {
    val pbState by viewModel.playbackState.collectAsState()
    val useOnDeviceAsr by viewModel.useOnDeviceAsr.collectAsState()
    when (pbState) {
        PlaybackState.Idle -> {
            FilledTonalButton(
                onClick = { viewModel.startPlayback() },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("録音を再生")
            }
        }
        PlaybackState.Playing -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilledTonalButton(
                    onClick = { viewModel.pausePlayback() },
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                ) {
                    Icon(Icons.Filled.Pause, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("一時停止")
                }
                FilledTonalButton(
                    onClick = { viewModel.stopPlayback() },
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("停止")
                }
            }
        }
        PlaybackState.Paused -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilledTonalButton(
                    onClick = { viewModel.resumePlayback() },
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("再開")
                }
                FilledTonalButton(
                    onClick = { viewModel.stopPlayback() },
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("停止")
                }
            }
        }
    }
    // シークバー
    val playPos by viewModel.playbackPosition.collectAsState()
    val playDur by viewModel.playbackDuration.collectAsState()
    if (playDur > 0L && (pbState == PlaybackState.Playing || pbState == PlaybackState.Paused)) {
        Slider(
            value = if (playDur > 0L) playPos.toFloat() / playDur else 0f,
            onValueChange = { viewModel.seekAudio((it * playDur).toInt()) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp)
        )
        val posS = playPos / 1000; val durS = playDur / 1000
        Text(
            "%02d:%02d / %02d:%02d".format(posS / 60, posS % 60, durS / 60, durS % 60),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(Modifier.height(4.dp))
    // ─── ローカルWhisper切替 (設定画面と同期) ──────────────
    LocalWhisperToggle(
        useOnDevice = useOnDeviceAsr,
        onChange = { viewModel.setUseOnDeviceAsr(it) }
    )
    Spacer(Modifier.height(4.dp))
    // ─── 言語別 文字起こしボタン (2 列) ────────────────────
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = { onTranscribe(lastSavedSessionId, "ja") },
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(Icons.Filled.VolumeUp, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("日本語", fontSize = 16.sp)
        }
        Button(
            onClick = { onTranscribe(lastSavedSessionId, "zh") },
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(Icons.Filled.VolumeUp, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("中国語", fontSize = 16.sp)
        }
    }
    Spacer(Modifier.height(4.dp))
    // ─── 保存ボタン ────────────────────────────────────────
    OutlinedButton(
        onClick = { viewModel.saveAudioToDownloads() },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
    ) {
        Icon(Icons.Filled.VolumeUp, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("保存（音声ファイル）", fontSize = 15.sp)
    }
    Spacer(Modifier.height(4.dp))
    // ─── 戻る + キャンセル (横並び) ─────────────────────────
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            Text("戻る", fontSize = 15.sp)
        }
        OutlinedButton(
            onClick = onDiscard,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) {
            Icon(Icons.Filled.Cancel, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("キャンセル", fontSize = 15.sp)
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%02d:%02d".format(min, sec)
}

/**
 * ローカルWhisper ON/OFF ラジオグループ。
 * 設定画面 > 呼び出しモード > オンデバイスWhisper と StateFlow 経由で同期。
 * 録音画面 (RecordingScreen) と録音確認画面 (ImportReviewScreen,
 * StoppedPlaybackAndTranscribe) の両方から呼ばれる。
 */
@Composable
internal fun LocalWhisperToggle(
    useOnDevice: Boolean,
    onChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "ローカルWhisper",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = !useOnDevice,
                        onClick = { onChange(false) }
                    )
                    Text("OFF", style = MaterialTheme.typography.bodyMedium)
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = useOnDevice,
                        onClick = { onChange(true) }
                    )
                    Text("ON", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private fun stateLabel(state: RecordingState): String = when (state) {
    RecordingState.Idle -> "未開始"
    RecordingState.Recording -> "録音中"
    RecordingState.Paused -> "一時停止中"
    RecordingState.Stopped -> "停止済み"
    is RecordingState.Error -> "エラー"
}

@Composable
private fun stateColor(state: RecordingState): Color = when (state) {
    RecordingState.Recording -> MaterialTheme.colorScheme.error
    RecordingState.Paused -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurface
}

// ─── 振幅バッファ ────────────────────────────────────────────

class AmplitudeBuffer(private val capacity: Int) {
    private val data = FloatArray(capacity)
    private var head = 0
    private var size = 0

    fun push(value: Float) {
        data[head] = value
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    fun sample(index: Int): Float {
        if (index >= size) return 0f
        val pos = (head - 1 - index + capacity) % capacity
        return data[pos]
    }

    fun count(): Int = size
}

// ─── 波形可視化 ──────────────────────────────────────────────

@Composable
private fun AmplitudeVisualizer(
    buffer: AmplitudeBuffer,
    isActive: Boolean,
    isPaused: Boolean,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val outline = MaterialTheme.colorScheme.outline

    val beamColor = when {
        isActive -> primary
        isPaused -> onSurfaceVariant.copy(alpha = 0.5f)
        else -> outline.copy(alpha = 0.3f)
    }
    val ringColor = when {
        isActive -> primary.copy(alpha = 0.35f)
        isPaused -> onSurfaceVariant.copy(alpha = 0.25f)
        else -> outline.copy(alpha = 0.15f)
    }

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val maxRadius = min(size.width, size.height) / 2f
        val baseRadius = maxRadius * 0.35f
        val beamCount = 72

        // 外周リング（太め）
        drawCircle(
            color = ringColor,
            radius = baseRadius,
            center = Offset(cx, cy),
            style = Stroke(width = 2f.dp.toPx())
        )

        // 振幅ビーム（感度向上）
        for (i in 0 until beamCount) {
            val raw = buffer.sample(i).coerceIn(0f, 1f)
            val amp = raw * raw * 1.3f // 感度1.3倍
            val length = baseRadius * 0.2f + amp * (maxRadius - baseRadius) * 0.95f
            val angle = (i.toDouble() / beamCount) * 2.0 * Math.PI
            val startX = cx + (baseRadius * cos(angle)).toFloat()
            val startY = cy + (baseRadius * sin(angle)).toFloat()
            val endX = cx + ((baseRadius + length) * cos(angle)).toFloat()
            val endY = cy + ((baseRadius + length) * sin(angle)).toFloat()

            val alpha = (1f - i.toFloat() / beamCount).coerceIn(0f, 1f) * 0.9f
            drawLine(
                brush = Brush.linearGradient(
                    colors = listOf(
                        beamColor.copy(alpha = alpha * 0.3f),
                        beamColor.copy(alpha = alpha)
                    ),
                    start = Offset(startX, startY),
                    end = Offset(endX, endY)
                ),
                start = Offset(startX, startY),
                end = Offset(endX, endY),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        // 内側コア
        val coreColor = if (isActive) primary else onSurfaceVariant.copy(alpha = 0.5f)
        val coreRadius = baseRadius * 0.18f
        if (isActive) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primary.copy(alpha = 0.35f),
                        Color.Transparent
                    ),
                    center = Offset(cx, cy),
                    radius = coreRadius * 4f
                ),
                radius = coreRadius * 4f,
                center = Offset(cx, cy)
            )
        }
        drawCircle(
            color = coreColor,
            radius = coreRadius,
            center = Offset(cx, cy)
        )
    }
}

/**
 * v0.7.x: ライブ文字起こし（仮）表示欄。ストリーミング推論の中間結果を
 * 新しい行が下に来るよう逆順スクロールで表示する。空文字なら非表示。
 */
@Composable
private fun PartialTranscriptView(
    text: String,
    modifier: Modifier = Modifier,
) {
    if (text.isBlank()) return
    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 200.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "ライブ文字起こし（仮）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            // reverseLayout = true で末尾が画面下に来る（追記型テキスト向け）
            LazyColumn(reverseLayout = true) {
                item {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
