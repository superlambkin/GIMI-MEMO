package com.gijimemo.data.repository

import com.gijimemo.data.model.LlmCallMode
import com.gijimemo.data.model.LlmProviderConfig
import com.gijimemo.data.model.findByName
import com.gijimemo.data.prefs.EncryptedPrefs
import com.gijimemo.data.prefs.SettingsDataStore
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SettingsRepositoryTest {
    private val store: SettingsDataStore = mockk(relaxed = true)
    private val encryptedPrefs: EncryptedPrefs = mockk(relaxed = true)
    private val repo = SettingsRepository(store, encryptedPrefs)

    @Test
    fun `defaultProviders returns 5`() {
        val list = repo.defaultProviders()
        assertThat(list).hasSize(5)
    }

    @Test
    fun `selectedProvider resolves to defaults when store returns unknown`() = runTest {
        every { store.defaultProvider } returns flowOf("CustomName")
        val p = repo.selectedProvider()
        // Falls back to first default
        assertThat(p.name).isEqualTo("MiniMax")
    }

    @Test
    fun `getApiKey delegates to EncryptedPrefs`() {
        every { encryptedPrefs.getApiKey("apikey_MiniMax") } returns "key-1"
        assertThat(repo.getApiKey("apikey_MiniMax")).isEqualTo("key-1")
    }

    @Test
    fun `setApiKey delegates to EncryptedPrefs`() {
        repo.setApiKey("apikey_MiniMax", "k")
        coVerify { encryptedPrefs.putApiKey("apikey_MiniMax", "k") }
    }

    @Test
    fun `setDefaultCallMode delegates to store`() = runTest {
        repo.setDefaultCallMode(LlmCallMode.WHISPER_THEN_SUMMARY)
        coVerify { store.setDefaultCallMode(LlmCallMode.WHISPER_THEN_SUMMARY) }
    }
}