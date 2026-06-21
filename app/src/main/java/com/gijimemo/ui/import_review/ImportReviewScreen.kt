package com.gijimemo.ui.import_review

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
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gijimemo.ui.recording.PlaybackState
import com.gijimemo.ui.home.ImportedAudioMeta

/**
 * MP3 インポート直後の確認画面。
 * 録音停止直後の StoppedPlaybackAndTranscribe と機能・見た目を揃える。
 *  - 再生 / 一時停止 / 停止
 *  - 「日本語」「中国語」ボタン → Processing 画面へ言語指定で遷移
 *  - 「キャンセル」 → Session 削除 + ホームへ戻る
 */
@Composable
fun ImportReviewScreen(
    onTranscribe: (sessionId: String, lang: String) -> Unit,
    onCancel: () -> Unit,
    viewModel: ImportReviewViewModel = hiltViewModel()
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val pbState by viewModel.playbackState.collectAsStateWithLifecycle()
    // v0.7.2: Singleton ImportedMetaStore を直接参照し、HomeScreen と同じインスタンスで
    // メタ情報 (SR/BR/ファイル名等) を共有。NavBackStackEntry ごとに HomeViewModel が
    // 別インスタンスになる問題を回避する。
    val metaStore = androidx.hilt.navigation.compose.hiltViewModel<ImportedMetaStoreViewModel>()
    val importedMeta by metaStore.meta.collectAsStateWithLifecycle()

    // v0.7.3: 画面離脱時に再生中の音声を停止
    DisposableEffect(Unit) { onDispose { viewModel.stopPlayback() } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ─── 上部: タイトル + メタ情報 ────────────
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Icon(
                    Icons.Filled.LibraryMusic,
                    contentDescription = null,
                    modifier = Modifier.height(48.dp).width(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "インポート完了",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = session?.title ?: "...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (session != null && session!!.durationMs > 0L) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = formatDurationLabel(session!!.durationMs),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Light,
                        color = MaterialTheme.colorScheme.onSurface,
                        letterSpacing = 2.sp
                    )
                }
                // v0.7.2: インポートファイルのメタ情報 (SR/BR/ファイル名/場所/生成日時)
                importedMeta?.let { meta ->
                    Spacer(Modifier.height(12.dp))
                    ImportedAudioMetaCard(meta)
                }
            }

            // ─── 中央: 説明 ────────────────────────
            Text(
                "音声を再生して内容を確認し、\n言語を選んで文字起こしを開始してください。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            // ─── 下部: 再生 + 文字起こし + キャンセル ──
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 再生制御
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
                            Text("音声を再生")
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
                val playPos by viewModel.playbackPosition.collectAsStateWithLifecycle()
                val playDur by viewModel.playbackDuration.collectAsStateWithLifecycle()
                if (playDur > 0L) {
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

                // 言語別 文字起こしボタン (2 列)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            viewModel.stopPlayback()
                            metaStore.clear()
                            onTranscribe(viewModel.sessionId, "ja")
                        },
                        enabled = session != null,
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
                        onClick = {
                            viewModel.stopPlayback()
                            metaStore.clear()
                            onTranscribe(viewModel.sessionId, "zh")
                        },
                        enabled = session != null,
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

                // キャンセル: Session 削除 + ホームへ戻る
                OutlinedButton(
                    onClick = {
                        metaStore.clear()
                        viewModel.cancelImport(onCancel)
                    },
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

            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun formatDurationLabel(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%02d:%02d".format(min, sec)
}

/** v0.7.2: インポートファイルのメタ情報表示カード (SR/BR/ファイル名/場所/生成日時) */
@Composable
private fun ImportedAudioMetaCard(meta: ImportedAudioMeta) {
    androidx.compose.material3.Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // サンプリングレート / ビットレート
            val srText = if (meta.sampleRate > 0) "${meta.sampleRate / 1000}kHz" else "—"
            val brText = if (meta.bitRate > 0) "${meta.bitRate / 1000}kbps" else "—"
            MetaRow(label = "形式", value = "$srText · $brText")
            // ファイル名 (元ファイル名)
            MetaRow(label = "ファイル名", value = meta.fileName)
            // ファイル場所 (内部保存先)
            MetaRow(label = "保存先", value = meta.fileLocation)
            // ファイル生成日時 (元ファイルの最終更新日時、なければインポート時刻)
            val ts = if (meta.originalLastModifiedMs > 0L) meta.originalLastModifiedMs else meta.importedAtMs
            val dateText = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.JAPAN).format(java.util.Date(ts))
            val tag = if (meta.originalLastModifiedMs > 0L) "元ファイル更新" else "インポート"
            MetaRow(label = "生成日時", value = "$dateText ($tag)")
            // ファイルサイズ
            val sizeText = when {
                meta.fileSizeBytes < 1024 -> "${meta.fileSizeBytes} B"
                meta.fileSizeBytes < 1024 * 1024 -> "${meta.fileSizeBytes / 1024} KB"
                else -> "%.1f MB".format(meta.fileSizeBytes / 1024.0 / 1024.0)
            }
            MetaRow(label = "サイズ", value = sizeText)
        }
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
