// core-audio/src/test/java/com/gijimemo/audio/MediaRecorderLameImplTest.kt
package com.gijimemo.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MediaRecorderLameImplTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `initial state is Idle`() = runTest {
        val recorder = MediaRecorderLameImpl(context)
        recorder.state.test {
            assertThat(awaitItem()).isEqualTo(RecordingState.Idle)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `start transitions to Recording`() = runTest {
        val recorder = MediaRecorderLameImpl(context)
        val tmpFile = File.createTempFile("test", ".mp3")
        tmpFile.deleteOnExit()
        recorder.state.test {
            assertThat(awaitItem()).isEqualTo(RecordingState.Idle)
            recorder.start(tmpFile.absolutePath)
            assertThat(awaitItem()).isEqualTo(RecordingState.Recording)
            cancelAndIgnoreRemainingEvents()
        }
        recorder.stop()
    }

    @Test
    fun `currentFilePath is null initially`() {
        val recorder = MediaRecorderLameImpl(context)
        assertThat(recorder.currentFilePath).isNull()
    }
}
