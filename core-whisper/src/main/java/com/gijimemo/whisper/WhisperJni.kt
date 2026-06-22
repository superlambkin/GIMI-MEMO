package com.gijimemo.whisper

/**
 * JNI bridge to whisper.cpp native library.
 *
 * Important: must be a `class` (not `internal object`). `internal object` in
 * Kotlin gets a `$moduleName` suffix on its members, which would change the
 * JNI symbol name (e.g. `initContext$core_whisper_release`) and break the
 * native `Java_com_gijimemo_whisper_WhisperJni_initContext` lookup.
 */
class WhisperJni {
    companion object {
        init {
            System.loadLibrary("whisper")
        }

        /** Load a GGML model from file. Returns native context pointer. */
        @JvmStatic external fun initContext(modelPath: String, useGpu: Boolean): Long

        /** Release native model resources. */
        @JvmStatic external fun freeContext(contextPtr: Long)

        /**
         * Run full transcription on PCM float data (16kHz mono, normalized).
         * @param language "ja" / "zh" / "en" など。null なら自動検出。
         * @param vadModelPath Silero VAD モデルパス。null で VAD 無効。
         */
        @JvmStatic external fun fullTranscribe(
            contextPtr: Long,
            numThreads: Int,
            language: String?,
            audioData: FloatArray,
            vadModelPath: String? = null
        )

        /**
         * v0.7.2: 指定時間窓 (offset_ms / duration_ms) の文字起こし。
         * @param vadModelPath Silero VAD モデルパス。null で VAD 無効。
         */
        @JvmStatic external fun fullTranscribeChunked(
            contextPtr: Long,
            numThreads: Int,
            language: String?,
            audioData: FloatArray,
            offsetMs: Int,
            durationMs: Int,
            vadModelPath: String? = null
        )

        /** Number of text segments from last transcription. */
        @JvmStatic external fun getTextSegmentCount(contextPtr: Long): Int

        /** Get the i-th segment text. */
        @JvmStatic external fun getTextSegment(contextPtr: Long, index: Int): String

        /**
         * v0.7.x Phase 2: i 番目のセグメント開始時刻 (ms 単位)。
         * whisper_full_get_segment_t0 は centiseconds (10ms) を返すため、JNI 側で ×10 して ms に変換する。
         */
        @JvmStatic external fun getSegmentTimestamp0(contextPtr: Long, segmentIndex: Int): Long

        /** v0.7.x Phase 2: i 番目のセグメント終了時刻 (ms 単位)。 */
        @JvmStatic external fun getSegmentTimestamp1(contextPtr: Long, segmentIndex: Int): Long
    }
}

// ─── Static facade ──────────────────────────────────────────────────────
object WhisperJniBridge {
    fun initContext(modelPath: String, useGpu: Boolean = false): Long = WhisperJni.initContext(modelPath, useGpu)
    fun freeContext(contextPtr: Long): Unit = WhisperJni.freeContext(contextPtr)
    fun fullTranscribe(contextPtr: Long, numThreads: Int, language: String?, audioData: FloatArray, vadModelPath: String? = null): Unit =
        WhisperJni.fullTranscribe(contextPtr, numThreads, language, audioData, vadModelPath)
    fun fullTranscribeChunked(contextPtr: Long, numThreads: Int, language: String?, audioData: FloatArray, offsetMs: Int, durationMs: Int, vadModelPath: String? = null): Unit =
        WhisperJni.fullTranscribeChunked(contextPtr, numThreads, language, audioData, offsetMs, durationMs, vadModelPath)
    fun getTextSegmentCount(contextPtr: Long): Int = WhisperJni.getTextSegmentCount(contextPtr)
    fun getTextSegment(contextPtr: Long, index: Int): String = WhisperJni.getTextSegment(contextPtr, index)
    fun getSegmentTimestamp0(contextPtr: Long, segmentIndex: Int): Long = WhisperJni.getSegmentTimestamp0(contextPtr, segmentIndex)
    fun getSegmentTimestamp1(contextPtr: Long, segmentIndex: Int): Long = WhisperJni.getSegmentTimestamp1(contextPtr, segmentIndex)
}
