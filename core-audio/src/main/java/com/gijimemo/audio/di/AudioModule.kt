// core-audio/src/main/java/com/gijimemo/audio/di/AudioModule.kt
package com.gijimemo.audio.di

import com.gijimemo.audio.AudioRecorder
import com.gijimemo.audio.MediaRecorderLameImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AudioModule {
    @Binds
    @Singleton
    abstract fun bindAudioRecorder(impl: MediaRecorderLameImpl): AudioRecorder
}
