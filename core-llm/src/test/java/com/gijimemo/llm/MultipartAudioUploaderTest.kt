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
}
