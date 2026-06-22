package com.gijimemo.llm

/**
 * v0.7.x ストリーミング文字起こしの delta イベント。
 *
 * PCM Flow の各窓推論ごとに 1 つ emit される。
 * [isFinal] は将来用フラグで、本実装では常に false (ウィンドウベースの中間結果のみ)。
 *
 * @param text 窓推論で得られたテキスト
 * @param t0Ms セグメント開始時刻 (オーディオ全体基準、ms)
 * @param t1Ms セグメント終了時刻 (オーディオ全体基準、ms)
 * @param isFinal 確定済みかどうか (将来拡張用)
 */
data class TranscriptDelta(
    val text: String,
    val t0Ms: Long,
    val t1Ms: Long,
    val isFinal: Boolean = false,
)