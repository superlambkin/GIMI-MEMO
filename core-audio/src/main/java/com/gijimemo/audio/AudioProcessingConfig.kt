package com.gijimemo.audio

/**
 * 録音時の音声処理設定。
 * RecordingViewModel が SettingsRepository から読み取り、AudioRecorder に渡す。
 */
data class AudioProcessingConfig(
    val sampleRate: Int = 16000,
    val bitRate: Int = 48000,
    /** ノイズ抑制（空調・ファン等の定常ノイズ） */
    val noiseSuppressor: Boolean = true,
    /** 自動音量調整（発言者の距離差を補正） */
    val automaticGainControl: Boolean = true,
    /** 声活動検出（無音部分をスキップ） */
    val voiceActivityDetection: Boolean = true
)
