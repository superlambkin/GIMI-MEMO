package com.gijimemo.audio

/**
 * 録音時の音声処理設定。
 * RecordingViewModel が SettingsRepository から読み取り、AudioRecorder に渡す。
 */
data class AudioProcessingConfig(
    val sampleRate: Int = 16000,
    // v0.7.2: 48kbps → 128kbps。16kHz mono AAC-LC における推奨値で、
    // 同梱 Recorder 系アプリと同じ音質・音量感を実現。
    val bitRate: Int = 128000,
    /** ノイズ抑制（空調・ファン等の定常ノイズ） */
    val noiseSuppressor: Boolean = true,
    /** 自動音量調整（発言者の距離差を補正） */
    val automaticGainControl: Boolean = true,
    /** 声活動検出（無音部分をスキップ） */
    val voiceActivityDetection: Boolean = true,
    /** v0.7.2: PCM 録音後のソフトウェアゲイン倍率 (1.0=等倍, 2.0=+6dB)。
     *  AudioSource=VOICE_COMMUNICATION で音量が小さくなる問題を補う。
     *  MediaRecorder (AAC エンコード) では効果がないため将来用に残す。 */
    val gainMultiplier: Float = 1.5f
)
