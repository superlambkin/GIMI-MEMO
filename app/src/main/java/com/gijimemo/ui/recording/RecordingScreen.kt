package com.gijimemo.ui.recording

import android.Manifest
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
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
    onTranscribe: (sessionId: String) -> Unit,
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

    // 振幅バッファ（リングバッファ方式で最新 N フレームを保持）
    val amplitudeBuffer = remember { AmplitudeBuffer(capacity = 64) }
    LaunchedEffect(Unit) {
        viewModel.amplitude.collect { amp ->
            // MediaRecorder maxAmplitude は 0..32767。dB 風に正規化して 0..1 に。
            val normalized = (min(amp, 32767).toFloat() / 32767f).coerceIn(0f, 1f)
            amplitudeBuffer.push(normalized)
        }
    }

    // Recording duration timer
    var elapsedMs by remember { mutableLongStateOf(0L) }

    // 直近に stop() で永続化された sessionId。「改文字」ボタンが読む。
    // Stopped 状態のときだけ有効。録音を再開したらクリアする。
    var lastSavedSessionId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state) {
        if (state == RecordingState.Recording) {
            lastSavedSessionId = null
        }
    }

    LaunchedEffect(state) {
        if (state == RecordingState.Recording) {
            val start = System.currentTimeMillis() - elapsedMs
            while (state == RecordingState.Recording) {
                elapsedMs = System.currentTimeMillis() - start
                delay(200)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        if (!hasPermission) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("マイクの許可が必要です")
                Button(onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                    Text("許可")
                }
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Text(text = "ステータス：${state.label()}", fontSize = 20.sp)
                Text(text = formatDuration(elapsedMs), fontSize = 36.sp)

                // 音声可視化ウィンドウ（画面中央、大きめ）
                AmplitudeVisualizer(
                    buffer = amplitudeBuffer,
                    isActive = state == RecordingState.Recording,
                    isPaused = state == RecordingState.Paused,
                    modifier = Modifier.size(280.dp)
                )

                when (state) {
                    RecordingState.Idle, RecordingState.Stopped -> {
                        Button(
                            onClick = {
                                elapsedMs = 0L
                                viewModel.startRecording()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                        ) {
                            Icon(Icons.Filled.Mic, contentDescription = null)
                            Text(" 録音開始", fontSize = 18.sp)
                        }
                    }
                    RecordingState.Recording -> {
                        Button(
                            onClick = { viewModel.pauseRecording() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                        ) {
                            Icon(Icons.Filled.Pause, contentDescription = null)
                            Text(" 一時停止", fontSize = 18.sp)
                        }
                    }
                    RecordingState.Paused -> {
                        Button(
                            onClick = { viewModel.resumeRecording() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Text(" 再開", fontSize = 18.sp)
                        }
                    }
                    is RecordingState.Error -> {
                        Text(
                            "録音エラー、再試行してください",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // 下部操作：停止 / 改文字 / キャンセル（横並び、操作しやすく）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 「停止」：録音を止めるだけ（ページ遷移なし）。Stopped 状態になる。
                    Button(
                        onClick = {
                            scope.launch {
                                val session = viewModel.stopRecording(
                                    title = "会議 ${System.currentTimeMillis()}",
                                    durationMs = elapsedMs
                                )
                                lastSavedSessionId = session?.id
                            }
                        },
                        enabled = state == RecordingState.Recording || state == RecordingState.Paused,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 56.dp)
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                        Text(" 停止", fontSize = 16.sp)
                    }
                    // 「改文字（文字起こし）」：Stopped 状態のときに有効。押下で処理画面へ遷移。
                    Button(
                        onClick = { lastSavedSessionId?.let { onTranscribe(it) } },
                        enabled = state == RecordingState.Stopped && lastSavedSessionId != null,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 56.dp)
                    ) {
                        Text("改文字", fontSize = 16.sp)
                    }
                    // 「キャンセル」：画面を閉じる
                    androidx.compose.material3.OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 56.dp)
                    ) {
                        Text("キャンセル", fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%02d:%02d".format(min, sec)
}

private fun RecordingState.label(): String = when (this) {
    RecordingState.Idle -> "未開始"
    RecordingState.Recording -> "録音中"
    RecordingState.Paused -> "一時停止中"
    RecordingState.Stopped -> "停止済み"
    is RecordingState.Error -> "エラー"
}

// ─── 振幅バッファ ────────────────────────────────────────────

/**
 * リングバッファで最新 N フレームの振幅を保持。UI から順次読み出して描画する。
 */
class AmplitudeBuffer(private val capacity: Int) {
    private val data = FloatArray(capacity)
    private var head = 0
    private var size = 0

    fun push(value: Float) {
        data[head] = value
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    /** i 番目のサンプル（0 が最新） */
    fun sample(index: Int): Float {
        if (index >= size) return 0f
        val pos = (head - 1 - index + capacity) % capacity
        return data[pos]
    }

    fun count(): Int = size
}

// ─── 波形可視化 ──────────────────────────────────────────────

/**
 * 円形パルス波。中心から外向きに N 本の光線が振幅に応じて伸びる。
 * - Recording: 金色グラデーション + ソフトグロー
 * - Paused: ミュートグレー
 * - Idle/Stopped: 静止した控えめなリング
 */
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
        isPaused -> onSurfaceVariant.copy(alpha = 0.45f)
        else -> outline.copy(alpha = 0.25f)
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
        val baseRadius = maxRadius * 0.42f
        val beamCount = 64

        // 外周リング（ベース円）
        drawCircle(
            color = ringColor,
            radius = baseRadius,
            center = Offset(cx, cy),
            style = Stroke(width = 1.5f.dp.toPx())
        )

        // 振幅ビーム：最新が明るく、遠い過去ほど薄い
        for (i in 0 until beamCount) {
            val raw = buffer.sample(i).coerceIn(0f, 1f)
            // 指数強調（小声もそれなりに動く）
            val amp = raw * raw
            val length = baseRadius * 0.15f + amp * (maxRadius - baseRadius) * 0.9f
            val angle = (i.toDouble() / beamCount) * 2.0 * Math.PI
            val startX = cx + (baseRadius * cos(angle)).toFloat()
            val startY = cy + (baseRadius * sin(angle)).toFloat()
            val endX = cx + ((baseRadius + length) * cos(angle)).toFloat()
            val endY = cy + ((baseRadius + length) * sin(angle)).toFloat()

            // 透明度：最新ほど不透明
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

        // 内側コア：Recording 中は金色に光る
        val coreColor = if (isActive) primary else onSurfaceVariant.copy(alpha = 0.5f)
        val coreRadius = baseRadius * 0.18f
        // グロー（外側）
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
        // コア本体
        drawCircle(
            color = coreColor,
            radius = coreRadius,
            center = Offset(cx, cy)
        )
    }
}
