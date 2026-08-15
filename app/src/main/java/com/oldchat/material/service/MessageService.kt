package com.oldchat.material.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.oldchat.material.MainActivity
import com.oldchat.material.OldChatApplication

/**
 * Background service that keeps WebSocket alive.
 *
 * Mirrors MessageService from §11.1:
 * - START_STICKY
 * - API 26+ foreground service notification
 * - startIfAllowed → WSManager.start
 * - onDestroy → stop global WS
 */
class MessageService : Service() {

    companion object {
        private const val CHANNEL_ID = "oldchat_message_service"
        private const val NOTIFICATION_ID = 1001

        fun startIfAllowed(context: Context) {
            val app = context.applicationContext as OldChatApplication
            if (app.authManager.isLoggedIn) {
                val intent = Intent(context, MessageService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        val app = application as OldChatApplication
        if (app.authManager.isLoggedIn) {
            // 由 MessageReceiver 按偏好模式统一管理 WS + HTTP 轮询
            app.messageReceiver.start()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        // 不再直接 stop，轮询由 MessageReceiver 按模式与生命周期自行管理
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OldChat Material 后台服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持 WebSocket 连接以接收实时消息"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OldChat Material")
            .setContentText("正在运行…")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }
}
