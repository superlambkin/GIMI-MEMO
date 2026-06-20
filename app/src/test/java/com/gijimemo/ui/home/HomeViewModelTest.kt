package com.gijimemo.ui.home

import android.content.Context
import android.net.Uri
import android.util.Log
import com.gijimemo.data.model.Session
import com.gijimemo.data.model.SessionStatus
import com.gijimemo.data.repository.SessionRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val repo: SessionRepository = mockk()
    private val context: Context = mockk(relaxed = true)
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(Log::class)
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `sessions state contains all sessions`() = runTest {
        every { repo.observeAll() } returns flowOf(
            listOf(
                Session("1", "A", 100L, 0L, "a.mp3", 0L, SessionStatus.READY),
                Session("2", "B", 200L, 0L, "b.mp3", 0L, SessionStatus.SHARED)
            )
        )
        val tmpDir = System.getProperty("java.io.tmpdir")
        every { context.filesDir } returns File(tmpDir)
        coEvery { repo.save(any()) } returns Unit

        val vm = HomeViewModel(repo, context)
        val sessions = vm.sessions.first()
        assertThat(sessions).hasSize(2)
    }

    @Test
    fun `cleanupStuckSessions resets TRANSCRIBING sessions to ERROR`() = runTest(testDispatcher) {
        val stuck1 = Session("1", "Stuck", 100L, 0L, "a.mp3", 0L, SessionStatus.TRANSCRIBING)
        val stuck2 = Session("2", "Stuck2", 200L, 0L, "b.mp3", 0L, SessionStatus.TRANSCRIBING)
        val normal = Session("3", "Normal", 300L, 0L, "c.mp3", 0L, SessionStatus.READY)

        every { repo.observeAll() } returns flowOf(listOf(stuck1, stuck2, normal))
        coEvery { repo.updateStatus(any(), any(), any()) } returns Unit

        val vm = HomeViewModel(repo, context)
        vm.cleanupStuckSessions()

        // ※initでもcleanupStuckSessionsが呼ばれるためatLeastを使用
        coVerify(atLeast = 1) { repo.updateStatus("1", SessionStatus.ERROR, any()) }
        coVerify(atLeast = 1) { repo.updateStatus("2", SessionStatus.ERROR, any()) }
        coVerify(inverse = true) { repo.updateStatus("3", SessionStatus.ERROR, any()) }
    }

    @Test
    fun `cleanupStuckSessions does NOT reset normal sessions`() = runTest(testDispatcher) {
        val ready = Session("1", "Ready", 100L, 0L, "a.mp3", 0L, SessionStatus.READY)
        val shared = Session("2", "Shared", 200L, 0L, "b.mp3", 0L, SessionStatus.SHARED)
        val stopped = Session("3", "Stopped", 300L, 0L, "c.mp3", 0L, SessionStatus.STOPPED)
        val error = Session("4", "Error", 400L, 0L, "d.mp3", 0L, SessionStatus.ERROR)

        every { repo.observeAll() } returns flowOf(listOf(ready, shared, stopped, error))
        coEvery { repo.updateStatus(any(), any(), any()) } returns Unit

        val vm = HomeViewModel(repo, context)
        vm.cleanupStuckSessions()

        coVerify(inverse = true) { repo.updateStatus(any(), SessionStatus.ERROR, any()) }
    }

    @Test
    fun `importTxtFile creates session with rawTranscript`() = runTest(testDispatcher) {
        val uri: Uri = mockk()
        val tmpDir = System.getProperty("java.io.tmpdir")
        every { context.filesDir } returns File(tmpDir)
        every { context.contentResolver.openInputStream(any()) } returns ByteArrayInputStream("Hello World".toByteArray())
        every { repo.observeAll() } returns flowOf(emptyList())

        val sessionSlot = slot<Session>()
        coEvery { repo.save(capture(sessionSlot)) } returns Unit
        coEvery { repo.updateStatus(any(), any(), any()) } returns Unit

        val deferredId = CompletableDeferred<String?>()
        val vm = HomeViewModel(repo, context)
        vm.importTxtFile(uri) { id -> deferredId.complete(id) }

        // Dispatchers.IO の完了を待つ
        withContext(Dispatchers.IO) { } // IOスレッドプールの空きスロットまで待機
        val capturedId = deferredId.await()

        assertThat(capturedId).isNotNull()
        assertThat(sessionSlot.captured.rawTranscript).isEqualTo("Hello World")
        assertThat(sessionSlot.captured.status).isEqualTo(SessionStatus.STOPPED)
        assertThat(sessionSlot.captured.audioFilePath).isEmpty()
        assertThat(sessionSlot.captured.title).startsWith("TXT ")
    }
}
