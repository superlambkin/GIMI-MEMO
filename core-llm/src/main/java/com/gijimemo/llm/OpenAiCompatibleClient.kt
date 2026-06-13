package com.gijimemo.llm

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import java.io.File
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 单一 LLM 客户端实现 OpenAI 兼容协议：支持多模态（chat/completions）和 Whisper（audio/transcriptions）两种调用模式。
 */
@Singleton
class OpenAiCompatibleClient @Inject constructor(
    private val client: OkHttpClient
) {
    private val moshi = Moshi.Builder().build()
    private val mapAdapter: JsonAdapter<Map<String, Any>> =
        moshi.adapter(Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java))

    /**
     * 主入口：根据 callMode 走不同路径，最终输出 LlmEvent 流。
     */
    fun transcribeAndFormat(
        audioFile: File,
        options: LlmOptions
    ): Flow<LlmEvent> = flow {
        try {
            when (options.callMode) {
                LlmOptions.CallMode.MULTIMODAL -> {
                    emitAllMultimodal(audioFile, options)
                }
                LlmOptions.CallMode.WHISPER_THEN_SUMMARY -> {
                    val transcript = whisperTranscribe(audioFile, options)
                    emit(LlmEvent.Delta("\n[转写完成，正在生成会议纪要...]\n\n"))
                    emitAllChatCompletion(transcript, options)
                }
            }
        } catch (e: LlmException) {
            emit(LlmEvent.Error(e))
        } catch (e: Exception) {
            emit(LlmEvent.Error(LlmException.Unknown(e)))
        }
    }

    // ─── MULTIMODAL ───────────────────────────────────────────

    private suspend fun FlowCollector<LlmEvent>.emitAllMultimodal(
        audioFile: File,
        options: LlmOptions
    ) {
        val base64Audio = withContext(Dispatchers.IO) {
            Base64.getEncoder().encodeToString(audioFile.readBytes())
        }
        val payload = mapOf(
            "model" to options.model,
            "temperature" to options.temperature,
            "stream" to true,
            "messages" to listOf(
                mapOf(
                    "role" to "user",
                    "content" to listOf(
                        mapOf("type" to "text", "text" to options.prompt),
                        mapOf(
                            "type" to "input_audio",
                            "input_audio" to mapOf(
                                "data" to base64Audio,
                                "format" to "mp4"
                            )
                        )
                    )
                )
            )
        )
        val body = moshi.adapter(Map::class.java).toJson(payload)
            .toRequestBody("application/json".toMediaTypeOrNull())
        val req = Request.Builder()
            .url("${options.baseUrl}/chat/completions")
            .header("Authorization", "Bearer ${options.apiKey}")
            .post(body)
            .build()
        executeStream(req).collect { emit(it) }
    }

    // ─── WHISPER THEN SUMMARY ─────────────────────────────────

    private suspend fun whisperTranscribe(audioFile: File, options: LlmOptions): String {
        val uploader = MultipartAudioUploader(client)
        return uploader.uploadFile(
            url = "${options.baseUrl}/audio/transcriptions",
            apiKey = options.apiKey,
            model = "whisper-1",
            file = audioFile
        )
    }

    private suspend fun FlowCollector<LlmEvent>.emitAllChatCompletion(
        transcript: String,
        options: LlmOptions
    ) {
        val payload = mapOf(
            "model" to options.model,
            "temperature" to options.temperature,
            "stream" to true,
            "messages" to listOf(
                mapOf("role" to "user", "content" to "${options.prompt}\n\n$transcript")
            )
        )
        val body = moshi.adapter(Map::class.java).toJson(payload)
            .toRequestBody("application/json".toMediaTypeOrNull())
        val req = Request.Builder()
            .url("${options.baseUrl}/chat/completions")
            .header("Authorization", "Bearer ${options.apiKey}")
            .post(body)
            .build()
        executeStream(req).collect { emit(it) }
    }

    // ─── STREAM EXECUTION ────────────────────────────────────

    private suspend fun executeStream(req: Request): Flow<LlmEvent> = flow {
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string().orEmpty()
                throw when (resp.code) {
                    401 -> LlmException.InvalidApiKey()
                    413 -> LlmException.FileTooLarge()
                    429 -> LlmException.RateLimited()
                    else -> LlmException.Unknown(RuntimeException("HTTP ${resp.code}: $errBody"))
                }
            }
            val source = resp.body?.source() ?: throw LlmException.Unknown(RuntimeException("Empty body"))
            val buffer = Buffer()
            source.readAll(buffer)
            val fullText = StringBuilder()
            SseStreamParser.parse(buffer.readUtf8()).collect { delta ->
                fullText.append(delta)
                emit(LlmEvent.Delta(delta))
            }
            emit(LlmEvent.Complete(fullText.toString(), model = req.url.encodedPath))
        }
    }
}

private typealias FlowCollector<T> = kotlinx.coroutines.flow.FlowCollector<T>
