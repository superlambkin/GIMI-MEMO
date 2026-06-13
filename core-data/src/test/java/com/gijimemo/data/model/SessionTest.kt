package com.gijimemo.data.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SessionTest {
    @Test
    fun `Session copy with new status keeps other fields`() {
        val original = Session(
            id = "abc",
            title = "Test",
            createdAt = 1000L,
            durationMs = 60_000L,
            audioFilePath = "/path/audio.mp3",
            audioSizeBytes = 1024L,
            status = SessionStatus.RECORDING
        )
        val updated = original.copy(status = SessionStatus.READY)
        assertThat(updated.id).isEqualTo("abc")
        assertThat(updated.status).isEqualTo(SessionStatus.READY)
    }

    @Test
    fun `SessionStatus enum has all expected values`() {
        val values = SessionStatus.entries.map { it.name }
        assertThat(values).containsExactly(
            "RECORDING", "STOPPED", "TRANSCRIBING", "READY", "SHARED", "ERROR"
        )
    }
}