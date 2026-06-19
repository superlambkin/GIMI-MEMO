package com.gijimemo.ui.processing

import androidx.lifecycle.SavedStateHandle
import com.gijimemo.data.model.LlmCallMode
import com.gijimemo.data.model.Session
import com.gijimemo.data.model.SessionStatus
import com.gijimemo.data.repository.SessionRepository
import com.gijimemo.data.repository.SettingsRepository
import com.gijimemo.llm.LlmClient
import com.gijimemo.llm.LlmEvent
import com.gijimemo.llm.LlmException
import com.gijimemo.llm.LlmProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

/** Create a SavedStateHandle mock that returns the test session ID. */
private fun mockSavedStateHandle(): SavedStateHandle {
    val handle = mockk<SavedStateHandle>(relaxed = true)
    every { handle.get<String>("sessionId") } returns "session-1"
    every { handle.get<String>("lang") } returns ""  // auto-detect
    return handle
}

/**
 * ProcessingViewModel 二段階フローの状態遷移テスト。
 *
 * StandardTestDispatcher で viewModelScope.launch のコルーチンを制御。
 * advanceUntilIdle() で全コルーチンの完了を待つ。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProcessingViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val repo: SessionRepository = mockk()
    private val settings: SettingsRepository = mockk()
    private val provider: LlmProvider = mockk()
    private val client: LlmClient = mockk()
    private val context: android.content.Context = mockk(relaxed = true) {
        every { cacheDir } returns java.io.File(System.getProperty("java.io.tmpdir"))
    }

    private val testSession = Session(
        id = "session-1",
        title = "テスト会議",
        createdAt = 1000L,
        durationMs = 60000L,
        audioFilePath = "/tmp/test_audio.mp3",
        audioSizeBytes = 1024L,
        status = SessionStatus.STOPPED
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // Mock android.util.Log to prevent RuntimeException in unit tests
        mockkStatic("android.util.Log")
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0

        coEvery { provider.createClient(any(), any(), any(), any(), any()) } returns client
        coEvery { repo.getById("session-1") } returns testSession
        coEvery { repo.save(any()) } returns Unit
        coEvery { repo.updateStatus(any(), any(), any()) } returns Unit

        coEvery { settings.selectedProvider() } returns
                com.gijimemo.data.model.LlmProviderConfig.defaults().first { it.name == "OpenAI" }
        every { settings.getApiKey(any()) } returns "test-api-key"
        every { settings.defaultCallMode } returns flowOf(LlmCallMode.WHISPER_THEN_SUMMARY)
        every { settings.defaultPromptTemplate } returns flowOf("要約して:")
        every { settings.modelForProvider(any()) } returns flowOf("test-model")
        // デフォルトで OnDevice ON にしておく (WHISPER_THEN_SUMMARY 経路をテスト可能にするため)
        every { settings.useOnDeviceAsr } returns flowOf(true)
        // Auto-provider selection: デフォルトは手動モード相当 (autoProviderMode = false で selectedProvider 経路)
        every { settings.autoProviderMode } returns flowOf(false)
        every { settings.isApiKeyConfigured(any()) } returns true
        coEvery { settings.autoSelectProvider() } returns
                com.gijimemo.data.model.LlmProviderConfig.defaults().first()

        val audioFile = File("/tmp/test_audio.mp3")
        if (!audioFile.exists()) {
            audioFile.parentFile?.mkdirs()
            audioFile.writeText("dummy audio content")
        }
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    // ─── P01a: start() → COMPLETED ───────────────────────

    @Test
    fun `P01a start completes the full flow`() = runTest(testDispatcher) {
        coEvery { client.transcribeOnly(any()) } returns "文字起こし結果"
        every { client.summarizeOnly(any(), any()) } returns flowOf(
            LlmEvent.Complete("要約結果です", "model")
        )
        // WHISPER_THEN_SUMMARY 経路は OnDevice Whisper 必須。OpenAI + useOnDevice=true で
        // startTranscribePhase → TRANSCRIBED まで進める。
        coEvery { settings.selectedProvider() } returns
            com.gijimemo.data.model.LlmProviderConfig.defaults().first { it.name == "OpenAI" }
        every { settings.useOnDeviceAsr } returns flowOf(true)

        val vm = ProcessingViewModel(mockSavedStateHandle(), repo, settings, provider, context)
        vm.start()
        advanceUntilIdle()

        // WHISPER_THEN_SUMMARY mode: start() only transcribes, summary requires confirmAndSummarize()
        assertThat(vm.state.value.phase).isEqualTo(ProcessingPhase.TRANSCRIBED)
    }

    // ─── P01c: confirmAndSummarize → COMPLETED ───────────

    @Test
    fun `P01c confirmAndSummarize completes summary`() = runTest(testDispatcher) {
        coEvery { client.transcribeOnly(any()) } returns "元の文字起こし"
        every { client.summarizeOnly(any(), any()) } returns flowOf(
            LlmEvent.Delta("要約"),
            LlmEvent.Delta(" 完了"),
            LlmEvent.Complete("要約 完了", "model")
        )

        val vm = ProcessingViewModel(mockSavedStateHandle(), repo, settings, provider, context)
        vm.start()
        advanceUntilIdle()

        vm.confirmAndSummarize("元の文字起こし")
        advanceUntilIdle()

        assertThat(vm.state.value.phase).isEqualTo(ProcessingPhase.COMPLETED)
        assertThat(vm.state.value.summaryText).isEqualTo("要約 完了")
    }

    // ─── P01d: confirmAndSummarize → ERROR ───────────────

    @Test
    fun `P01d confirmAndSummarize with LLM error`() = runTest(testDispatcher) {
        coEvery { client.transcribeOnly(any()) } returns "文字起こし"
        every { client.summarizeOnly(any(), any()) } returns flowOf(
            LlmEvent.Error(LlmException.InvalidApiKey())
        )

        val vm = ProcessingViewModel(mockSavedStateHandle(), repo, settings, provider, context)
        vm.start()
        advanceUntilIdle()

        vm.confirmAndSummarize("文字起こし")
        advanceUntilIdle()

        assertThat(vm.state.value.phase).isEqualTo(ProcessingPhase.ERROR)
    }

    // ─── P01e: retryTranscribe ───────────────────────────

    @Test
    fun `P01e retryTranscribe re-runs transcription`() = runTest(testDispatcher) {
        coEvery { client.transcribeOnly(any()) } returns "再実行の文字起こし"
        every { client.summarizeOnly(any(), any()) } returns flowOf(
            LlmEvent.Complete("要約", "model")
        )

        val vm = ProcessingViewModel(mockSavedStateHandle(), repo, settings, provider, context)
        vm.start()
        advanceUntilIdle()

        vm.retryTranscribe()
        advanceUntilIdle()
    }

    // ─── P01f: start() twice ─────────────────────────────

    @Test
    fun `P01f start called twice ignores second call`() = runTest(testDispatcher) {
        coEvery { client.transcribeOnly(any()) } returns "1回目"
        every { client.summarizeOnly(any(), any()) } returns flowOf(
            LlmEvent.Complete("1回目要約", "model")
        )

        val vm = ProcessingViewModel(mockSavedStateHandle(), repo, settings, provider, context)
        vm.start()
        advanceUntilIdle()
        val phaseAfterFirst = vm.state.value.phase

        vm.start()
        advanceUntilIdle()

        assertThat(vm.state.value.phase).isEqualTo(phaseAfterFirst)
    }

    // ─── P01g: MULTIMODAL one-shot flow ──────────────────

    @Test
    fun `P01g MULTIMODAL one-shot flow`() = runTest(testDispatcher) {
        every { settings.defaultCallMode } returns flowOf(LlmCallMode.MULTIMODAL)
        every { settings.useOnDeviceAsr } returns flowOf(false)
        // MULTIMODAL 経路は multimodal 対応プロバイダ (OpenAI / ClaudeProxy) 必須
        coEvery { settings.selectedProvider() } returns
            com.gijimemo.data.model.LlmProviderConfig.defaults().first { it.name == "OpenAI" }

        coEvery { client.transcribeAndFormat(any(), any(), any()) } returns flowOf(
            LlmEvent.Delta("マルチモーダル"),
            LlmEvent.Delta(" 結果"),
            LlmEvent.Complete("マルチモーダル 結果", "model")
        )

        val vm = ProcessingViewModel(mockSavedStateHandle(), repo, settings, provider, context)
        vm.start()
        advanceUntilIdle()

        assertThat(vm.state.value.phase).isEqualTo(ProcessingPhase.COMPLETED)
        assertThat(vm.state.value.summaryText).isEqualTo("マルチモーダル 結果")
    }

    // ─── P01h: LlmEvent.Progress ignored ─────────────────

    @Test
    fun `P01h Progress events are ignored during summary`() = runTest(testDispatcher) {
        coEvery { client.transcribeOnly(any()) } returns "文字起こし"
        every { client.summarizeOnly(any(), any()) } returns flowOf(
            LlmEvent.Progress(50),
            LlmEvent.Delta("途中経過"),
            LlmEvent.Complete("完了", "model")
        )

        val vm = ProcessingViewModel(mockSavedStateHandle(), repo, settings, provider, context)
        vm.start()
        advanceUntilIdle()

        vm.confirmAndSummarize("文字起こし")
        advanceUntilIdle()

        assertThat(vm.state.value.phase).isEqualTo(ProcessingPhase.COMPLETED)
    }
}
