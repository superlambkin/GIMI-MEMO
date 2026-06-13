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
     */
    fun createClient(config: LlmProviderConfig, apiKey: String): LlmClient {
        return WrappedLlmClient(okHttpClient, config, apiKey)
    }

    companion object {
        /**
         * 静态工厂方法（用于测试直接调用，无需 Hilt 注入）。
         */
        fun createClient(config: LlmProviderConfig, apiKey: String, okHttpClient: OkHttpClient): LlmClient {
            return WrappedLlmClient(okHttpClient, config, apiKey)
        }
    }
}

private class WrappedLlmClient(
    private val okHttpClient: OkHttpClient,
    private val config: LlmProviderConfig,
    private val apiKey: String
) : LlmClient {
    private val delegate = OpenAiCompatibleClient(okHttpClient)

    override fun transcribeAndFormat(
        audioFile: java.io.File,
        prompt: String,
        mode: LlmCallMode
    ): Flow<LlmEvent> {
        val effectiveMode = if (mode == LlmCallMode.MULTIMODAL && !config.supportsMultimodal) {
            LlmCallMode.WHISPER_THEN_SUMMARY
        } else mode
        val options = LlmOptions(
            baseUrl = config.baseUrl,
            apiKey = apiKey,
            model = config.defaultModel,
            callMode = when (effectiveMode) {
                LlmCallMode.MULTIMODAL -> LlmOptions.CallMode.MULTIMODAL
                LlmCallMode.WHISPER_THEN_SUMMARY -> LlmOptions.CallMode.WHISPER_THEN_SUMMARY
            },
            prompt = prompt
        )
        return delegate.transcribeAndFormat(audioFile, options)
    }
}
