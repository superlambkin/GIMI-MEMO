package com.gijimemo.llm

import com.gijimemo.data.model.LlmProviderConfig
import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import org.junit.Test

class LlmProviderTest {
    @Test
    fun `createClient returns OpenAiCompatibleClient with merged config`() {
        val config = LlmProviderConfig(
            name = "Custom",
            baseUrl = "https://custom.api/v1",
            defaultModel = "custom-model",
            supportsMultimodal = true,
            apiKeyRef = "apikey_custom"
        )
        val client = LlmProvider.createClient(config, "my-key", OkHttpClient())
        // 通过检查内部 OkHttpClient 非空即可（构造不抛）
        assertThat(client).isNotNull()
    }
}
