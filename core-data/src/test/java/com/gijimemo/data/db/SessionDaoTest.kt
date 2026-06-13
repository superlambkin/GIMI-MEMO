package com.gijimemo.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionDaoTest {
    private lateinit var db: GijiMemoDatabase
    private lateinit var dao: SessionDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GijiMemoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.sessionDao()
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun `insert and read by id`() = runTest {
        val entity = SessionEntity(
            id = "1", title = "A", createdAt = 100L, durationMs = 1000L,
            audioFilePath = "a.mp3", audioSizeBytes = 100L, status = "STOPPED"
        )
        dao.insert(entity)
        val loaded = dao.getById("1")
        assertThat(loaded).isEqualTo(entity)
    }

    @Test
    fun `observeAll returns inserted in createdAt desc order`() = runTest {
        dao.insert(SessionEntity("1", "B", 200L, 0L, "a", 0L, "STOPPED"))
        dao.insert(SessionEntity("2", "A", 100L, 0L, "b", 0L, "STOPPED"))
        val list = dao.observeAll().first()
        assertThat(list.map { it.id }).containsExactly("1", "2").inOrder()
    }
}