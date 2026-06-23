package com.gijimemo.llm

data class LlmOptions(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val callMode: CallMode,
    val prompt: String,
    val temperature: Double = 0.3,
    /** Whisper API 言語ヒント ("ja"/"zh"/"en"等)。null なら自動検出。 */
    val language: String? = null,
) {
    enum class CallMode { MULTIMODAL, WHISPER_THEN_SUMMARY }
}
