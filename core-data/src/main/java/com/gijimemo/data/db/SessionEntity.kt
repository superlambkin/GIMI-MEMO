package com.gijimemo.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.gijimemo.data.model.Session
import com.gijimemo.data.model.SessionStatus

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val durationMs: Long,
    val audioFilePath: String,
    val audioSizeBytes: Long,
    val status: String,                  // SessionStatus.name
    val transcriptMd: String? = null,
    val docxFilePath: String? = null,
    val mdFilePath: String? = null,
    val txtFilePath: String? = null,
    val llmProvider: String? = null,
    val llmModel: String? = null,
    val errorMessage: String? = null,
    val processingDurationMs: Long = 0L,
    val rawTranscript: String? = null
)

fun SessionEntity.toDomain(): Session = Session(
    id = id,
    title = title,
    createdAt = createdAt,
    durationMs = durationMs,
    audioFilePath = audioFilePath,
    audioSizeBytes = audioSizeBytes,
    status = SessionStatus.valueOf(status),
    transcriptMd = transcriptMd,
    docxFilePath = docxFilePath,
    mdFilePath = mdFilePath,
    txtFilePath = txtFilePath,
    llmProvider = llmProvider,
    llmModel = llmModel,
    errorMessage = errorMessage,
    processingDurationMs = processingDurationMs,
    rawTranscript = rawTranscript
)

fun Session.toEntity(): SessionEntity = SessionEntity(
    id = id,
    title = title,
    createdAt = createdAt,
    durationMs = durationMs,
    audioFilePath = audioFilePath,
    audioSizeBytes = audioSizeBytes,
    status = status.name,
    transcriptMd = transcriptMd,
    docxFilePath = docxFilePath,
    mdFilePath = mdFilePath,
    txtFilePath = txtFilePath,
    llmProvider = llmProvider,
    llmModel = llmModel,
    errorMessage = errorMessage,
    processingDurationMs = processingDurationMs,
    rawTranscript = rawTranscript
)