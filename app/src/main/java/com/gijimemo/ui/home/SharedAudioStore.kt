// app/src/main/java/com/gijimemo/ui/home/SharedAudioStore.kt
package com.gijimemo.ui.home

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v0.9.1: 他アプリからの「共有（ACTION_SEND）」で受け取った音声 URI を保持する。
 * MainActivity がセットし、HomeScreen が消費（インポート）する。
 */
@Singleton
class SharedAudioStore @Inject constructor() {
    private val _pending = MutableStateFlow<Uri?>(null)
    val pending: StateFlow<Uri?> = _pending.asStateFlow()

    fun offer(uri: Uri) {
        _pending.value = uri
    }

    fun consume(): Uri? {
        val v = _pending.value
        _pending.value = null
        return v
    }
}
