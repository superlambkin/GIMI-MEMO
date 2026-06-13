package com.gijimemo.data.db

import com.gijimemo.data.model.Session
import com.gijimemo.data.model.SessionStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SessionEntityTest {
    @Test
    fun `SessionEntity toDomain maps all fields`() {
        val entity = SessionEntity(
            id = "x", title = "t", createdAt = 1L, durationMs = 2L,
            audioFilePath = "a", audioSizeBytes = 3L,
            status = SessionStatus.READY.name,
            transcriptMd = "md", docxFilePath = "d", mdFilePath = "m", txtFilePath = "x",
            llmProvider = "MiniMax", llmModel = "MiniMax-M3", errorMessage = null
        )
        val session = entity.toDomain()
        assertThat(session).isEqualTo(
            Session(
                id = "x", title = "t", createdAt = 1L, durationMs = 2L,
                audioFilePath = "a", audioSizeBytes = 3L,
                status = SessionStatus.READY,
                transcriptMd = "md", docxFilePath = "d", mdFilePath = "m", txtFilePath = "x",
                llmProvider = "MiniMax", llmModel = "MiniMax-M3", errorMessage = null
            )
        )
    }

    @Test
    fun `Session toEntity maps all fields`() {
        val session = Session(
            id = "x", title = "t", createdAt = 1L, durationMs = 2L,
            audioFilePath = "a", audioSizeBytes = 3L, status = SessionStatus.SHARED
        )
        val entity = session.toEntity()
        assertThat(entity.id).isEqualTo("x")
        assertThat(entity.status).isEqualTo("SHARED")
    }
}