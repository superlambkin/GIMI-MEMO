package com.gijimemo.data.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LlmProviderConfigTest {
    @Test
    fun `default providers include MiniMax as first`() {
        val providers = LlmProviderConfig.defaults()
        assertThat(providers.first().name).isEqualTo("MiniMax")
    }

    @Test
    fun `default providers count is 5`() {
        assertThat(LlmProviderConfig.defaults().size).isEqualTo(5)
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