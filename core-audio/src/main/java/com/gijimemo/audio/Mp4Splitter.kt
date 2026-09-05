// core-audio/src/main/java/com/gijimemo/audio/Mp4Splitter.kt
package com.gijimemo.audio

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * v0.9.1（方案A）: MP4/M4A (AAC) を MediaMuxer を使わず、コンテナを直接バイト分割する。
 *
 * moov の sample table（stts/stsc/stsz/stco）を解析してサンプル毎の
 * (絶対オフセット, サイズ, 再生時間) を得て、チャンク境界で mdat の
 * バイト範囲をコピーし、チャンク用に縮小した moov を再構築する。
 * MediaMuxer のコンテナ再構築コスト（実測で数十秒）を回避し、分割をほぼディスク速度にする。
 *
 * 解析不能・非対応構造の場合は空リストを返し、呼び出し側が従来の MediaMuxer 経路へ
 * フォールバックする（安全側に倒す）。
 */
object Mp4Splitter {

    private data class Sample(val offset: Long, val size: Int, val duration: Int)

    private class Parsed(
        val ftyp: ByteArray,
        val mvhd: ByteArray, val tkhd: ByteArray, val mdhd: ByteArray, val hdlr: ByteArray,
        val smhd: ByteArray, val dinf: ByteArray, val stsd: ByteArray,
        val samples: List<Sample>,
        val hasCo64: Boolean
    )

    fun splitByBytes(mp4File: File, outputDir: File, chunkSizeBytes: Long): List<File> {
        if (chunkSizeBytes <= 0) return emptyList()
        val data = try { mp4File.readBytes() } catch (e: Exception) { return emptyList() }
        val parsed = try { parse(data) } catch (e: Exception) { null } ?: return emptyList()
        if (parsed.samples.size < 2) return emptyList()

        // サンプルをチャンク（<= chunkSizeBytes）にグループ化
        val groups = ArrayList<MutableList<Sample>>()
        var cur = ArrayList<Sample>()
        var curBytes = 0L
        for (s in parsed.samples) {
            cur.add(s)
            curBytes += s.size
            if (curBytes >= chunkSizeBytes) {
                groups.add(cur); cur = ArrayList(); curBytes = 0L
            }
        }
        if (cur.isNotEmpty()) groups.add(cur)
        if (groups.size <= 1) return emptyList()

        return groups.mapIndexed { i, group ->
            writeChunk(outputDir, data, parsed, group, i)
        }
    }

    // ─── 解析 ─────────────────────────────────────────────

    private fun parse(data: ByteArray): Parsed? {
        val ftyp = topBox(data, "ftyp") ?: return null
        val moov = topBox(data, "moov") ?: return null

        val mvhd = childBox(moov, "mvhd") ?: return null
        val trak = childBox(moov, "trak") ?: return null
        val tkhd = childBox(trak, "tkhd") ?: return null
        val mdia = childBox(trak, "mdia") ?: return null
        val mdhd = childBox(mdia, "mdhd") ?: return null
        val hdlr = childBox(mdia, "hdlr") ?: return null
        val minf = childBox(mdia, "minf") ?: return null
        val smhd = childBox(minf, "smhd") ?: return null
        val dinf = childBox(minf, "dinf") ?: return null
        val stbl = childBox(minf, "stbl") ?: return null
        val stsd = childBox(stbl, "stsd") ?: return null
        val stts = childBox(stbl, "stts") ?: return null
        val stsc = childBox(stbl, "stsc") ?: return null
        val stco = childBox(stbl, "stco")
        val co64 = childBox(stbl, "co64")
        val stsz = childBox(stbl, "stsz")
        val stz2 = childBox(stbl, "stz2")
        if ((stco == null && co64 == null) || (stsz == null && stz2 == null)) return null

        val durations = parseStts(stts) ?: return null
        val sizes = if (stz2 != null) parseStz2(stz2) else parseStsz(stsz!!)
        if (sizes.isEmpty() || sizes.size != durations.size) return null
        val chunkSampleCounts = parseStsc(stsc, sizes.size) ?: return null
        val chunkOffsets = if (co64 != null) parseCo64(co64) else parseStco(stco!!)
        if (chunkOffsets.isEmpty()) return null

        val samples = ArrayList<Sample>(sizes.size)
        var chunkIdx = 0
        var inChunk = 0
        var offsetInChunk = 0L
        for (i in sizes.indices) {
            if (chunkIdx >= chunkSampleCounts.size) return null
            if (inChunk >= chunkSampleCounts[chunkIdx]) {
                chunkIdx++; inChunk = 0; offsetInChunk = 0L
                if (chunkIdx >= chunkSampleCounts.size || chunkIdx >= chunkOffsets.size) return null
            }
            samples.add(Sample(chunkOffsets[chunkIdx] + offsetInChunk, sizes[i], durations[i]))
            offsetInChunk += sizes[i]
            inChunk++
        }
        return Parsed(ftyp, mvhd, tkhd, mdhd, hdlr, smhd, dinf, stsd, samples, co64 != null)
    }

