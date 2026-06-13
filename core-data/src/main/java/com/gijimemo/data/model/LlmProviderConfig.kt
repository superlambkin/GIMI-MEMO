package com.gijimemo.data.model

data class LlmProviderConfig(
    val name: String,
    val baseUrl: String,
    val defaultModel: String,
    val supportedModels: List<String>,
    val supportsMultimodal: Boolean,
    val apiKeyRef: String
) {
    companion object {
        fun defaults(): List<LlmProviderConfig> = listOf(
            LlmProviderConfig(
                name = "MiniMax 国内",
                baseUrl = "https://api.minimaxi.com/v1",
                defaultModel = "MiniMax-M3",
                supportedModels = listOf("MiniMax-M3", "MiniMax-Text-01", "MiniMax-VL-01"),
                supportsMultimodal = true,
                apiKeyRef = "apikey_minimax_cn"
            ),
            LlmProviderConfig(
                name = "MiniMax 海外",
                baseUrl = "https://api.minimax.io/v1",
                defaultModel = "MiniMax-M3",
                supportedModels = listOf("MiniMax-M3", "MiniMax-Text-01", "MiniMax-VL-01"),
                supportsMultimodal = true,
                apiKeyRef = "apikey_minimax_overseas"
            ),
            LlmProviderConfig(
                name = "OpenAI",
                baseUrl = "https://api.openai.com/v1",
                defaultModel = "gpt-4o-audio-preview",
                supportedModels = listOf("gpt-4o-audio-preview", "gpt-4o", "gpt-4o-mini", "gpt-4-turbo"),
                supportsMultimodal = true,
                apiKeyRef = "apikey_openai"
            ),
            LlmProviderConfig(
                name = "ClaudeProxy",
                baseUrl = "",
                defaultModel = "claude-sonnet-4-6",
                supportedModels = listOf("claude-sonnet-4-6", "claude-opus-4-1", "claude-3-5-sonnet"),
                supportsMultimodal = true,
                apiKeyRef = "apikey_claude_proxy"
            ),
            LlmProviderConfig(
                name = "DeepSeek",
                baseUrl = "https://api.deepseek.com/v1",
                defaultModel = "deepseek-chat",
                supportedModels = listOf("deepseek-chat", "deepseek-coder", "deepseek-reasoner"),
                supportsMultimodal = false,
                apiKeyRef = "apikey_deepseek"
            ),
            LlmProviderConfig(
                name = "Ollama",
                baseUrl = "http://10.0.2.2:11434/v1",
                defaultModel = "llama3.1",
                supportedModels = listOf("llama3.1", "llama3.2", "qwen2.5", "mistral"),
                supportsMultimodal = false,
                apiKeyRef = "apikey_ollama"
            )
        )
    }
}

fun List<LlmProviderConfig>.findByName(name: String): LlmProviderConfig? =
    firstOrNull { it.name == name }