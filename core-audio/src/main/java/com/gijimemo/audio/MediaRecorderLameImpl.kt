// core-audio/src/main/java/com/gijimemo/audio/MediaRecorderLameImpl.kt
package com.gijimemo.audio

import android.content.Context
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
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

    private var currentConfig: AudioProcessingConfig = AudioProcessingConfig()

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    override val state = _state.asStateFlow()

    private val _amplitude = MutableSharedFlow<Int>(replay = 0, extraBufferCapacity = 16)
    override val amplitude: SharedFlow<Int> = _amplitude.asSharedFlow()

    // ─── VAD (Voice Activity Detection) ──────────────────────
    private var vadEnabled = false
    /** ノイズフロア（無音時の振幅レベル） */
    private var noiseFloor = 0f
    /** 現在の VAD 状態 */
    private var isVoiceActive = false
    /** 連続無音フレーム数 */
    private var silentFrames = 0
    /** VAD 閾値（ノイズフロアからの相対倍率） */
    private val vadThresholdMultiplier = 2.5f
    /** 無音と判定する連続フレーム数（100ms 単位、3秒） */
    private val vadSilentFramesLimit = 30

    private val amplitudePollRunnable = object : Runnable {
        override fun run() {
            try {
                val amp = recorder?.maxAmplitude ?: 0
                _amplitude.tryEmit(amp)
                if (vadEnabled) {
                    updateVad(amp.toFloat())
                }
            } catch (_: Exception) {}
            if (_state.value == RecordingState.Recording) {
                handler?.postDelayed(this, 100)
            }
        }
    }
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** MediaRecorder.getAudioSessionId() は @hide のためリフレクションで取得 */
    private fun MediaRecorder.reflectAudioSessionId(): Int = try {
        val method = MediaRecorder::class.java.getMethod("getAudioSessionId")
        method.invoke(this) as Int
    } catch (_: Exception) { 0 }

    /**
     * 設定に基づき NoiseSuppressor / AGC を有効化。
     * 端末が対応していない場合は警告ログを出してスキップ。
     * v0.7.2: silent catch を撤廃し、診断ログを追加。
     */
    private fun enableAudioEffects(audioSessionId: Int) {
        if (audioSessionId == 0) {
            Log.w(TAG, "enableAudioEffects: audioSessionId=0 (getAudioSessionId failed), skipping")
            return
        }
        if (currentConfig.noiseSuppressor) {
            if (NoiseSuppressor.isAvailable()) {
                try {
                    NoiseSuppressor.create(audioSessionId)?.enabled = true
                    Log.i(TAG, "NoiseSuppressor enabled")
                } catch (e: Exception) {
                    Log.w(TAG, "NoiseSuppressor.create failed: ${e.message}")
                }
            } else {
                Log.w(TAG, "NoiseSuppressor not available on this device; NS setting ignored")
            }
        }
        if (currentConfig.automaticGainControl) {
            if (AutomaticGainControl.isAvailable()) {
                @Suppress("DEPRECATION")
                try {
                    AutomaticGainControl.create(audioSessionId)?.enabled = true
                    Log.i(TAG, "AutomaticGainControl enabled")
                } catch (e: Exception) {
                    Log.w(TAG, "AutomaticGainControl.create failed: ${e.message}")
                }
            } else {
                Log.w(TAG, "AutomaticGainControl not available; rely on VOICE_COMMUNICATION platform AGC")
            }
        }
    }

    /**
     * 簡易 VAD: 振幅を監視し、ノイズフロアを基準に声活動を判定。
     * 100ms ごとに amplitudePollRunnable から呼ばれる。
     */
    private fun updateVad(amplitude: Float) {
        // ノイズフロアの更新（緩やかに追従）
        if (amplitude > 0f) {
            noiseFloor = noiseFloor * 0.95f + amplitude * 0.05f
        }
        val threshold = noiseFloor * vadThresholdMultiplier
        if (amplitude > threshold && amplitude > 50) {
            // 声活動中
            silentFrames = 0
            if (!isVoiceActive) {
                isVoiceActive = true
                Log.v(TAG, "VAD: voice started (amp=$amplitude, floor=$noiseFloor)")
            }
        } else {
            silentFrames++
            if (silentFrames >= vadSilentFramesLimit && isVoiceActive) {
                isVoiceActive = false
                Log.v(TAG, "VAD: voice ended (amp=$amplitude, silentFrames=$silentFrames)")
            }
        }
    }

    override suspend fun start(outputPath: String, config: AudioProcessingConfig) {
        if (_state.value != RecordingState.Idle && _state.value != RecordingState.Stopped) {
            throw IllegalStateException("Cannot start in state ${_state.value}")
        }
        currentConfig = config
        vadEnabled = config.voiceActivityDetection
        noiseFloor = 0f; isVoiceActive = false; silentFrames = 0

        // Ensure parent dir
        File(outputPath).parentFile?.mkdirs()

        @Suppress("DEPRECATION")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
        rec.apply {
            // Phase 2: VOICE_COMMUNICATION はプラットフォームレベルで AEC + NS が有効になる
            setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(config.sampleRate)
            setAudioEncodingBitRate(config.bitRate)
            setOutputFile(outputPath)
            prepare()
        }
        enableAudioEffects(rec.reflectAudioSessionId())
        rec.start()
        recorder = rec
        _currentFilePath = outputPath
        _state.value = RecordingState.Recording
        handler.post(amplitudePollRunnable)
    }

    override suspend fun startWithFileDescriptor(outputFd: FileDescriptor, config: AudioProcessingConfig) {
        if (_state.value != RecordingState.Idle && _state.value != RecordingState.Stopped) {
            throw IllegalStateException("Cannot start in state ${_state.value}")
        }
        currentConfig = config
        vadEnabled = config.voiceActivityDetection
        noiseFloor = 0f; isVoiceActive = false; silentFrames = 0

        @Suppress("DEPRECATION")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
        rec.apply {
            setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(config.sampleRate)
            setAudioEncodingBitRate(config.bitRate)
            setOutputFile(outputFd)
            prepare()
        }
        enableAudioEffects(rec.reflectAudioSessionId())
        rec.start()
        recorder = rec
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
            if (vadEnabled) {
                Log.i(TAG, "VAD: noiseFloor=$noiseFloor, lastVoiceActive=$isVoiceActive")
            }
        } catch (_: Exception) {
        } finally {
            rec.reset()
            rec.release()
            recorder = null
            _state.value = RecordingState.Idle
        }
    }

    companion object {
        private const val TAG = "MediaRecorderLameImpl"
    }
}
