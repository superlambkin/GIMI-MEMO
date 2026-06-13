// core-audio/src/main/java/com/gijimemo/audio/AudioRecorder.kt
package com.gijimemo.audio

import kotlinx.coroutines.flow.Flow

interface AudioRecorder {
    /** 录音状态流 */
    val state: Flow<RecordingState>

    /** 实时振幅（用于波形） */
    val amplitude: Flow<Int>

    /** 当前录音文件路径，未录音时 null */
    val currentFilePath: String?

    /** 开始录音，指定输出 MP3 路径 */
    suspend fun start(outputPath: String)

    /** 暂停 */
    suspend fun pause()

    /** 继续（从暂停恢复） */
    suspend fun resume()

    /** 停止并返回最终文件路径 */
    suspend fun stop(): String
}

sealed class RecordingState {
    object Idle : RecordingState()
    object Recording : RecordingState()
    object Paused : RecordingState()
    object Stopped : RecordingState()
    data class Error(val cause: Throwable) : RecordingState()
}
