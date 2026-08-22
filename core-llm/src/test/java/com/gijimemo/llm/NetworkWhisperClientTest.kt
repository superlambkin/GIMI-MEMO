package com.gijimemo.llm

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * v0.9.1: ローカルPC ネットワーク Whisper クライアントの単体テスト。
 * 実サーバの OpenAPI スキーマ（POST /asr, multipart audio_file）に合わせて検証する。
 */
class NetworkWhisperClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: NetworkWhisperClient

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        client = NetworkWhisperClient(OkHttpClient())
    }

    @After
    fun tearDown() { server.shutdown() }

    private fun tempAudio(): File {
        val file = File.createTempFile("asr_test", ".mp3")
        file.writeBytes(ByteArray(1024) { 0x41 })
        file.deleteOnExit()
        return file
    }

    @Test
    fun `transcribe posts multipart audio_file to asr endpoint and returns plain text`() = runTest {
        server.enqueue(MockResponse().setBody("こんにちは。テストです。"))

        val text = client.transcribe(
            audioFile = tempAudio(),
            url = server.url("/asr").toString(),
            language = "ja"
        )

        assertThat(text).isEqualTo("こんにちは。テストです。")
        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/asr")
        assertThat(recorded.method).isEqualTo("POST")
        val body = recorded.body.readUtf8()
        assertThat(body).contains("audio_file")
        assertThat(body).contains("name=\"language\"")
        assertThat(body).contains("ja")
        assertThat(body).contains("name=\"task\"")
        assertThat(body).contains("transcribe")
    }

    @Test
    fun `transcribe omits language when null`() = runTest {
        server.enqueue(MockResponse().setBody("result"))

        client.transcribe(
            audioFile = tempAudio(),
            url = server.url("/asr").toString(),
            language = null
        )

        val body = server.takeRequest().body.readUtf8()
        assertThat(body).doesNotContain("name=\"language\"")
    }

    @Test
    fun `transcribe parses JSON text response`() = runTest {
        server.enqueue(MockResponse().setBody("""{"text": "JSON 形式の認識結果"}"""))

        val text = client.transcribe(
            audioFile = tempAudio(),
            url = server.url("/asr").toString()
        )

        assertThat(text).isEqualTo("JSON 形式の認識結果")
    }

    @Test
    fun `transcribe maps HTTP error to Unknown exception`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("internal error"))

        val ex = runCatching {
            client.transcribe(audioFile = tempAudio(), url = server.url("/asr").toString())
        }.exceptionOrNull()

        assertThat(ex).isInstanceOf(LlmException.Unknown::class.java)
        assertThat(ex?.message).contains("HTTP 500")
    }

    @Test
    fun `transcribe maps connection failure to NetworkError`() = runTest {
        val dead = MockWebServer()
        dead.start()
        val url = dead.url("/asr").toString()
        dead.shutdown() // 接続できない状態にしてネットワーク層の失敗を再現

        val ex = runCatching {
            client.transcribe(audioFile = tempAudio(), url = url)
        }.exceptionOrNull()

        assertThat(ex).isInstanceOf(LlmException.NetworkError::class.java)
    }
}
