package com.gijimemo.data.model

enum class LlmCallMode {
    MULTIMODAL,             // 单次多模态调用
    WHISPER_THEN_SUMMARY    // 先 Whisper 转写，再总结
}