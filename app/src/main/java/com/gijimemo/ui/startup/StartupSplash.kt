package com.gijimemo.ui.startup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Splash / first-launch extraction screen. Shown above the NavHost until
 * [StartupState.Ready] is reached. Renders progress for the 141 MB bundled
 * Whisper model and offers a retry on failure.
 */
@Composable
fun StartupSplash(
    state: StartupState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = "GijiMemo",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 36.sp,
                style = MaterialTheme.typography.headlineLarge
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "議事録アシスタント",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(48.dp))

            when (state) {
                is StartupState.Checking -> {
                    // Brief indeterminate state before extraction check finishes.
                    Text(
                        text = "起動中...",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 14.sp
                    )
                }
                is StartupState.Extracting -> {
                    Text(
                        text = "ローカル音声認識モデル (${(state.progress * 100).toInt()}%)",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.width(240.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "初回のみ 1〜3 秒かかります",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
                is StartupState.Failed -> {
                    Text(
                        text = "モデル準備に失敗しました",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onRetry) {
                        Text("再試行")
                    }
                }
                is StartupState.Ready -> {
                    // NavHost will replace this screen — render nothing visible.
                    Spacer(Modifier.size(0.dp))
                }
            }
        }
    }
}
