package com.gijimemo.data.model

enum class SessionStatus {
    RECORDING,    // 录音中
    STOPPED,      // 已停止，未转写
    TRANSCRIBING, // 转写中
    READY,        // 文档已生成
    SHARED,       // 已分享邮件
    ERROR         // 失败
}