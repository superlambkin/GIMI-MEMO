package com.gijimemo.data.repository

import com.gijimemo.data.model.LlmCallMode
import com.gijimemo.data.model.LlmProviderConfig
import com.gijimemo.data.model.findByName
import com.gijimemo.data.prefs.EncryptedPrefs
import com.gijimemo.data.prefs.SettingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    private val store: SettingsDataStore,
    private val encryptedPrefs: EncryptedPrefs
) {
    fun defaultProviders(): List<LlmProviderConfig> = LlmProviderConfig.defaults()

    suspend fun selectedProvider(): LlmProviderConfig {
        val name = store.defaultProvider.first()
        return defaultProviders().findByName(name) ?: defaultProviders().first()
    }

    fun selectedProviderFlow(): Flow<LlmProviderConfig> =
        store.defaultProvider.map { name ->
            defaultProviders().findByName(name) ?: defaultProviders().first()
        }

    fun getApiKey(ref: String): String? = encryptedPrefs.getApiKey(ref)
    fun setApiKey(ref: String, key: String) = encryptedPrefs.putApiKey(ref, key)

    val defaultCallMode get() = store.defaultCallMode
    val defaultChunkMinutes get() = store.defaultChunkMinutes
    val defaultRecipient get() = store.defaultRecipient
    val defaultFormatPriority get() = store.defaultFormatPriority
    val defaultThemeMode get() = store.defaultThemeMode
    val defaultPromptTemplate get() = store.defaultPromptTemplate

    fun modelForProvider(providerName: String) = store.modelForProvider(providerName)

    suspend fun setDefaultProvider(v: String) = store.setDefaultProvider(v)

    suspend fun setDefaultCallMode(v: LlmCallMode) = store.setDefaultCallMode(v)
    suspend fun setDefaultChunkMinutes(v: Int) = store.setDefaultChunkMinutes(v)
    suspend fun setDefaultRecipient(v: String) = store.setDefaultRecipient(v)
    suspend fun setDefaultFormatPriority(v: String) = store.setDefaultFormatPriority(v)
    suspend fun setDefaultThemeMode(v: String) = store.setDefaultThemeMode(v)
    suspend fun setDefaultPromptTemplate(v: String) = store.setDefaultPromptTemplate(v)
    suspend fun setModelForProvider(providerName: String, model: String) =
        store.setModelForProvider(providerName, model)
}