package com.gijimemo.whisper

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer

/**
 * Decodes AAC audio (in MP4 container) to 16-bit 16kHz mono WAV file.
 *
 * This is required because whisper.cpp expects raw PCM WAV input.
 * Uses Android MediaCodec (available since API 16, guaranteed on minSdk 26).
 */
object AudioDecoder {

    private const val TAG = "AudioDecoder"
    private const val TARGET_SAMPLE_RATE = 16000

    /**
     * Decode an AAC audio file (MP4 container) to a temporary WAV file.
     * @return path to the decoded WAV file
     */
    fun decodeToWav(inputPath: String, outputDir: File): String {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(inputPath)
        } catch (e: Exception) {
            throw RuntimeException("Cannot read audio file: $inputPath", e)
        }

        // Find audio track
        val trackIndex = findAudioTrack(extractor)
            ?: throw RuntimeException("No audio track found in: $inputPath")

        extractor.selectTrack(trackIndex)
        val inputFormat = extractor.getTrackFormat(trackIndex)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)
            ?: throw RuntimeException("Audio track has no MIME type")

        Log.d(TAG, "Decoding: mime=$mime format=$inputFormat")

        // Configure decoder
        val decoder = MediaCodec.createDecoderByType(mime)
        val outputFormat = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_RAW, TARGET_SAMPLE_RATE, 1
        )
        decoder.configure(inputFormat, null, null, 0)
        decoder.start()

        // v0.9.1: PCM をメモリに蓄積せず WAV ファイルへストリーミング書き出しする。
        // 従来は全 PCM を ShortArray で保持（40分音声で約 80MB）し、
        // 256MB ヒープで OOM を引き起こしていた。
        val outputFile = File(outputDir, "whisper_decoded_${System.nanoTime()}.wav")
        val fos = FileOutputStream(outputFile)
        // 44 バイト WAV ヘッダを仮書き（data サイズは最後にパッチ）
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)             // PCM
        header.putShort(1)             // mono
        header.putInt(TARGET_SAMPLE_RATE)
        header.putInt(TARGET_SAMPLE_RATE * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray())
        header.putInt(0)
        fos.write(header.array())

        var pcmSize = 0L
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(1_000L)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                        if (inputBuffer == null) {
                            Log.w(TAG, "dequeueInputBuffer returned null, retrying")
                            continue
                        }
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 1_000L)
                if (outputIndex >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                    if (bufferInfo.size > 0) {
                        val outputBuffer = decoder.getOutputBuffer(outputIndex)
                        if (outputBuffer == null) {
                            Log.w(TAG, "dequeueOutputBuffer returned null, skip")
                            decoder.releaseOutputBuffer(outputIndex, false)
                            continue
                        }
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        // Decoder output: may be 44.1kHz stereo, we need 16kHz mono
                        val pcmShorts = decodeToMono16kHz(
                            outputBuffer, inputFormat, bufferInfo
                        )
                        // PCM を直接ファイルへ書き出し（メモリに保持しない）
                        val pcmBytes = ByteBuffer.allocate(pcmShorts.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                        pcmBytes.asShortBuffer().put(pcmShorts)
                        fos.write(pcmBytes.array())
                        pcmSize += pcmShorts.size
                    }
                    decoder.releaseOutputBuffer(outputIndex, false)
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Format changed, no action needed
                }
            }
        } finally {
            fos.close()
        }

        decoder.stop()
        decoder.release()
        extractor.release()

        // WAV ヘッダのサイズ欄を確定値でパッチ（リトルエンディアン）
        val dataSize = pcmSize * 2L
        RandomAccessFile(outputFile, "rw").use { raf ->
            raf.seek(4)
            raf.writeInt(Integer.reverseBytes((36L + dataSize).toInt())) // RIFF size
            raf.seek(40)
            raf.writeInt(Integer.reverseBytes(dataSize.toInt()))         // data chunk size
        }
        Log.d(TAG, "Decoded to: ${outputFile.absolutePath} (${pcmSize} samples)")
        return outputFile.absolutePath
    }

    // ─── private ──────────────────────────────────────────────

    private fun findAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return null
    }

    /**
     * Decode raw AAC output to mono 16kHz short array.
     * AAC decoder may output stereo or 44.1kHz, we mix down and resample.
     */
    private fun decodeToMono16kHz(
        buffer: ByteBuffer,
        format: MediaFormat,
        info: MediaCodec.BufferInfo
    ): ShortArray {
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 1)
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE, TARGET_SAMPLE_RATE)

        // Position the buffer view to the actual payload range reported by
        // MediaCodec. Without this, the underlying ByteBuffer's capacity may
        // be larger than [info.size], and reading the full buffer later
        // (e.g. via ShortBuffer.get()) returns stale or out-of-bounds data.
        val payload = buffer.duplicate()
        payload.position(info.offset)
        payload.limit(info.offset + info.size)
        payload.order(ByteOrder.LITTLE_ENDIAN)
        val shortBuf = payload.asShortBuffer()
        val actualShortCount = shortBuf.remaining()

        val perChannel = actualShortCount / channels
        val pcm = ShortArray(perChannel)

        for (i in 0 until perChannel) {
            if (channels == 1) {
                pcm[i] = shortBuf[i]
            } else {
                // Mix down to mono (average left + right)
                var sum = 0
                for (ch in 0 until channels) {
                    sum += shortBuf[i * channels + ch].toInt()
                }
                pcm[i] = (sum / channels).toShort()
            }
        }

        // Resample if needed
        return if (sampleRate == TARGET_SAMPLE_RATE) {
            pcm
        } else {
            linearResample(pcm, sampleRate, TARGET_SAMPLE_RATE)
        }
    }

    private fun linearResample(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate == toRate) return input
        val ratio = toRate.toDouble() / fromRate.toDouble()
        val outputLen = (input.size * ratio).toInt()
        val output = ShortArray(outputLen)
        for (i in output.indices) {
            val srcPos = i / ratio
            val srcIndex = srcPos.toInt()
            val frac = srcPos - srcIndex
            val a = input[srcIndex.coerceIn(input.indices)].toInt()
            val b = input[(srcIndex + 1).coerceIn(input.indices)].toInt()
            output[i] = (a + ((b - a) * frac).toInt()).toShort()
        }
        return output
    }
}
