package com.gijimemo.llm

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

class OpenAiCompatibleClientMultimodalTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OpenAiCompatibleClient

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        client = OpenAiCompatibleClient(OkHttpClient())
    }

    @After
    fun tearDown() { server.shutdown() }

    @Test
    fun `multimodal call streams delta events`() = runTest {
        val sse = """
            data: {"choices":[{"delta":{"content":"Hello "}}]}

            data: {"choices":[{"delta":{"content":"world"}}]}

            data: [DONE]

        """.trimIndent()
        server.enqueue(MockResponse().setBody(sse))

        val file = File.createTempFile("test", ".m4a")
        file.writeText("fake")
        file.deleteOnExit()
        val events = client.transcribeAndFormat(
            audioFile = file,
            options = LlmOptions(
                baseUrl = server.url("/v1").toString().removeSuffix("/"),
                apiKey = "k",
                model = "gpt-4o-audio-preview",
                callMode = LlmOptions.CallMode.MULTIMODAL,
                prompt = "transcribe"
            )
        ).toList()

        val deltas = events.filterIsInstance<LlmEvent.Delta>().map { it.text }
        assertThat(deltas).containsExactly("Hello ", "world")
        assertThat(events.last()).isInstanceOf(LlmEvent.Complete::class.java)
    }

    @Test
    fun `401 throws InvalidApiKey`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("invalid api key"))
        val file = File.createTempFile("test", ".m4a").apply { writeText("x") }
        val events = client.transcribeAndFormat(
            audioFile = file,
            options = LlmOptions(
                baseUrl = server.url("/v1").toString().removeSuffix("/"),
                apiKey = "k",
                model = "m",
                callMode = LlmOptions.CallMode.MULTIMODAL,
                prompt = "p"
            )
        ).toList()
        val err = events.filterIsInstance<LlmEvent.Error>().firstOrNull()
        assertThat(err?.cause).isInstanceOf(LlmException.InvalidApiKey::class.java)
    }
}
