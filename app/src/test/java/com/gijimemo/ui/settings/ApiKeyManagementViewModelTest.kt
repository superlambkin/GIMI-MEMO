package com.gijimemo.ui.settings

import android.util.Log
import com.gijimemo.data.model.LlmProviderConfig
import com.gijimemo.data.repository.SettingsRepository
import com.gijimemo.llm.LlmClient
import com.gijimemo.llm.LlmProvider
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockkStatic
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ApiKeyManagementViewModel の単体テスト。
 * SettingsRepository / LlmProvider は MockK で完全モック。
 * EncryptedPrefs 経由の実際の暗号/復号はしない (Repository が抽象化)。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApiKeyManagementViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var llmProvider: LlmProvider

    /** 6 プロバイダ分のモック (name + apiKeyRef のみ実体、他は relaxed) */
    private val sixProviders by lazy {
        listOf(
            mockk<LlmProviderConfig>(relaxed = true) {
                every { name } returns "MiniMax 国内"
                every { apiKeyRef } returns "apikey_minimax_cn"
            },
            mockk<LlmProviderConfig>(relaxed = true) {
                every { name } returns "MiniMax 海外"
                every { apiKeyRef } returns "apikey_minimax_overseas"
            },
            mockk<LlmProviderConfig>(relaxed = true) {
                every { name } returns "OpenAI"
                every { apiKeyRef } returns "apikey_openai"
                every { defaultModel } returns "gpt-4o-audio-preview"
            },
            mockk<LlmProviderConfig>(relaxed = true) {
                every { name } returns "ClaudeProxy"
                every { apiKeyRef } returns "apikey_claude_proxy"
            },
            mockk<LlmProviderConfig>(relaxed = true) {
                every { name } returns "DeepSeek"
                every { apiKeyRef } returns "apikey_deepseek"
            },
            mockk<LlmProviderConfig>(relaxed = true) {
                every { name } returns "Ollama"
                every { apiKeyRef } returns "apikey_ollama"
            }
        )
    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(Log::class)
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        settingsRepo = mockk(relaxed = true)
        llmProvider = mockk(relaxed = true)
        every { settingsRepo.defaultProviders() } returns sixProviders
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun makeViewModel() = ApiKeyManagementViewModel(settingsRepo, llmProvider)

    @Test
    fun `providers list contains 6 entries`() {
        val vm = makeViewModel()
        assertEquals(6, vm.providers.size)
    }

    @Test
    fun `init loads existing api keys from repository into draft`() = runTest {
        // 既に OpenAI / MiniMax Key が保存されている状態
        every { settingsRepo.getApiKey("apikey_openai") } returns "sk-existing-openai"
        every { settingsRepo.getApiKey("apikey_minimax_cn") } returns "minimax-key-1"
        every { settingsRepo.getApiKey("apikey_minimax_overseas") } returns null
        every { settingsRepo.getApiKey("apikey_claude_proxy") } returns null
        every { settingsRepo.getApiKey("apikey_deepseek") } returns null
        every { settingsRepo.getApiKey("apikey_ollama") } returns null

        val vm = makeViewModel()
        advanceUntilIdle()

        val draft = vm.draft.value
        assertEquals("sk-existing-openai", draft["apikey_openai"])
        assertEquals("minimax-key-1", draft["apikey_minimax_cn"])
        assertEquals("", draft["apikey_minimax_overseas"])
    }

    @Test
    fun `onKeyChange updates draft and resets test state to Idle`() = runTest {
        every { settingsRepo.getApiKey(any()) } returns null
        val vm = makeViewModel()
        advanceUntilIdle()

        vm.onKeyChange("apikey_openai", "sk-new-value")
        assertEquals("sk-new-value", vm.draft.value["apikey_openai"])
        assertTrue(vm.testState.value["apikey_openai"] is ApiKeyManagementViewModel.ApiTestState.Idle)
    }

    @Test
    fun `saveAll writes all 6 keys to repository`() = runTest {
        every { settingsRepo.getApiKey(any()) } returns null
        every { settingsRepo.setApiKey(any(), any()) } just Runs

        val vm = makeViewModel()
        advanceUntilIdle()
        vm.onKeyChange("apikey_openai", "sk-test1")
        vm.onKeyChange("apikey_minimax_cn", "minimax-test")

        vm.saveAll()
        advanceUntilIdle()

        verify(exactly = 1) { settingsRepo.setApiKey("apikey_openai", "sk-test1") }
        verify(exactly = 1) { settingsRepo.setApiKey("apikey_minimax_cn", "minimax-test") }
        verify(exactly = 1) { settingsRepo.setApiKey("apikey_minimax_overseas", "") }
        verify(exactly = 1) { settingsRepo.setApiKey("apikey_claude_proxy", "") }
        verify(exactly = 1) { settingsRepo.setApiKey("apikey_deepseek", "") }
        verify(exactly = 1) { settingsRepo.setApiKey("apikey_ollama", "") }

        val result = vm.saveResult.value
        assertNotNull("saveResult should not be null after saveAll", result)
        assertTrue("result is ${result?.javaClass?.simpleName}", result is ApiKeyManagementViewModel.SaveResult.Success)
        assertEquals(6, (result as ApiKeyManagementViewModel.SaveResult.Success).savedCount)
    }

    @Test
    fun `saveAll with error sets Failure result`() = runTest {
        every { settingsRepo.getApiKey(any()) } returns null
        every { settingsRepo.setApiKey(any(), any()) } throws RuntimeException("disk full")

        val vm = makeViewModel()
        advanceUntilIdle()
        vm.onKeyChange("apikey_openai", "sk-fail")

        vm.saveAll()
        advanceUntilIdle()

        val result = vm.saveResult.value
        assertNotNull("saveResult should not be null after saveAll", result)
        assertTrue("result is ${result?.javaClass?.simpleName}", result is ApiKeyManagementViewModel.SaveResult.Failure)
        assertEquals("disk full", (result as ApiKeyManagementViewModel.SaveResult.Failure).message)
    }

    @Test
    fun `testOne with blank key sets Error for non-Ollama provider`() = runTest {
        every { settingsRepo.getApiKey(any()) } returns null
        val vm = makeViewModel()
        advanceUntilIdle()

        vm.testOne("apikey_openai")  // 空文字でテスト → Error
        advanceUntilIdle()

        val state = vm.testState.value["apikey_openai"]
        assertTrue("state is ${state?.javaClass?.simpleName}", state is ApiKeyManagementViewModel.ApiTestState.Error)
    }

    @Test
    fun `testOne successful sets Success state`() = runTest {
        every { settingsRepo.getApiKey(any()) } returns null
        val mockClient = mockk<LlmClient> {
            coEvery { testConnection() } returns "pong from openai"
        }
        every { llmProvider.createClient(any(), any(), any(), any(), any()) } returns mockClient

        val vm = makeViewModel()
        advanceUntilIdle()
        vm.onKeyChange("apikey_openai", "sk-real")

        vm.testOne("apikey_openai")
        advanceUntilIdle()

        val state = vm.testState.value["apikey_openai"]
        assertTrue("state was ${state?.javaClass?.simpleName}", state is ApiKeyManagementViewModel.ApiTestState.Success)
        assertEquals("pong from openai", (state as ApiKeyManagementViewModel.ApiTestState.Success).response)
    }

    @Test
    fun `dismissSaveResult resets to null`() = runTest {
        every { settingsRepo.getApiKey(any()) } returns null
        every { settingsRepo.setApiKey(any(), any()) } just Runs

        val vm = makeViewModel()
        advanceUntilIdle()
        vm.saveAll()
        advanceUntilIdle()
        assertNotNull("expected saveResult to be set", vm.saveResult.value)

        vm.dismissSaveResult()
        assertEquals(null, vm.saveResult.value)
    }
}
