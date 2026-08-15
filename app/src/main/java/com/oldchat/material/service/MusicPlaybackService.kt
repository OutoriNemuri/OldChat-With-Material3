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
import androidx.media3.common.Player
import com.oldchat.material.MainActivity
import com.oldchat.material.feature.discover.MusicPlayerHolder

/**
 * 音乐前台服务 + 通知栏控制。
 *
 * 通知栏提供：播放/暂停、循环模式切换、停止。
 * 复用 [MusicPlayerHolder] 的全局单例 ExoPlayer，保证播放页退出后音乐继续、通知栏可控。
 */
class MusicPlaybackService : Service() {

    companion object {
        private const val CHANNEL_ID = "oldchat_music_playback"
        private const val NOTIFICATION_ID = 2001

        // 广播 action（服务内部与 UI 协作）
        const val ACTION_PLAY_PAUSE = "com.oldchat.material.action.MUSIC_PLAY_PAUSE"
        const val ACTION_TOGGLE_REPEAT = "com.oldchat.material.action.MUSIC_TOGGLE_REPEAT"
        const val ACTION_STOP = "com.oldchat.material.action.MUSIC_STOP"

        // 通知点击跳转到正在播放页的 Intent extra
        const val EXTRA_OPEN_MUSIC = "com.oldchat.material.extra.OPEN_MUSIC"
        const val EXTRA_MUSIC_TITLE = "com.oldchat.material.extra.MUSIC_TITLE"

        /** 当前播放歌曲标题（服务内存中，供通知栏刷新，跨进程用静态字段）。 */
        @Volatile
        var currentTitle: String = ""
        @Volatile
        var currentArtist: String = ""
        @Volatile
        var repeatMode: Boolean = false

        /** 启动前台服务（播放音乐时调用）。 */
        fun start(context: Context, title: String, artist: String) {
            currentTitle = title
            currentArtist = artist
            val intent = Intent(context, MusicPlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 停止服务（停止播放时调用）。 */
        fun stop(context: Context) {
            context.stopService(Intent(context, MusicPlaybackService::class.java))
        }
    }

    private val player: Player get() = MusicPlayerHolder.obtain(this)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> {
                if (player.isPlaying) player.pause() else player.play()
            }
            ACTION_TOGGLE_REPEAT -> {
                repeatMode = !repeatMode
                MusicPlayerHolder.currentRepeatMode = repeatMode
                player.repeatMode = if (repeatMode) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            }
            ACTION_STOP -> {
                player.stop()
                player.clearMediaItems()
                MusicPlayerHolder.currentMediaUri = null
                MusicPlayerHolder.isPrepared = false
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        // 监听播放状态，实时刷新通知栏（播放/暂停图标）
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                updateNotification()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateNotification()
            }
        })

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        // 若非停止按钮触发（如系统回收），不主动释放播放器；停止按钮已 stop+clearMediaItems
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OldChat Material 音乐播放",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "音乐播放控制"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            // 点击通知跳转到"正在播放"界面（音乐广场 + 当前播放歌曲），而非主界面
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(EXTRA_OPEN_MUSIC, true)
                putExtra(EXTRA_MUSIC_TITLE, currentTitle)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val playPauseIntent = PendingIntent.getService(
            this, 1, Intent(this, MusicPlaybackService::class.java).setAction(ACTION_PLAY_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val repeatIntent = PendingIntent.getService(
            this, 2, Intent(this, MusicPlaybackService::class.java).setAction(ACTION_TOGGLE_REPEAT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 3, Intent(this, MusicPlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val isPlaying = try { player.isPlaying } catch (_: Exception) { false }
        val title = currentTitle.ifEmpty { "旧聊音乐" }
        val artist = currentArtist

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist.ifEmpty { "正在播放" })
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentIntent)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_media_previous, "停止", stopIntent
            )
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "暂停" else "播放",
                playPauseIntent
            )
            .addAction(
                if (repeatMode) android.R.drawable.ic_menu_revert else android.R.drawable.ic_menu_set_as,
                if (repeatMode) "循环开" else "循环关",
                repeatIntent
            )
            .build()
    }

    private fun updateNotification() {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
            // 服务已销毁等情况，忽略
        }
    }
}
