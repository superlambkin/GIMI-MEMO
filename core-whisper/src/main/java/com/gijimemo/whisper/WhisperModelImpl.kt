package com.gijimemo.whisper

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer

/**
 * Implementation of [WhisperModel] backed by whisper.cpp via JNI.
 *
 * Thread safety: All [transcribe]* calls must run on a background thread.
 * The native whisper_full() is blocking and may take seconds to minutes.
 */
class WhisperModelImpl : WhisperModel {

    private var nativePtr: Long = 0
    // CPUコア数 - 1 (OSとバックグラウンド用に1コア予約)。1コア端末では下限1にクランプ。
    private val numThreads: Int = (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1)

    override val isLoaded: Boolean get() = nativePtr != 0L

    override fun load(modelFile: File, useGpu: Boolean) {
        if (nativePtr != 0L) release()

        if (!modelFile.exists()) {
            throw IllegalStateException("Model file not found: ${modelFile.absolutePath}")
        }
        Log.d(TAG, "Loading model: ${modelFile.absolutePath} (useGpu=$useGpu)")

        val ptr = WhisperJniBridge.initContext(modelFile.absolutePath, useGpu)
        if (ptr == 0L) {
            throw IllegalStateException("Failed to load Whisper model: ${modelFile.absolutePath}")
        }
        nativePtr = ptr
        Log.d(TAG, "Model loaded, nativePtr=$ptr")
    }

    override fun transcribe(audioData: FloatArray): String {
        return transcribe(audioData, language = null)
    }

    /**
     * Transcribe with explicit language hint.
     * @param language "ja" / "zh" / "en" など。null なら whisper 自動検出。
     */
    override fun transcribe(audioData: FloatArray, language: String?): String {
        checkLoaded()
        Log.d(TAG, "Transcribing ${audioData.size} samples with $numThreads threads, lang=${language ?: "auto"}")
        WhisperJniBridge.fullTranscribe(nativePtr, numThreads, language, audioData)

        val count = WhisperJniBridge.getTextSegmentCount(nativePtr)
        val sb = StringBuilder()
        for (i in 0 until count) {
            if (sb.isNotEmpty()) sb.append(" ")
            sb.append(WhisperJniBridge.getTextSegment(nativePtr, i))
        }
        val text = sb.toString().trim()
        Log.d(TAG, "Transcription complete: ${text.length} chars, $count segments")
        return text
    }

    override fun transcribeWithTimestamps(audioData: FloatArray): List<WhisperSegment> {
        checkLoaded()
        WhisperJniBridge.fullTranscribe(nativePtr, numThreads, null, audioData)

        val count = WhisperJniBridge.getTextSegmentCount(nativePtr)
        // Note: We don't have a JNI function for timestamps yet,
        // so we return segments with 0 timestamps. V2 enhancement.
        return (0 until count).map { i ->
            WhisperSegment(
                text = WhisperJniBridge.getTextSegment(nativePtr, i),
                startMs = 0L,
                endMs = 0L
            )
        }
    }

    /**
     * v0.7.2: 30秒窓 + 2秒オーバーラップで文字起こし。
     * stride = windowMs - overlapMs = 28000ms でループ。
     * 各窓のテキストを空白区切りで連結。重複排除は将来拡張 (v0.7.3)。
     */
    override fun transcribeWithOverlap(
        audioData: FloatArray,
        language: String?,
        windowMs: Int,
        overlapMs: Int
    ): String {
        checkLoaded()
        val sampleRate = 16000
        val windowSamples = windowMs * sampleRate / 1000
        val strideSamples = (windowMs - overlapMs) * sampleRate / 1000
        val totalSamples = audioData.size
        Log.d(TAG, "transcribeWithOverlap: total=${totalSamples} window=${windowMs}ms overlap=${overlapMs}ms stride=${strideSamples} samples")

        val sb = StringBuilder()
        var offsetSamples = 0
        while (offsetSamples < totalSamples) {
            val endSamples = minOf(offsetSamples + windowSamples, totalSamples)
            val chunkSize = endSamples - offsetSamples
            // 0.5秒未満の残りはスキップ (推論精度低)
            if (chunkSize < sampleRate / 2) break

            val chunk = FloatArray(chunkSize)
            System.arraycopy(audioData, offsetSamples, chunk, 0, chunkSize)
            val offsetMs = offsetSamples * 1000 / sampleRate
            val durationMs = chunkSize * 1000 / sampleRate
            Log.d(TAG, "  window: offset=${offsetMs}ms duration=${durationMs}ms samples=$chunkSize")

            WhisperJniBridge.fullTranscribeChunked(
                nativePtr, numThreads, language, chunk, offsetMs, durationMs
            )

            val count = WhisperJniBridge.getTextSegmentCount(nativePtr)
            for (i in 0 until count) {
                if (sb.isNotEmpty()) sb.append(" ")
                sb.append(WhisperJniBridge.getTextSegment(nativePtr, i))
            }
            offsetSamples += strideSamples
        }
        val text = sb.toString().trim()
        Log.d(TAG, "transcribeWithOverlap complete: ${text.length} chars")
        return text
    }

    override fun transcribeFile(wavFile: File): String {
        return transcribeFile(wavFile, language = null)
    }

