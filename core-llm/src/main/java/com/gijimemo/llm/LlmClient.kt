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

    /**
     * 接続テスト: 設定ページから API Key と endpoint が有効か確認するための最小呼び出し。
     * 短いテキストでチャットコンプリーションを呼び、応答テキストを返す。
     */
    suspend fun testConnection(): String
}
