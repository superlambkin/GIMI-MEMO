package com.gijimemo.ui.home

import com.gijimemo.data.model.Session
import com.gijimemo.data.model.SessionStatus
import com.gijimemo.data.repository.SessionRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val repo: SessionRepository = mockk()
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { repo.observeAll() } returns flowOf(
            listOf(
                Session("1", "A", 100L, 0L, "a.mp3", 0L, SessionStatus.READY),
                Session("2", "B", 200L, 0L, "b.mp3", 0L, SessionStatus.SHARED)
            )
        )
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `sessions state contains all sessions`() = runTest {
        val vm = HomeViewModel(repo)
        val sessions = vm.sessions.first()
        assertThat(sessions).hasSize(2)
    }
}
