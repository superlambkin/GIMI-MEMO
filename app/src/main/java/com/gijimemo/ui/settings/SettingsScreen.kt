package com.gijimemo.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
    val recipient by viewModel.recipient.collectAsStateWithLifecycle()
    val selected by viewModel.selectedProviderName.collectAsStateWithLifecycle()
    val prompt by viewModel.promptTemplate.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("设置", style = MaterialTheme.typography.headlineMedium)

        // Provider dropdown
        var expanded by remember { mutableStateOf(false) }
        Text("LLM 服务商：${selected ?: "未选"}")
        TextButton(onClick = { expanded = true }) { Text("更改") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            viewModel.providers.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.name) },
                    onClick = { viewModel.selectProvider(p.name); expanded = false }
                )
            }
        }

        // Current Provider API Key input
        val currentProvider = viewModel.providers.firstOrNull { it.name == selected }
        if (currentProvider != null) {
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
        }

        // Call Mode
        Text("调用模式：${callMode.name}")
        TextButton(onClick = { viewModel.setCallMode(LlmCallMode.MULTIMODAL) }) { Text("多模态") }
        TextButton(onClick = { viewModel.setCallMode(LlmCallMode.WHISPER_THEN_SUMMARY) }) { Text("Whisper+总结") }

        // Chunk minutes
        Text("切片阈值：$chunk 分钟 (0=不限)")
        Slider(
            value = chunk.toFloat(),
            onValueChange = { viewModel.setChunkMinutes(it.toInt()) },
            valueRange = 0f..60f,
            steps = 60
        )

        // Recipient
        OutlinedTextField(
            value = recipient,
            onValueChange = viewModel::setRecipient,
            label = { Text("默认收件人") },
            modifier = Modifier.fillMaxSize()
        )

        // Prompt template
        OutlinedTextField(
            value = prompt,
            onValueChange = viewModel::setPromptTemplate,
            label = { Text("Prompt 模板") },
            modifier = Modifier.fillMaxSize()
        )

        Button(onClick = onBack) { Text("返回") }
    }
}
