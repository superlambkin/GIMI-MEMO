package com.gijimemo.data.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LlmProviderConfigTest {
    @Test
    fun `default providers include MiniMax CN as first`() {
        val providers = LlmProviderConfig.defaults()
        assertThat(providers.first().name).isEqualTo("MiniMax 国内")
    }

    @Test
    fun `default providers count is 6`() {
        assertThat(LlmProviderConfig.defaults().size).isEqualTo(6)
    }

    @Test
    fun `MiniMax CN uses minimaxi dot com base url`() {
        val p = LlmProviderConfig.defaults().findByName("MiniMax 国内")
        assertThat(p).isNotNull()
        assertThat(p!!.baseUrl).isEqualTo("https://api.minimaxi.com/v1")
        assertThat(p.apiKeyRef).isEqualTo("apikey_minimax_cn")
    }

    @Test
    fun `MiniMax Overseas uses minimax dot io base url`() {
        val p = LlmProviderConfig.defaults().findByName("MiniMax 海外")
        assertThat(p).isNotNull()
        assertThat(p!!.baseUrl).isEqualTo("https://api.minimax.io/v1")
        assertThat(p.apiKeyRef).isEqualTo("apikey_minimax_overseas")
    }

    @Test
    fun `MiniMax CN and Overseas have independent apiKeyRef`() {
        val providers = LlmProviderConfig.defaults()
        val cn = providers.findByName("MiniMax 国内")
        val overseas = providers.findByName("MiniMax 海外")
        assertThat(cn).isNotNull()
        assertThat(overseas).isNotNull()
        assertThat(cn!!.apiKeyRef).isNotEqualTo(overseas!!.apiKeyRef)
    }

    @Test
    fun `findByName returns matching provider`() {
        val p = LlmProviderConfig.defaults().findByName("DeepSeek")
        assertThat(p).isNotNull()
        assertThat(p!!.supportsMultimodal).isFalse()
    }

    @Test
    fun `findByName returns null for unknown`() {
        assertThat(LlmProviderConfig.defaults().findByName("Fake")).isNull()
    }

    @Test
    fun `LlmCallMode enum has both modes`() {
        val names = LlmCallMode.entries.map { it.name }
        assertThat(names).containsExactly("MULTIMODAL", "WHISPER_THEN_SUMMARY")
    }
}