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
    private val keyRecipients = stringPreferencesKey("recipient_list")
    private val keyFormatPriority = stringPreferencesKey("format_priority")
    private val keyThemeMode = stringPreferencesKey("theme_mode")
    private val keyPromptTemplate = stringPreferencesKey("prompt_template")

    val defaultProvider: Flow<String> = context.dataStore.data.map { it[keyProvider] ?: "MiniMax 国内" }
    val defaultModel: Flow<String> = context.dataStore.data.map { it[keyModel] ?: "MiniMax-M3" }
    val defaultCallMode: Flow<LlmCallMode> = context.dataStore.data.map {
        LlmCallMode.valueOf(it[keyCallMode] ?: LlmCallMode.MULTIMODAL.name)
    }
    val defaultChunkMinutes: Flow<Int> = context.dataStore.data.map { it[keyChunkMinutes] ?: 25 }
    val defaultRecipient: Flow<String> = context.dataStore.data.map { it[keyRecipients] ?: "" }
    val recipients: Flow<List<String>> = context.dataStore.data.map { prefs ->
        prefs[keyRecipients]
            ?.split("\n")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
    }
    val defaultFormatPriority: Flow<String> = context.dataStore.data.map { it[keyFormatPriority] ?: "docx,md,txt" }
    val defaultThemeMode: Flow<String> = context.dataStore.data.map { it[keyThemeMode] ?: "system" }
    val defaultPromptTemplate: Flow<String> = context.dataStore.data.map {
        it[keyPromptTemplate] ?: DEFAULT_PROMPT_TEMPLATE
    }

    suspend fun setDefaultProvider(v: String) = context.dataStore.edit { it[keyProvider] = v }
    suspend fun setDefaultModel(v: String) = context.dataStore.edit { it[keyModel] = v }
    suspend fun setDefaultCallMode(v: LlmCallMode) = context.dataStore.edit { it[keyCallMode] = v.name }
    suspend fun setDefaultChunkMinutes(v: Int) = context.dataStore.edit { it[keyChunkMinutes] = v }
    suspend fun setDefaultRecipient(v: String) = context.dataStore.edit { it[keyRecipients] = v }
    suspend fun setRecipients(list: List<String>) =
        context.dataStore.edit { it[keyRecipients] = list.joinToString("\n") }
    suspend fun addRecipient(email: String) {
        val trimmed = email.trim()
        if (trimmed.isEmpty()) return
        context.dataStore.edit { prefs ->
            val current = prefs[keyRecipients]
                ?.split("\n")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
            if (current.contains(trimmed)) return@edit
            prefs[keyRecipients] = (current + trimmed).joinToString("\n")
        }
    }
    suspend fun removeRecipient(email: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[keyRecipients]
                ?.split("\n")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
            prefs[keyRecipients] = current.filter { it != email }.joinToString("\n")
        }
    }
    suspend fun setDefaultFormatPriority(v: String) = context.dataStore.edit { it[keyFormatPriority] = v }
    suspend fun setDefaultThemeMode(v: String) = context.dataStore.edit { it[keyThemeMode] = v }
    suspend fun setDefaultPromptTemplate(v: String) = context.dataStore.edit { it[keyPromptTemplate] = v }

    // Per-provider model selection: key = "default_model_<providerName>"
    fun modelForProvider(providerName: String): Flow<String?> =
        context.dataStore.data.map { it[stringPreferencesKey("default_model_$providerName")] }

    suspend fun setModelForProvider(providerName: String, model: String) =
        context.dataStore.edit { it[stringPreferencesKey("default_model_$providerName")] = model }

    companion object {
        const val DEFAULT_PROMPT_TEMPLATE = """以下の会議録音を文字起こしし、以下の構造で Markdown 議事録を出力してください：

# 会議のテーマ
（内容から自動抽出）

## 参加者
（発言者を識別）

## 議題と討論
- 議題 1：... 発言者 A の意見...
- 議題 2：...

## 決定事項
- 決定 1：...

## アクションアイテム
- [ ] 担当者：事項（期限）"""
    }
}