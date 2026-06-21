package com.gijimemo.data.model

data class Session(
    val id: String,
    val title: String,
    val createdAt: Long,
    val durationMs: Long,
    /**
     * 录音文件位置标识。
     * - 公共目录（API 29+ 默认）：MediaStore content URI 字符串，如
     *   `content://media/external/audio/media/12345`
     * - API 26-28 降级：公共目录绝对路径，如
     *   `/storage/emulated/0/Music/GijiMemo/<uuid>.m4a`
     * - 旧数据（私有目录）：`/data/data/com.gijimemo/files/audio/<uuid>.mp3`
     *
     * 读取侧（播放、分享）应统一用 `Uri.parse(audioFilePath)` 解析。
     */
    val audioFilePath: String,
    val audioSizeBytes: Long,
    val status: SessionStatus = SessionStatus.STOPPED,
    val transcriptMd: String? = null,
    val docxFilePath: String? = null,
    val mdFilePath: String? = null,
    val txtFilePath: String? = null,
    val llmProvider: String? = null,
    val llmModel: String? = null,
    val errorMessage: String? = null,
    /**
     * 文字起こし + 要約の合計処理時間 (ms)。完了時に記録。
     * 0L は未計測 (旧データ / インポート直後 etc.)。
     */
    val processingDurationMs: Long = 0L,
    /**
     * 文字起こし結果の原文（要約前のテキスト）。TXTファイル生成に使用。
     * null の場合は transcriptMd を TXT に使用（後方互換）。
     */
    val rawTranscript: String? = null,
    /**
     * 文字起こしのみの処理時間 (ms)。WHISPER_THEN_SUMMARY 経路で 0 より大きい値。
     * 0L は未計測 / マルチモーダル経路。
     */
    val transcribeDurationMs: Long = 0L,
    /**
     * 実行時の呼び出しモード。PreviewScreen で文字起こし時間の表示判定に使用。
     * null = 未記録 (旧データ / インポート直後)。
     */
    val llmCallMode: LlmCallMode? = null
)