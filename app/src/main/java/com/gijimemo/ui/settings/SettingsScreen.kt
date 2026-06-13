package com.gijimemo.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gijimemo.data.model.LlmCallMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val callMode by viewModel.callMode.collectAsStateWithLifecycle()
    val chunk by viewModel.chunkMinutes.collectAsStateWithLifecycle()
    val recipients by viewModel.recipients.collectAsStateWithLifecycle()
    val selected by viewModel.selectedProviderName.collectAsStateWithLifecycle()
    val prompt by viewModel.promptTemplate.collectAsStateWithLifecycle()
    val currentModel by viewModel.currentModel.collectAsStateWithLifecycle()
    val apiTestState by viewModel.apiTestState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("設定", style = MaterialTheme.typography.headlineMedium)

        // Provider dropdown
        var providerExpanded by remember { mutableStateOf(false) }
        Text("LLM プロバイダー：${selected ?: "未選択"}")
        TextButton(onClick = { providerExpanded = true }) { Text("変更") }
        DropdownMenu(expanded = providerExpanded, onDismissRequest = { providerExpanded = false }) {
            viewModel.providers.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.name) },
                    onClick = { viewModel.selectProvider(p.name); providerExpanded = false }
                )
            }
        }

        // Current Provider API Key input
        val currentProvider = viewModel.providers.firstOrNull { it.name == selected }
        if (currentProvider != null) {
            if (currentProvider.baseUrl.isNotBlank()) {
                Text(
                    text = "Endpoint: ${currentProvider.baseUrl}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            var key by remember { mutableStateOf(viewModel.getApiKey(currentProvider.apiKeyRef) ?: "") }
            OutlinedTextField(
                value = key,
                onValueChange = {
                    key = it
                    viewModel.setApiKey(currentProvider.apiKeyRef, it)
                },
                label = { Text("${currentProvider.name} API Key") },
                modifier = Modifier.fillMaxSize()
            )
            TextButton(onClick = { viewModel.testApi() }) { Text("接続テスト") }

            // Model dropdown (T5.3)
            val models = currentProvider.supportedModels
            if (models.isNotEmpty()) {
                var modelExpanded by remember { mutableStateOf(false) }
                Text("デフォルトモデル：${currentModel.ifBlank { currentProvider.defaultModel }}")
                TextButton(onClick = { modelExpanded = true }) { Text("モデル変更") }
                DropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }) {
                    models.forEach { m ->
                        DropdownMenuItem(
                            text = { Text(m) },
                            onClick = { viewModel.setModel(m); modelExpanded = false }
                        )
                    }
                }
            }
        }

        // Call Mode
        Text("呼び出しモード：${callMode.name}")
        TextButton(onClick = { viewModel.setCallMode(LlmCallMode.MULTIMODAL) }) { Text("マルチモーダル") }
        TextButton(onClick = { viewModel.setCallMode(LlmCallMode.WHISPER_THEN_SUMMARY) }) { Text("Whisper+要約") }

        // Chunk minutes
        Text("分割しきい値：$chunk 分 (0=無制限)")
        Slider(
            value = chunk.toFloat(),
            onValueChange = { viewModel.setChunkMinutes(it.toInt()) },
            valueRange = 0f..60f,
            steps = 60
        )

        // Recipient list (预设收件人)
        Text("受信者プリセット", style = MaterialTheme.typography.titleSmall)
        if (recipients.isEmpty()) {
            Text(
                text = "（なし）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            recipients.forEach { email ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(email, modifier = Modifier.padding(8.dp))
                        IconButton(onClick = { viewModel.removeRecipient(email) }) {
                            Icon(Icons.Filled.Close, contentDescription = "削除")
                        }
                    }
                }
            }
        }
        var newRecipient by remember { mutableStateOf("") }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = newRecipient,
                onValueChange = { newRecipient = it },
                label = { Text("新しい受信者メール") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = {
                    if (newRecipient.isNotBlank()) {
                        viewModel.addRecipient(newRecipient.trim())
                        newRecipient = ""
                    }
                },
                enabled = newRecipient.isNotBlank()
            ) { Text("追加") }
        }

        // Prompt template
        OutlinedTextField(
            value = prompt,
            onValueChange = viewModel::setPromptTemplate,
            label = { Text("プロンプトテンプレート") },
            modifier = Modifier.fillMaxSize()
        )

        // すべての設定は DataStore に即時保存されるため、
        // 「保存」は画面を閉じる動作と同じ。「キャンセル」も同じ（破棄対象なし）。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onBack) { Text("キャンセル") }
            Button(onClick = onBack) { Text("保存") }
        }
    }

    // 接続テストの結果ダイアログ
    when (val s = apiTestState) {
        SettingsViewModel.ApiTestState.Idle, SettingsViewModel.ApiTestState.Running -> Unit
        is SettingsViewModel.ApiTestState.Success -> {
            AlertDialog(
                onDismissRequest = { viewModel.dismissApiTest() },
                title = { Text("接続成功") },
                text = { Text("応答: ${s.response}") },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissApiTest() }) { Text("OK") }
                }
            )
        }
        is SettingsViewModel.ApiTestState.Error -> {
            AlertDialog(
                onDismissRequest = { viewModel.dismissApiTest() },
                title = { Text("接続失敗") },
                text = { Text(s.message) },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissApiTest() }) { Text("OK") }
                }
            )
        }
    }
}
