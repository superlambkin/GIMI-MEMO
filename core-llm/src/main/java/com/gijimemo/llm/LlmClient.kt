package com.gijimemo.llm

import com.gijimemo.data.model.LlmCallMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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

    /**
     * v0.7.x: PCM の Flow を受けて 3秒窓 + 0.5秒オーバーラップで逐次推論する。
     * 各推論結果は [TranscriptDelta] として逐次 emit される。
     *
     * デフォルト実装は空 Flow。ストリーミング非対応の実装 (例: API-only LlmClient) は
     * override 不要で本メソッドを継承するだけで動作する。
     *
     * @param pcmFlow 16bit PCM (ShortArray) の SharedFlow / Flow。
     *                各要素は任意の長さのチャンクを想定。
     * @param language "ja" / "zh" / "en" など。null なら自動検出。
     * @param sampleRate 入力 PCM のサンプルレート (Hz)。既定 16000。
     * @param windowMs 推論窓長 (ms)。既定 3000。
     * @param overlapMs 窓オーバーラップ (ms)。既定 500。
     * @param vadModelPath Silero VAD モデルパス。null なら VAD 無効。
     */
    fun transcribeStream(
        pcmFlow: Flow<ShortArray>,
        language: String,
        sampleRate: Int = 16000,
        windowMs: Long = 3000,
        overlapMs: Long = 500,
        vadModelPath: String? = null,
    ): Flow<TranscriptDelta> = emptyFlow()
}
