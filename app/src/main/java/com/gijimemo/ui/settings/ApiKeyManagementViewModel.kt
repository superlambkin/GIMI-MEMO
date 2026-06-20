package com.gijimemo.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gijimemo.data.model.LlmProviderConfig
import com.gijimemo.data.repository.SettingsRepository
import com.gijimemo.llm.LlmProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * API Key 一括管理画面の ViewModel。
 *
 * - 6 プロバイダ分の API Key を 1 画面に並べて編集 → 一括保存
 * - 各プロバイダの接続テスト (個別)
 * - 自動選択モードで「設定済 ✓」バッジを表示
 */
@HiltViewModel
class ApiKeyManagementViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val llmProvider: LlmProvider
) : ViewModel() {

    /** 6 プロバイダ一覧 (順序は defaults() 順) */
    val providers: List<LlmProviderConfig> = settings.defaultProviders()

    /** 編集中の draft (ref → 入力値)。保存前の作業領域。 */
    private val _draft = MutableStateFlow<Map<String, String>>(emptyMap())
    val draft: StateFlow<Map<String, String>> = _draft.asStateFlow()

    /** 接続テスト結果 (ref → state)。Idle = 未実行。 */
    private val _testState = MutableStateFlow<Map<String, ApiTestState>>(emptyMap())
    val testState: StateFlow<Map<String, ApiTestState>> = _testState.asStateFlow()

    /** 一括保存の最終結果 (Success/Failure)。UI で Snackbar 通知用。 */
    private val _saveResult = MutableStateFlow<SaveResult?>(null)
    val saveResult: StateFlow<SaveResult?> = _saveResult.asStateFlow()

    init {
        // 起動時に現在の保存値を読んで draft にコピー。
        // getApiKey は SharedPreferences 同期読み (軽量・暗号化済み) なので
        // 専用 IO ディスパッチャへの切替はしない (テストで advanceUntilIdle を効かせるため)。
        val initial = providers.associate { p ->
            p.apiKeyRef to (settings.getApiKey(p.apiKeyRef) ?: "")
        }
        _draft.value = initial
        _testState.value = providers.associate { it.apiKeyRef to ApiTestState.Idle }
    }

    /**
     * 入力フィールド変更。空文字は「未設定」を意味する。
     */
    fun onKeyChange(ref: String, value: String) {
        _draft.update { it.toMutableMap().apply { this[ref] = value } }
        // 編集中はテスト結果を Idle に戻す (実行中以外)
        if (_testState.value[ref] !is ApiTestState.Running) {
            _testState.update {
                it.toMutableMap().apply { this[ref] = ApiTestState.Idle }
            }
        }
    }

    /**
     * 1 プロバイダの接続テスト。draft の現在値で試す (まだ保存されていなくても)。
     */
    fun testOne(ref: String) {
        val config = providers.firstOrNull { it.apiKeyRef == ref } ?: return
        val key = _draft.value[ref].orEmpty()
        if (key.isBlank() && config.name != "Ollama") {
            _testState.update { it.toMutableMap().apply { this[ref] = ApiTestState.Error("API Key が空です") } }
            return
        }
        _testState.update { it.toMutableMap().apply { this[ref] = ApiTestState.Running } }
        viewModelScope.launch {
            try {
                val client = llmProvider.createClient(
                    config = config,
                    apiKey = key,
                    model = config.defaultModel,
                    useOnDeviceAsr = false
                )
                val response = client.testConnection()
                _testState.update {
                    it.toMutableMap().apply { this[ref] = ApiTestState.Success(response.take(200)) }
                }
            } catch (e: Exception) {
                Log.w("ApiKeyMgmt", "testOne($ref) failed: ${e.message}")
                _testState.update {
                    it.toMutableMap().apply { this[ref] = ApiTestState.Error(e.message ?: e::class.java.simpleName) }
                }
            }
        }
    }

    /**
     * 全プロバイダ分の API Key を draft → EncryptedPrefs に一括保存。
     * setApiKey は EncryptedSharedPreferences への同期書き込み (軽量)。
     */
    fun saveAll() {
        viewModelScope.launch {
            try {
                providers.forEach { p ->
                    val value = _draft.value[p.apiKeyRef].orEmpty()
                    settings.setApiKey(p.apiKeyRef, value)  // 空文字も許容 (上書き削除)
                }
                _saveResult.value = SaveResult.Success(providers.size)
            } catch (e: Exception) {
                Log.e("ApiKeyMgmt", "saveAll failed", e)
                _saveResult.value = SaveResult.Failure(e.message ?: e::class.java.simpleName)
            }
        }
    }

    fun dismissSaveResult() {
        _saveResult.value = null
    }

    /** UI 初期表示時のフォールバック (init 完了前の 1 フレーム目用) */
    fun getDraft(ref: String): String = _draft.value[ref].orEmpty()

    /** 実際に保存済みのAPI Keyがあるか（draft の編集中状態ではなく、settings の永続値で判定） */
    fun hasSavedKey(ref: String): Boolean {
        val config = providers.firstOrNull { it.apiKeyRef == ref }
        // Ollama は API Key 不要で常に利用可能
        if (config?.name == "Ollama") return true
        val saved = settings.getApiKey(ref)
        return !saved.isNullOrBlank()
    }

    sealed class SaveResult {
        data class Success(val savedCount: Int) : SaveResult()
        data class Failure(val message: String) : SaveResult()
    }

    /** Connection test result */
    sealed class ApiTestState {
        object Idle : ApiTestState()
        object Running : ApiTestState()
        data class Success(val response: String) : ApiTestState()
        data class Error(val message: String) : ApiTestState()
    }
}
