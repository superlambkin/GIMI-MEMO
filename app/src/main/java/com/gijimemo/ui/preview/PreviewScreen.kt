package com.gijimemo.ui.preview

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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

@Composable
fun PreviewScreen(
    defaultRecipient: String = "",
    onBack: () -> Unit,
    viewModel: PreviewViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var recipient by remember { mutableStateOf(defaultRecipient) }

    LaunchedEffect(Unit) { viewModel.load() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text(text = state.session?.title ?: "加载中")
        Text(text = state.markdown)
        OutlinedTextField(
            value = recipient,
            onValueChange = { recipient = it },
            label = { Text("收件人邮箱") },
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 8.dp)
        )
        Button(
            onClick = { viewModel.share(recipient) },
            enabled = state.session != null && recipient.isNotBlank()
        ) {
            Text("分享到邮件")
        }
        Button(onClick = onBack) { Text("返回") }
    }
}
