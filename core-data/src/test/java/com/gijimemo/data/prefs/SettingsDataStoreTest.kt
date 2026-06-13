package com.gijimemo.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gijimemo.data.model.LlmCallMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
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
        runBlocking { store.setRecipients(emptyList()) }
    }

    @Test
    fun `default provider is MiniMax CN`() = runTest {
        assertThat(store.defaultProvider.first()).isEqualTo("MiniMax 国内")
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
    fun `recipients default to empty list`() = runTest {
        assertThat(store.recipients.first()).isEmpty()
    }

    @Test
    fun `addRecipient appends to list`() = runTest {
        store.addRecipient("a@example.com")
        store.addRecipient("b@example.com")
        assertThat(store.recipients.first()).containsExactly("a@example.com", "b@example.com")
    }

    @Test
    fun `addRecipient ignores duplicate`() = runTest {
        store.addRecipient("dup@example.com")
        store.addRecipient("dup@example.com")
        assertThat(store.recipients.first()).containsExactly("dup@example.com")
    }

    @Test
    fun `addRecipient ignores blank email`() = runTest {
        store.addRecipient("")
        store.addRecipient("   ")
        assertThat(store.recipients.first()).isEmpty()
    }

    @Test
    fun `removeRecipient drops from list`() = runTest {
        store.addRecipient("a@example.com")
        store.addRecipient("b@example.com")
        store.removeRecipient("a@example.com")
        assertThat(store.recipients.first()).containsExactly("b@example.com")
    }

    @Test
    fun `setRecipients replaces entire list`() = runTest {
        store.addRecipient("a@example.com")
        store.setRecipients(listOf("x@example.com", "y@example.com"))
        assertThat(store.recipients.first()).containsExactly("x@example.com", "y@example.com")
    }
}