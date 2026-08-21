package com.gijimemo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gijimemo.ui.GijiMemoNavHost
import com.gijimemo.ui.startup.StartupSplash
import com.gijimemo.ui.startup.StartupState
import com.gijimemo.ui.startup.StartupViewModel
import com.gijimemo.data.repository.SettingsRepository
import com.gijimemo.ui.home.SharedAudioStore
import com.gijimemo.ui.theme.GijiMemoTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var sharedAudioStore: SharedAudioStore

    private val startupViewModel: StartupViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleSharedAudioIntent(intent)
        setContent {
            val themeMode by settings.themeMode.collectAsState(initial = 0)
            // v0.7.5: Activity 夜間モードをテーマ設定と同期（MIUI 強制ダークを防止）
            LaunchedEffect(themeMode) {
                val mode = when (themeMode) {
                    1 -> AppCompatDelegate.MODE_NIGHT_NO
                    2 -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                AppCompatDelegate.setDefaultNightMode(mode)
            }
            GijiMemoTheme(themeMode = themeMode) {
                CompositionLocalProvider(LocalLifecycleOwner provides this) {
                    val startupState by startupViewModel.state.collectAsStateWithLifecycle()
                    when (startupState) {
                        is StartupState.Ready -> {
                            GijiMemoNavHost()
                        }
                        else -> {
                            StartupSplash(
                                state = startupState,
                                onRetry = { /* StartupViewModel re-extracts on init; for now this is a no-op and we rely on the failure being logged. */ },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }

    /** アプリ起動中に別アプリから再度共有された場合も受け取る。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSharedAudioIntent(intent)
    }

    /** ACTION_SEND で共有された音声 URI を SharedAudioStore へ渡す。 */
    private fun handleSharedAudioIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        if (!intent.type.orEmpty().startsWith("audio/")) return
        val uri = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (uri != null) {
            sharedAudioStore.offer(uri)
        }
    }
}
