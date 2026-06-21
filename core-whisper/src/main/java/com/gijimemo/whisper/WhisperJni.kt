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

        /** Load a GGML model from file. Returns native context pointer.
         *  v0.7.2: use_gpu フラグ追加 (Whisper+要約経路のみ true)。
         */
        @JvmStatic external fun initContext(modelPath: String, useGpu: Boolean): Long

        /** Release native model resources. */
        @JvmStatic external fun freeContext(contextPtr: Long)

        /** Run full transcription on PCM float data (16kHz mono, normalized).
         *  @param language "ja" / "zh" / "en" など。null なら自動検出。
         */
        @JvmStatic external fun fullTranscribe(
            contextPtr: Long,
            numThreads: Int,
            language: String?,
            audioData: FloatArray
        )

        /** v0.7.2: 指定時間窓 (offset_ms / duration_ms) の文字起こし。
         *  30秒単位分割 + 2秒オーバーラップ処理用。
         */
        @JvmStatic external fun fullTranscribeChunked(
            contextPtr: Long,
            numThreads: Int,
            language: String?,
            audioData: FloatArray,
            offsetMs: Int,
            durationMs: Int
        )

        /** Number of text segments from last transcription. */
        @JvmStatic external fun getTextSegmentCount(contextPtr: Long): Int

        /** Get the i-th segment text. */
        @JvmStatic external fun getTextSegment(contextPtr: Long, index: Int): String
    }
}

// ─── Static facade ────────────────────────────────────────────
//
// Allow callers to use `WhisperJni.initContext(...)` exactly as before.
// The actual native functions live in `WhisperJni.Companion`; this object
// is a thin pass-through that preserves the calling convention while
// making the JNI symbol names stable (`initContext`, not
// `initContext$core_whisper_release`).
object WhisperJniBridge {
    fun initContext(modelPath: String, useGpu: Boolean = false): Long = WhisperJni.initContext(modelPath, useGpu)
    fun freeContext(contextPtr: Long): Unit = WhisperJni.freeContext(contextPtr)
    fun fullTranscribe(contextPtr: Long, numThreads: Int, language: String?, audioData: FloatArray): Unit =
        WhisperJni.fullTranscribe(contextPtr, numThreads, language, audioData)
    fun fullTranscribeChunked(
        contextPtr: Long,
        numThreads: Int,
        language: String?,
        audioData: FloatArray,
        offsetMs: Int,
        durationMs: Int
    ): Unit = WhisperJni.fullTranscribeChunked(contextPtr, numThreads, language, audioData, offsetMs, durationMs)
    fun getTextSegmentCount(contextPtr: Long): Int = WhisperJni.getTextSegmentCount(contextPtr)
    fun getTextSegment(contextPtr: Long, index: Int): String = WhisperJni.getTextSegment(contextPtr, index)
}
