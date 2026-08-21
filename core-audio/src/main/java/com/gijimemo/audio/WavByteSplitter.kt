// core-audio/src/main/java/com/gijimemo/audio/WavByteSplitter.kt
package com.gijimemo.audio

import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 16-bit PCM WAV ファイルをバイト単位でストリーミング分割する。
 *
 * PCM 全体をメモリに載せず、元ファイルのバイト範囲をコピーして各チャンク WAV を
 * 生成するため、長時間音声（数時間）でも O(1) メモリで済む。
 * 従来の「全 PCM を ShortArray で読み込む」方式は 1 時間音声で約 115MB の
 * ヒープを消費し OOM の原因になっていた。
 *
 * 前提: 標準 44 バイト RIFF/WAVE ヘッダ（AudioDecoder.writeWav と同形式）。
 */
object WavByteSplitter {

    private const val HEADER_SIZE = 44
    private const val COPY_BUFFER_SIZE = 64 * 1024

    /**
     * @param wavFile 16-bit PCM WAV（44 バイト標準ヘッダ）
     * @param outputDir チャンク出力先ディレクトリ
     * @param chunkSizeBytes 1 チャンクの目標バイト数（PCM データ部分）
     * @return 分割後の WAV チャンクファイル。分割不要（1 チャンクに収まる）なら空リスト
     * @throws IllegalArgumentException ヘッダ不正・PCM データなし
     * @throws EOFException ファイルが途中で切れている場合
     */
    fun splitByBytes(wavFile: File, outputDir: File, chunkSizeBytes: Long): List<File> {
        require(chunkSizeBytes > 0) { "chunkSizeBytes must be > 0" }

        val dataSize = wavFile.length() - HEADER_SIZE
        require(dataSize > 0) { "WAV has no PCM data: ${wavFile.name}" }

        // 16-bit サンプル境界に合わせて偶数バイトに丸める
        val chunkBytes = (chunkSizeBytes / 2 * 2).coerceAtLeast(2L)
        val totalChunks = ((dataSize + chunkBytes - 1) / chunkBytes).toInt()
        if (totalChunks <= 1) return emptyList() // 分割不要 → 元ファイルをそのまま使う

        FileInputStream(wavFile).use { input ->
            val header = ByteArray(HEADER_SIZE)
            val headerRead = input.read(header)
            require(headerRead == HEADER_SIZE) { "WAV header too short: ${wavFile.name}" }
            require(isRiffWav(header)) { "Not a RIFF/WAVE file: ${wavFile.name}" }

            val chunks = mutableListOf<File>()
            val buf = ByteArray(COPY_BUFFER_SIZE)
            var remaining = dataSize
            var idx = 0
            while (remaining > 0) {
                val thisChunk = minOf(chunkBytes, remaining)
                val chunkFile = File(outputDir, "wav_chunk_${idx}_${System.nanoTime()}.wav")
                FileOutputStream(chunkFile).use { out ->
                    out.write(patchedHeader(header, thisChunk))
                    copyBytes(input, out, thisChunk, buf)
                }
                chunks.add(chunkFile)
                remaining -= thisChunk
                idx++
            }
            return chunks
        }
    }

    private fun isRiffWav(header: ByteArray): Boolean {
        return header.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
            header.copyOfRange(8, 12).contentEquals("WAVE".toByteArray())
    }

    /** data チャンクサイズ（オフセット 40）と RIFF サイズ（オフセット 4）を書き換えたヘッダ。 */
    private fun patchedHeader(header: ByteArray, dataSize: Long): ByteArray {
        val h = header.copyOf()
        putLeInt(h, 4, (36L + dataSize).toInt())
        putLeInt(h, 40, dataSize.toInt())
        return h
    }

    private fun copyBytes(input: FileInputStream, out: FileOutputStream, count: Long, buf: ByteArray) {
        var remaining = count
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) throw EOFException("Unexpected end of WAV while splitting")
            out.write(buf, 0, n)
            remaining -= n
        }
    }

    private fun putLeInt(b: ByteArray, offset: Int, v: Int) {
        b[offset] = (v and 0xFF).toByte()
        b[offset + 1] = ((v shr 8) and 0xFF).toByte()
        b[offset + 2] = ((v shr 16) and 0xFF).toByte()
        b[offset + 3] = ((v shr 24) and 0xFF).toByte()
    }
}
