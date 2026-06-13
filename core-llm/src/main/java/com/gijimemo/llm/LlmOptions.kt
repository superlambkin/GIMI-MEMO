package com.gijimemo.llm

data class LlmOptions(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val callMode: CallMode,
    val prompt: String,
    val temperature: Double = 0.3
) {
    enum class CallMode { MULTIMODAL, WHISPER_THEN_SUMMARY }
}