    private fun writeChunk(outputDir: File, data: ByteArray, p: Parsed, samples: List<Sample>, idx: Int): File {
        val file = File(outputDir, "mp4_chunk_${idx}_${System.nanoTime()}.m4a")
        FileOutputStream(file).use { out ->
            out.write(p.ftyp)

            // mdat ペイロード（AAC は通常サンプルが連続）
            val rangeStart = samples.first().offset
            val rangeEnd = samples.last().offset + samples.last().size
            val payload = data.copyOfRange(rangeStart.toInt(), rangeEnd.toInt())

            // moov（mdat ペイロード先頭オフセットは moov サイズに依存するため 2 パス）
            val placeholder = buildMoov(p, samples, 0L)
            val mdatOffset = (p.ftyp.size + placeholder.size + 8).toLong()
            val moov = buildMoov(p, samples, mdatOffset)
            out.write(moov)

            val header = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            header.putInt(8 + payload.size)
            header.put("mdat".toByteArray())
            out.write(header.array())
            out.write(payload)
        }
        return file
    }

    private fun buildMoov(p: Parsed, samples: List<Sample>, mdatOffset: Long): ByteArray {
        val stts = buildStts(samples)
        val stsc = buildStsc(samples.size)
        val stsz = buildStsz(samples)
        val stco = buildStco(p.hasCo64, mdatOffset)
        val stbl = box("stbl", p.stsd + stts + stsc + stsz + stco)
        val minf = box("minf", p.smhd + p.dinf + stbl)
        val mdia = box("mdia", p.mdhd + p.hdlr + minf)
        val trak = box("trak", p.tkhd + mdia)
        return box("moov", p.mvhd + trak)
    }

    // ─── sample table 構築 ────────────────────────────────

    private fun buildStts(samples: List<Sample>): ByteArray {
        val runs = ArrayList<IntArray>()
        var runCount = 0; var runDelta = -1
        for (s in samples) {
            if (runDelta < 0) { runDelta = s.duration; runCount = 1 }
            else if (s.duration == runDelta) runCount++
            else { runs.add(intArrayOf(runCount, runDelta)); runCount = 1; runDelta = s.duration }
        }
        if (runDelta >= 0) runs.add(intArrayOf(runCount, runDelta))
        val out = ByteArrayOutputStream()
        out.write(fullBoxHeader("stts", 4 + runs.size * 8))
        val bb = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(runs.size); out.write(bb.array(), 0, 4)
        for (r in runs) {
            bb.clear(); bb.putInt(r[0]); bb.putInt(r[1]); out.write(bb.array())
        }
        return out.toByteArray()
    }

    private fun buildStsc(sampleCount: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(fullBoxHeader("stsc", 4 + 12))
        val bb = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(1)          // entry count
        bb.putInt(1)          // first_chunk
        bb.putInt(sampleCount) // samples_per_chunk
        bb.putInt(1)          // sample_description_index
        out.write(bb.array())
        return out.toByteArray()
    }

    private fun buildStsz(samples: List<Sample>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(fullBoxHeader("stsz", 8 + samples.size * 4))
        val bb = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(0); bb.putInt(samples.size); out.write(bb.array())
        for (s in samples) { bb.clear(); bb.putInt(s.size); out.write(bb.array()) }
        return out.toByteArray()
    }

    private fun buildStco(hasCo64: Boolean, mdatOffset: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(fullBoxHeader(if (hasCo64) "co64" else "stco", 4 + if (hasCo64) 8 else 4))
        val bb = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(1) // entry count
        if (hasCo64) { bb.putLong(mdatOffset); out.write(bb.array()) }
        else { bb.putInt(mdatOffset.toInt()); out.write(bb.array(), 0, 8) }
        return out.toByteArray()
    }

    // ─── ボックス操作 ─────────────────────────────────────

