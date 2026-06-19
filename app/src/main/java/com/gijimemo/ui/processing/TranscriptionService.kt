package com.gijimemo.ui.processing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * 文字起こし処理中の画面オフ対策 ForegroundService。
 *
 * 役割:
 * 1. Foreground Service 通知を表示 → システムがプロセスを強制終了するのを防ぐ
 * 2. Partial WakeLock を確保 → CPU / Wi-Fi がスリープするのを防ぐ
 *
 * 使用方法:
 *   TranscriptionService.start(context)  // 文字起こし開始時
 *   TranscriptionService.stop(context)   // 完了/エラー時
 *
 * 実際の文字起こしロジックは ProcessingViewModel が行い、
 * 本 Service は画面オフ対策のライフサイクル管理を担当する。
 */
class TranscriptionService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        // Partial WakeLock: CPU と Wi-Fi をスリープさせない
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                "GijiMemo:Transcription"
            ).apply {
                acquire(60 * 60 * 1000L) // 最大 1 時間（タイムアウト安全策）
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.d(TAG, "TranscriptionService stopped")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "文字起こし",
            NotificationManager.IMPORTANCE_LOW // バイブレーションなし、静か
        ).apply {
            description = "文字起こし処理中の通知"
            setShowBadge(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val builder: Notification.Builder
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            builder = Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            builder = Notification.Builder(this)
        }
        return builder
            .setContentTitle("文字起こし中")
            .setContentText("音声ファイルを処理しています...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "TranscriptionSvc"
        private const val CHANNEL_ID = "transcription_service"
        private const val NOTIFICATION_ID = 1001

        /** 文字起こし開始時に ForegroundService を起動する。 */
        fun start(context: Context) {
            val intent = Intent(context, TranscriptionService::class.java)
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    @Suppress("DEPRECATION")
                    context.startService(intent)
                }
                Log.d(TAG, "TranscriptionService started")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start TranscriptionService: ${e.message}")
            }
        }

        /** 文字起こし完了/エラー時に ForegroundService を停止する。 */
        fun stop(context: Context) {
            val intent = Intent(context, TranscriptionService::class.java)
            try {
                context.stopService(intent)
                Log.d(TAG, "TranscriptionService stop requested")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop TranscriptionService: ${e.message}")
            }
        }
    }
}
