package com.gijimemo.llm

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * OpenAiCompatibleClient の結合テスト。
 *
 * MockWebServer を使用して LLM API 応答をシミュレート。
 * android.util.Log は mockkStatic でモック化。
 */
class OpenAiCompatibleClientMultimodalTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OpenAiCompatibleClient

    @Before
    fun setup() {
        // Mock android.util.Log to prevent RuntimeException in non-Robolectric tests
        mockkStatic("android.util.Log")
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.i(any<String>(), any<String>(), any<Throwable>()) } returns 0

        server = MockWebServer()
        server.start()
        // Context は emitAllMultimodal の WAV キャッシュ用にのみ使われ、
        // 本テストでは MULTIMODAL を直接叩かないので mock 不要 → io.mockk で簡易生成。
        client = OpenAiCompatibleClient(OkHttpClient(), io.mockk.mockk(relaxed = true))
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

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

    @Test
    fun `minimax-style non-standard delta does not crash`() = runTest {
        val sse = """
            data: {"choices":[{"delta":{"type":"text","text":"会議は"}}]}

            data: {"choices":[{"delta":{"type":"text","text":" 始まりました"}}]}

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
                model = "MiniMax-M1",
                callMode = LlmOptions.CallMode.MULTIMODAL,
                prompt = "p"
            )
        ).toList()

        val errors = events.filterIsInstance<LlmEvent.Error>()
        assertThat(errors).isEmpty()
        val complete = events.filterIsInstance<LlmEvent.Complete>().firstOrNull()
        assertThat(complete).isNotNull()
        assertThat(complete!!.fullText).isEqualTo("会議は 始まりました")
    }

    @Test
    fun `minimax-audio-type-delta does not crash`() = runTest {
        val sse = """
            data: {"choices":[{"delta":{"type":"audio","audio":"AAA="}}]}

            data: {"choices":[{"delta":{"type":"audio","audio":"BBB="}}]}

            data: [DONE]

        """.trimIndent()
        server.enqueue(MockResponse().setBody(sse))

        val file = File.createTempFile("test", ".m4a").apply { writeText("x") }
        val events = client.transcribeAndFormat(
            audioFile = file,
            options = LlmOptions(
                baseUrl = server.url("/v1").toString().removeSuffix("/"),
                apiKey = "k",
                model = "MiniMax-M1",
                callMode = LlmOptions.CallMode.MULTIMODAL,
                prompt = "p"
            )
        ).toList()
        assertThat(events.filterIsInstance<LlmEvent.Error>()).isEmpty()
    }

    @Test
    fun `transcribeAndFormat on Main thread does not crash with NetworkOnMainThread`() = runTest {
        val sse = """
            data: {"choices":[{"delta":{"content":"hi"}}]}

            data: [DONE]

        """.trimIndent()
        server.enqueue(MockResponse().setBody(sse))
        val file = File.createTempFile("test", ".m4a").apply { writeText("x") }

        val events = withContext(Dispatchers.Main) {
            client.transcribeAndFormat(
                audioFile = file,
                options = LlmOptions(
                    baseUrl = server.url("/v1").toString().removeSuffix("/"),
                    apiKey = "k",
                    model = "gpt-4o-audio-preview",
                    callMode = LlmOptions.CallMode.MULTIMODAL,
                    prompt = "p"
                )
            ).toList()
        }

        val errors = events.filterIsInstance<LlmEvent.Error>()
        assertThat(errors).isEmpty()
        val complete = events.filterIsInstance<LlmEvent.Complete>().firstOrNull()
        assertThat(complete).isNotNull()
        assertThat(complete!!.fullText).isEqualTo("hi")
    }
}
