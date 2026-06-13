package com.gijimemo.data.di

import android.content.Context
import androidx.room.Room
import com.gijimemo.data.db.GijiMemoDatabase
import com.gijimemo.data.db.SessionDao
import com.gijimemo.data.prefs.EncryptedPrefs
import com.gijimemo.data.prefs.SecurePrefs
import com.gijimemo.data.prefs.SettingsDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): GijiMemoDatabase =
        Room.databaseBuilder(ctx, GijiMemoDatabase::class.java, "gijimemo.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideSessionDao(db: GijiMemoDatabase): SessionDao = db.sessionDao()

    @Provides
    @Singleton
    fun provideSecurePrefs(@ApplicationContext ctx: Context): SecurePrefs =
        EncryptedPrefs(ctx)

    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext ctx: Context): SettingsDataStore =
        SettingsDataStore(ctx)
}