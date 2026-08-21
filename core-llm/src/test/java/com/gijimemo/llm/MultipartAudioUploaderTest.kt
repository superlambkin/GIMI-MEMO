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

class MultipartAudioUploaderTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var uploader: MultipartAudioUploader

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient()
        uploader = MultipartAudioUploader(client)
    }

    @After
    fun tearDown() { server.shutdown() }

    @Test
    fun `uploadFile posts multipart and returns text`() = runTest {
        server.enqueue(MockResponse().setBody("hello transcribed"))
        val file = File.createTempFile("test", ".m4a")
        file.writeText("fake audio")
        file.deleteOnExit()

        val text = uploader.uploadFile(
            url = server.url("/audio/transcriptions").toString(),
            apiKey = "k",
            model = "whisper-1",
            file = file
        )
        assertThat(text).isEqualTo("hello transcribed")
        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/audio/transcriptions")
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer k")
    }

    @Test
    fun `uploadFile maps 429 to RateLimited`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        val file = File.createTempFile("test", ".m4a")
        file.writeText("fake audio")
        file.deleteOnExit()

        val ex = runCatching {
            uploader.uploadFile(
                url = server.url("/audio/transcriptions").toString(),
                apiKey = "k",
                model = "whisper-1",
                file = file
            )
        }.exceptionOrNull()

        assertThat(ex).isInstanceOf(LlmException.RateLimited::class.java)
    }

    @Test
    fun `uploadFile maps 5xx to ServerError (retryable)`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("internal error"))
        val file = File.createTempFile("test", ".m4a")
        file.writeText("fake audio")
        file.deleteOnExit()

        val ex = runCatching {
            uploader.uploadFile(
                url = server.url("/audio/transcriptions").toString(),
                apiKey = "k",
                model = "whisper-1",
                file = file
            )
        }.exceptionOrNull()

        assertThat(ex).isInstanceOf(LlmException.ServerError::class.java)
    }

    @Test
    fun `uploadFile maps 413 to FileTooLarge`() = runTest {
        server.enqueue(MockResponse().setResponseCode(413))
        val file = File.createTempFile("test", ".m4a")
        file.writeText("fake audio")
        file.deleteOnExit()

        val ex = runCatching {
            uploader.uploadFile(
                url = server.url("/audio/transcriptions").toString(),
                apiKey = "k",
                model = "whisper-1",
                file = file
            )
        }.exceptionOrNull()

        assertThat(ex).isInstanceOf(LlmException.FileTooLarge::class.java)
    }

    @Test
    fun `uploadFile maps connection failure to NetworkError (retryable)`() = runTest {
        val dead = MockWebServer()
        dead.start()
        val url = dead.url("/audio/transcriptions").toString()
        dead.shutdown() // 接続できない状態にして network 層の失敗を再現

        val file = File.createTempFile("test", ".m4a")
        file.writeText("fake audio")
        file.deleteOnExit()

        val ex = runCatching {
            uploader.uploadFile(
                url = url,
                apiKey = "k",
                model = "whisper-1",
                file = file
            )
        }.exceptionOrNull()

        assertThat(ex).isInstanceOf(LlmException.NetworkError::class.java)
    }
}
