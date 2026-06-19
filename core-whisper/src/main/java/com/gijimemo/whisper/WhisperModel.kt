package com.gijimemo.whisper

import java.io.File

/**
 * High-level on-device Whisper transcription API.
 *
 * Lifecycle: load(...) → transcribe*(...) → release()
 */
interface WhisperModel {

    /** Load a GGML model file. Must call once before transcribe. */
    fun load(modelFile: File)

    /** Transcribe PCM float data (16kHz mono, normalized -1.0..1.0). Returns full text. */
    fun transcribe(audioData: FloatArray): String

    /** Transcribe and return segmented results with timing. */
    fun transcribeWithTimestamps(audioData: FloatArray): List<WhisperSegment>

    /** Transcribe a WAV file (16-bit 16kHz mono). Loads, decodes, transcribes. */
    fun transcribeFile(wavFile: File): String

    /**
     * Transcribe with explicit language hint ("ja" / "zh" / "en" など。null なら自動検出)。
     * デフォルト実装は language を無視し既存メソッドにフォールバックする
     * (テスト用ダミー実装の互換性のため)。
     */
    fun transcribeFile(wavFile: File, language: String?): String = transcribeFile(wavFile)

    /** Release native resources. */
    fun release()

    /** Whether a model is currently loaded. */
    val isLoaded: Boolean
}

data class WhisperSegment(
    val text: String,
    val startMs: Long,
    val endMs: Long
)
