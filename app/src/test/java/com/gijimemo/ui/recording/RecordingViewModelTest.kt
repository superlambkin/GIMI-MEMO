package com.gijimemo.ui.recording

import com.gijimemo.audio.AudioRecorder
import com.gijimemo.audio.RecordingState
import com.gijimemo.data.model.Session
import com.gijimemo.data.model.SessionStatus
import com.gijimemo.data.repository.SessionRepository
import com.gijimemo.data.repository.SettingsRepository
import com.gijimemo.llm.OnDeviceWhisperClient
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingViewModelTest {
    private val recorder: AudioRecorder = mockk(relaxed = true)
    private val repo: SessionRepository = mockk(relaxed = true)
    private val settings: SettingsRepository = mockk(relaxed = true)
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { recorder.state } returns MutableStateFlow(RecordingState.Idle)
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `initial state is Idle`() {
        val vm = RecordingViewModel(recorder, repo, settings, mockk(relaxed = true), mockk(relaxed = true))
        assertThat(vm.state.value).isEqualTo(RecordingState.Idle)
    }

    @Test
    fun `initial audioFilePath is null`() {
        val vm = RecordingViewModel(recorder, repo, settings, mockk(relaxed = true), mockk(relaxed = true))
        assertThat(vm.audioFilePath).isNull()
    }

    @Test
    fun `initial playbackState is Idle`() {
        val vm = RecordingViewModel(recorder, repo, settings, mockk(relaxed = true), mockk(relaxed = true))
        assertThat(vm.playbackState.value).isEqualTo(PlaybackState.Idle)
    }

    @Test
    fun `stopRecording returns null when no session was started`() = runTest {
        coEvery { recorder.stop() } returns Unit
        val vm = RecordingViewModel(recorder, repo, settings, mockk(relaxed = true), mockk(relaxed = true))
        val session = vm.stopRecording(title = "Test", durationMs = 12_345L)
        assertThat(session).isNull()
    }
}
