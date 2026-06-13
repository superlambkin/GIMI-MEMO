package com.gijimemo.llm

sealed class LlmEvent {
    data class Progress(val percent: Int, val message: String? = null) : LlmEvent()
    data class Delta(val text: String) : LlmEvent()
    data class Complete(val fullText: String, val model: String? = null) : LlmEvent()
    data class Error(val cause: Throwable) : LlmEvent()
}
