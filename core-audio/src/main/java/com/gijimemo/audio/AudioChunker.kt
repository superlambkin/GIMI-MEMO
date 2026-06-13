// core-audio/src/main/java/com/gijimemo/audio/AudioChunker.kt
package com.gijimemo.audio

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 长录音切片决策。实际物理切片（用 ffmpeg 切割 MP3）见 RecordingViewModel 层调用。
 */
@Singleton
class AudioChunker @Inject constructor() {
    /** 是否需要切片（0 = 不切片） */
    fun shouldChunk(durationMs: Long, chunkMinutes: Int): Boolean {
        if (chunkMinutes <= 0) return false
        return durationMs > chunkMinutes * 60_000L
    }

    /** 切片数量（短录音 = 1） */
    fun chunkCount(durationMs: Long, chunkMinutes: Int): Int {
        if (chunkMinutes <= 0) return 1
        val chunkMs = chunkMinutes * 60_000L
        return ((durationMs + chunkMs - 1) / chunkMs).toInt().coerceAtLeast(1)
    }
}
