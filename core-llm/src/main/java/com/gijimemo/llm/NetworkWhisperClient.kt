package com.gijimemo.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ローカルPC のネットワーク Whisper サーバ用クライアント。
 *
 * プロトコル（実サーバ OpenAPI スキーマを確認済み / 既定 http://192.168.0.88:9000/asr）:
 *   POST /asr（multipart/form-data）
 *     - audio_file（必須）: 音声ファイル
 *     - language（任意・既定 auto）: ja / zh / en ...
 *     - task（任意・既定 transcribe）
 *     - output（任意・既定 txt）
 *   応答: 200 プレーンテキスト（認識結果）。一部実装は {"text": "..."} の JSON を返す。
 *
 * v0.9.1: クラウド OpenAI と違い 25MB 制限がなく、長時間音声も分割せずそのまま送信できる。
 * ローカルネットワークのため読み取りタイムアウトは長め（30分）に設定する。
 */
@Singleton
class NetworkWhisperClient @Inject constructor(client: OkHttpClient) {
    private val httpClient = client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .build()

    /**
     * 1 つの音声ファイルをネットワーク Whisper サーバへ送信し、認識テキストを返す。
     * @throws LlmException.NetworkError ネットワーク層の失敗（接続不可・タイムアウト等）
     * @throws LlmException.Unknown HTTP エラー（4xx / 5xx）
     */
    suspend fun transcribe(
        audioFile: File,
        url: String,
        language: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val mediaTypeStr = when (audioFile.extension.lowercase()) {
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            "m4a", "mp4" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "webm" -> "audio/webm"
            else -> "audio/mp4" // fallback
        }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "audio_file",
                audioFile.name,
                audioFile.asRequestBody(mediaTypeStr.toMediaTypeOrNull())
            )
            .apply {
                if (!language.isNullOrBlank()) addFormDataPart("language", language)
                addFormDataPart("task", "transcribe")
                addFormDataPart("output", "txt")
            }
            .build()
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()
        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: IOException) {
            // ネットワーク層の失敗 → 接続不可・タイムアウト等
            throw LlmException.NetworkError(e)
        }
        response.use {
            val bodyText = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                throw LlmException.Unknown(RuntimeException("HTTP ${it.code}: ${bodyText.take(200)}"))
            }
            extractText(bodyText)
        }
    }

    /** 応答が {"text": "..."} の JSON なら text を取り出し、それ以外はプレーンテキストとして返す。 */
    private fun extractText(body: String): String {
        val trimmed = body.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            val m = Regex("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(trimmed)
            if (m != null) {
                return m.groupValues[1]
                    .replace("\\n", "\n")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
            }
        }
        return trimmed
    }
}
