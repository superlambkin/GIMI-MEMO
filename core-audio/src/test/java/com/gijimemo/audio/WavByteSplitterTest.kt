// core-audio/src/test/java/com/gijimemo/audio/WavByteSplitterTest.kt
package com.gijimemo.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavByteSplitterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 16-bit PCM WAV（44 バイト標準ヘッダ）をバイト列で生成する。 */
    private fun wavBytes(pcmSizeBytes: Int): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + pcmSizeBytes)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)            // PCM
        header.putShort(1)            // mono
        header.putInt(16000)          // sample rate
        header.putInt(16000 * 2)      // byte rate
        header.putShort(2)            // block align
        header.putShort(16)           // bits per sample
        header.put("data".toByteArray())
        header.putInt(pcmSizeBytes)
        return header.array() + ByteArray(pcmSizeBytes) { (it % 251).toByte() }
    }

    private fun dataSizeOf(wav: File): Int {
        val b = wav.readBytes()
        return ByteBuffer.wrap(b, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun pcmOf(wav: File): ByteArray {
        val b = wav.readBytes()
        return b.copyOfRange(44, b.size)
    }

    @Test
    fun `splits wav into chunks of requested size`() {
        val src = File(tmp.root, "src.wav").apply { writeBytes(wavBytes(pcmSizeBytes = 20_000)) }

        val chunks = WavByteSplitter.splitByBytes(src, tmp.root, chunkSizeBytes = 8_000)

        assertThat(chunks).hasSize(3)
        assertThat(dataSizeOf(chunks[0])).isEqualTo(8_000)
        assertThat(dataSizeOf(chunks[1])).isEqualTo(8_000)
        assertThat(dataSizeOf(chunks[2])).isEqualTo(4_000)

        // PCM 内容が元ファイルのスライスと一致する（ストリーミング分割の正しさ）
        val original = pcmOf(src)
        assertThat(pcmOf(chunks[0])).isEqualTo(original.copyOfRange(0, 8_000))
        assertThat(pcmOf(chunks[1])).isEqualTo(original.copyOfRange(8_000, 16_000))
        assertThat(pcmOf(chunks[2])).isEqualTo(original.copyOfRange(16_000, 20_000))

        // 各チャンクは有効な RIFF/WAVE ヘッダを持つ
        chunks.forEach { c ->
            val head = c.readBytes().copyOfRange(0, 12)
            assertThat(String(head, 0, 4)).isEqualTo("RIFF")
            assertThat(String(head, 8, 4)).isEqualTo("WAVE")
        }
    }

    @Test
    fun `returns empty list when file fits in one chunk`() {
        val src = File(tmp.root, "small.wav").apply { writeBytes(wavBytes(pcmSizeBytes = 2_000)) }

        val chunks = WavByteSplitter.splitByBytes(src, tmp.root, chunkSizeBytes = 8_000)

        assertThat(chunks).isEmpty()
    }

    @Test
    fun `rounds chunk size down to even byte boundary`() {
        val src = File(tmp.root, "src.wav").apply { writeBytes(wavBytes(pcmSizeBytes = 10_000)) }

        // 奇数バイト指定でも 16-bit サンプル境界（偶数）に丸められる
        val chunks = WavByteSplitter.splitByBytes(src, tmp.root, chunkSizeBytes = 4_001)

        assertThat(chunks).hasSize(3)
        assertThat(dataSizeOf(chunks[0])).isEqualTo(4_000)
        assertThat(dataSizeOf(chunks[1])).isEqualTo(4_000)
        assertThat(dataSizeOf(chunks[2])).isEqualTo(2_000)
    }

    @Test
    fun `rejects non WAV file`() {
        val src = File(tmp.root, "fake.wav").apply { writeText("this is not a wav file") }

        val ex = runCatching {
            WavByteSplitter.splitByBytes(src, tmp.root, chunkSizeBytes = 1_000)
        }.exceptionOrNull()

        assertThat(ex).isInstanceOf(IllegalArgumentException::class.java)
    }
}
