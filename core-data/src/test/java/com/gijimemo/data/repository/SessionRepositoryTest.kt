package com.gijimemo.data.repository

import com.gijimemo.data.db.SessionDao
import com.gijimemo.data.db.SessionEntity
import com.gijimemo.data.db.toEntity
import com.gijimemo.data.model.Session
import com.gijimemo.data.model.SessionStatus
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SessionRepositoryTest {
    private val dao: SessionDao = mockk(relaxed = true)
    private val repo = SessionRepository(dao)

    @Test
    fun `observeAll maps entities to domain`() = runTest {
        coEvery { dao.observeAll() } returns flowOf(
            listOf(SessionEntity("1", "t", 1L, 0L, "a", 0L, "STOPPED"))
        )
        val list = repo.observeAll().first()
        assertThat(list).hasSize(1)
        assertThat(list[0].id).isEqualTo("1")
    }

    @Test
    fun `save inserts via dao`() = runTest {
        val session = Session("1", "t", 1L, 0L, "a", 0L, SessionStatus.STOPPED)
        repo.save(session)
        coVerify { dao.insert(session.toEntity()) }
    }

    @Test
    fun `updateStatus calls dao updateStatus`() = runTest {
        repo.updateStatus("1", SessionStatus.ERROR, "boom")
        coVerify { dao.updateStatus("1", "ERROR", "boom") }
    }
}