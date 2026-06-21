package com.gijimemo.whisper

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.io.FileOutputStream
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

        // Use dynamically-grown ShortArray to avoid boxing overhead of MutableList<Short>.
        // For long recordings (~38min → 36M samples), boxing would require ~600MB+ heap
        // and crash with OOM on devices with 256MB heap limit.
        var allPcm = ShortArray(65536) // initial capacity (128KB)
        var pcmSize = 0
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

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
                    // Grow ShortArray if needed
                    val needed = pcmSize + pcmShorts.size
                    if (needed > allPcm.size) {
                        val newSize = maxOf(allPcm.size * 2, needed)
                        allPcm = allPcm.copyOf(newSize)
                    }
                    pcmShorts.copyInto(allPcm, pcmSize)
                    pcmSize += pcmShorts.size
                }
                decoder.releaseOutputBuffer(outputIndex, false)
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // Format changed, no action needed
            }
        }

        decoder.stop()
        decoder.release()
        extractor.release()

        // Write WAV file
        val outputFile = File(outputDir, "whisper_decoded_${System.nanoTime()}.wav")
        writeWav(outputFile, allPcm.copyOf(pcmSize))
        Log.d(TAG, "Decoded to: ${outputFile.absolutePath} (${allPcm.size} samples)")
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

    private fun writeWav(file: File, pcmData: ShortArray) {
        val dataSize = pcmData.size * 2L // 16-bit samples
        val fileSize = 36L + dataSize

        FileOutputStream(file).use { fos ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

            // RIFF header
            header.put("RIFF".toByteArray())
            header.putInt((fileSize).toInt())
            header.put("WAVE".toByteArray())

            // fmt chunk
            header.put("fmt ".toByteArray())
            header.putInt(16) // chunk size
            header.putShort(1) // PCM format
            header.putShort(1) // mono
            header.putInt(TARGET_SAMPLE_RATE)
            header.putInt(TARGET_SAMPLE_RATE * 2) // byte rate (16-bit mono)
            header.putShort(2) // block align
            header.putShort(16) // bits per sample

            // data chunk
            header.put("data".toByteArray())
            header.putInt(dataSize.toInt())

            fos.write(header.array())

            // PCM data
            val pcmBytes = ByteBuffer.allocate(pcmData.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            pcmBytes.asShortBuffer().put(pcmData)
            fos.write(pcmBytes.array())
        }
    }
}
