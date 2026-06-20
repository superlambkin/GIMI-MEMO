package com.gijimemo.ui.preview

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * クラウドTTS API クライアント。
 * ローカルTTSで日本語が使えない場合のフォールバック。
 * Google Translate TTS エンジンを利用（無料・APIキー不要）。
 */
class CloudTtsClient {

    private val tag = "CloudTts"

    /**
     * テキストを音声合成し、MP3ファイルとして保存してパスを返す。
     * @param text 読み上げるテキスト
     * @param lang 言語コード（"ja" = 日本語）
     * @param cacheDir 一時ファイル保存ディレクトリ
     * @return MP3ファイルパス、失敗時はnull
     */
    suspend fun synthesize(
        text: String,
        lang: String = "ja",
        cacheDir: File
    ): String? {
        return withContext(Dispatchers.IO) {
            try {
                val encoded = java.net.URLEncoder.encode(text, "UTF-8")
                val urlStr = "https://translate.google.com/translate_tts?ie=UTF-8&q=$encoded&tl=$lang&client=tw-ob"
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.connectTimeout = 10000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = true

                val responseCode = conn.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    Log.w(tag, "API error: $responseCode")
                    conn.disconnect()
                    return@withContext null
                }

                val mp3File = File(cacheDir, "cloud_tts_${System.currentTimeMillis()}.mp3")
                conn.inputStream.use { input ->
                    mp3File.outputStream().use { output -> input.copyTo(output) }
                }
                conn.disconnect()
                Log.d(tag, "Synthesized ${text.length}chars -> ${mp3File.absolutePath}")
                mp3File.absolutePath
            } catch (e: Exception) {
                Log.e(tag, "Cloud TTS failed: ${e.message}")
                null
            }
        }
    }
}
