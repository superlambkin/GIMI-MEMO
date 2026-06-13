package com.gijimemo.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gijimemo.data.model.LlmCallMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsDataStore(private val context: Context) {
    private val keyProvider = stringPreferencesKey("default_provider")
    private val keyModel = stringPreferencesKey("default_model")
    private val keyCallMode = stringPreferencesKey("default_call_mode")
    private val keyChunkMinutes = intPreferencesKey("default_chunk_minutes")
    private val keyRecipient = stringPreferencesKey("default_recipient")
    private val keyFormatPriority = stringPreferencesKey("format_priority")
    private val keyThemeMode = stringPreferencesKey("theme_mode")
    private val keyPromptTemplate = stringPreferencesKey("prompt_template")

    val defaultProvider: Flow<String> = context.dataStore.data.map { it[keyProvider] ?: "MiniMax" }
    val defaultModel: Flow<String> = context.dataStore.data.map { it[keyModel] ?: "MiniMax-M3" }
    val defaultCallMode: Flow<LlmCallMode> = context.dataStore.data.map {
        LlmCallMode.valueOf(it[keyCallMode] ?: LlmCallMode.MULTIMODAL.name)
    }
    val defaultChunkMinutes: Flow<Int> = context.dataStore.data.map { it[keyChunkMinutes] ?: 25 }
    val defaultRecipient: Flow<String> = context.dataStore.data.map { it[keyRecipient] ?: "" }
    val defaultFormatPriority: Flow<String> = context.dataStore.data.map { it[keyFormatPriority] ?: "docx,md,txt" }
    val defaultThemeMode: Flow<String> = context.dataStore.data.map { it[keyThemeMode] ?: "system" }
    val defaultPromptTemplate: Flow<String> = context.dataStore.data.map {
        it[keyPromptTemplate] ?: DEFAULT_PROMPT_TEMPLATE
    }

    suspend fun setDefaultProvider(v: String) = context.dataStore.edit { it[keyProvider] = v }
    suspend fun setDefaultModel(v: String) = context.dataStore.edit { it[keyModel] = v }
    suspend fun setDefaultCallMode(v: LlmCallMode) = context.dataStore.edit { it[keyCallMode] = v.name }
    suspend fun setDefaultChunkMinutes(v: Int) = context.dataStore.edit { it[keyChunkMinutes] = v }
    suspend fun setDefaultRecipient(v: String) = context.dataStore.edit { it[keyRecipient] = v }
    suspend fun setDefaultFormatPriority(v: String) = context.dataStore.edit { it[keyFormatPriority] = v }
    suspend fun setDefaultThemeMode(v: String) = context.dataStore.edit { it[keyThemeMode] = v }
    suspend fun setDefaultPromptTemplate(v: String) = context.dataStore.edit { it[keyPromptTemplate] = v }

    // Per-provider model selection: key = "default_model_<providerName>"
    fun modelForProvider(providerName: String): Flow<String?> =
        context.dataStore.data.map { it[stringPreferencesKey("default_model_$providerName")] }

    suspend fun setModelForProvider(providerName: String, model: String) =
        context.dataStore.edit { it[stringPreferencesKey("default_model_$providerName")] = model }

    companion object {
        const val DEFAULT_PROMPT_TEMPLATE = """请把以下会议录音转写为中文，并按以下结构输出 Markdown 会议纪要：

# 会议主题
（自动从内容提取）

## 参会人
（识别发言人）

## 议题与讨论
- 议题 1：... 发言人 A 的观点...
- 议题 2：...

## 决策事项
- 决策 1：...

## 行动项
- [ ] 负责人：事项（截止日期）"""
    }
}