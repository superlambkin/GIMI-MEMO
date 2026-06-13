package com.gijimemo.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gijimemo.data.model.LlmCallMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsDataStoreTest {
    private lateinit var store: SettingsDataStore

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        store = SettingsDataStore(context)
    }

    @Test
    fun `default provider is MiniMax`() = runTest {
        assertThat(store.defaultProvider.first()).isEqualTo("MiniMax")
    }

    @Test
    fun `set and read default call mode`() = runTest {
        store.setDefaultCallMode(LlmCallMode.WHISPER_THEN_SUMMARY)
        assertThat(store.defaultCallMode.first()).isEqualTo(LlmCallMode.WHISPER_THEN_SUMMARY)
    }

    @Test
    fun `default chunk minutes is 25`() = runTest {
        assertThat(store.defaultChunkMinutes.first()).isEqualTo(25)
    }

    @Test
    fun `set and read default recipient`() = runTest {
        store.setDefaultRecipient("test@example.com")
        assertThat(store.defaultRecipient.first()).isEqualTo("test@example.com")
    }
}