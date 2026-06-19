package com.gijimemo.whisper.di

import com.gijimemo.whisper.ModelManager
import com.gijimemo.whisper.WhisperModel
import com.gijimemo.whisper.WhisperModelImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WhisperModule {

    @Provides
    @Singleton
    fun provideWhisperModel(): WhisperModel = WhisperModelImpl()

    // ModelManager is already @Singleton with @Inject constructor,
    // but we need OkHttpClient which is likely already provided elsewhere.
    // Since ModelManager uses @Inject constructor, Hilt can create it automatically.
    // No explicit provider needed unless OkHttpClient is scoped differently.

    // Note: OkHttpClient must be available in the Hilt graph.
    // The app's NetworkModule or similar should provide it @Singleton.
}
