package com.gijimemo.llm

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okio.Buffer

object SseStreamParser {
    private val moshi: Moshi = Moshi.Builder().build()
    private val mapAdapter: JsonAdapter<Map<String, Any>> =
        moshi.adapter(Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java))

    /**
     * 输入 SSE 格式文本流（多行），输出 content 字段 delta。
     */
    fun parse(input: String): Flow<String> = flow {
        val lines = input.split("\n")
        for (line in lines) {
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
            } catch (_: Exception) {
                // 跳过坏行
            }
        }
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
            } catch (_: Exception) {}
        }
    }
}
