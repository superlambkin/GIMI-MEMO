package com.gijimemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gijimemo.ui.GijiMemoNavHost
import com.gijimemo.ui.theme.GijiMemoTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GijiMemoTheme {
                // Fix: activity-compose 1.9.0 不会自动注入 androidx.lifecycle.compose.LocalLifecycleOwner
                // (在 1.9.1+ 修复), collectAsStateWithLifecycle 需要它才能工作
                CompositionLocalProvider(LocalLifecycleOwner provides this) {
                    GijiMemoNavHost()
                }
            }
        }
    }
}
