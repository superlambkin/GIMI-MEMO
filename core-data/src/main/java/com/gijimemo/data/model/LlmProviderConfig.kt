package com.gijimemo.data.model

data class LlmProviderConfig(
    val name: String,
    val baseUrl: String,
    val defaultModel: String,
    val supportsMultimodal: Boolean,
    val apiKeyRef: String
) {
    companion object {
        fun defaults(): List<LlmProviderConfig> = listOf(
            LlmProviderConfig(
                name = "MiniMax",
                baseUrl = "https://api.MiniMax.com/v1",
                defaultModel = "MiniMax-M3",
                supportsMultimodal = true,
                apiKeyRef = "apikey_MiniMax"
            ),
            LlmProviderConfig(
                name = "OpenAI",
                baseUrl = "https://api.openai.com/v1",
                defaultModel = "gpt-4o-audio-preview",
                supportsMultimodal = true,
                apiKeyRef = "apikey_openai"
            ),
            LlmProviderConfig(
                name = "ClaudeProxy",
                baseUrl = "",
                defaultModel = "claude-sonnet-4-6",
                supportsMultimodal = true,
                apiKeyRef = "apikey_claude_proxy"
            ),
            LlmProviderConfig(
                name = "DeepSeek",
                baseUrl = "https://api.deepseek.com/v1",
                defaultModel = "deepseek-chat",
                supportsMultimodal = false,
                apiKeyRef = "apikey_deepseek"
            ),
            LlmProviderConfig(
                name = "Ollama",
                baseUrl = "http://10.0.2.2:11434/v1",
                defaultModel = "llama3.1",
                supportsMultimodal = false,
                apiKeyRef = "apikey_ollama"
            )
        )
    }
}

fun List<LlmProviderConfig>.findByName(name: String): LlmProviderConfig? =
    firstOrNull { it.name == name }