    /** トップレベルから [type] ボックス全体を返す。 */
    private fun topBox(data: ByteArray, type: String): ByteArray? = boxRange(data, 0, data.size, type)

    /** 親ボックス直下の子ボックス [type] を返す（親自身のヘッダを飛ばして検索）。 */
    private fun childBox(parent: ByteArray, type: String): ByteArray? {
        val headerSize = if (readU32(parent, 0) == 1) 16 else 8
        return boxRange(parent, headerSize, parent.size, type)
    }

    private fun boxRange(data: ByteArray, start: Int, end: Int, type: String): ByteArray? {
        var pos = start
        while (pos + 8 <= end) {
            val size = readU32(data, pos)
            if (size < 8) break
            val t = String(data, pos + 4, 4)
            if (t == type) return data.copyOfRange(pos, (pos + size).coerceAtMost(end))
            pos += size
        }
        return null
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 8)
        val bb = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(8 + payload.size); out.write(bb.array())
        out.write(type.toByteArray())
        out.write(payload)
        return out.toByteArray()
    }

    private fun fullBoxHeader(type: String, payloadSize: Int): ByteArray {
        val out = ByteArrayOutputStream(12)
        val bb = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(12 + payloadSize); out.write(bb.array()) // size = 4(size)+4(type)+4(ver/flags)+payload
        out.write(type.toByteArray())
        out.write(ByteArray(4)) // version + flags = 0
        return out.toByteArray()
    }

    // ─── sample table パース ──────────────────────────────

    private fun parseStts(box: ByteArray): IntArray? {
        val count = readU32(box, 12).coerceAtMost((box.size - 16) / 8)
        val out = ArrayList<Int>()
        var pos = 16
        for (i in 0 until count) {
            val n = readU32(box, pos); val d = readU32(box, pos + 4); pos += 8
            repeat(n.coerceAtMost(1_000_000)) { out.add(d) }
        }
        return if (out.isEmpty()) null else out.toIntArray()
    }

    private fun parseStsc(box: ByteArray, totalSamples: Int): IntArray? {
        val count = readU32(box, 12)
        val entries = ArrayList<IntArray>()
        var pos = 16
        for (i in 0 until count) {
            entries.add(intArrayOf(readU32(box, pos), readU32(box, pos + 4), readU32(box, pos + 8))); pos += 12
        }
        if (entries.isEmpty()) return null
        val counts = ArrayList<Int>()
        for (i in entries.indices) {
            val first = entries[i][0]; val spc = entries[i][1]
            val next = if (i + 1 < entries.size) entries[i + 1][0] else Int.MAX_VALUE
            var c = first
            while (c < next && counts.size < totalSamples) { counts.add(spc); c++ }
        }
        return if (counts.isEmpty()) null else counts.toIntArray()
    }

    private fun parseStco(box: ByteArray): LongArray {
        val count = readU32(box, 12).coerceAtMost((box.size - 16) / 4)
        val out = LongArray(count); var pos = 16
        for (i in 0 until count) { out[i] = readU32(box, pos).toLong(); pos += 4 }
        return out
    }

    private fun parseCo64(box: ByteArray): LongArray {
        val count = readU32(box, 12).coerceAtMost((box.size - 16) / 8)
        val out = LongArray(count); var pos = 16
        for (i in 0 until count) { out[i] = readU64(box, pos); pos += 8 }
        return out
    }

    private fun parseStsz(box: ByteArray): IntArray {
        val sampleSize = readU32(box, 12)
        val count = readU32(box, 16).coerceAtMost((box.size - 20) / 4)
        val out = IntArray(count)
        if (sampleSize != 0) { out.fill(sampleSize); return out }
        var pos = 20
        for (i in 0 until count) { out[i] = readU32(box, pos); pos += 4 }
        return out
    }

    private fun parseStz2(box: ByteArray): IntArray {
        if ((box[15].toInt() and 0xFF) != 16) return IntArray(0)
        val count = readU32(box, 16).coerceAtMost((box.size - 20) / 2)
        val out = IntArray(count); var pos = 20
        for (i in 0 until count) { out[i] = readU16(box, pos); pos += 2 }
        return out
    }

    // ─── バイト読み取り ───────────────────────────────────

    private fun readU32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    private fun readU64(b: ByteArray, off: Int): Long =
        (readU32(b, off).toLong() shl 32) or (readU32(b, off + 4).toLong() and 0xFFFFFFFFL)

    private fun readU16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
}
