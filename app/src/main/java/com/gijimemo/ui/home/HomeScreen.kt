package com.gijimemo.ui.home

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.DocumentsContract
import android.provider.DocumentsContract.EXTRA_INITIAL_URI
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gijimemo.BuildConfig
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNewRecording: () -> Unit,
    onSessionImported: (String) -> Unit,
    onTxtImported: (String) -> Unit,
    onBatchImported: (List<String>) -> Unit,
    onSessionClick: (String) -> Unit,
    onSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // v0.9.1: 他アプリから共有された音声を受け取る。起動時・起動中の共有の両方に対応。
    val sharedUri by viewModel.sharedAudio.collectAsStateWithLifecycle()
    LaunchedEffect(sharedUri) {
        if (sharedUri != null) {
            viewModel.importAudioFile(sharedUri!!) { id ->
                viewModel.consumeSharedAudio()
                if (id != null) {
                    onSessionImported(id)
                } else {
                    scope.launch { snackbarHostState.showSnackbar("共有音声のインポートに失敗しました") }
                }
            }
        }
    }

    // v0.9.0: 複数ファイル選択に対応。1件なら従来どおり確認画面へ、2件以上なら一括文字起こしへ。
    val pickAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        if (uris.size == 1) {
            viewModel.importAudioFile(uris[0]) { id ->
                if (id != null) {
                    onSessionImported(id)
                } else {
                    scope.launch { snackbarHostState.showSnackbar("インポートに失敗しました") }
                }
            }
        } else {
            viewModel.importAudioFiles(uris) { ids ->
                if (ids.isNotEmpty()) {
                    onBatchImported(ids)
                } else {
                    scope.launch { snackbarHostState.showSnackbar("インポートに失敗しました") }
                }
            }
        }
    }

    val pickTxtLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
        if (uri != null) {
            viewModel.importTxtFile(uri) { id ->
                if (id != null) {
                    onTxtImported(id)
                } else {
                    scope.launch { snackbarHostState.showSnackbar("TXTインポートに失敗しました") }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "GIMI MEMO v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Light,
                        letterSpacing = 4.sp
                    )
                },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "設定")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { data -> Snackbar(snackbarData = data) } },
        floatingActionButton = {
            androidx.compose.foundation.layout.Column(
                horizontalAlignment = androidx.compose.ui.Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(onClick = { pickAudioLauncher.launch(arrayOf("audio/*")) }) {
                    Icon(Icons.Filled.LibraryMusic, contentDescription = null)
                    Text(" MP3 インポート")
                }
                TextButton(onClick = {
                    viewModel.ensureTxtImportDir()
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "text/plain"
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            putExtra(EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(
                                "com.android.externalstorage.documents",
                                "primary:Download/GIMI_MEMO"
                            ))
                        }
                    }
                    pickTxtLauncher.launch(intent)
                }) {
                    Icon(Icons.Filled.Description, contentDescription = null)
                    Text(" TXT インポート")
                }
                ExtendedFloatingActionButton(
                    onClick = onNewRecording,
                    icon = { Icon(Icons.Filled.Mic, contentDescription = null) },
                    text = { Text("録音") }
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.Top
        ) {
            items(sessions, key = { it.id }) { session ->
                SessionCard(session = session, onClick = { onSessionClick(session.id) })
            }
        }
    }
}
