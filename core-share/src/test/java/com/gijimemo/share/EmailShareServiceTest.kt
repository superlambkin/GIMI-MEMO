package com.gijimemo.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EmailShareServiceTest {

    private lateinit var service: EmailShareService
    private val mockUri = Uri.parse("content://test.authority/file")

    @Before
    fun setup() {
        // Mock FileProvider.getUriForFile to avoid file path validation issues
        mockkStatic(FileProvider::class)
        every {
            FileProvider.getUriForFile(any<Context>(), any<String>(), any<File>())
        } returns mockUri
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `buildIntent with single attachment creates ACTION_SEND intent`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        service = EmailShareService(context, "test.authority")

        val intent = service.buildIntent(
            attachments = listOf(File("test.txt")),
            subject = "Test Subject",
            body = "Test Body",
            recipient = "test@example.com"
        )

        assertThat(intent.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(intent.getStringExtra(Intent.EXTRA_SUBJECT)).isEqualTo("Test Subject")
        assertThat(intent.getStringExtra(Intent.EXTRA_TEXT)).isEqualTo("Test Body")
        assertThat(intent.getStringArrayExtra(Intent.EXTRA_EMAIL))
            .asList()
            .containsExactly("test@example.com")
    }

    @Test
    fun `buildIntent with multiple attachments creates ACTION_SEND_MULTIPLE intent`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        service = EmailShareService(context, "test.authority")

        val intent = service.buildIntent(
            attachments = listOf(File("test1.txt"), File("test2.txt")),
            subject = "Test Subject",
            body = "Test Body",
            recipient = "test@example.com"
        )

        assertThat(intent.action).isEqualTo(Intent.ACTION_SEND_MULTIPLE)
    }
}