package com.gijimemo.whisper

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Known Whisper models available for download. */
data class WhisperModelInfo(
    val name: String,
    val displayName: String,
    val url: String,
    val sizeBytes: Long,
    val description: String,
    /** True if the model is shipped inside the APK under assets/whisper_models/. */
    val isBundled: Boolean = false
)

/**
 * Manages download and caching of whisper.cpp GGML model files.
 * Models are stored in [context.filesDir]/whisper_models/.
 */
@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    private val modelDir: File get() = File(context.filesDir, "whisper_models").also { it.mkdirs() }

    /** Available models. First entry is the default. */
    val availableModels: List<WhisperModelInfo> = listOf(
        // v0.7.4: 5モデルを選択可能に（tiny / base / small / medium / large Q5_1量子化）
        WhisperModelInfo(
            name = "ggml-tiny-q5_1.bin",
            displayName = "超高速 (Q5_1 tiny, ~31MB)",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin",
            sizeBytes = 32152673L,
            description = "APK同梱: 最速・推奨",
            isBundled = true
        ),
        WhisperModelInfo(
            name = "ggml-base-q5_1.bin",
            displayName = "高速 (Q5_1 base, ~57MB)",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin",
            sizeBytes = 59707625L,
            description = "ダウンロード: 高精度",
            isBundled = false
        ),
        WhisperModelInfo(
            name = "ggml-small-q5_1.bin",
            displayName = "標準 (Q5_1 small, ~184MB)",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin",
            sizeBytes = 184L * 1024 * 1024,
            description = "ダウンロード: 精度・速度のバランス"
        ),
        WhisperModelInfo(
            name = "ggml-medium-q5_0.bin",
            displayName = "高精度 (Q5_0 medium, ~346MB)",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-medium-q5_0.bin",
            sizeBytes = 346L * 1024 * 1024,
            description = "ダウンロード: 高精度"
        ),
        WhisperModelInfo(
            name = "ggml-large-v3-q5_0.bin",
            displayName = "最高精度 (Q5_0 large-v3, ~529MB)",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-q5_0.bin",
            sizeBytes = 529L * 1024 * 1024,
            description = "ダウンロード: 最大精度・低速"
        ),
    )

    /**
     * Copy a bundled model from APK assets to internal storage on first launch.
     * Idempotent: if the target file already exists (from a previous launch or
     * a manual download), this is a no-op. Network is not touched.
     *
     * @param onProgress callback with progress 0f..1f (called on background thread).
     * @throws IllegalArgumentException if [modelName] is not flagged as bundled.
     * @throws IOException if the assets file is missing or copy fails.
     */
    suspend fun ensureBundledModel(
        modelName: String,
        onProgress: (Float) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        val info = availableModels.find { it.name == modelName }
            ?: throw IllegalArgumentException("Unknown model: $modelName")
        if (!info.isBundled) {
            throw IllegalArgumentException("Model is not bundled in APK: $modelName")
        }

        val targetFile = getModelFile(modelName)
        if (targetFile.exists() && targetFile.length() == info.sizeBytes) {
            Log.d(TAG, "Bundled model already extracted: ${targetFile.absolutePath}")
            onProgress(1f)
            return@withContext
        }

        val assetsPath = "whisper_models/${info.name}"
        Log.d(TAG, "Extracting bundled model from assets: $assetsPath -> ${targetFile.absolutePath}")
        onProgress(0f)

        val tempFile = File(targetFile.absolutePath + ".tmp")
        context.assets.open(assetsPath).use { input ->
            FileOutputStream(tempFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Long = 0
                var lastReported = 0f
                while (true) {
                    val n = input.read(buffer)
                    if (n == -1) break
                    output.write(buffer, 0, n)
                    bytesRead += n
                    val p = (bytesRead.toFloat() / info.sizeBytes).coerceIn(0f, 1f)
                    if (p - lastReported >= 0.01f) {
                        onProgress(p)
                        lastReported = p
                    }
                }
            }
        }

        if (!tempFile.renameTo(targetFile)) {
            tempFile.delete()
            throw IOException("Failed to rename temp file to: ${targetFile.name}")
        }
        Log.d(TAG, "Bundled model extracted: ${targetFile.length()} bytes")
        onProgress(1f)
    }

    /** Get the file path for a model. */
    fun getModelFile(modelName: String): File = File(modelDir, modelName)

    /**
     * Check if a model is already downloaded (or, for bundled models, available
     * to be extracted from APK assets).
     */
    fun isModelDownloaded(modelName: String): Boolean {
        val info = availableModels.find { it.name == modelName }
        return if (info?.isBundled == true) {
            // Bundled models are always considered "available" — extraction is cheap.
            true
        } else {
            getModelFile(modelName).exists()
        }
    }

    /**
     * True if the bundled model has already been extracted to internal storage.
     * Distinct from [isModelDownloaded]: a bundled model is "available" without
     * ever being on disk, but the UI splash gate cares about whether the
     * extraction step has actually run.
     */
    fun isBundledModelExtracted(modelName: String): Boolean {
        val file = getModelFile(modelName)
        return file.exists() && file.length() > 0
    }

    /**
     * Delete legacy default model files from previous installs if present.
     * Safe to call on every launch.
     * - `ggml-base-q5_1.bin`: v0.2.0 era default
     * - `ggml-tiny.bin`: short-lived tiny experiment (reverted to base for accuracy)
     */
    fun cleanupLegacyModel() {
        listOf("ggml-base-q5_1.bin", "ggml-tiny.bin", "ggml-base.bin").forEach { name ->
            val legacy = getModelFile(name)
            if (legacy.exists()) {
                Log.d(TAG, "Removing legacy model: ${legacy.name} (${legacy.length()} bytes)")
                legacy.delete()
            }
        }
    }

    /** Get download progress for a model (0f..1f) based on partial download. */
    fun getDownloadProgress(modelName: String): Float {
        val file = getModelFile(modelName)
        val info = availableModels.find { it.name == modelName } ?: return 0f
        if (!file.exists()) return 0f
        return (file.length().toFloat() / info.sizeBytes).coerceIn(0f, 1f)
    }

    /**
     * Download a model file from HuggingFace.
     * @param modelName name in [availableModels]
     * @param onProgress callback with progress 0f..1f (called on background thread)
     */
    suspend fun downloadModel(
        modelName: String,
        onProgress: (Float) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        val info = availableModels.find { it.name == modelName }
            ?: throw IllegalArgumentException("Unknown model: $modelName")

        val targetFile = getModelFile(modelName)
        if (targetFile.exists()) {
            Log.d(TAG, "Model already downloaded: ${targetFile.absolutePath}")
            onProgress(1f)
            return@withContext
        }

        val tempFile = File(targetFile.absolutePath + ".tmp")
        Log.d(TAG, "Downloading model: ${info.url} -> ${tempFile.absolutePath}")

        val request = Request.Builder()
            .url(info.url)
            .addHeader("User-Agent", "GijiMemo/0.1")
            .build()

        val client = okHttpClient.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .build()

        var response: Response? = null
        try {
            response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                throw IOException("Download failed: HTTP ${response.code}")
            }

            val body = response.body ?: throw IOException("Empty response body")
            val contentLength = body.contentLength()
            val inputStream = body.byteStream()
            val outputStream = FileOutputStream(tempFile)

            val buffer = ByteArray(8192)
            var bytesRead: Long = 0
            var lastReportedProgress = 0f
            var bytesSinceLastLog = 0L

            inputStream.use { input ->
                outputStream.use { output ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n == -1) break
                        output.write(buffer, 0, n)
                        bytesRead += n
                        bytesSinceLastLog += n

                        // Report progress
                        if (contentLength > 0) {
                            val progress = (bytesRead.toFloat() / contentLength).coerceIn(0f, 1f)
                            if (progress - lastReportedProgress >= 0.01f) {
                                onProgress(progress)
                                lastReportedProgress = progress
                            }
                        }

                        // Log every ~1MB
                        if (bytesSinceLastLog >= 1024 * 1024) {
                            Log.d(TAG, "Download: ${bytesRead / 1024 / 1024}MB / ${contentLength / 1024 / 1024}MB")
                            bytesSinceLastLog = 0
                        }
                    }
                }
            }

            // Rename temp to final
            if (!tempFile.renameTo(targetFile)) {
                throw IOException("Failed to rename temp file to: ${targetFile.name}")
            }

            Log.d(TAG, "Model downloaded: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
            onProgress(1f)

        } catch (e: Exception) {
            tempFile.delete()
            Log.e(TAG, "Download failed: ${e.message}", e)
            throw e
        } finally {
            response?.close()
        }
    }

    /** Delete a downloaded model to free storage. */
    fun deleteModel(modelName: String) {
        getModelFile(modelName).delete()
        Log.d(TAG, "Model deleted: $modelName")
    }

    /** Available storage for model download in bytes. */
    fun availableStorageBytes(): Long {
        val stat = File(context.filesDir.parent ?: context.filesDir.absolutePath)
        return stat.freeSpace
    }

    companion object {
        private const val TAG = "ModelManager"
    }
}
