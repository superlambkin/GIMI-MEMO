// app/src/main/java/com/gijimemo/ui/processing/CloudWhisperTranscriber.kt
package com.gijimemo.ui.processing

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.gijimemo.audio.WavByteSplitter
import com.gijimemo.data.repository.SettingsRepository
import com.gijimemo.llm.LlmClient
import com.gijimemo.llm.LlmException
import com.gijimemo.llm.LlmProvider
import com.gijimemo.whisper.AudioDecoder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * クラウド OpenAI Whisper による 1 ファイルの文字起こしエンジン。
 *
 * v0.9.0: ProcessingViewModel から分割・並列送信・リトライのロジックを抽出し、
 * 単一文字起こし（ProcessingViewModel）と一括インポート（BatchImportViewModel）で共有する。
 * 状態（StateFlow）には依存せず、進行状況は [onProgress] コールバックで通知する。
 */
@Singleton
class CloudWhisperTranscriber @Inject constructor(
    private val settings: SettingsRepository,
    private val provider: LlmProvider,
    @ApplicationContext private val context: Context
) {

    /** 転写の進行状況。呼び出し側が UI に反映する。 */
    data class Progress(
        val detailStatus: String = "",
        val totalChunks: Int = 0,
        val completedChunks: Int = 0,
        val splitTimeMs: Long = 0L,
        val chunkTimeEstimateMs: Long = 0L
    )

    /**
     * 1 つの音声ファイルをクラウド Whisper で文字起こしする。
     * - ファイルサイズが分割閾値以下ならそのまま送信（raw AAC は M4A コンテナにラップ）
     * - 超える場合は M4A 直接分割 → 並列送信（最大 3 並行・指数バックオフリトライ）
     * - 分割不能なコーデックは WAV デコード → バイト範囲ストリーミング分割にフォールバック
     *
     * @return 転写テキスト。一部チャンク失敗時は警告マーカーが末尾に付与される。
     * @throws IllegalStateException Whisper クライアント未初期化・全チャンク失敗時
     */
    suspend fun transcribeFile(
        source: File,
        chunkSizeMb: Int,
        onProgress: (Progress) -> Unit = {}
    ): String {
        val whisperClient = buildWhisperClient()
            ?: throw IllegalStateException("OpenAI Whisper クライアントが初期化できません。API Key を設定してください。")
        val chunkSizeBytes = (chunkSizeMb * 1024 * 1024L).coerceAtLeast(1024 * 1024)

        val cacheDir = File(context.cacheDir, "chunk_cache").apply { mkdirs() }

        // 単一ファイルで収まる → 直接送信
        if (source.length() <= chunkSizeBytes) {
            val fileToSend = if (isRawAacFile(source)) {
                onProgress(Progress(detailStatus = "AAC→M4A 変換中..."))
                wrapAacInM4a(source, cacheDir) ?: source
            } else {
                source
            }
            onProgress(Progress(detailStatus = "Whisper API 送信中..."))
            return transcribeWithRetry(whisperClient, fileToSend, "単一ファイル")
        }

        // 分割（MediaExtractor + MediaMuxer）。v0.9.1 方案A（Mp4Splitter 直接バイト分割）は
        // 単体検証が未完のため未配線。検証完了後にここで置き換える。
        onProgress(Progress(detailStatus = "M4A 分割中..."))
        val tSplit = System.currentTimeMillis()
        val m4aChunks = withContext(Dispatchers.IO) {
            splitM4aIntoChunks(source, cacheDir, chunkSizeBytes)
        }
        val chunks = m4aChunks.toMutableList()
        var splitTimeMs = 0L

        if (chunks.isEmpty()) {
            // M4A 分割失敗 → デコード方式にフォールバック
            onProgress(Progress(detailStatus = "AAC→WAV デコード中..."))
            val wavPath = withContext(Dispatchers.IO) {
                AudioDecoder.decodeToWav(source.absolutePath, cacheDir)
            }
            val wavFile = File(wavPath)
            onProgress(Progress(detailStatus = "WAV 分割中..."))
            val wavChunks = withContext(Dispatchers.IO) {
                WavByteSplitter.splitByBytes(wavFile, cacheDir, chunkSizeBytes)
            }
            if (wavChunks.isEmpty()) {
                // 分割不要（1 チャンクに収まる）→ デコード済み WAV をそのまま使用
                chunks.add(wavFile)
            } else {
                wavFile.delete()
                chunks.addAll(wavChunks)
            }
        } else {
            splitTimeMs = System.currentTimeMillis() - tSplit
            Log.d(TAG, "[TIMING] M4A split: ${splitTimeMs}ms, ${chunks.size} chunks")
            onProgress(Progress(
                detailStatus = "チャンク転写中...",
                totalChunks = chunks.size,
                splitTimeMs = splitTimeMs
            ))
        }

        // 全チャンクを並列 Whisper API で文字起こし（最大 3 並行・指数バックオフ付きリトライ）
        val totalChunks = chunks.size
        val semaphore = java.util.concurrent.Semaphore(3)
        var progress = Progress(
            detailStatus = "チャンク転写中...",
            totalChunks = totalChunks,
            splitTimeMs = splitTimeMs
        )
        onProgress(progress)
        // v0.9.1: チャンクループは必ず IO ディスパッチャで実行する。
        // 従来は viewModelScope（Main）を継承した async 内で semaphore.acquire() を
        // 呼んでいたため、チャンク数 > 並列度(3) のときにメインスレッドがブロックされ
        // UI がフリーズした（長時間音声の 4 チャンク目以降で発生）。
        val progressRef = java.util.concurrent.atomic.AtomicReference(progress)
        val results = withContext(Dispatchers.IO) {
            coroutineScope {
                chunks.mapIndexed { i, chunkFile ->
                    async {
                        val tChunkStart = System.currentTimeMillis()
                        semaphore.acquire()
                        try {
                            val transcript = transcribeWithRetry(whisperClient, chunkFile, "チャンク ${i + 1}/$totalChunks")
                            i to transcript.trim()
                        } catch (e: Exception) {
                            Log.e(TAG, "Chunk ${i + 1}/$totalChunks failed after retries: ${e.message}")
                            i to ""
                        } finally {
                            // 実測のチャンク処理時間で残り時間予測を逐次補正（ms/MB の移動平均）
                            val elapsed = System.currentTimeMillis() - tChunkStart
                            val chunkMb = (chunkFile.length() / (1024.0 * 1024.0)).coerceAtLeast(0.5)
                            chunkFile.delete()
                            semaphore.release()
                            val perMbMs = elapsed / chunkMb
                            progressRef.updateAndGet { p ->
                                val prevPerMb = if (p.completedChunks > 0)
                                    p.chunkTimeEstimateMs.toDouble() / chunkSizeMb
                                else perMbMs
                                val newPerMb = prevPerMb * 0.6 + perMbMs * 0.4
                                p.copy(
                                    completedChunks = p.completedChunks + 1,
                                    chunkTimeEstimateMs = (newPerMb * chunkSizeMb).toLong().coerceIn(15000L, 120000L)
                                )
                            }
                            onProgress(progressRef.get())
                        }
                    }
                }.map { it.await() }.sortedBy { (i, _) -> i }
            }
        }

        // チャンク番号順に結合。リトライ後も失敗したチャンクは警告として明示する
        val fullTranscript = StringBuilder()
        val failedChunks = mutableListOf<Int>()
        for ((i, text) in results) {
            if (text.isNotEmpty()) {
                if (fullTranscript.isNotEmpty()) fullTranscript.append(" ")
                fullTranscript.append(text)
            } else {
                failedChunks.add(i + 1)
            }
        }
        if (failedChunks.isNotEmpty()) {
            val warning = "【警告: チャンク ${failedChunks.joinToString(",")}/${totalChunks} の文字起こしに失敗しました。音声の一部が欠落しています。】"
            Log.w(TAG, warning)
            if (fullTranscript.isNotEmpty()) fullTranscript.append("\n$warning")
        }
        val result = fullTranscript.toString().trim()

        // 全チャンク失敗 → 空文字の成功として返さず例外にする
        if (result.isEmpty()) {
            throw IllegalStateException("全チャンクの文字起こしに失敗しました。ネットワーク状態を確認して再試行してください。")
        }

        // キャッシュディレクトリをクリーンアップ
        cacheDir.listFiles()?.forEach { it.delete() }
        return result
    }

    /** 設定から OpenAI Whisper クライアントを生成する（毎回最新の API Key を反映）。 */
    private suspend fun buildWhisperClient(): LlmClient? {
        val openAiConfig = settings.defaultProviders().firstOrNull { it.name == "OpenAI" } ?: return null
        val openAiKey = settings.getApiKey(openAiConfig.apiKeyRef)
        if (openAiKey.isNullOrBlank()) return null
        return provider.createClient(openAiConfig, openAiKey, openAiConfig.defaultModel)
    }

    /** Whisper API への送信を指数バックオフ付きでリトライする。恒久障害は即時スロー。 */
    private suspend fun transcribeWithRetry(
        whisperClient: LlmClient,
        audioFile: File,
        label: String
    ): String {
        var attempt = 0
        while (true) {
            try {
                return whisperClient.transcribeOnly(audioFile).trim()
            } catch (e: Exception) {
                if (!isRetryable(e) || attempt >= MAX_CHUNK_RETRIES) throw e
                val delayMs = CHUNK_RETRY_BASE_DELAY_MS * (1L shl attempt)
                Log.w(TAG, "$label failed (${e.message}), retry in ${delayMs}ms (${attempt + 1}/$MAX_CHUNK_RETRIES)")
                delay(delayMs)
                attempt++
            }
        }
    }

    /** AAC フレーム 1 個分のデータ */
    private data class AudioFrame(
        val data: ByteArray,
        val presentationTimeUs: Long,
        val flags: Int
    )

    /**
     * M4A/AAC ファイルを [chunkSizeBytes] 以下のチャンクに分割する。
     * 全フレームをメモリに保持せず、1 チャンク分だけを蓄積して逐次書き出す
     * ストリーミング方式（長時間音声でもメモリ消費はチャンクサイズに比例）。
     *
     * @return 分割後のチャンクファイル。分割不要・読み込み不可・非対応コーデックは空リスト
     */
    private fun splitM4aIntoChunks(source: File, outputDir: File, chunkSizeBytes: Long): List<File> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.absolutePath)
        } catch (e: Exception) {
            Log.w(TAG, "MediaExtractor cannot read $source, falling back: ${e.message}")
            return emptyList()
        }

        val trackIndex = findAudioTrackM4a(extractor)
        if (trackIndex < 0) {
            Log.w(TAG, "No audio track found, falling back")
            extractor.release()
            return emptyList()
        }

        val format = extractor.getTrackFormat(trackIndex)
        extractor.selectTrack(trackIndex)

        // 1 チャンク分のフレームだけを保持して書き出す
        // （v0.9.1 検証メモ: 読みながら直接 MediaMuxer へ書く方式は、読み取り中に
        //  muxer.stop() を挟むためか当該端末で約48秒と逆に遅くなった。従来の
        //  「全フレーム読込 → まとめて書き出し」に戻す。真の高速化は方案A（MediaMuxer
        //  を使わないバイト範囲分割）で対応する。）
        val chunkFrames = mutableListOf<AudioFrame>()
        var currentChunkBytes = 0L
        val chunks = mutableListOf<File>()
        // 読み取りバッファは 1 つ使い回す
        val buf = ByteBuffer.allocate(8192)

        try {
            var chunkIdx = 0
            while (true) {
                buf.clear()
                val sampleSize = extractor.readSampleData(buf, 0)
                if (sampleSize < 0) break

                val data = ByteArray(sampleSize)
                buf.rewind()
                buf.get(data)
                chunkFrames.add(AudioFrame(data, extractor.sampleTime, extractor.sampleFlags))
                currentChunkBytes += sampleSize

                if (currentChunkBytes >= chunkSizeBytes) {
                    chunks.add(writeM4aChunkFile(outputDir, format, chunkFrames, chunkIdx))
                    chunkFrames.clear()
                    currentChunkBytes = 0L
                    chunkIdx++
                }
                if (!extractor.advance()) break
            }

            // 分割不要（全体が 1 チャンクに収まる）→ 元ファイルをそのまま使う
            if (chunks.isEmpty()) return emptyList()

            // 残りフレームを最終チャンクとして書き出す
            if (chunkFrames.isNotEmpty()) {
                chunks.add(writeM4aChunkFile(outputDir, format, chunkFrames, chunkIdx))
            }
        } catch (e: Exception) {
            Log.w(TAG, "splitM4aIntoChunks failed, falling back: ${e.message}")
            chunks.forEach { it.delete() }
            return emptyList()
        } finally {
            extractor.release()
        }

        Log.d(TAG, "splitM4aIntoChunks: ${source.length()}B → ${chunks.size} chunks")
        return chunks
    }

    /** フレームリストを 1 つの M4A チャンクとして書き出し、ファイルを返す。失敗時はファイルを削除して例外を再送出。 */
    private fun writeM4aChunkFile(
        outputDir: File,
        format: MediaFormat,
        frames: List<AudioFrame>,
        chunkIdx: Int
    ): File {
        val chunkFile = File(outputDir, "chunk_${chunkIdx}_${System.nanoTime()}.m4a")
        try {
            writeM4aChunk(chunkFile, format, frames, 0, frames.size)
            return chunkFile
        } catch (e: Exception) {
            chunkFile.delete()
            throw e
        }
    }

    private fun findAudioTrackM4a(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return -1
    }

    /** フレーム範囲を M4A ファイルとして書き出す。 */
    private fun writeM4aChunk(
        outputFile: File,
        format: MediaFormat,
        frames: List<AudioFrame>,
        start: Int,
        end: Int
    ) {
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val trackId = muxer.addTrack(format)
            muxer.start()
            for (i in start until end) {
                val f = frames[i]
                val buf = ByteBuffer.wrap(f.data)
                val info = MediaCodec.BufferInfo().apply {
                    size = f.data.size
                    flags = f.flags
                    presentationTimeUs = f.presentationTimeUs
                    offset = 0
                }
                muxer.writeSampleData(trackId, buf, info)
            }
        } finally {
            muxer.stop()
            muxer.release()
        }
    }

    /** raw AAC (ADTS) を M4A コンテナにラップする。失敗時は null。 */
    private fun wrapAacInM4a(source: File, outputDir: File): File? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.absolutePath)
        } catch (e: Exception) {
            Log.w(TAG, "wrapAacInM4a: cannot read $source: ${e.message}")
            return null
        }
        val trackIndex = findAudioTrackM4a(extractor)
        if (trackIndex < 0) { extractor.release(); return null }
        val format = extractor.getTrackFormat(trackIndex)
        extractor.selectTrack(trackIndex)

        val frames = mutableListOf<AudioFrame>()
        while (true) {
            val buf = ByteBuffer.allocate(8192)
            val sampleSize = extractor.readSampleData(buf, 0)
            if (sampleSize < 0) break
            val data = ByteArray(sampleSize)
            buf.rewind(); buf.get(data)
            frames.add(AudioFrame(data, extractor.sampleTime, extractor.sampleFlags))
            if (!extractor.advance()) break
        }
        extractor.release()
        if (frames.isEmpty()) return null

        val outFile = File(outputDir, "m4a_wrapped_${System.nanoTime()}.m4a")
        writeM4aChunk(outFile, format, frames, 0, frames.size)
        Log.d(TAG, "wrapAacInM4a: ${source.length()}B → ${outFile.absolutePath} (${frames.size} frames)")
        return outFile
    }

    companion object {
        private const val TAG = "CloudWhisper"
        /** チャンク転写の最大リトライ回数（初回呼び出しに加えて最大 3 回再試行） */
        private const val MAX_CHUNK_RETRIES = 3
        /** リトライ初回の待機時間(ms)。指数バックオフ: 1s → 2s → 4s */
        private const val CHUNK_RETRY_BASE_DELAY_MS = 1000L

        /**
         * 一時的な障害（リトライ有効）かどうか。
         * 429 レート制限 / 5xx サーバーエラー / ネットワーク断・タイムアウトのみリトライする。
         */
        fun isRetryable(e: Throwable): Boolean = when (e) {
            is LlmException.RateLimited -> true
            is LlmException.ServerError -> true
            is LlmException.NetworkError -> true
            is LlmException.Timeout -> true
            else -> e is java.io.IOException
        }

        /**
         * 先頭 2 バイトが ADTS 同期ワード (0xFFFx) かを確認して raw AAC を検出。
         * OpenAI Whisper API は raw AAC をサポートしていないため、事前に WAV 変換が必要。
         */
        private fun isRawAacFile(file: File): Boolean {
            return try {
                val bytes = file.readBytes()
                bytes.size >= 2 &&
                    (bytes[0].toInt() and 0xFF) == 0xFF &&
                    (bytes[1].toInt() and 0xF0) == 0xF0
            } catch (e: Exception) {
                false
            }
        }
    }
}
