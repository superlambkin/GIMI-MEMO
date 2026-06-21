package com.gijimemo.llm

import android.content.Context
import android.util.Log
import com.gijimemo.data.model.LlmCallMode
import com.gijimemo.whisper.AudioDecoder
import com.gijimemo.whisper.ModelManager
import com.gijimemo.whisper.WhisperModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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
        val modelName = "ggml-base-q5_1.bin"
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

        try {
            // 3. Decode AAC → WAV
            val wavFile = withContext(Dispatchers.IO) {
                val outDir = File(context.cacheDir, "whisper_decoded")
                outDir.mkdirs()
                AudioDecoder.decodeToWav(audioFile.absolutePath, outDir)
            }

            // 4. Transcribe (v0.7.2: 30秒窓 + 2秒オーバーラップで高精度・高速化)
            //    WhisperModelImpl なら transcribeFileWithOverlap()、それ以外は通常経路。
            val result = if (whisperModel is com.gijimemo.whisper.WhisperModelImpl) {
                whisperModel.transcribeFileWithOverlap(File(wavFile), languageHint)
            } else {
                whisperModel.transcribeFile(File(wavFile), languageHint)
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
        val modelName = "ggml-base-q5_1.bin"
        val info = modelManager.availableModels.find { it.name == modelName }
        if (info == null || !modelManager.isModelDownloaded(modelName)) {
            return "オンデバイスWhisper: モデル未ダウンロード"
        }
        // Ensure extraction happened (cheap if already present).
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
            val modelName = "ggml-base-q5_1.bin"
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

    companion object {
        private const val TAG = "OnDeviceWhisper"
    }
}
