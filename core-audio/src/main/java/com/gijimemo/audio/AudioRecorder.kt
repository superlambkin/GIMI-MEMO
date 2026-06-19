// core-audio/src/main/java/com/gijimemo/audio/AudioRecorder.kt
package com.gijimemo.audio

import kotlinx.coroutines.flow.Flow
import java.io.FileDescriptor

interface AudioRecorder {
    /** 录音状态流 */
    val state: Flow<RecordingState>

    /** 实时振幅（用于波形） */
    val amplitude: Flow<Int>

    /** 当前录音文件路径，未录音时 null */
    val currentFilePath: String?

    /** 开始录音，指定输出文件路径（App 私有目录场景） */
    suspend fun start(outputPath: String)

    /**
     * 开始录音，输出到 [FileDescriptor]（MediaStore / ContentProvider 场景）。
     * 实现方需负责 [FileDescriptor] 的生命周期。
     */
    suspend fun startWithFileDescriptor(outputFd: FileDescriptor)

    /** 暂停 */
    suspend fun pause()

    /** 继续（从暂停恢复） */
    suspend fun resume()

    /**
     * 停止录音并释放资源。
     * 最终录音位置由调用方在 [start] 之前维护（见 [RecordingViewModel]），
     * 录音器不感知具体的文件标识（路径或 content URI）。
     */
    suspend fun stop()
}

sealed class RecordingState {
    object Idle : RecordingState()
    object Recording : RecordingState()
    object Paused : RecordingState()
    object Stopped : RecordingState()
    data class Error(val cause: Throwable) : RecordingState()
}
