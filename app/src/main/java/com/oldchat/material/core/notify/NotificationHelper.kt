package com.oldchat.material.core.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.oldchat.material.MainActivity
import com.oldchat.material.OldChatApplication
import com.oldchat.material.R
import com.oldchat.material.core.model.GroupMessage
import com.oldchat.material.core.model.Message
import com.oldchat.material.core.model.MessagePayloadBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 进程级「当前是否前台 / 当前打开哪个会话」状态。
 *
 * BUG-09 配套：通知必须能判断「用户是不是正看着这个会话」，否则自己开着聊天页
 * 还会被自己的消息弹一脸。MainActivity 维护 isForeground，
 * ChatScreen / GroupChatScreen 维护 activeChatId。
 */
object AppForeground {
    @Volatile var isForeground: Boolean = false

    /** 当前打开的会话 id：私聊为对方 uid，群聊为 group_id；不在聊天页时为 null。 */
    @Volatile var activeChatId: String? = null
}

/**
 * 新消息系统通知（client-guide §18.9 / ALIGN-06、BUG-09）。
 *
 * 原实现完全没有新消息通知；本类补齐：
 * - 一个 HIGH 重要度渠道（声音/震动可在设置里关，关掉时按 silent 发送）
 * - 私聊 / 群聊分渠道展示，同一会话复用同一条通知（replace，不刷屏）
 * - 前台且正在看该会话时不打扰；自己发的消息不通知
 *
 * 三个开关（notificationsEnabled / notificationSound / notificationVibration）
 * 来自 PreferencesManager，启动时订阅一次，后续读 volatile 字段，避免在主线程读 DataStore。
 */
object NotificationHelper {

    private const val TAG = "NotificationHelper"
    private const val CHANNEL_MESSAGES = "oldchat_messages"
    private const val CHANNEL_SERVICE = "oldchat_message_service"

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile private var enabled = true
    @Volatile private var soundEnabled = true
    @Volatile private var vibrationEnabled = true
    @Volatile private var initialized = false

    /** 由 OldChatApplication.onCreate 调用（cacheManager 就绪之后）。 */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        createChannels(context)
        val prefs = OldChatApplication.instance.cacheManager.preferences
        scope.launch {
            prefs.notificationsEnabled.distinctUntilChanged().collect { enabled = it }
        }
        scope.launch {
            prefs.notificationSound.distinctUntilChanged().collect { soundEnabled = it }
        }
        scope.launch {
            prefs.notificationVibration.distinctUntilChanged().collect { vibrationEnabled = it }
        }
    }

    private fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // 消息渠道：HIGH（要有提示音/悬浮），具体是否响由每条通知的 silent 控制
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGES,
                "新消息",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "私聊与群聊新消息提醒"
                enableVibration(true)
            }
        )
        // 前台服务渠道（原来只在 MessageService 里创建，这里统一）
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                "后台服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持 WebSocket 连接以接收实时消息"
                setShowBadge(false)
            }
        )
    }

    /** 私聊新消息。 */
    fun notifyDirect(message: Message) {
        if (!enabled) return
        val myUid = OldChatApplication.instance.authManager.myUid
        if (myUid != null && message.fromUid == myUid) return   // 自己发的不提醒
        val chatKey = if (message.fromUid == myUid) message.threadId else message.fromUid
        if (isViewingChat(chatKey)) return

        val name = resolveDisplayName(message.fromUid)
        val text = MessagePayloadBuilder.extractPreviewText(message.msgType, message.body)
        post(
            chatKey = chatKey,
            title = name,
            text = text,
            groupId = null,
            groupName = null
        )
    }

    /** 群聊新消息。 */
    fun notifyGroup(message: GroupMessage) {
        if (!enabled) return
        val myUid = OldChatApplication.instance.authManager.myUid
        if (myUid != null && message.fromUid == myUid) return
        if (isViewingChat(message.groupId)) return

        val groupName = OldChatApplication.instance.cacheManager.groups
            .get(message.groupId)?.name ?: "群聊"
        val sender = resolveDisplayName(message.fromUid)
        val text = MessagePayloadBuilder.extractPreviewText(message.msgType, message.body)
        post(
            chatKey = message.groupId,
            title = groupName,
            text = "$sender: $text",
            groupId = message.groupId,
            groupName = groupName
        )
    }

    private fun isViewingChat(chatKey: String): Boolean =
        AppForeground.isForeground && AppForeground.activeChatId == chatKey

    private fun resolveDisplayName(uid: String): String =
        OldChatApplication.instance.cacheManager.friends.get(uid)?.nickname
            ?.takeIf { it.isNotBlank() } ?: uid

    private fun post(
        chatKey: String,
        title: String,
        text: String,
        groupId: String?,
        groupName: String?
    ) {
        val app = OldChatApplication.instance
        try {
            if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return

            val intent = Intent(app, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_CHAT_ID, chatKey)
                if (groupId != null) putExtra(EXTRA_GROUP_ID, groupId)
            }
            val pendingIntent = PendingIntent.getActivity(
                app,
                chatKey.hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val builder = NotificationCompat.Builder(app, CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_stat_oldchat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOnlyAlertOnce(false)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)

            if (!soundEnabled && !vibrationEnabled) {
                builder.setSilent(true)
            } else {
                // 只关声音或只关震动时，用自定义 defaults 表达
                var defaults = 0
                if (soundEnabled) defaults = defaults or NotificationCompat.DEFAULT_SOUND
                if (vibrationEnabled) defaults = defaults or NotificationCompat.DEFAULT_VIBRATE
                builder.setDefaults(defaults)
                    .setVibrate(if (vibrationEnabled) longArrayOf(0, 200, 100, 200) else longArrayOf(0))
            }

            if (groupId != null && groupName != null) {
                builder.setSubText(groupName)
            }

            NotificationManagerCompat.from(app)
                .notify(chatKey.hashCode() and 0x7fffffff, builder.build())
        } catch (e: SecurityException) {
            // 未授予 POST_NOTIFICATIONS：静默忽略（MainActivity 会引导申请）
            Log.w(TAG, "notify skipped (no permission)", e)
        } catch (e: Exception) {
            Log.e(TAG, "notify failed", e)
        }
    }

    const val EXTRA_CHAT_ID = "extra_chat_id"
    const val EXTRA_GROUP_ID = "extra_group_id"
}
