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
                // MiniMax M3 は input_audio 非対応 (image_url / video_url のみ)。
                // 音声を扱う場合は OnDevice Whisper で文字起こし → text を chat completions で要約する 2 段階フロー必須。
                supportsMultimodal = false,
                apiKeyRef = "apikey_minimax_cn"
            ),
            LlmProviderConfig(
                name = "MiniMax 海外",
                baseUrl = "https://api.minimax.io/v1",
                defaultModel = "MiniMax-M3",
                supportedModels = listOf("MiniMax-M3", "MiniMax-Text-01", "MiniMax-VL-01"),
                supportsMultimodal = false,
                apiKeyRef = "apikey_minimax_overseas"
            ),
            LlmProviderConfig(
                name = "OpenAI",
                baseUrl = "https://api.openai.com/v1",
                defaultModel = "gpt-4o-mini",
                // gpt-4o-audio-preview は input_audio 対応だがプレビューモデルのため
                // 一部のAPIキーでは利用不可。必要な場合はモデル選択から手動で追加してください。
                supportedModels = listOf("gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "gpt-4", "o3-mini"),
                supportsMultimodal = false,
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
                // v0.9.0: 要約用モデルを DeepSeek V4 Flash に変更（選択肢にも追加）
                defaultModel = "deepseek-v4-flash",
                supportedModels = listOf("deepseek-v4-flash", "deepseek-chat", "deepseek-coder", "deepseek-reasoner"),
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