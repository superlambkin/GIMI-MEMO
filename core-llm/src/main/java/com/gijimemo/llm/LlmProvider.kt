package com.gijimemo.llm

import android.content.Context
import android.util.Log
import com.gijimemo.data.model.LlmCallMode
import com.gijimemo.data.model.LlmProviderConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LlmProvider @Inject constructor(
    private val okHttpClient: OkHttpClient,
    @ApplicationContext private val context: Context,
    private val onDeviceWhisperClient: OnDeviceWhisperClient? = null
) {
    /**
     * 构造 LlmClient。
     * @param model 用户在设置中选的具体模型；为 null 时回退到 config.defaultModel
     * @param useOnDeviceAsr true 时使用オンデバイスWhisper（文字起こし）+ クラウドLLM（要約）
     * @param useGpu v0.7.2: Whisper+要約経路のみ true で OpenCL/GPU 経由の高速化
     * @param langHint Whisper 言語ヒント "ja"/"zh" 等。null/空なら自動検出。
     */
    fun createClient(
        config: LlmProviderConfig,
        apiKey: String,
        model: String? = null,
        useOnDeviceAsr: Boolean = false,
        useGpu: Boolean = false,
        langHint: String? = null
    ): LlmClient {
        if (useOnDeviceAsr) {
            val onDevice = onDeviceWhisperClient
                ?: error("On-device Whisper not available. Is core-whisper module included?")
            onDevice.configure(LlmOptions(
                baseUrl = config.baseUrl,
                apiKey = apiKey,
                model = model ?: config.defaultModel,
                callMode = LlmOptions.CallMode.WHISPER_THEN_SUMMARY,
                prompt = ""
            ))
            onDevice.setLanguageHint(langHint)
            // v0.7.2: useGpu は OnDeviceWhisperClient 生成時に固定されるため、
            // ここではフラグを記録してクライアント側がロード時に参照する設計にする。
            // OnDeviceWhisperClient 自体は Immutable で useGpu はコンストラクタ固定のため、
            // 設定変更時の再生成は ProcessingViewModel 側で制御する。
            if (useGpu) Log.d("LlmProvider", "createClient: useGpu=true (OpenCL enabled for Whisper)")
            return onDevice
        }
        return WrappedLlmClient(okHttpClient, context, config, apiKey, model)
    }

    companion object {
        /**
         * 静态工厂方法（用于测试直接调用，无需 Hilt 注入）。
         */
        fun createClient(config: LlmProviderConfig, apiKey: String, okHttpClient: OkHttpClient, context: Context, model: String? = null): LlmClient {
            return WrappedLlmClient(okHttpClient, context, config, apiKey, model)
        }
    }
}

private class WrappedLlmClient(
    private val okHttpClient: OkHttpClient,
    private val context: Context,
    private val config: LlmProviderConfig,
    private val apiKey: String,
    private val modelOverride: String?
) : LlmClient {
    private val delegate = OpenAiCompatibleClient(okHttpClient, context)

    private fun buildOptions(prompt: String, mode: LlmCallMode = LlmCallMode.WHISPER_THEN_SUMMARY): LlmOptions {
        // モード変換は「MULTIMODAL 非対応プロバイダへのフォールバック」のみ。
        // 逆方向（WHISPER_THEN_SUMMARY → MULTIMODAL）は行わない:
        //  - ユーザーが明示的に Whisper+要約を選んだ場合
        //  - ViewModel がファイルサイズ超過でフォールバックした場合
        //  のいずれでも従うべきだから。
        val effectiveMode = when {
            mode == LlmCallMode.MULTIMODAL && !config.supportsMultimodal -> LlmCallMode.WHISPER_THEN_SUMMARY
            else -> mode
        }
        return LlmOptions(
            baseUrl = config.baseUrl,
            apiKey = apiKey,
            model = modelOverride ?: config.defaultModel,
            callMode = when (effectiveMode) {
                LlmCallMode.MULTIMODAL -> LlmOptions.CallMode.MULTIMODAL
                LlmCallMode.WHISPER_THEN_SUMMARY -> LlmOptions.CallMode.WHISPER_THEN_SUMMARY
            },
            prompt = prompt
        )
    }

    override fun transcribeAndFormat(
        audioFile: File,
        prompt: String,
        mode: LlmCallMode
    ): Flow<LlmEvent> {
        return delegate.transcribeAndFormat(audioFile, buildOptions(prompt, mode))
    }

    override suspend fun transcribeOnly(audioFile: File): String {
        return delegate.transcribeOnly(audioFile, buildOptions(""))
    }

    override fun summarizeOnly(text: String, prompt: String): Flow<LlmEvent> {
        return delegate.summarizeOnly(text, buildOptions(prompt))
    }

    override suspend fun testConnection(): String {
        return delegate.testConnection(baseUrl = config.baseUrl, apiKey = apiKey, model = modelOverride ?: config.defaultModel)
    }
}
