package com.gijimemo.llm

import android.content.Context
import android.util.Log
import com.gijimemo.whisper.AudioDecoder
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.qualifiers.ApplicationContext
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
    private val client: OkHttpClient,
    @ApplicationContext private val context: Context
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
            Log.d(TAG, "transcribeAndFormat start: mode=${options.callMode} model=${options.model} url=${options.baseUrl}")
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
            Log.e(TAG, "LlmException: ${e::class.java.simpleName} msg=${e.message}", e)
            emit(LlmEvent.Error(e))
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected exception: ${e::class.java.simpleName} msg=${e.message}", e)
            emit(LlmEvent.Error(LlmException.Unknown(e)))
        }
    }

    private val TAG = "GijiMemoLLM"

    // ─── MULTIMODAL ───────────────────────────────────────────

    private suspend fun FlowCollector<LlmEvent>.emitAllMultimodal(
        audioFile: File,
        options: LlmOptions
    ) {
        // OpenAI / MiniMax 系の input_audio は WAV (PCM 16bit) を期待する。
        // 録音ファイルは AAC-in-MP4 (.m4a) なので AudioDecoder で WAV へ変換してから
        // base64 化する。"mp4" を渡すと「音声ファイルが添付されていない」相当の
        // 応答が返るプロバイダがある (Whisper.cpp が要求するのと同じ WAV 16kHz mono)。
        val (base64Audio, format) = withContext(Dispatchers.IO) {
            try {
                val cacheDir = File(context.cacheDir, "multimodal_decoded").apply { mkdirs() }
                val wavPath = AudioDecoder.decodeToWav(audioFile.absolutePath, cacheDir)
                val wavFile = File(wavPath)
                val b64 = Base64.getEncoder().encodeToString(wavFile.readBytes())
                Log.d(TAG, "emitAllMultimodal: WAV converted ${audioFile.length()}B -> ${wavFile.length()}B")
                wavFile.delete()
                b64 to "wav"
            } catch (e: Exception) {
                // WAV 変換失敗時は元ファイル (mp4/m4a) をそのまま送る最終手段
                Log.w(TAG, "WAV decode failed, falling back to raw mp4: ${e.message}")
                val b64 = Base64.getEncoder().encodeToString(audioFile.readBytes())
                b64 to "mp4"
            }
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
                                "format" to format
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
            file = audioFile,
            language = options.language?.takeIf { it.isNotBlank() }
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

    // ─── TRANSCRIBE ONLY (文字起こしのみ) ──────────────────

    /**
     * Whisper API で文字起こしのみ実行。要約は行わない。
     */
    suspend fun transcribeOnly(audioFile: File, options: LlmOptions): String {
        Log.d(TAG, "transcribeOnly: ${audioFile.name} url=${options.baseUrl}")
        return whisperTranscribe(audioFile, options)
    }

    // ─── SUMMARIZE ONLY (要約のみ) ─────────────────────────

    /**
     * 文字起こし済みテキストを LLM で要約する。ストリーミング Flow を返す。
     */
    fun summarizeOnly(text: String, options: LlmOptions): Flow<LlmEvent> = flow {
        try {
            emitAllChatCompletion(text, options)
        } catch (e: LlmException) {
            Log.e(TAG, "LlmException: ${e::class.java.simpleName} msg=${e.message}", e)
            emit(LlmEvent.Error(e))
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected exception: ${e::class.java.simpleName} msg=${e.message}", e)
            emit(LlmEvent.Error(LlmException.Unknown(e)))
        }
    }

    // ─── STREAM EXECUTION ────────────────────────────────────

    /**
     * 接続テスト: 短い非ストリーミング chat completion を投げる。
     * audio 不要・model 不要 (default を使う)。成功時は応答テキスト、失敗時は例外を投げる。
     */
    suspend fun testConnection(baseUrl: String, apiKey: String, model: String): String = withContext(Dispatchers.IO) {
        val payload = mapOf(
            "model" to model,
            "messages" to listOf(
                mapOf("role" to "user", "content" to "ping")
            )
        )
        val body = moshi.adapter(Map::class.java).toJson(payload)
            .toRequestBody("application/json".toMediaTypeOrNull())
        val req = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            val respBody = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw when (resp.code) {
                    401 -> LlmException.InvalidApiKey()
                    413 -> LlmException.FileTooLarge()
                    429 -> LlmException.RateLimited()
                    else -> LlmException.Unknown(RuntimeException("HTTP ${resp.code} at $baseUrl/chat/completions: $respBody"))
                }
            }
            // 简单提取 content
            val obj = moshi.adapter(Map::class.java).fromJson(respBody) as? Map<*, *>
            @Suppress("UNCHECKED_CAST")
            val choices = obj?.get("choices") as? List<Map<String, Any?>>
            val first = choices?.firstOrNull()
            val message = first?.get("message") as? Map<String, Any?>
            (message?.get("content") as? String) ?: respBody.take(200)
        }
    }

    private suspend fun executeStream(req: Request): Flow<LlmEvent> = flow {
        Log.d(TAG, "executeStream POST ${req.url} on=${Thread.currentThread().name}")
        // 关键：OkHttp 的 execute() 是阻塞调用，必须跑在 IO 线程。
        // viewModelScope 默认是 Main，不切线程会触发 NetworkOnMainThreadException。
        val outcome: StreamOutcome = withContext(Dispatchers.IO) {
            try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val errBody = resp.body?.string().orEmpty()
                        Log.e(TAG, "HTTP ${resp.code} ${req.url} body=$errBody")
                        return@use StreamOutcome.HttpError(resp.code, errBody)
                    }
                    val source = resp.body?.source() ?: return@use StreamOutcome.EmptyBody
                    val buffer = Buffer()
                    source.readAll(buffer)
                    val raw = buffer.readUtf8()
                    Log.d(TAG, "Response first 200 chars: ${raw.take(200)}")
                    if (raw.isBlank()) {
                        Log.e(TAG, "Server returned empty string body at ${req.url}")
                        return@use StreamOutcome.EmptyBody
                    }
                    StreamOutcome.Ok(raw)
                }
            } catch (e: LlmException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "executeStream IO error: ${e::class.java.simpleName} msg=${e.message}", e)
                throw e
            }
        }
        when (outcome) {
            is StreamOutcome.HttpError -> {
                val (code, body) = outcome.code to outcome.body
                throw when (code) {
                    401 -> LlmException.InvalidApiKey()
                    413 -> LlmException.FileTooLarge()
                    429 -> LlmException.RateLimited()
                    else -> LlmException.Unknown(RuntimeException("HTTP $code at ${req.url}: $body"))
                }
            }
            StreamOutcome.EmptyBody -> {
                throw LlmException.Unknown(RuntimeException("Empty response body at ${req.url}"))
            }
            is StreamOutcome.Ok -> {
                val fullText = StringBuilder()
                try {
                    SseStreamParser.parse(outcome.raw).collect { delta ->
                        fullText.append(delta)
                        emit(LlmEvent.Delta(delta))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "SseStreamParser failed: ${e::class.java.simpleName} msg=${e.message} body[:200]=${outcome.raw.take(200)}", e)
                    throw e
                }
                Log.d(TAG, "Stream complete: collected ${fullText.length} chars")
                emit(LlmEvent.Complete(fullText.toString(), model = req.url.encodedPath))
            }
        }
    }

    private sealed class StreamOutcome {
        data class Ok(val raw: String) : StreamOutcome()
        data class HttpError(val code: Int, val body: String) : StreamOutcome()
        object EmptyBody : StreamOutcome()
    }
}

private typealias FlowCollector<T> = kotlinx.coroutines.flow.FlowCollector<T>
