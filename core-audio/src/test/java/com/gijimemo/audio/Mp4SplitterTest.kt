// core-audio/src/test/java/com/gijimemo/audio/Mp4SplitterTest.kt
package com.gijimemo.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Mp4SplitterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ─── 最小 MP4 構築ヘルパ ──────────────────────────────

    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val bb = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(8 + payload.size); out.write(bb.array())
        out.write(type.toByteArray())
        out.write(payload)
        return out.toByteArray()
    }

    private fun fullBox(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val bb = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(12 + payload.size); out.write(bb.array())
        out.write(type.toByteArray())
        out.write(ByteArray(4)) // version+flags
        out.write(payload)
        return out.toByteArray()
    }

    /** samples: 各サンプルのバイト列。これらを mdat に連続配置した MP4 を返す。 */
    private fun buildMp4(samples: List<ByteArray>): ByteArray {
        val ftyp = box("ftyp", "isom".toByteArray() + ByteArray(4) + "isom".toByteArray())

        val stsd = fullBox("stsd", ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(1); putInt(0) // entryCount, 先頭に 4 バイトダミー + entry（中身は検証しない）
        }.array() + ByteArray(8)) // ダミーの sample entry

        val runs = listOf(intArrayOf(samples.size, 1024))
        val sttsPayload = ByteArrayOutputStream().apply {
            val bb = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
            bb.putInt(runs.size); write(bb.array())
            for (r in runs) {
                val rb = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                rb.putInt(r[0]); rb.putInt(r[1]); write(rb.array())
            }
        }.toByteArray()
        val stts = fullBox("stts", sttsPayload)

        val stscPayload = ByteArrayOutputStream().apply {
            val bb = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            bb.putInt(1); bb.putInt(1); bb.putInt(samples.size); bb.putInt(1); write(bb.array())
        }.toByteArray()
        val stsc = fullBox("stsc", stscPayload)

        val stszPayload = ByteArrayOutputStream().apply {
            val bb = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            bb.putInt(0); bb.putInt(samples.size); write(bb.array())
            for (s in samples) {
                val sb = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                sb.putInt(s.size); write(sb.array())
            }
        }.toByteArray()
        val stsz = fullBox("stsz", stszPayload)

        // mdat ペイロード開始オフセットは ftyp + moov + 8。ここでは一旦 0 で作って後でパッチせず、
        // 先に mdat 絶対オフセットを計算してから stco を作る。
        // → 簡略化のため、まず stco プレースホルダで moov を組み、オフセットを確定してから作り直す。
        val stcoPlaceholder = fullBox("stco", ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(1); putInt(0)
        }.array())

        val stbl = box("stbl", stsd + stts + stsc + stsz + stcoPlaceholder)
        val smhd = fullBox("smhd", ByteArray(4))
        val dinf = box("dinf", fullBox("dref", ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(1); putInt(0)
        }.array() + ByteArray(12)))
        val minf = box("minf", smhd + dinf + stbl)
        val mdhd = fullBox("mdhd", ByteArray(16))
        val hdlr = fullBox("hdlr", ByteArray(8) + "soun".toByteArray() + ByteArray(12))
        val mdia = box("mdia", mdhd + hdlr + minf)
        val tkhd = fullBox("tkhd", ByteArray(80))
        val trak = box("trak", tkhd + mdia)
        val mvhd = fullBox("mvhd", ByteArray(100))
        val moovPlaceholder = box("moov", mvhd + trak)

        val mdatOffset = ftyp.size + moovPlaceholder.size + 8
        val stco = fullBox("stco", ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(1); putInt(mdatOffset)
        }.array())
        val stbl2 = box("stbl", stsd + stts + stsc + stsz + stco)
        val moov = box("moov", mvhd + box("trak", tkhd + box("mdia", mdhd + hdlr + box("minf", smhd + dinf + stbl2))))

        // mdat
        val mdatPayload = ByteArrayOutputStream()
        samples.forEach { mdatPayload.write(it) }
        val mdat = ByteArrayOutputStream().apply {
            val bb = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
            bb.putInt(8 + mdatPayload.size()); write(bb.array())
            write("mdat".toByteArray())
            write(mdatPayload.toByteArray())
        }.toByteArray()

        return ftyp + moov + mdat
    }

    // ─── テスト ───────────────────────────────────────────

    @Test
    fun `splits mp4 into byte-range chunks`() {
        // 10 サンプル（各 10KB）→ 合計 100KB
        val samples = (0 until 10).map { ByteArray(10_000) { (it % 251).toByte() } }
        val mp4 = File(tmp.root, "in.m4a").apply { writeBytes(buildMp4(samples)) }

        val chunks = Mp4Splitter.splitByBytes(mp4, tmp.root, chunkSizeBytes = 30_000)

        // 100KB / 30KB → 4 チャンク
        assertThat(chunks).hasSize(4)

        // 各チャンクは ftyp で始まり、全チャンクの mdat ペイロード連結が元サンプル列と一致する
        val expectedPayload = ByteArray(100_000)
        var off = 0
        for (s in samples) { System.arraycopy(s, 0, expectedPayload, off, s.size); off += s.size }

        val concatenated = ByteArrayOutputStream()
        for (chunk in chunks) {
            val bytes = chunk.readBytes()
            assertThat(String(bytes, 4, 4)).isEqualTo("ftyp")
            val mdatIdx = indexOfMdat(bytes)
            assertThat(mdatIdx).isGreaterThan(0)
            concatenated.write(bytes, mdatIdx + 8, bytes.size - mdatIdx - 8)
        }
        assertThat(concatenated.toByteArray()).isEqualTo(expectedPayload)
    }

    @Test
    fun `returns empty when file fits in one chunk`() {
        val samples = (0 until 3).map { ByteArray(1_000) { 1 } }
        val mp4 = File(tmp.root, "small.m4a").apply { writeBytes(buildMp4(samples)) }
        assertThat(Mp4Splitter.splitByBytes(mp4, tmp.root, chunkSizeBytes = 1_000_000)).isEmpty()
    }

    @Test
    fun `returns empty for non mp4`() {
        val f = File(tmp.root, "notmp4.mp3").apply { writeBytes(ByteArray(10_000) { 0 }) }
        assertThat(Mp4Splitter.splitByBytes(f, tmp.root, chunkSizeBytes = 1_000)).isEmpty()
    }

    private fun indexOfMdat(bytes: ByteArray): Int {
        for (i in 0 until bytes.size - 8) {
            if (bytes[i] == 'm'.code.toByte() && bytes[i + 1] == 'd'.code.toByte() &&
                bytes[i + 2] == 'a'.code.toByte() && bytes[i + 3] == 't'.code.toByte()
            ) return i
        }
        return -1
    }
}
