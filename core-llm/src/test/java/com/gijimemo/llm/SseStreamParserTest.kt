package com.gijimemo.llm

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SseStreamParserTest {
    @Test
    fun `parses single delta line`() = runTest {
        val input = "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\n"
        val deltas = SseStreamParser.parse(input).toList()
        assertThat(deltas).hasSize(1)
        assertThat(deltas[0]).isEqualTo("Hello")
    }

    @Test
    fun `parses multiple deltas`() = runTest {
        val input = """
            data: {"choices":[{"delta":{"content":"Hello "}}]}

            data: {"choices":[{"delta":{"content":"world"}}]}

            data: {"choices":[{"delta":{"content":"!"}}]}

        """.trimIndent()
        val deltas = SseStreamParser.parse(input).toList()
        assertThat(deltas).containsExactly("Hello ", "world", "!").inOrder()
    }

    @Test
    fun `ignores DONE marker`() = runTest {
        val input = "data: [DONE]\n\n"
        val deltas = SseStreamParser.parse(input).toList()
        assertThat(deltas).isEmpty()
    }

    @Test
    fun `handles malformed JSON gracefully`() = runTest {
        val input = "data: {bad json}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n"
        val deltas = SseStreamParser.parse(input).toList()
        assertThat(deltas).containsExactly("ok")
    }

    /**
     * MiniMax audio 模型可能返回 {"type":"text","text":"..."} 或 {"type":"audio","audio":"<b64>"}，
     * 而不是 OpenAI 标准 {"content":"..."}。"text" 字段必须被提取为 delta。
     */
    @Test
    fun `non-standard delta with type+text is parsed via text field`() = runTest {
        val input = """
            data: {"choices":[{"delta":{"type":"text","text":"hello"}}]}

            data: {"choices":[{"delta":{"type":"text","text":" world"}}]}

            data: [DONE]

        """.trimIndent()
        val deltas = SseStreamParser.parse(input).toList()
        assertThat(deltas).containsExactly("hello", " world").inOrder()
    }

    @Test
    fun `non-standard delta with text field only is parsed`() = runTest {
        val input = """data: {"choices":[{"delta":{"text":"direct text"}}]}"""
        val deltas = SseStreamParser.parse(input).toList()
        assertThat(deltas).containsExactly("direct text")
    }
}