    /** transcribeFile with language hint. */
    override fun transcribeFile(wavFile: File, language: String?): String {
        val pcmFloat = readWavAsFloat(wavFile)
        return transcribe(pcmFloat, language)
    }

    /** v0.7.2: ファイル読み込み → transcribeWithOverlap のヘルパー。 */
    fun transcribeFileWithOverlap(wavFile: File, language: String?): String {
        val pcmFloat = readWavAsFloat(wavFile)
        return transcribeWithOverlap(pcmFloat, language)
    }

    override fun release() {
        if (nativePtr != 0L) {
            WhisperJniBridge.freeContext(nativePtr)
            nativePtr = 0L
            Log.d(TAG, "Model released")
        }
    }

    // ─── WAV parsing ─────────────────────────────────────────

    /**
     * Read a 16-bit PCM WAV file (16kHz mono) and convert to normalized float array.
     *
     * WAV header format (44 bytes):
     *   0-3  "RIFF"
     *   4-7  file size - 8
     *   8-11 "WAVE"
     *   12-15 "fmt "
     *   16-19 chunk size (16 for PCM)
     *   20-21 audio format (1 = PCM)
     *   22-23 number of channels
     *   24-27 sample rate
     *   28-31 byte rate
     *   32-33 block align
     *   34-35 bits per sample
     *   36-39 "data"
     *   40-43 data chunk size
     *   44+   PCM data
     */
    private fun readWavAsFloat(file: File): FloatArray {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // Validate WAV header
        val riff = String(byteArrayOf(bytes[0], bytes[1], bytes[2], bytes[3]))
        val wave = String(byteArrayOf(bytes[8], bytes[9], bytes[10], bytes[11]))
        require(riff == "RIFF" && wave == "WAVE") { "Not a valid WAV file: $file" }

        val channels = buffer.getShort(22).toInt()
        val sampleRate = buffer.getInt(24)
        val bitsPerSample = buffer.getShort(34).toInt()
        val dataSize = buffer.getInt(40)

        // Validate expected format
        if (channels != 1) Log.w(TAG, "WAV has $channels channels, expected mono")
        if (sampleRate != 16000) Log.w(TAG, "WAV sample rate $sampleRate, expected 16000")
        if (bitsPerSample != 16) Log.w(TAG, "WAV bits per sample $bitsPerSample, expected 16")

        // Find start of PCM data (skip any extra fmt chunks)
        var dataOffset = 44
        // Skip any extra chunks between fmt and data. Cap iterations to avoid
        // being misled by malformed header fields (e.g. bogus chunkSize that
        // pushes dataOffset past the file length and triggers an
        // IndexOutOfBoundsException in copyOfRange downstream).
        var iterGuard = 0
        while (dataOffset + 4 < bytes.size && iterGuard++ < 16) {
            val chunkId = String(byteArrayOf(bytes[dataOffset], bytes[dataOffset + 1],
                bytes[dataOffset + 2], bytes[dataOffset + 3]))
            if (chunkId == "data") {
                dataOffset += 8 // skip "data" + 4-byte size
                break
            }
            val chunkSize = bytes.toIntAt(dataOffset + 4)
            // Reject negative or absurdly large chunk sizes — treat as malformed
            // and fall through to default 44-byte offset.
            if (chunkSize < 0 || chunkSize > bytes.size) {
                Log.w(TAG, "WAV chunk size $chunkSize out of range, ignoring")
                dataOffset = 44
                break
            }
            dataOffset += 8 + chunkSize
        }

        // Hard clamp: if we somehow walked past the end (malformed header),
        // fall back to byte 44 (standard 16-bit PCM mono layout).
        if (dataOffset > bytes.size) dataOffset = 44

        // Clamp to actual bytes on disk; dataSize in header may disagree with
        // bytes.size if the file was truncated.
        val effectiveDataBytes = (bytes.size - dataOffset).coerceAtLeast(0)
        val pcmData = bytes.copyOfRange(dataOffset, dataOffset + effectiveDataBytes)

        // PCM is 16-bit: each sample is 2 bytes. Odd trailing byte would
        // otherwise produce a partial short and trip ByteBuffer bounds.
        val alignedLen = pcmData.size - (pcmData.size % 2)
        val shortBuffer = ByteBuffer.wrap(pcmData, 0, alignedLen)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
        val result = FloatArray(shortBuffer.remaining())
        for (i in result.indices) {
            result[i] = shortBuffer[i].toFloat() / 32768f
        }
        return result
    }

    // ─── helpers ──────────────────────────────────────────────

    private fun checkLoaded() {
        check(nativePtr != 0L) { "Whisper model not loaded. Call load() first." }
    }

    private fun ByteArray.toIntAt(offset: Int): Int {
        return (this[offset].toInt() and 0xFF) or
                ((this[offset + 1].toInt() and 0xFF) shl 8) or
                ((this[offset + 2].toInt() and 0xFF) shl 16) or
                ((this[offset + 3].toInt() and 0xFF) shl 24)
    }

    companion object {
        private const val TAG = "WhisperModel"
        // v0.7.2: 30秒窓 + 2秒オーバーラップで高精度・高速化
        const val WINDOW_MS = 30_000
        const val OVERLAP_MS = 2_000
    }
}
