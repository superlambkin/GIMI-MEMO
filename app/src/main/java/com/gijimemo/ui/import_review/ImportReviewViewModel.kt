package com.gijimemo.ui.import_review

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gijimemo.data.model.Session
import com.gijimemo.data.prefs.SettingsDataStore
import com.gijimemo.data.repository.SessionRepository
import com.gijimemo.data.repository.SettingsRepository
import com.gijimemo.ui.recording.PlaybackState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * MP3 インポート直後の確認画面用 ViewModel。
 * 録音停止後の StoppedPlaybackAndTranscribe と機能的に等価:
 *  - インポート済み Session の音声を再生
 *  - キャンセル時は Session + ファイルを削除して home に戻る
 * [PlaybackState] と画面側 UI は `ui.recording` パッケージのものを再利用する。
 */
@HiltViewModel
class ImportReviewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: SessionRepository,
    private val settings: SettingsRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val sessionId: String = savedStateHandle.get<String>("sessionId") ?: error("missing sessionId")

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    private val _playbackState = MutableStateFlow(PlaybackState.Idle)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private var player: MediaPlayer? = null
    private var positionJob: kotlinx.coroutines.Job? = null
    private val _playbackPosition = MutableStateFlow(0L)
    val playbackPosition: StateFlow<Long> = _playbackPosition.asStateFlow()
    private val _playbackDuration = MutableStateFlow(0L)
    val playbackDuration: StateFlow<Long> = _playbackDuration.asStateFlow()

    init {
        viewModelScope.launch {
            _session.value = repo.getById(sessionId)
        }
    }

    /**
     * 設定画面 > 呼び出しモード > 文字起こし方式 と同期する StateFlow。
     * cloud / on_device / network の 3 値。録音確認画面にラジオボタンとして露出させる。
     */
    val asrMode: StateFlow<String> = settings.asrMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsDataStore.ASR_MODE_CLOUD)

    /** 設定画面に通知するため SettingsRepository 経由で永続化する。 */
    fun setAsrMode(mode: String) {
        viewModelScope.launch { settings.setAsrMode(mode) }
    }

    override fun onCleared() {
        super.onCleared()
        releasePlayer()
    }

    // ─── 再生制御（シーク対応） ───────────────────────────

    fun startPlayback() {
        val location = _session.value?.audioFilePath ?: return
        if (location.isBlank()) return
        releasePlayer()
        try {
            val mp = MediaPlayer().apply {
                if (location.startsWith("content://") || location.startsWith("file://")) {
                    setDataSource(context, Uri.parse(location))
                } else {
                    setDataSource(location)
                }
                setOnCompletionListener {
                    _playbackState.value = PlaybackState.Idle
                    positionJob?.cancel()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what extra=$extra location=$location")
                    _playbackState.value = PlaybackState.Idle
                    positionJob?.cancel()
                    true
                }
                prepare()
                start()
            }
            player = mp
            _playbackDuration.value = mp.duration.toLong()
            positionJob = viewModelScope.launch {
                while (true) {
                    player?.let { if (it.isPlaying) _playbackPosition.value = it.currentPosition.toLong() }
                    kotlinx.coroutines.delay(250)
                }
            }
            _playbackState.value = PlaybackState.Playing
        } catch (e: Exception) {
            Log.e(TAG, "再生開始失敗 (location=$location): ${e.message}", e)
            _playbackState.value = PlaybackState.Idle
        }
    }

    fun pausePlayback() {
        player?.let {
            if (it.isPlaying) {
                it.pause()
                _playbackState.value = PlaybackState.Paused
            }
        }
    }

    fun resumePlayback() {
        player?.let {
            if (!it.isPlaying && _playbackState.value == PlaybackState.Paused) {
                it.start()
                _playbackState.value = PlaybackState.Playing
            }
        }
    }

    fun stopPlayback() {
        positionJob?.cancel()
        releasePlayer()
        _playbackState.value = PlaybackState.Idle
        _playbackPosition.value = 0L
    }

    fun seekAudio(positionMs: Int) {
        player?.let { if (positionMs in 0..it.duration) { it.seekTo(positionMs); _playbackPosition.value = positionMs.toLong() } }
    }

    private fun releasePlayer() {
        player?.apply {
            if (isPlaying) stop()
            release()
        }
        player = null
    }

    /**
     * インポートをキャンセルする。Session DB エントリと音声ファイルを削除。
     */
    fun cancelImport(onDone: () -> Unit) {
        releasePlayer()
        viewModelScope.launch {
            val s = _session.value
            if (s != null) {
                // 音声ファイル削除 (インポート時は files/audio/{id}.mp3 に保存される想定)
                s.audioFilePath.let { loc ->
                    runCatching {
                        when {
                            loc.startsWith("content://") -> {
                                context.contentResolver.delete(Uri.parse(loc), null, null)
                                Unit
                            }
                            else -> {
                                val f = File(loc)
                                if (f.exists()) f.delete()
                                Unit
                            }
                        }
                    }
                }
                repo.delete(s.id)
            }
            onDone()
        }
    }

    companion object {
        private const val TAG = "ImportReviewVM"
    }
}
