package com.gijimemo.ui.settings

import android.util.Log
import com.gijimemo.data.model.LlmCallMode
import com.gijimemo.data.model.LlmProviderConfig
import com.gijimemo.data.repository.SettingsRepository
import com.gijimemo.llm.LlmClient
import com.gijimemo.llm.LlmProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val settings: SettingsRepository = mockk(relaxed = true)
    private val llmProvider: LlmProvider = mockk(relaxed = true)
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(Log::class)
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0

        every { settings.defaultProviders() } returns LlmProviderConfig.defaults()
        every { settings.defaultCallMode } returns flowOf(LlmCallMode.MULTIMODAL)
        every { settings.defaultChunkMinutes } returns flowOf(25)
        every { settings.defaultRecipient } returns flowOf("")
        every { settings.recipients } returns flowOf(emptyList())
        every { settings.defaultPromptTemplate } returns flowOf("")
        coEvery { settings.selectedProvider() } returns LlmProviderConfig.defaults().first()
        every { settings.modelForProvider(any()) } returns flowOf(null)
        every { settings.getApiKey(any()) } returns null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `providers returns 6 defaults`() {
        val vm = SettingsViewModel(settings, llmProvider)
        assertThat(vm.providers).hasSize(6)
    }

    @Test
    fun `initial selectedProviderName matches selectedProvider`() = runTest(testDispatcher) {
        coEvery { settings.selectedProvider() } returns LlmProviderConfig.defaults()[2]
        val vm = SettingsViewModel(settings, llmProvider)
        assertThat(vm.selectedProviderName.value).isEqualTo("OpenAI")
    }

    @Test
    fun `apiTestState starts as Idle`() {
        val vm = SettingsViewModel(settings, llmProvider)
        assertThat(vm.apiTestState.value).isInstanceOf(SettingsViewModel.ApiTestState.Idle::class.java)
    }

    @Test
    fun `dismissApiTest stays Idle when already Idle`() {
        val vm = SettingsViewModel(settings, llmProvider)
        vm.dismissApiTest()
        assertThat(vm.apiTestState.value).isInstanceOf(SettingsViewModel.ApiTestState.Idle::class.java)
    }

    @Test
    fun `callMode starts with default MULTIMODAL`() {
        val vm = SettingsViewModel(settings, llmProvider)
        assertThat(vm.callMode.value).isEqualTo(LlmCallMode.MULTIMODAL)
    }

    @Test
    fun `chunkMinutes starts with default 25`() {
        val vm = SettingsViewModel(settings, llmProvider)
        assertThat(vm.chunkMinutes.value).isEqualTo(25)
    }

    @Test
    fun `recipients starts as empty list`() {
        val vm = SettingsViewModel(settings, llmProvider)
        assertThat(vm.recipients.value).isEmpty()
    }

    @Test
    fun `selectProvider updates selectedProviderName and persists`() = runTest(testDispatcher) {
        val vm = SettingsViewModel(settings, llmProvider)
        vm.selectProvider("DeepSeek")
        assertThat(vm.selectedProviderName.value).isEqualTo("DeepSeek")
        coVerify { settings.setDefaultProvider("DeepSeek") }
    }

    @Test
    fun `setCallMode delegates to settings`() = runTest(testDispatcher) {
        val vm = SettingsViewModel(settings, llmProvider)
        vm.setCallMode(LlmCallMode.WHISPER_THEN_SUMMARY)
        coVerify { settings.setDefaultCallMode(LlmCallMode.WHISPER_THEN_SUMMARY) }
    }

    @Test
    fun `setChunkMinutes delegates to settings`() = runTest(testDispatcher) {
        val vm = SettingsViewModel(settings, llmProvider)
        vm.setChunkMinutes(30)
        coVerify { settings.setDefaultChunkMinutes(30) }
    }

    @Test
    fun `setRecipient delegates to settings`() = runTest(testDispatcher) {
        val vm = SettingsViewModel(settings, llmProvider)
        vm.setRecipient("test@example.com")
        coVerify { settings.setDefaultRecipient("test@example.com") }
    }

    @Test
    fun `addRecipient delegates to settings`() = runTest(testDispatcher) {
        val vm = SettingsViewModel(settings, llmProvider)
        vm.addRecipient("a@example.com")
        coVerify { settings.addRecipient("a@example.com") }
    }

    @Test
    fun `removeRecipient delegates to settings`() = runTest(testDispatcher) {
        val vm = SettingsViewModel(settings, llmProvider)
        vm.removeRecipient("a@example.com")
        coVerify { settings.removeRecipient("a@example.com") }
    }

    @Test
    fun `getApiKey delegates to settings and returns value`() {
        every { settings.getApiKey("apikey_minimax_cn") } returns "key-xyz"
        val vm = SettingsViewModel(settings, llmProvider)
        val result = vm.getApiKey("apikey_minimax_cn")
        assertThat(result).isEqualTo("key-xyz")
    }

    @Test
    fun `setApiKey delegates to settings`() {
        val vm = SettingsViewModel(settings, llmProvider)
        vm.setApiKey("apikey_test", "my-key")
        verify { settings.setApiKey("apikey_test", "my-key") }
    }

    @Test
    fun `setPromptTemplate delegates to settings`() = runTest(testDispatcher) {
        val vm = SettingsViewModel(settings, llmProvider)
        vm.setPromptTemplate("custom template")
        coVerify { settings.setDefaultPromptTemplate("custom template") }
    }

    @Test
    fun `setModel delegates to settings with selected provider name`() = runTest(testDispatcher) {
        val vm = SettingsViewModel(settings, llmProvider)
        vm.selectProvider("MiniMax 海外")
        vm.setModel("MiniMax-VL-01")
        coVerify { settings.setModelForProvider("MiniMax 海外", "MiniMax-VL-01") }
    }

    @Test
    fun `setModel silently ignored when no provider selected`() {
        val vm = SettingsViewModel(settings, llmProvider)
        vm.setModel("any-model")
        // No exception is the pass condition
    }

    @Test
    fun `supportedModels returns models for the selected provider`() {
        coEvery { settings.selectedProvider() } returns LlmProviderConfig.defaults()[2]
        val vm = SettingsViewModel(settings, llmProvider)
        val models = vm.supportedModels()
        assertThat(models).containsExactly(
            "gpt-4o-audio-preview", "gpt-4o", "gpt-4o-mini", "gpt-4-turbo"
        )
    }

    @Test
    fun `supportedModels returns empty list for provider not in defaults`() {
        coEvery { settings.selectedProvider() } returns LlmProviderConfig(
            name = "Unknown", baseUrl = "", defaultModel = "",
            supportedModels = emptyList(), supportsMultimodal = false, apiKeyRef = ""
        )
        val vm = SettingsViewModel(settings, llmProvider)
        assertThat(vm.supportedModels()).isEmpty()
    }

    @Test
    fun `testApi with empty API key transitions to Error`() {
        every { settings.getApiKey(any()) } returns null
        val vm = SettingsViewModel(settings, llmProvider)
        vm.testApi()
        val state = vm.apiTestState.value
        assertThat(state).isInstanceOf(SettingsViewModel.ApiTestState.Error::class.java)
        assertThat((state as SettingsViewModel.ApiTestState.Error).message).contains("API Key")
    }

    @Test
    fun `testApi transitions from Idle to Success`() = runTest(testDispatcher) {
        val client: LlmClient = mockk()
        coEvery { client.testConnection() } returns "Hello, world!"
        every { llmProvider.createClient(any(), any(), any()) } returns client
        every { settings.getApiKey("apikey_minimax_cn") } returns "valid-key"
        val vm = SettingsViewModel(settings, llmProvider)
        vm.testApi()
        val state = vm.apiTestState.value
        assertThat(state).isInstanceOf(SettingsViewModel.ApiTestState.Success::class.java)
        assertThat((state as SettingsViewModel.ApiTestState.Success).response).isEqualTo("Hello, world!")
    }

    @Test
    fun `testApi response is truncated to 300 chars`() = runTest(testDispatcher) {
        val longResponse = "A".repeat(500)
        val client: LlmClient = mockk()
        coEvery { client.testConnection() } returns longResponse
        every { llmProvider.createClient(any(), any(), any()) } returns client
        every { settings.getApiKey("apikey_minimax_cn") } returns "valid-key"
        val vm = SettingsViewModel(settings, llmProvider)
        vm.testApi()
        val state = vm.apiTestState.value
        assertThat(state).isInstanceOf(SettingsViewModel.ApiTestState.Success::class.java)
        assertThat((state as SettingsViewModel.ApiTestState.Success).response.length).isEqualTo(300)
    }

    @Test
    fun `testApi transitions from Idle to Error when client throws`() = runTest(testDispatcher) {
        val client: LlmClient = mockk()
        coEvery { client.testConnection() } throws RuntimeException("Connection refused")
        every { llmProvider.createClient(any(), any(), any()) } returns client
        every { settings.getApiKey("apikey_minimax_cn") } returns "valid-key"
        val vm = SettingsViewModel(settings, llmProvider)
        vm.testApi()
        val state = vm.apiTestState.value
        assertThat(state).isInstanceOf(SettingsViewModel.ApiTestState.Error::class.java)
        assertThat((state as SettingsViewModel.ApiTestState.Error).message).isEqualTo("Connection refused")
    }

    @Test
    fun `testApi returns early when selected provider not found in defaults list`() {
        coEvery { settings.selectedProvider() } returns LlmProviderConfig(
            name = "NonExistent", baseUrl = "", defaultModel = "",
            supportedModels = emptyList(), supportsMultimodal = false, apiKeyRef = ""
        )
        val vm = SettingsViewModel(settings, llmProvider)
        vm.testApi()
        assertThat(vm.apiTestState.value).isInstanceOf(SettingsViewModel.ApiTestState.Idle::class.java)
    }
}
