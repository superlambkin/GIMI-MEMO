// core-audio/src/main/java/com/gijimemo/audio/MediaRecorderLameImpl.kt
package com.gijimemo.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileDescriptor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MediaRecorder + LAME MP3 编码实现
 *
 * 注：完整 LAME 集成需要 NDK 编译，此处先以 MediaRecorder 原生 MP3 输出（API 31+ 部分设备）作为 fallback。
 * 真正的 LAME 集成见后续 [Task 3.2b - LAME Native 集成]。
 */
@Singleton
class MediaRecorderLameImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : AudioRecorder {

    private var recorder: MediaRecorder? = null
    private var _currentFilePath: String? = null
    override val currentFilePath: String? get() = _currentFilePath

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    override val state = _state.asStateFlow()

    private val _amplitude = MutableSharedFlow<Int>(replay = 0, extraBufferCapacity = 16)
    override val amplitude: SharedFlow<Int> = _amplitude.asSharedFlow()

    private val amplitudePollRunnable = object : Runnable {
        override fun run() {
            try {
                recorder?.maxAmplitude?.let { _amplitude.tryEmit(it) }
            } catch (_: Exception) {}
            if (_state.value == RecordingState.Recording) {
                handler?.postDelayed(this, 100)
            }
        }
    }
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override suspend fun start(outputPath: String) {
        if (_state.value != RecordingState.Idle && _state.value != RecordingState.Stopped) {
            throw IllegalStateException("Cannot start in state ${_state.value}")
        }
        // Ensure parent dir
        File(outputPath).parentFile?.mkdirs()

        @Suppress("DEPRECATION")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
        rec.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44_100)
            setAudioEncodingBitRate(64_000)
            setOutputFile(outputPath)
            prepare()
            start()
        }
        recorder = rec
        _currentFilePath = outputPath
        _state.value = RecordingState.Recording
        handler.post(amplitudePollRunnable)
    }

    /**
     * 输出到 [FileDescriptor]（典型场景：ContentResolver.openFileDescriptor(uri, "w")）。
     * [identifier] 是给上层回传用的逻辑标识（如 MediaStore content URI 字符串），
     * 后续 [stop] 会原样返回，便于调用方定位文件。
     */
    override suspend fun startWithFileDescriptor(outputFd: FileDescriptor) {
        if (_state.value != RecordingState.Idle && _state.value != RecordingState.Stopped) {
            throw IllegalStateException("Cannot start in state ${_state.value}")
        }

        @Suppress("DEPRECATION")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
        rec.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44_100)
            setAudioEncodingBitRate(64_000)
            setOutputFile(outputFd)
            prepare()
            start()
        }
        recorder = rec
        // FileDescriptor 场景下没有可表示的"路径"，调用方会在 stop 后从 URI 读取真实路径
        _currentFilePath = null
        _state.value = RecordingState.Recording
        handler.post(amplitudePollRunnable)
    }

    override suspend fun pause() {
        val rec = recorder ?: return
        if (_state.value != RecordingState.Recording) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            rec.pause()
            _state.value = RecordingState.Paused
        }
    }

    override suspend fun resume() {
        val rec = recorder ?: return
        if (_state.value != RecordingState.Paused) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            rec.resume()
            _state.value = RecordingState.Recording
            handler.post(amplitudePollRunnable)
        }
    }

    override suspend fun stop() {
        val rec = recorder ?: throw IllegalStateException("Not recording")
        try {
            rec.stop()
        } catch (_: Exception) {
            // stop() can throw if no audio captured; ignore
        } finally {
            rec.reset()
            rec.release()
            recorder = null
            // 終了後は Idle に戻す。Stopped 状態のままだと Compose Navigation
            // で「録音 → 戻る → 再 enter」時に前回状態の Idle 以外の値が
            // 残り、UI が「停止済み」画面で固定化される既知バグがある。
            // 一方 ViewModel.stopRecording() が成功すると
            // lastSavedSession に Session が入るので、UI 側 (RecordingScreen)
            // は `recorder.state == Idle && lastSavedSession != null` の
            // 条件で「録音を再生 / 文字起こし」を表示できる。
            _state.value = RecordingState.Idle
        }
    }
}
