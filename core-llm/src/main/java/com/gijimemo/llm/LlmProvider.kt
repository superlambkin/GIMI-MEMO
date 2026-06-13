package com.gijimemo.llm

import com.gijimemo.data.model.LlmCallMode
import com.gijimemo.data.model.LlmProviderConfig
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LlmProvider @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    /**
     * 构造 LlmClient 及其配置。所有 provider 共用 OpenAI 兼容协议。
     * @param model 用户在设置中选的具体模型；为 null 时回退到 config.defaultModel
     */
    fun createClient(config: LlmProviderConfig, apiKey: String, model: String? = null): LlmClient {
        return WrappedLlmClient(okHttpClient, config, apiKey, model)
    }

    companion object {
        /**
         * 静态工厂方法（用于测试直接调用，无需 Hilt 注入）。
         */
        fun createClient(config: LlmProviderConfig, apiKey: String, okHttpClient: OkHttpClient, model: String? = null): LlmClient {
            return WrappedLlmClient(okHttpClient, config, apiKey, model)
        }
    }
}

private class WrappedLlmClient(
    private val okHttpClient: OkHttpClient,
    private val config: LlmProviderConfig,
    private val apiKey: String,
    private val modelOverride: String?
) : LlmClient {
    private val delegate = OpenAiCompatibleClient(okHttpClient)

    override fun transcribeAndFormat(
        audioFile: java.io.File,
        prompt: String,
        mode: LlmCallMode
    ): Flow<LlmEvent> {
        // Auto-fallback: MiniMax / ClaudeProxy 不支持 Whisper 端点 (/audio/transcriptions 返 404)
        // 当用户选择 WHISPER 但 provider 支持多模态时，强制改用 MULTIMODAL
        val effectiveMode = when {
            mode == LlmCallMode.MULTIMODAL && !config.supportsMultimodal -> LlmCallMode.WHISPER_THEN_SUMMARY
            mode == LlmCallMode.WHISPER_THEN_SUMMARY && config.supportsMultimodal -> LlmCallMode.MULTIMODAL
            else -> mode
        }
        val options = LlmOptions(
            baseUrl = config.baseUrl,
            apiKey = apiKey,
            model = modelOverride ?: config.defaultModel,
            callMode = when (effectiveMode) {
                LlmCallMode.MULTIMODAL -> LlmOptions.CallMode.MULTIMODAL
                LlmCallMode.WHISPER_THEN_SUMMARY -> LlmOptions.CallMode.WHISPER_THEN_SUMMARY
            },
            prompt = prompt
        )
        return delegate.transcribeAndFormat(audioFile, options)
    }

    override suspend fun testConnection(): String {
        return delegate.testConnection(baseUrl = config.baseUrl, apiKey = apiKey, model = modelOverride ?: config.defaultModel)
    }
}
