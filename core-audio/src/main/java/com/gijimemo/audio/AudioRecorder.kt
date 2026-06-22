// core-audio/src/main/java/com/gijimemo/audio/AudioRecorder.kt
package com.gijimemo.audio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import java.io.FileDescriptor

interface AudioRecorder {
    /** 录音状态流 */
    val state: Flow<RecordingState>

    /** 实时振幅（用于波形） */
    val amplitude: Flow<Int>

    /**
     * v0.7.x PCM ストリーム: MediaRecorder 録音と並走で AudioRecord から取得した
     * 16kHz / mono / PCM_16BIT のチャンクをリアルタイム配信する。
     * ストリーミング文字起こし (Whisper) での利用を想定。
     * 起動失敗（permission 不足等）でも Flow は open のまま、stop() まで再試行可能。
     */
    val pcmChunkFlow: SharedFlow<ShortArray>

    /** 当前录音文件路径，未录音时 null */
    val currentFilePath: String?

    /** 开始录音，指定输出文件路径（App 私有目录场景） */
    suspend fun start(outputPath: String, config: AudioProcessingConfig = AudioProcessingConfig())

    /**
     * 开始录音，输出到 [FileDescriptor]（MediaStore / ContentProvider 场景）。
     * 实现方需负责 [FileDescriptor] 的生命周期。
     */
    suspend fun startWithFileDescriptor(outputFd: FileDescriptor, config: AudioProcessingConfig = AudioProcessingConfig())

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
