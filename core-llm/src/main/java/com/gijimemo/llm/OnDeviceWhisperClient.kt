package com.gijimemo.llm

import android.content.Context
import android.util.Log
import com.gijimemo.data.model.LlmCallMode
import com.gijimemo.data.repository.SettingsRepository
import com.gijimemo.whisper.AudioDecoder
import com.gijimemo.whisper.ModelManager
import com.gijimemo.whisper.WhisperModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * LlmClient 実装: 文字起こしはオンデバイス Whisper (whisper.cpp)、
 * 要約はクラウド LLM API を使用する。
 *
 * 二段階フロー:
 *   1. [transcribeOnly] → 端末内 Whisper で文字起こし
 *   2. ユーザー確認後 → [summarizeOnly] → クラウド LLM で要約
 *
 * Note: Provided manually via LlmModule (not @Inject) to avoid Hilt cross-module
 * metadata issues with core-whisper.
 */
class OnDeviceWhisperClient(
    private val context: Context,
    private val whisperModel: WhisperModel,
    private val modelManager: ModelManager,
    private val openAiClient: OpenAiCompatibleClient,
    /** v0.7.2: Whisper+要約経路のみ true。OpenCL/GPU 経由の高速化。 */
    private val settings: SettingsRepository? = null,
    private val useGpu: Boolean = false
) : LlmClient {

    private var currentOptions: LlmOptions? = null
    /** ユーザー指定の言語ヒント ("ja" / "zh" / null = auto)。configure() で設定。 */
    private var languageHint: String? = null

    override fun transcribeAndFormat(
        audioFile: File,
        prompt: String,
        mode: LlmCallMode
    ): Flow<LlmEvent> = flow {
        // Phase 1: 文字起こし
        val transcript = this@OnDeviceWhisperClient.transcribeOnly(audioFile)
        emit(LlmEvent.Delta("\n[文字起こし完了]\n\n$transcript\n\n[要約を生成中...]\n\n"))
        // Phase 2: 要約
        val options = currentOptions ?: error("LLM options not configured")
        val summaryFlow = summarizeOnly(transcript, prompt)
        summaryFlow.collect { emit(it) }
    }

    override suspend fun transcribeOnly(audioFile: File): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "transcribeOnly: ${audioFile.name}")

        // 1. Ensure bundled model is extracted to filesDir (no-op after first launch).
        //    For non-bundled models, fall back to network download.
        val modelName = settings?.let {
            try { kotlinx.coroutines.runBlocking { it.whisperModel.first() } } catch (_: Exception) { null }
        } ?: "ggml-tiny-q5_1.bin"
        val info = modelManager.availableModels.find { it.name == modelName }
        if (info != null && info.isBundled) {
            modelManager.ensureBundledModel(modelName)
        } else if (!modelManager.isModelDownloaded(modelName)) {
            Log.d(TAG, "Model not downloaded, starting download...")
            modelManager.downloadModel(modelName)
        }

        val modelFile = modelManager.getModelFile(modelName)
        Log.d(TAG, "Using model: ${modelFile.absolutePath} (${modelFile.length()} bytes)")

        // 2. Load model (skip if already loaded by preload)
        if (!whisperModel.isLoaded) {
            Log.d(TAG, "Whisper model not preloaded; loading now (this is slow)")
            whisperModel.load(modelFile, useGpu)
        } else {
            Log.d(TAG, "Whisper model already preloaded — skip load")
        }

        // v0.7.4: Silero VAD モデルを assets から展開
        val vadModelFile = extractVadModel()

        try {
            // 3. Decode AAC → WAV
            val wavFile = withContext(Dispatchers.IO) {
                val outDir = File(context.cacheDir, "whisper_decoded")
                outDir.mkdirs()
                AudioDecoder.decodeToWav(audioFile.absolutePath, outDir)
            }

            // 4. Transcribe (v0.7.2: 30秒窓 + 2秒オーバーラップで高精度・高速化)
            //    v0.7.4: VAD モデルパスを渡して無音区間スキップ
            val wavFileForTranscribe = File(wavFile)
            val result = if (whisperModel is com.gijimemo.whisper.WhisperModelImpl) {
                (whisperModel as com.gijimemo.whisper.WhisperModelImpl).transcribeFileWithOverlap(
                    wavFileForTranscribe, languageHint, vadModelFile?.absolutePath
                )
            } else {
                whisperModel.transcribeFile(wavFileForTranscribe, languageHint)
            }

            // 5. Cleanup temp WAV
            File(wavFile).delete()

            Log.d(TAG, "Transcription complete: ${result.length} chars")
            result
        } finally {
            // 6. Release model
            //    NOTE: 録音中に preload した場合、ここで release してしまうと
            //    次回の文字起こしでまた load し直しになる。Singleton 化されている
            //    ので、メモリリリースはアプリ終了時の onCleared に任せる方が
            //    ユーザー体験が良い。
            //    一旦保持し続ける。
            // whisperModel.release()
        }
    }

    /**
     * v0.7.x: PCM Flow を 3秒窓 + 0.5秒オーバーラップで逐次推論する。
     *
     * アルゴリズム:
     *   1. [pcmFlow] を collect → 16bit PCM のリングバッファ (capacity = windowMs + overlapMs ぶん) に追記。
     *   2. バッファ長 ≥ windowMs + overlapMs (3.5秒) になるたび、
     *      末尾 windowMs (3秒) 分を FloatArray に正規化して推論。
     *   3. 推論結果を [TranscriptDelta] として emit。
     *   4. バッファを (windowMs - overlapMs) ぶん (= 2.5秒相当分) 前進 (シフトではなく copy で簡略化)。
     *   5. [pcmFlow] 完了時、バッファ残余を最終窓として推論 (残 < overlapMs なら破棄)。
     *
     * 単一スレッド・順次処理 (Mutex 不要)。呼び出し側で `flowOn(Dispatchers.IO)` 推奨。
     */
    override fun transcribeStream(
        pcmFlow: Flow<ShortArray>,
        language: String,
        sampleRate: Int,
        windowMs: Long,
        overlapMs: Long,
        vadModelPath: String?,
    ): Flow<TranscriptDelta> = flow {
        // 0. Whisper モデルが未ロードならロード (preload 済みなら即 return)
        if (!whisperModel.isLoaded) {
            Log.w(TAG, "transcribeStream: model not preloaded, falling back to preloadModel()")
            preloadModel()
        }
        if (!whisperModel.isLoaded) {
            error("Whisper model not loaded. Call preloadModel() first.")
        }

        // 1. パラメータ計算 (sample 数)
        val windowSamples = (windowMs * sampleRate / 1000).toInt()        // 48000 (3s @16kHz)
        val strideSamples = ((windowMs - overlapMs) * sampleRate / 1000).toInt() // 40000 (2.5s)
        val maxBufferSamples = windowSamples + ((overlapMs * sampleRate / 1000).toInt()) // 56000 (3.5s)
        val minFinalSamples = (overlapMs * sampleRate / 1000).toInt()    // 8000 (0.5s) 未満は破棄

        // 2. VAD モデル (transcribeOnly と同じ extract 経路を共有)
        val effectiveVadPath = vadModelPath ?: extractVadModel()?.absolutePath

        // 3. リングバッファ (可変長 ArrayList<Short> で代用。capacity 上限 3.5秒 ≒ 112KB)
        val buffer = ArrayList<Short>(maxBufferSamples)
        var totalSamplesConsumed = 0L  // オーディオ全体基準のオフセット計算用

        suspend fun processWindow(startInBuffer: Int, sampleLen: Int, offsetMs: Long) {
            val floatArr = FloatArray(sampleLen)
            for (i in 0 until sampleLen) {
                // -1.0..1.0 正規化 (WhisperModelImpl.readWavAsFloat 111-114 と同等)
                floatArr[i] = buffer[startInBuffer + i].toFloat() / 32768f
            }
            val segments = whisperModel.transcribeChunk(floatArr, offsetMs, language, effectiveVadPath)
            for (seg in segments) {
                if (seg.text.isBlank()) continue
                emit(TranscriptDelta(text = seg.text, t0Ms = seg.startMs, t1Ms = seg.endMs, isFinal = false))
            }
        }

        pcmFlow.collect { chunk ->
            // バッファに追加
            for (s in chunk) buffer.add(s)
            // 窓推論ループ: 3.5秒以上溜まっている限り、3秒窓を推論 → 2.5秒ぶん前進
            while (buffer.size >= maxBufferSamples) {
                val startIdx = buffer.size - windowSamples
                val offsetMs = (totalSamplesConsumed + startIdx) * 1000 / sampleRate
                processWindow(startIdx, windowSamples, offsetMs)
                // 先頭 strideSamples を残し、残り (overlapMs ぶん) を削除
                buffer.subList(0, buffer.size - strideSamples).clear()
                totalSamplesConsumed += strideSamples.toLong()
            }
        }

        // 4. ストリーム完了後の最終窓
        if (buffer.size >= minFinalSamples) {
            val offsetMs = totalSamplesConsumed * 1000 / sampleRate
            processWindow(0, buffer.size, offsetMs)
        } else if (buffer.isNotEmpty()) {
            Log.d(TAG, "transcribeStream: dropping final ${buffer.size} samples (< ${minFinalSamples})")
        }
    }

    override fun summarizeOnly(text: String, prompt: String): Flow<LlmEvent> {
        val options = currentOptions ?: error("LLM options not configured. Call configure() first.")
        Log.d(TAG, "summarizeOnly: ${text.length} chars, model=${options.model}")
        return openAiClient.summarizeOnly(text, LlmOptions(
            baseUrl = options.baseUrl,
            apiKey = options.apiKey,
            model = options.model,
            callMode = LlmOptions.CallMode.WHISPER_THEN_SUMMARY,
            prompt = prompt
        ))
    }

    override suspend fun testConnection(): String {
        val modelName = settings?.whisperModel?.first() ?: "ggml-tiny-q5_1.bin"
        val info = modelManager.availableModels.find { it.name == modelName }
        if (info == null || !modelManager.isModelDownloaded(modelName)) {
            return "オンデバイスWhisper: モデル未ダウンロード"
        }
        if (info.isBundled) {
            modelManager.ensureBundledModel(modelName)
        }
        val modelFile = modelManager.getModelFile(modelName)
        return if (modelFile.exists()) {
            "オンデバイスWhisper: モデル準備完了 (${modelFile.length() / 1024 / 1024}MB)"
        } else {
            "オンデバイスWhisper: モデルファイルが見つかりません"
        }
    }

    /**
     * 設定情報を適用する。トランスクライブ前に必ず呼ぶこと。
     */
    fun configure(options: LlmOptions) {
        this.currentOptions = options
    }

    /** ユーザー指定の言語ヒントを設定する ("ja"/"zh"/null)。 */
    fun setLanguageHint(lang: String?) {
        this.languageHint = lang?.takeIf { it.isNotBlank() }
    }

    /**
     * Whisper モデルを事前ロードする。
     * 録音開始時など、文字起こし開始前に呼ぶことで、後のロード待ち時間 (30〜90秒) を削除できる。
     * - 既にロード済みなら no-op (Singleton なのでアプリ全体で 1 度だけ走る)
     * - バンドルモデルが未展開なら展開も行う
     * 失敗時はログ警告のみ (致命的ではない、実際の transcribe 時にリトライされる)
     */
    suspend fun preloadModel() = withContext(Dispatchers.IO) {
        if (whisperModel.isLoaded) {
            Log.d(TAG, "preloadModel: already loaded, skip")
            return@withContext
        }
        try {
            val modelName = settings?.whisperModel?.first() ?: "ggml-tiny-q5_1.bin"
            val info = modelManager.availableModels.find { it.name == modelName }
            if (info != null && info.isBundled) {
                modelManager.ensureBundledModel(modelName)
            } else if (!modelManager.isModelDownloaded(modelName)) {
                Log.d(TAG, "preloadModel: model not downloaded, skipping (will download at transcribe)")
                return@withContext
            }
            val modelFile = modelManager.getModelFile(modelName)
            Log.d(TAG, "preloadModel: loading ${modelFile.absolutePath}")
            val t0 = System.currentTimeMillis()
            whisperModel.load(modelFile, useGpu)
            Log.d(TAG, "preloadModel: loaded in ${System.currentTimeMillis() - t0}ms")
        } catch (e: Exception) {
            Log.w(TAG, "preloadModel failed (will retry at transcribe time): ${e.message}", e)
        }
    }

    /** Silero VAD モデルを assets から filesDir に展開する。 */
    private fun extractVadModel(): File? {
        return try {
            val target = File(context.filesDir, "whisper_models/ggml-silero-vad.bin")
            if (target.exists()) {
                Log.d(TAG, "VAD model already extracted: ${target.absolutePath}")
                return target
            }
            target.parentFile?.mkdirs()
            context.assets.open("ggml-silero-vad.bin").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            Log.d(TAG, "VAD model extracted: ${target.absolutePath} (${target.length()} bytes)")
            target
        } catch (e: Exception) {
            Log.w(TAG, "VAD model extract failed: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "OnDeviceWhisper"
    }
}
