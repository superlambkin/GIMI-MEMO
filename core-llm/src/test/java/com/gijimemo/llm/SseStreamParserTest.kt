package com.gijimemo.llm

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

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
}
