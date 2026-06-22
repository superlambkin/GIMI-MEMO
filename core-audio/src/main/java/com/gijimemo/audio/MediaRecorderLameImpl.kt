// core-audio/src/main/java/com/gijimemo/audio/MediaRecorderLameImpl.kt
package com.gijimemo.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
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

    // ─── PCM ストリーム (v0.7.x) ─────────────────────
    /**
     * MediaRecorder と並走で AudioRecord から取得した PCM チャンク (16kHz / mono / PCM_16BIT)。
     * 約 0.25 秒分 (4096 samples) を 1 チャンクとして逐次 emit する。
     * consumer (Whisper ストリーミング等) は stop() まで購読可能。
     */
    private val _pcmChunkFlow = MutableSharedFlow<ShortArray>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val pcmChunkFlow: SharedFlow<ShortArray> = _pcmChunkFlow.asSharedFlow()

    /** AudioRecord インスタンス（PCM ストリーム用）。MediaRecorder と独立して管理。 */
    private var audioRecord: AudioRecord? = null
    /** PCM 読み取りループを実行中のワーカースレッド。 */
    private var pcmReadThread: Thread? = null
    /** PCM ループ停止フラグ（stop() で読み取りループを安全に抜けるため）。 */
    @Volatile private var pcmReadStop: Boolean = false

    /** v0.7.x: MediaRecorder.start() 直後に AudioRecord を並走起動。 */
    private fun startPcmStream(sampleRate: Int) {
        releasePcmStream()
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "startPcmStream: invalid minBufferSize=$minBuffer, skipping")
            return
        }
        val bufferSize = minBuffer * 2
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "startPcmStream: RECORD_AUDIO not granted: ${e.message}")
            return
        } catch (e: Exception) {
            Log.w(TAG, "startPcmStream: AudioRecord ctor failed: ${e.message}")
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "startPcmStream: AudioRecord not initialized, releasing")
            record.release()
            return
        }
        audioRecord = record
        pcmReadStop = false
        try {
            record.startRecording()
        } catch (e: Exception) {
            Log.w(TAG, "startPcmStream: startRecording failed: ${e.message}")
            record.release()
            audioRecord = null
            return
        }
        pcmReadThread = Thread({
            val chunk = ShortArray(PCM_CHUNK_SAMPLES)
            try {
                while (!pcmReadStop) {
                    val read = try {
                        record.read(chunk, 0, chunk.size)
                    } catch (_: Exception) {
                        -1
                    }
                    if (read <= 0) {
                        if (read == AudioRecord.ERROR_INVALID_OPERATION ||
                            read == AudioRecord.ERROR_BAD_VALUE
                        ) {
                            break
                        }
                        // ERROR (-1) / ERROR_DEAD_OBJECT (-2) は短時間スリープして再試行。
                        if (read < 0) {
                            try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                        }
                        continue
                    }
                    val payload = if (read == chunk.size) chunk else chunk.copyOf(read)
                    _pcmChunkFlow.tryEmit(payload)
                }
            } finally {
                // ループ脱出時に AudioRecord を解放。
                try { record.stop() } catch (_: Exception) {}
                try { record.release() } catch (_: Exception) {}
            }
        }, "PcmReadThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /** v0.7.x: AudioRecord 停止 + 読み取りループ停止。例外で死んでも try-finally 経由で呼ばれる。 */
    private fun releasePcmStream() {
        pcmReadStop = true
        val thread = pcmReadThread
        pcmReadThread = null
        try { thread?.join(200) } catch (_: InterruptedException) {}
        // thread 内の finally で stop/release 済み。null フォールバック用に明示停止も実施。
        audioRecord?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        audioRecord = null
    }

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
            // VOICE_RECOGNITION: 音声認識向け。プラットフォーム AGC を無効化し、
            // 生のマイク信号を最大ゲインで取得。人の声を最も大きく録音できる。
            // VOICE_COMMUNICATION は音量が小さくなりすぎる問題があった。
            setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
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
        // v0.7.x: ストリーミング文字起こし用に PCM を並走取得
        startPcmStream(config.sampleRate)
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
            setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
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
        // v0.7.x: ストリーミング文字起こし用に PCM を並走取得
        startPcmStream(config.sampleRate)
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
            // v0.7.x: MediaRecorder が例外で死んでも PCM ストリームを確実に解放
            try { releasePcmStream() } catch (_: Exception) {}
            rec.reset()
            rec.release()
            recorder = null
            _state.value = RecordingState.Idle
        }
    }

    companion object {
        private const val TAG = "MediaRecorderLameImpl"
        /** v0.7.x: PCM 1 チャンクあたりのサンプル数 (16kHz 換算で約 0.25 秒)。 */
        private const val PCM_CHUNK_SAMPLES = 4096
    }
}
