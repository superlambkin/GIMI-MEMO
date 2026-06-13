package com.gijimemo.ui.recording

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.gijimemo.audio.RecordingState
import kotlinx.coroutines.launch

@Composable
fun RecordingScreen(
    onStop: (sessionId: String) -> Unit,
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

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            viewModel.startRecording()
        }
    }

    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        if (!hasPermission) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("需要麦克风权限")
                Button(onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                    Text("授权")
                }
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(text = "状态：${state.label()}")
                when (state) {
                    RecordingState.Recording -> {
                        IconButton(onClick = { viewModel.pauseRecording() }) {
                            Icon(Icons.Filled.Pause, contentDescription = "暂停")
                        }
                    }
                    RecordingState.Paused -> {
                        IconButton(onClick = { viewModel.resumeRecording() }) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "继续")
                        }
                    }
                    else -> {}
                }
                Button(onClick = {
                    scope.launch {
                        val session = viewModel.stopRecording("会议 ${System.currentTimeMillis()}")
                        if (session != null) onStop(session.id)
                    }
                }) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Text(" 停止并转写")
                }
                Button(onClick = onCancel) {
                    Text("取消")
                }
            }
        }
    }
}

private fun RecordingState.label(): String = when (this) {
    RecordingState.Idle -> "未开始"
    RecordingState.Recording -> "录音中"
    RecordingState.Paused -> "已暂停"
    RecordingState.Stopped -> "已停止"
    is RecordingState.Error -> "出错"
}
