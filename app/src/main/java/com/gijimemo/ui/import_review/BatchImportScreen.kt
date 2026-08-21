// app/src/main/java/com/gijimemo/ui/import_review/BatchImportScreen.kt
package com.gijimemo.ui.import_review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * v0.9.0: 複数 MP3 の一括文字起こし進捗画面。
 * 時系列順に 1 ファイルずつ転写し、完了時に結合 Session へ遷移する。
 */
@Composable
fun BatchImportScreen(
    onComplete: (String) -> Unit,
    onCancel: () -> Unit,
    viewModel: BatchImportViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 転写開始
    LaunchedEffect(Unit) {
        viewModel.start()
    }

    // v0.9.1: 完了遷移は ViewModel のコルーチン内から直接行うと Navigation の
    // ライフサイクル例外（INITIALIZED→DESTROYED）が発生するため、
    // 画面側の LaunchedEffect（コンポジション後）から行う。
    LaunchedEffect(state.phase, state.combinedSessionId) {
        val id = state.combinedSessionId
        if (state.phase == BatchImportPhase.DONE && id != null) {
            onComplete(id)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "一括文字起こし",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            if (state.phase == BatchImportPhase.TRANSCRIBING) {
                "ファイル ${(state.currentIndex + 1).coerceAtMost(state.files.size)}/${state.files.size} を文字起こし中..."
            } else {
                "準備中..."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        LinearProgressIndicator(
            progress = { if (state.files.isEmpty()) 0f else (state.currentIndex + 1).toFloat() / state.files.size },
            modifier = Modifier.fillMaxWidth()
        )

        // 現在の詳細（分割中 / Chunk n/m 等）
        if (state.currentDetail.isNotBlank()) {
            Text(
                state.currentDetail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(state.files) { _, f ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        f.fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (f.status == BatchFileStatus.TRANSCRIBING) FontWeight.Bold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        when (f.status) {
                            BatchFileStatus.PENDING -> "待機"
                            BatchFileStatus.TRANSCRIBING -> "転写中..."
                            BatchFileStatus.DONE -> "完了 ✓"
                            BatchFileStatus.FAILED -> "失敗 ✗"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = when (f.status) {
                            BatchFileStatus.FAILED -> MaterialTheme.colorScheme.error
                            BatchFileStatus.DONE -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }

        if (state.phase == BatchImportPhase.ERROR) {
            Text(
                state.error ?: "エラーが発生しました",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }

        TextButton(onClick = onCancel) {
            Text("キャンセル")
        }
    }
}
