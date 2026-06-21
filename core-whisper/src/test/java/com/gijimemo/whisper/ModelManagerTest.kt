package com.gijimemo.whisper

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [ModelManager] (logic-only — does NOT touch the real
 * 141 MB ggml-base.bin asset to keep the test suite fast).
 *
 * The 141 MB model asset exists on disk for the real APK build, but reading
 * it in a test would dominate the suite runtime. We instead exercise:
 *   - the [WhisperModelInfo] registry contents
 *   - bundled vs non-bundled branching in [ModelManager.isModelDownloaded]
 *   - the [ModelManager.cleanupLegacyModel] no-op path
 *   - argument validation in [ModelManager.ensureBundledModel]
 */
@RunWith(RobolectricTestRunner::class)
class ModelManagerTest {

    private lateinit var context: Context
    private lateinit var modelManager: ModelManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        modelManager = ModelManager(context, OkHttpClient())
    }

    @Test
    fun `availableModels has exactly one bundled model`() {
        val bundled = modelManager.availableModels.filter { it.isBundled }
        assertThat(bundled).hasSize(1)
        assertThat(bundled.first().name).isEqualTo("ggml-base-q5_1.bin")
    }

    @Test
    fun `isBundledModelExtracted returns false before extraction`() {
        // Fresh state: no file on disk yet.
        assertThat(modelManager.isBundledModelExtracted("ggml-base-q5_1.bin")).isFalse()
    }

    @Test
    fun `isBundledModelExtracted returns true once file is on disk`() {
        // Simulate the post-extraction state without actually running the
        // 57 MB copy.
        val target = modelManager.getModelFile("ggml-base-q5_1.bin")
        target.parentFile?.mkdirs()
        target.writeBytes(ByteArray(1024) { 0x55 })

        assertThat(modelManager.isBundledModelExtracted("ggml-base-q5_1.bin")).isTrue()
    }

    @Test
    fun `isModelDownloaded returns true for bundled models without touching storage`() = runTest {
        // Bundled models are always considered "downloaded" — we can extract
        // them on demand from APK assets, so on-disk presence is irrelevant.
        assertThat(modelManager.isModelDownloaded("ggml-base-q5_1.bin")).isTrue()
    }

    @Test
    fun `isModelDownloaded returns false for non-bundled model that is not on disk`() = runTest {
        // The test never downloads anything, so the tiny Q5_1 entry should be
        // flagged as not yet on disk.
        assertThat(modelManager.isModelDownloaded("ggml-tiny-q5_1.bin")).isFalse()
    }

    @Test
    fun `isModelDownloaded returns false for unknown model name`() = runTest {
        // Unknown names fall through to "does the file exist?" → false.
        assertThat(modelManager.isModelDownloaded("ggml-does-not-exist.bin")).isFalse()
    }

    @Test
    fun `ensureBundledModel throws for non-bundled model name`() = runTest {
        var thrown: IllegalArgumentException? = null
        try {
            modelManager.ensureBundledModel("ggml-tiny-q5_1.bin")
        } catch (e: IllegalArgumentException) {
            thrown = e
        }
        assertThat(thrown).isNotNull()
        assertThat(thrown!!.message).contains("not bundled")
    }

    @Test
    fun `ensureBundledModel throws for unknown model name`() = runTest {
        var thrown: IllegalArgumentException? = null
        try {
            modelManager.ensureBundledModel("does-not-exist.bin")
        } catch (e: IllegalArgumentException) {
            thrown = e
        }
        assertThat(thrown).isNotNull()
    }

    @Test
    fun `getDownloadProgress returns 0f for non-existent non-bundled model`() {
        // Pure helper: no IO, no assets, just math.
        assertThat(modelManager.getDownloadProgress("ggml-tiny-q5_1.bin")).isEqualTo(0f)
    }

    @Test
    fun `cleanupLegacyModel is idempotent on clean state`() {
        // Calling on a clean state must not throw. Idempotency is a property
        // we care about because cleanup runs on every app launch.
        modelManager.cleanupLegacyModel()
        modelManager.cleanupLegacyModel()
    }

    @Test
    fun `cleanupLegacyModel removes stale q5_1 file from previous install`() {
        // Simulate a v0.2.0 install that had q5_1 as default: the legacy
        // file should be removed on next launch.
        val legacyFile = modelManager.getModelFile("ggml-base-q5_1.bin")
        legacyFile.parentFile?.mkdirs()
        legacyFile.writeBytes(ByteArray(1024) { 0x55 })
        assertThat(legacyFile.exists()).isTrue()

        modelManager.cleanupLegacyModel()

        assertThat(legacyFile.exists()).isFalse()
    }
}
