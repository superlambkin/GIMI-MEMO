package com.gijimemo.ui.settings

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gijimemo.data.model.LlmProviderConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeyManagementScreen(
    onBack: () -> Unit,
    viewModel: ApiKeyManagementViewModel = hiltViewModel()
) {
    val testMap by viewModel.testState.collectAsStateWithLifecycle()
    val draftMap by viewModel.draft.collectAsStateWithLifecycle()
    val saveResult by viewModel.saveResult.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // 保存結果 SnackBar 通知
    LaunchedEffect(saveResult) {
        when (val r = saveResult) {
            is ApiKeyManagementViewModel.SaveResult.Success ->
                snackbarHostState.showSnackbar("${r.savedCount} 件の API Key を保存しました")
            is ApiKeyManagementViewModel.SaveResult.Failure ->
                snackbarHostState.showSnackbar("保存失敗: ${r.message}")
            null -> Unit
        }
        if (saveResult != null) viewModel.dismissSaveResult()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("API Key 一括管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.saveAll() }) {
                        Icon(Icons.Filled.Save, contentDescription = "一括保存")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { data -> Snackbar(snackbarData = data) } }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "各プロバイダの API Key を入力してください。\n" +
                "空白 = 未設定 (このプロバイダは自動選択から除外されます)。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))

            viewModel.providers.forEach { provider ->
                val currentValue = draftMap[provider.apiKeyRef].orEmpty()
                ApiKeyCard(
                    provider = provider,
                    currentValue = currentValue,
                    testState = testMap[provider.apiKeyRef] ?: ApiKeyManagementViewModel.ApiTestState.Idle,
                    onValueChange = { viewModel.onKeyChange(provider.apiKeyRef, it) },
                    onTest = { viewModel.testOne(provider.apiKeyRef) }
                )
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { viewModel.saveAll() },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
            ) {
                Icon(Icons.Filled.Save, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("一括保存", fontSize = 16.sp)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ApiKeyCard(
    provider: LlmProviderConfig,
    currentValue: String,
    testState: ApiKeyManagementViewModel.ApiTestState,
    onValueChange: (String) -> Unit,
    onTest: () -> Unit
) {
    // 単一情報源: ViewModel の draft 値。local の `var text` を持たないことで
    // 編集途中の IME reset / 親の再コンポーズで値が消える問題を回避。
    val configured = currentValue.isNotBlank() || provider.name == "Ollama"

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ── ヘッダー (名前 + 設定済 ✓) ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.VpnKey,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = provider.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (configured) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "設定済",
                            tint = Color(0xFF22A06B),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "設定済",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF22A06B)
                        )
                    }
                }
            }
            if (provider.baseUrl.isNotBlank()) {
                Text(
                    text = provider.baseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ── Key 入力 ──
            OutlinedTextField(
                value = currentValue,
                onValueChange = onValueChange,
                label = { Text("API Key") },
                placeholder = { Text(if (provider.name == "Ollama") "(通常不要)" else "sk-...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // ── テスト結果 inline ──
            when (testState) {
                ApiKeyManagementViewModel.ApiTestState.Idle -> Unit
                ApiKeyManagementViewModel.ApiTestState.Running -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "テスト中...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                is ApiKeyManagementViewModel.ApiTestState.Success -> {
                    Text(
                        "✓ ${testState.response}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF22A06B)
                    )
                }
                is ApiKeyManagementViewModel.ApiTestState.Error -> {
                    Text(
                        "✕ ${testState.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // ── テストボタン ──
            OutlinedButton(
                onClick = onTest,
                enabled = testState !is ApiKeyManagementViewModel.ApiTestState.Running,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
            ) {
                Icon(Icons.Filled.Science, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("接続テスト")
            }
        }
    }
}
