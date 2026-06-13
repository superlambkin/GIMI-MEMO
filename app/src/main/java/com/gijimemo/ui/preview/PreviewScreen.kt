package com.gijimemo.ui.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gijimemo.ui.settings.SettingsViewModel

@Composable
fun PreviewScreen(
    defaultRecipient: String = "",
    onBack: () -> Unit,
    viewModel: PreviewViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recipients by settingsViewModel.recipients.collectAsStateWithLifecycle()
    var recipient by remember { mutableStateOf(defaultRecipient) }

    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(recipients) {
        if (recipient.isBlank() && recipients.isNotEmpty()) {
            recipient = recipients.first()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text(text = state.session?.title ?: "加载中")
        Text(text = state.markdown)

        // Recipient selector (preset list dropdown)
        Text("收件人", style = MaterialTheme.typography.bodySmall)
        var expanded by remember { mutableStateOf(false) }
        TextButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(if (recipient.isBlank()) "选择收件人" else recipient)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (recipients.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("（请先在设置中添加收件人）") },
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
        // 也允许自由输入（兜底）
        OutlinedTextField(
            value = recipient,
            onValueChange = { recipient = it },
            label = { Text("或手动输入收件人") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        )
        Button(
            onClick = { viewModel.share(recipient) },
            enabled = state.session != null && recipient.isNotBlank(),
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            Icon(Icons.Filled.Share, contentDescription = null)
            Text(" 分享到邮件")
        }
        Button(onClick = onBack) { Text("返回") }
    }
}
