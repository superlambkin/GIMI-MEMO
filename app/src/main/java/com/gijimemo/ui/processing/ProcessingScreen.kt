package com.gijimemo.ui.processing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ProcessingScreen(
    onComplete: (sessionId: String) -> Unit,
    onError: () -> Unit,
    viewModel: ProcessingViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.start() }
    LaunchedEffect(state.finished) {
        if (state.finished) {
            onComplete(viewModel.sessionId)
        }
    }

    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        if (state.error != null) {
            Text("出错：${state.error}")
            Button(onClick = onError) { Text("返回") }
        } else {
            CircularProgressIndicator()
            Text(text = state.streamText.ifEmpty { "处理中..." })
        }
    }
}
