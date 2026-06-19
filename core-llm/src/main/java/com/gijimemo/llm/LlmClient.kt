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
     * 文字起こしのみを行う（要約なし）。オンデバイスWhisper / API Whisper の両方に対応。
     * @return 文字起こし生テキスト
     */
    suspend fun transcribeOnly(audioFile: File): String

    /**
     * 文字起こし済みテキストを LLM で要約する。
     * @param text 文字起こし済みテキスト
     * @param prompt 要約プロンプト
     * @return ストリーミング要約結果
     */
    fun summarizeOnly(text: String, prompt: String): Flow<LlmEvent>

    /**
     * 接続テスト: 設定ページから API Key と endpoint が有効か確認するための最小呼び出し。
     * 短いテキストでチャットコンプリーションを呼び、応答テキストを返す。
     */
    suspend fun testConnection(): String
}
