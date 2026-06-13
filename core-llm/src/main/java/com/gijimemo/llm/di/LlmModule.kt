package com.gijimemo.llm.di

import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
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
            Log.d("GijiMemoLLM", "--> ${req.method} ${req.url}")
            val resp: Response = chain.proceed(req)
            Log.d("GijiMemoLLM", "<-- ${resp.code} ${req.url}")
            resp
        }
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(logInterceptor)
            .build()
    }
}
