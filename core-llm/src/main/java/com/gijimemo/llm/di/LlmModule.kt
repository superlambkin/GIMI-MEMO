package com.gijimemo.llm.di

import android.content.Context
import android.util.Log
import com.gijimemo.data.repository.SettingsRepository
import com.gijimemo.llm.OnDeviceWhisperClient
import com.gijimemo.llm.OpenAiCompatibleClient
import com.gijimemo.whisper.ModelManager
import com.gijimemo.whisper.WhisperModel
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okio.Buffer
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LlmModule {
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logInterceptor = Interceptor { chain ->
            val req = chain.request()
            Log.d(TAG, "--> ${req.method} ${req.url}")

            // Request body 抜粋（デバッグ用。先頭 300 文字のみ）
            runCatching {
                val buf = Buffer()
                req.body?.writeTo(buf)
                val bodyStr = buf.readUtf8().replace("\n", " ").take(300)
                if (bodyStr.isNotBlank()) Log.d(TAG, "    body[:300]=${bodyStr}")
            }

            val resp: Response = chain.proceed(req)

            // Response body 抜粋（デバッグ用。先頭 500 文字のみ）
            val peekBody = runCatching { resp.peekBody(1024 * 8).string() }.getOrNull()
            Log.d(TAG, "<-- ${resp.code} ${req.url} contentType=${resp.body?.contentType()}")
            if (peekBody != null) {
                Log.d(TAG, "    body[:500]=${peekBody.replace("\n", "\\n").take(500)}")
            }

            resp
        }
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(logInterceptor)
            .build()
    }

    @Provides
    @Singleton
    fun provideOnDeviceWhisperClient(
        @ApplicationContext context: Context,
        whisperModel: WhisperModel,
        modelManager: ModelManager,
        openAiClient: OpenAiCompatibleClient,
        settings: SettingsRepository
    ): OnDeviceWhisperClient {
        // v0.7.4: 設定から選択したモデルを使用可能に。
        return OnDeviceWhisperClient(context, whisperModel, modelManager, openAiClient, settings, useGpu = false)
    }

    private const val TAG = "GijiMemoLLM"
}
