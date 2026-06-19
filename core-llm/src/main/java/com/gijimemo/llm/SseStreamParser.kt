package com.gijimemo.llm

import android.util.Log
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okio.Buffer

object SseStreamParser {
    private const val TAG = "GijiMemoLLM"
    private val moshi: Moshi = Moshi.Builder().build()
    private val mapAdapter: JsonAdapter<Map<String, Any>> =
        moshi.adapter(Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java))

    /**
     * 输入 SSE 格式文本流（多行），输出 content 字段 delta。
     */
    fun parse(input: String): Flow<String> = flow {
        val lines = input.split("\n")
        var parsedCount = 0
        var skippedCount = 0
        var firstPayload: String? = null
        for (line in lines) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("data:")) continue
            val payload = trimmed.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") continue
            if (firstPayload == null) firstPayload = payload.take(200)
            try {
                val map = mapAdapter.fromJson(payload)
                if (map == null) {
                    skippedCount++
                    continue
                }
                @Suppress("UNCHECKED_CAST")
                val choices = map["choices"] as? List<Map<String, Any>>
                if (choices == null) {
                    skippedCount++
                    continue
                }
                for (choice in choices) {
                    val delta = choice["delta"] as? Map<String, Any>
                    if (delta == null) {
                        skippedCount++
                        continue
                    }
                    // OpenAI 標準: {"content": "..."}
                    val content = delta["content"] as? String
                    if (content != null && content.isNotEmpty()) {
                        parsedCount++
                        emit(content)
                    } else {
                        // MiniMax audio モデル: {"type": "text", "text": "..."} または
                        // OpenAI 互換の alternative: "text" / "audio" フィールド
                        val altText = (delta["text"] as? String)
                            ?: (delta["reasoning"] as? String)
                        if (altText != null && altText.isNotEmpty()) {
                            parsedCount++
                            emit(altText)
                        } else {
                            // 跳过的非 OpenAI 形态 delta（例: type=audio の base64）—— 第一次出现时打印结构
                            if (parsedCount == 0 && skippedCount <= 1) {
                                Log.d(TAG, "SSE delta has no text-like field. delta=$delta")
                            }
                            skippedCount++
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "SSE parse failed for payload[:200]=${payload.take(200)}: ${e::class.java.simpleName} ${e.message}")
                skippedCount++
            }
        }
        Log.d(TAG, "SseStreamParser done: parsed=$parsedCount skipped=$skippedCount firstPayload=${firstPayload?.take(80)}")
    }

    /**
     * 输入 Buffered Source（SSE 流式），增量 emit content 字段。
     */
    fun parseFromSource(source: okio.BufferedSource): Flow<String> = flow {
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            val trimmed = line.trim()
            if (!trimmed.startsWith("data:")) continue
            val payload = trimmed.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") continue
            try {
                val map = mapAdapter.fromJson(payload) ?: continue
                @Suppress("UNCHECKED_CAST")
                val choices = map["choices"] as? List<Map<String, Any>> ?: continue
                for (choice in choices) {
                    val delta = choice["delta"] as? Map<String, Any> ?: continue
                    val content = delta["content"] as? String ?: continue
                    if (content.isNotEmpty()) emit(content)
                }
            } catch (e: Exception) {
                Log.w(TAG, "SSE parse failed (source): ${e.message}")
            }
        }
    }
}
