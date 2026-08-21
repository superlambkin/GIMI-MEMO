package com.gijimemo.llm

import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MultipartAudioUploader @Inject constructor(
    private val client: OkHttpClient
) {
    private val moshi = Moshi.Builder().build()
    private val stringAdapter = moshi.adapter(String::class.java)

    /**
     * 上传音频到 OpenAI 兼容的 /audio/transcriptions 端点，返回纯文本。
     */
    suspend fun uploadFile(
        url: String,
        apiKey: String,
        model: String,
        file: File,
        language: String? = null,
    ): String = withContext(Dispatchers.IO) {
        // ファイル拡張子に基づいて MIME タイプを選択
        val mediaTypeStr = when (file.extension.lowercase()) {
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            "m4a", "mp4" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "webm" -> "audio/webm"
            else -> "audio/mp4" // fallback
        }
        val mediaType = mediaTypeStr.toMediaTypeOrNull()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody(mediaType))
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "text")
            .apply { language?.let { addFormDataPart("language", it); android.util.Log.d("MultipartUpload", "language=$it") } }
            .build()
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        val resp = try {
            client.newCall(req).execute()
        } catch (e: java.io.IOException) {
            // 网络层失败（连接断・DNS・timeout 等）→ 可重试の NetworkError に変換
            throw LlmException.NetworkError(e)
        }
        resp.use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                throw mapHttpError(it.code, body)
            }
            body
        }
    }

    private fun mapHttpError(code: Int, body: String): LlmException = when (code) {
        401 -> LlmException.InvalidApiKey()
        413 -> LlmException.FileTooLarge()
        429 -> LlmException.RateLimited()
        in 500..599 -> LlmException.ServerError(code, body)
        else -> LlmException.Unknown(RuntimeException("HTTP $code: $body"))
    }
}
