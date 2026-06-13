// core-audio/src/test/java/com/gijimemo/audio/AudioChunkerTest.kt
package com.gijimemo.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class AudioChunkerTest {
    @Test
    fun `shouldChunk returns false for short audio`() {
        val chunker = AudioChunker()
        assertThat(chunker.shouldChunk(durationMs = 10 * 60 * 1000L, chunkMinutes = 25)).isFalse()
    }

    @Test
    fun `shouldChunk returns true for long audio`() {
        val chunker = AudioChunker()
        assertThat(chunker.shouldChunk(durationMs = 60 * 60 * 1000L, chunkMinutes = 25)).isTrue()
    }

    @Test
    fun `chunkCount returns 1 for short audio`() {
        val chunker = AudioChunker()
        assertThat(chunker.chunkCount(durationMs = 10 * 60 * 1000L, chunkMinutes = 25)).isEqualTo(1)
    }

    @Test
    fun `chunkCount returns 3 for 60min audio with 25min chunks`() {
        val chunker = AudioChunker()
        assertThat(chunker.chunkCount(durationMs = 60 * 60 * 1000L, chunkMinutes = 25)).isEqualTo(3)
    }

    @Test
    fun `chunkCount rounds up for partial last chunk`() {
        val chunker = AudioChunker()
        // 26 minutes with 25 min chunks = 2 chunks
        assertThat(chunker.chunkCount(durationMs = 26 * 60 * 1000L, chunkMinutes = 25)).isEqualTo(2)
    }
}
