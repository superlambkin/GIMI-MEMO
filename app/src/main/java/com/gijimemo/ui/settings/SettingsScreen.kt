package com.gijimemo.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.ModelTraining
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gijimemo.data.model.LlmCallMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onApiKeyManagement: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val callMode by viewModel.callMode.collectAsStateWithLifecycle()
    val chunk by viewModel.chunkMinutes.collectAsStateWithLifecycle()
    val recipients by viewModel.recipients.collectAsStateWithLifecycle()
    val selected by viewModel.selectedProviderName.collectAsStateWithLifecycle()
    val prompt by viewModel.promptTemplate.collectAsStateWithLifecycle()
    val currentModel by viewModel.currentModel.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ─── ヘッダー ───────────────────────────────────
        Text(
            "設定",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
        )

        // ─── ① サービス設定 ────────────────────────────
        SettingsSectionCard(title = "サービス", icon = { Icon(Icons.Filled.Tune, contentDescription = null) }) {
            // API Key 一括管理への導線のみ
            OutlinedButton(
                onClick = onApiKeyManagement,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Icon(Icons.Filled.VpnKey, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("API Key を一括管理")
            }
            Spacer(Modifier.height(8.dp))

            // プロバイダ選択
            var providerExpanded by remember { mutableStateOf(false) }
            SettingsLabel("サービス")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selected ?: "未選択",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = { providerExpanded = true },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("変更") }
            }
            DropdownMenu(
                expanded = providerExpanded,
                onDismissRequest = { providerExpanded = false }
            ) {
                viewModel.configuredProviders.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(p.name) },
                        onClick = { viewModel.selectProvider(p.name); providerExpanded = false }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Model 選択
            val currentProvider = viewModel.providers.firstOrNull { it.name == selected }
            if (currentProvider != null) {
                if (currentProvider.baseUrl.isNotBlank()) {
                    Text(
                        text = "Endpoint: ${currentProvider.baseUrl}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val models = currentProvider.supportedModels
                if (models.isNotEmpty()) {
                    var modelExpanded by remember { mutableStateOf(false) }
                    Spacer(Modifier.height(8.dp))
                    SettingsLabel("デフォルトモデル")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = currentModel.ifBlank { currentProvider.defaultModel },
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            onClick = { modelExpanded = true },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text("変更") }
                    }
                    DropdownMenu(
                        expanded = modelExpanded,
                        onDismissRequest = { modelExpanded = false }
                    ) {
                        models.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(m) },
                                onClick = { viewModel.setModel(m); modelExpanded = false }
                            )
                        }
                    }
                }
            }
        }

        // ─── ② 表示モード ────────────────────────────
        SettingsSectionCard(title = "表示モード", icon = { Icon(Icons.Filled.DisplaySettings, contentDescription = null) }) {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("テーマ", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.weight(1f))
                listOf(0 to "システム", 1 to "ライト", 2 to "ダーク").forEach { (mode, label) ->
                    OutlinedButton(
                        onClick = { viewModel.setThemeMode(mode) },
                        modifier = Modifier.heightIn(min = 40.dp),
                        enabled = themeMode != mode
                    ) { Text(label, fontSize = 12.sp) }
                    Spacer(Modifier.width(4.dp))
                }
            }
        }

        // ─── ③ 呼び出しモード ──────────────────────────
        SettingsSectionCard(title = "呼び出しモード", icon = { Icon(Icons.Filled.SwapHoriz, contentDescription = null) }) {
            SettingsLabel("現在：${callModeDisplay(callMode)}")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val supportsMultimodal = viewModel.currentProviderSupportsMultimodal()
                OutlinedButton(
                    onClick = { viewModel.setCallMode(LlmCallMode.MULTIMODAL) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                    enabled = callMode != LlmCallMode.MULTIMODAL && supportsMultimodal
                ) { Text(if (supportsMultimodal) "マルチモーダル" else "マルチモーダル（非対応）") }
                OutlinedButton(
                    onClick = { viewModel.setCallMode(LlmCallMode.WHISPER_THEN_SUMMARY) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                    enabled = callMode != LlmCallMode.WHISPER_THEN_SUMMARY
                ) { Text("Whisper+要約") }
            }

            Spacer(Modifier.height(8.dp))

            // オンデバイスWhisper
            val useOnDevice by viewModel.useOnDeviceAsr.collectAsStateWithLifecycle()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("オンデバイスWhisper", style = MaterialTheme.typography.bodyMedium)
                }
                Switch(checked = useOnDevice, onCheckedChange = { viewModel.setUseOnDeviceAsr(it) })
            }
            if (useOnDevice) {
                Text(
                    "端末内で文字起こしを実行します（初回使用時にモデル ~55MB をダウンロード）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }

        // ─── ③ 文字起こし設定 ────────────────────────────
        SettingsSectionCard(title = "文字起こし設定", icon = { Icon(Icons.Filled.Schedule, contentDescription = null) }) {
            // デコード有効/無効
            val decodeEnabled by viewModel.decodeEnabled.collectAsStateWithLifecycle()
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("AAC→WAV デコード", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (decodeEnabled) "有効（高精度、処理遅め）" else "無効（高速、ファイル直接送信）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = decodeEnabled, onCheckedChange = { viewModel.setDecodeEnabled(it) })
            }

            Spacer(Modifier.height(8.dp))
            SettingsLabel("分割サイズ：${chunk}MB（最大24MB）")
            Slider(
                value = chunk.toFloat(),
                onValueChange = { viewModel.setChunkMinutes(it.toInt()) },
                valueRange = 1f..24f,
                steps = 23
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("1MB", style = MaterialTheme.typography.bodySmall)
                Text("24MB", style = MaterialTheme.typography.bodySmall)
            }
        }

        // ─── ④ 録音設定 ────────────────────────────
        SettingsSectionCard(title = "録音設定", icon = { Icon(Icons.Filled.Mic, contentDescription = null) }) {
            val sampleRate by viewModel.recordingSampleRate.collectAsStateWithLifecycle()
            val bitRate by viewModel.recordingBitRate.collectAsStateWithLifecycle()
            val enableNs by viewModel.enableNoiseSuppressor.collectAsStateWithLifecycle()
            val enableAgc by viewModel.enableAutomaticGainControl.collectAsStateWithLifecycle()
            val enableVad by viewModel.enableVoiceActivityDetection.collectAsStateWithLifecycle()

            // サンプリングレート
            SettingsLabel("サンプリングレート")
            var srExpanded by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${sampleRate / 1000}kHz（${sampleRate}Hz）",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = { srExpanded = true },
                    modifier = Modifier.heightIn(min = 40.dp)
                ) { Text("変更", fontSize = 12.sp) }
            }
            DropdownMenu(
                expanded = srExpanded,
                onDismissRequest = { srExpanded = false }
            ) {
                listOf(8000, 16000, 22050, 44100).forEach { v ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                "${v / 1000}kHz（${v}Hz）${if (v == 16000) "★推奨" else ""}",
                                color = if (v == 16000) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        },
                        onClick = { viewModel.setRecordingSampleRate(v); srExpanded = false }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ビットレート
            SettingsLabel("ビットレート（AAC）")
            var brExpanded by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${bitRate / 1000}kbps",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = { brExpanded = true },
                    modifier = Modifier.heightIn(min = 40.dp)
                ) { Text("変更", fontSize = 12.sp) }
            }
            DropdownMenu(
                expanded = brExpanded,
                onDismissRequest = { brExpanded = false }
            ) {
                listOf(32000, 48000, 64000, 96000, 128000).forEach { v ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                "${v / 1000}kbps${if (v == 48000) " ★推奨" else ""}",
                                color = if (v == 48000) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        },
                        onClick = { viewModel.setRecordingBitRate(v); brExpanded = false }
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ─── 音声処理機能（ON/OFF） ──────────────────
            SettingsLabel("音声処理機能（会議録音の品質向上）")
            AudioProcessingToggle(
                label = "ノイズ抑制（空調・ファン等の定常ノイズを低減）",
                checked = enableNs,
                onCheckedChange = { viewModel.setEnableNoiseSuppressor(it) }
            )
            AudioProcessingToggle(
                label = "自動音量調整（発言者の距離差を補正）",
                checked = enableAgc,
                onCheckedChange = { viewModel.setEnableAutomaticGainControl(it) }
            )
            AudioProcessingToggle(
                label = "声活動検出（無音部分を検出し文字起こし精度向上）",
                checked = enableVad,
                onCheckedChange = { viewModel.setEnableVoiceActivityDetection(it) }
            )

            Spacer(Modifier.height(8.dp))
            Text(
                "全て初期値ON。各種デバイスで効果が異なるため、録音品質に応じて調整してください。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // ─── ⑤ 受信者プリセット ────────────────────────
        SettingsSectionCard(title = "受信者プリセット", icon = { Icon(Icons.Filled.Person, contentDescription = null) }) {
            if (recipients.isEmpty()) {
                Text(
                    "（登録なし）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                recipients.forEach { email ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(email, modifier = Modifier.weight(1f))
                            IconButton(onClick = { viewModel.removeRecipient(email) }) {
                                Icon(Icons.Filled.Close, contentDescription = "削除")
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            var newRecipient by remember { mutableStateOf("") }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newRecipient,
                    onValueChange = { newRecipient = it },
                    label = { Text("メールアドレス") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        if (newRecipient.isNotBlank()) {
                            viewModel.addRecipient(newRecipient.trim())
                            newRecipient = ""
                        }
                    },
                    enabled = newRecipient.isNotBlank()
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "追加")
                }
            }
        }

        // ─── ⑥ 要約テンプレート ──────────────────────
        SettingsSectionCard(title = "要約テンプレート（種類別）", icon = { Icon(Icons.Filled.ModelTraining, contentDescription = null) }) {
            var expandedType by remember { mutableStateOf("minutes") }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf("minutes" to "議事録", "lecture" to "講演会", "class" to "授業").forEach { (key, label) ->
                    OutlinedButton(
                        onClick = { expandedType = key },
                        modifier = Modifier.weight(1f).heightIn(min = 36.dp),
                        enabled = expandedType != key
                    ) {
                        Text(label, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf("interview" to "取材", "chat" to "雑談", "dr" to "DR").forEach { (key, label) ->
                    OutlinedButton(
                        onClick = { expandedType = key },
                        modifier = Modifier.weight(1f).heightIn(min = 36.dp),
                        enabled = expandedType != key
                    ) {
                        Text(label, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf("media" to "メディア", "custom1" to "カスタム1", "custom2" to "カスタム2").forEach { (key, label) ->
                    OutlinedButton(
                        onClick = { expandedType = key },
                        modifier = Modifier.weight(1f).heightIn(min = 36.dp),
                        enabled = expandedType != key
                    ) {
                        Text(label, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            var templateText by remember(expandedType) {
                mutableStateOf(viewModel.getTemplate(expandedType))
            }
            OutlinedTextField(
                value = templateText,
                onValueChange = { templateText = it; viewModel.setPromptTemplate(expandedType, it) },
                label = { Text("テンプレート内容") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp),
                maxLines = 6,
                textStyle = MaterialTheme.typography.bodySmall
            )
        }

        // ─── ⑦ 読み上げ設定 ──────────────────────────
        SettingsSectionCard(title = "読み上げ設定", icon = { Icon(Icons.Filled.VolumeUp, contentDescription = null) }) {
            val ttsRate by viewModel.ttsSpeechRate.collectAsStateWithLifecycle()
            val ttsPitch by viewModel.ttsPitch.collectAsStateWithLifecycle()
            val currentEngine by viewModel.ttsEngine.collectAsStateWithLifecycle()

            // エンジン選択
            val engines = remember { viewModel.availableEngines }
            if (engines.isNotEmpty()) {
                SettingsLabel("読み上げエンジン")
                var engineExpanded by remember { mutableStateOf(false) }
                val currentLabel = if (currentEngine != null)
                    engines.find { it.packageName == currentEngine }?.label ?: currentEngine
                else "システム標準"
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(currentLabel ?: "システム標準", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { engineExpanded = true }, modifier = Modifier.heightIn(min = 36.dp)) {
                        Text("変更", fontSize = 12.sp)
                    }
                }
                DropdownMenu(expanded = engineExpanded, onDismissRequest = { engineExpanded = false }) {
                    DropdownMenuItem(text = { Text("システム標準") },
                        onClick = { viewModel.setTtsEngine(null); engineExpanded = false })
                    engines.forEach { e ->
                        DropdownMenuItem(text = { Text(e.label) },
                            onClick = { viewModel.setTtsEngine(e.packageName); engineExpanded = false })
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // 話速
            SettingsLabel("話速: ${"%.1f".format(ttsRate)}")
            Slider(value = ttsRate, onValueChange = { viewModel.setTtsSpeechRate((it * 10).toInt() / 10f) },
                valueRange = 0.5f..2.0f, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("遅い", style = MaterialTheme.typography.bodySmall)
                Text("速い", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))

            // ピッチ
            SettingsLabel("ピッチ: ${"%.1f".format(ttsPitch)}")
            Slider(value = ttsPitch, onValueChange = { viewModel.setTtsPitch((it * 10).toInt() / 10f) },
                valueRange = 0.5f..2.0f, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("低い", style = MaterialTheme.typography.bodySmall)
                Text("高い", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))

            // 試聴
            Button(onClick = { viewModel.trialPlay(ttsRate, ttsPitch, currentEngine) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                Icon(Icons.Filled.VolumeUp, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("試聴する")
            }
        }

        // ─── 下部アクション ──────────────────────────────
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp)
            ) {
                Icon(Icons.Filled.Close, contentDescription = null)
                Text("  キャンセル")
            }
            Button(
                onClick = onBack,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp)
            ) {
                Icon(Icons.Filled.Check, contentDescription = null)
                Text("  保存して戻る")
            }
        }
    }

}

// ─── ヘルパーコンポーネント ─────────────────────────────────

/** 設定のセクションカード：タイトル + アイコン + コンテンツ */
@Composable
private fun SettingsSectionCard(
    title: String,
    icon: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                icon()
                Spacer(Modifier.width(8.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            content()
        }
    }
}

/** 設定ラベル（小文字・やや薄め） */
@Composable
private fun SettingsLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

/** 呼び出しモードの表示名 */
private fun callModeDisplay(mode: LlmCallMode): String = when (mode) {
    LlmCallMode.MULTIMODAL -> "マルチモーダル（音声直接送信）"
    LlmCallMode.WHISPER_THEN_SUMMARY -> "Whisper+要約（文字起こし後確認あり）"
}

@Composable
private fun AudioProcessingToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(end = 8.dp)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
