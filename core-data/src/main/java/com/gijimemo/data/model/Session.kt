package com.gijimemo.data.model

data class Session(
    val id: String,
    val title: String,
    val createdAt: Long,
    val durationMs: Long,
    val audioFilePath: String,
    val audioSizeBytes: Long,
    val status: SessionStatus = SessionStatus.STOPPED,
    val transcriptMd: String? = null,
    val docxFilePath: String? = null,
    val mdFilePath: String? = null,
    val txtFilePath: String? = null,
    val llmProvider: String? = null,
    val llmModel: String? = null,
    val errorMessage: String? = null
)