package com.gijimemo.llm

import com.gijimemo.data.model.LlmCallMode
import kotlinx.coroutines.flow.Flow
import java.io.File

interface LlmClient {
    fun transcribeAndFormat(
        audioFile: File,
        prompt: String,
        mode: LlmCallMode
    ): Flow<LlmEvent>
}
