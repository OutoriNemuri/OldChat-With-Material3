package com.oldchat.material.core.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.oldchat.material.core.auth.AuthManager
import com.oldchat.material.core.model.Message
import com.oldchat.material.core.model.GroupMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * WebSocket manager using OkHttp WebSocket.
 * Mirrors WSManager + SimpleWebSocketClient from client-guide.md §2.4.
 *
 * Features:
 * - OkHttp WebSocket (RFC 6455 compliant)
 * - Message parsing & dispatch (DirectMessage, GroupMessage, Recall, Typing, Presence)
 * - Exponential backoff reconnection (1s → 60s, ±20% jitter)
 * - Encryption/decryption of WS messages via session keys
 * - Listener pattern (add/remove in onResume/onPause)
 * - Connection state tracking
 */
class WebSocketManager(
    private val serverConfig: ServerConfig,
    private val authManager: AuthManager,
    private val gson: Gson
) {
    companion object {
        private const val TAG = "WebSocketManager"
        private const val INITIAL_RECONNECT_DELAY_MS = 1000L
        private const val MAX_RECONNECT_DELAY_MS = 60_000L
        private const val RECONNECT_JITTER_FACTOR = 0.2 // ±20%
        private const val BOUNDED_QUEUE_CAPACITY = 256
    }

    // Connection state
    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    // Incoming message flows
    private val _directMessages = MutableSharedFlow<Message>(replay = 0, extraBufferCapacity = 256)
    val directMessages: SharedFlow<Message> = _directMessages

    private val _groupMessages = MutableSharedFlow<GroupMessage>(replay = 0, extraBufferCapacity = 256)
    val groupMessages: SharedFlow<GroupMessage> = _groupMessages

    private val _typingEvents = MutableSharedFlow<TypingEvent>(replay = 0, extraBufferCapacity = 64)
    val typingEvents: SharedFlow<TypingEvent> = _typingEvents

    private val _presenceEvents = MutableSharedFlow<PresenceEvent>(replay = 0, extraBufferCapacity = 64)
    val presenceEvents: SharedFlow<PresenceEvent> = _presenceEvents

    private val _recallEvents = MutableSharedFlow<RecallEvent>(replay = 0, extraBufferCapacity = 32)
    val recallEvents: SharedFlow<RecallEvent> = _recallEvents

    // 已读/回执事件（DIRECT_READ / GROUP_READ，pts 信封里也有）
    private val _readEvents = MutableSharedFlow<ReadEvent>(replay = 0, extraBufferCapacity = 64)
    val readEvents: SharedFlow<ReadEvent> = _readEvents

    // 杂项事件（好友/红包/动态/频道/通知等），供后续 UI 消费
    private val _miscEvents = MutableSharedFlow<MiscEvent>(replay = 0, extraBufferCapacity = 64)
    val miscEvents: SharedFlow<MiscEvent> = _miscEvents

    // 账号级 pts 游标（文档 §6.1：单调递增，断线补差靠它）
    private val _pts = MutableStateFlow(0L)
    val pts: StateFlow<Long> = _pts

    // Internal state
    private var webSocket: WebSocket? = null
    private var connectionGeneration = 0
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isManuallyStopped = false

    // Message dispatch queue (bounded, with backpressure)
    private val dispatchChannel = Channel<String>(Channel.BUFFERED)

    init {
        // Start message dispatcher
        scope.launch {
            for (rawMessage in dispatchChannel) {
                dispatchMessage(rawMessage)
            }
        }
    }

    // ---- Public API ----

    /**
     * Start WebSocket connection.
     * Idempotent: multiple calls are safe.
     */
    fun start() {
        if (_connectionState.value == ConnectionState.CONNECTED ||
            _connectionState.value == ConnectionState.CONNECTING
        ) {
            return
        }
        isManuallyStopped = false
        connect()
    }

    /**
     * Stop WebSocket connection and prevent reconnection.
     */
    fun stop() {
        isManuallyStopped = true
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, "Client closing")
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * 由 HTTP 轮询注入一条直聊消息，复用 directMessages Flow 分发。
     * 供 MessageReceiver 在仅 HTTP / WS 降级场景使用。
     */
    fun emitHttpDirectMessage(message: Message) {
        if (message.fromUid.isEmpty()) return
        scope.launch { _directMessages.emit(message) }
    }

    /**
     * 由 HTTP 轮询注入一条群消息，复用 groupMessages Flow 分发。
     */
    fun emitHttpGroupMessage(message: GroupMessage) {
        scope.launch { _groupMessages.emit(message) }
    }

    /**
     * Add a listener for WebSocket events (UI lifecycle).
     * Call in onResume; remove in onPause.
     */
    fun addListener(listener: WSListener) {
        listeners.add(listener)
    }

    /**
     * Remove a listener.
     */
    fun removeListener(listener: WSListener) {
        listeners.remove(listener)
    }

    // ---- Connection Management ----

    private fun connect() {
        val token = authManager.accessToken
        if (token == null) {
            Log.w(TAG, "Cannot connect: no access token")
            return
        }

        _connectionState.value = ConnectionState.CONNECTING
        connectionGeneration++

        // Establish ECDH session before connecting (server requires a session
        // for the WS handshake — see §2.2/§2.4; without it the server returns
        // 400 {"error":"missing session"}).
        scope.launch {
            val apiClient = ApiClient(serverConfig, authManager, gson)
            apiClient.ensureSession()
            doConnect(token)
        }
    }

    private fun doConnect(token: String) {
        val wsUrl = buildWsUrl(token)
        Log.d(TAG, "Connecting to WebSocket: $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .header("Authorization", "Bearer $token")
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                _connectionState.value = ConnectionState.CONNECTED
                reconnectAttempt = 0

                scope.launch(Dispatchers.Main) {
                    listeners.forEach { it.onConnectionChanged(ConnectionState.CONNECTED) }
                }

                // Trigger unread sync compensation
                syncUnread()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                this@WebSocketManager.webSocket = null
                _connectionState.value = ConnectionState.DISCONNECTED
                authManager.clearSession()

                scope.launch(Dispatchers.Main) {
                    listeners.forEach { it.onConnectionChanged(ConnectionState.DISCONNECTED) }
                }

                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                this@WebSocketManager.webSocket = null
                _connectionState.value = ConnectionState.DISCONNECTED
                authManager.clearSession()

                scope.launch(Dispatchers.Main) {
                    listeners.forEach { it.onConnectionChanged(ConnectionState.DISCONNECTED) }
                }

                scheduleReconnect()
            }
        }

        webSocket = HttpClientProvider.okHttpClient.newWebSocket(request, listener)
    }

    /**
     * Build WebSocket URL with token and session id as query parameters.
     * 对照 Windows 版 OldChat：?token=<token>&sid=<session_id>，另需 Authorization 头。
     */
    private fun buildWsUrl(token: String): String {
        val baseUrl = serverConfig.resolveApiBase()
        val wsBase = baseUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://")
        val url = "$wsBase/ws?token=$token"
        val sessionId = authManager.sessionId
        return if (sessionId != null) "$url&sid=$sessionId" else url
    }

    // ---- Reconnection (exponential backoff with jitter) ----

    private fun scheduleReconnect() {
        if (isManuallyStopped) return

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delay = calculateReconnectDelay()
            Log.d(TAG, "Reconnecting in ${delay}ms (attempt $reconnectAttempt)")
            delay(delay)
            reconnectAttempt++
            connect()
        }
    }

    private fun calculateReconnectDelay(): Long {
        // Exponential backoff: 1s, 2s, 4s, ..., max 60s
        val baseDelay = INITIAL_RECONNECT_DELAY_MS * (1L shl reconnectAttempt)
            .coerceAtMost(MAX_RECONNECT_DELAY_MS)

        // Add ±20% jitter
        val jitter = (baseDelay * RECONNECT_JITTER_FACTOR * (Random.nextDouble() * 2 - 1)).toLong()
        return baseDelay + jitter
    }

    // ---- Message Handling ----

    private fun handleIncomingMessage(rawMessage: String) {
        // 尝试解密（对照 Windows 版：JSON 信封 {iv, data, mac}）
        val encKey = authManager.sessionEncKey
        val macKey = authManager.sessionMacKey
        val decrypted = if (encKey != null && macKey != null) {
            try {
                com.oldchat.material.core.crypto.CryptoUtil.decryptEnvelope(
                    rawMessage, encKey, macKey
                ) ?: rawMessage  // 解密失败 => 可能本就是明文
            } catch (e: Exception) {
                rawMessage
            }
        } else {
            rawMessage
        }

        // Offer to dispatch channel (bounded queue with backpressure)
        dispatchChannel.trySend(decrypted)
    }

    /**
     * Parse and dispatch a single incoming message.
     * Runs on the dispatch coroutine.
     *
     * 兼容两种信封格式：
     * 1. 文档 §6.1 的事件信封：{pts, pts_count, type, date, payload}
     *    - type 为大写蛇形（DIRECT_MESSAGE_NEW / GROUP_MESSAGE_NEW / ...）
     *    - 真正的消息对象在 payload 字段
     * 2. 旧版/实际部署的信封：{type, data / message}（小写 type）
     */
    private fun dispatchMessage(rawJson: String) {
        try {
            val jsonObj = gson.fromJson(rawJson, JsonObject::class.java) ?: return

            // 更新 pts 游标（若信封带 pts）
            val ptsVal = jsonObj.get("pts")?.asLong
            if (ptsVal != null && ptsVal > 0) {
                _pts.value = ptsVal
            }

            var type = jsonObj.get("type")?.asString

            // 优先取 payload 字段（文档 §6.1 事件信封）
            var payloadObj: JsonObject? = null
            val payloadElem = jsonObj.get("payload")
            if (payloadElem is JsonObject) {
                payloadObj = payloadElem
                val nestedType = payloadElem.get("type")?.asString
                if (nestedType != null && (type == null || type == "event" || type == "message")) {
                    type = nestedType
                }
            }

            // 兼容旧格式 {type, data / message}
            if (payloadObj == null) {
                val dataElem = jsonObj.get("data")
                val messageElem = jsonObj.get("message")
                if (dataElem is JsonObject) {
                    payloadObj = dataElem
                    val nestedType = dataElem.get("type")?.asString
                    if (nestedType != null && (type == null || type == "message" || type == "event")) {
                        type = nestedType
                    }
                } else if (messageElem is JsonObject) {
                    payloadObj = messageElem
                    val nestedType = messageElem.get("type")?.asString
                    if (nestedType != null && (type == null || type == "message" || type == "event")) {
                        type = nestedType
                    }
                }
            }

            // 若无 payload 字段且无 data/message，用整个信封当 payload
            val payload = payloadObj ?: jsonObj
            if (type == null) return

            when (type) {
                // ---- 私聊消息 ----
                "direct_message", "new_message", "message", "DIRECT_MESSAGE_NEW" -> {
                    val message = gson.fromJson(payload, Message::class.java)
                    if (message != null && message.fromUid.isNotEmpty()) {
                        scope.launch { _directMessages.emit(message) }
                    }
                }
                // ---- 群消息 ----
                "group_message", "group_new_message", "GROUP_MESSAGE_NEW" -> {
                    val message = gson.fromJson(payload, GroupMessage::class.java)
                    if (message != null) {
                        val fixed = if (message.groupSeq == 0L) {
                            val sortSeq = payload.get("sort_seq")?.asLong ?: 0L
                            if (sortSeq != 0L) message.copy(groupSeq = sortSeq) else message
                        } else message
                        scope.launch { _groupMessages.emit(fixed) }
                    }
                }
                // ---- 正在输入 ----
                "typing", "TYPING" -> {
                    val uid = payload.get("from_uid")?.asString ?: return
                    val chatId = payload.get("thread_id")?.asString
                        ?: payload.get("group_id")?.asString ?: return
                    val isTyping = payload.get("is_typing")?.asBoolean ?: false
                    scope.launch { _typingEvents.emit(TypingEvent(chatId, uid, isTyping)) }
                }
                // ---- 在线状态 ----
                "presence", "PRESENCE" -> {
                    val uid = payload.get("uid")?.asString ?: return
                    val status = payload.get("status")?.asString ?: "offline"
                    scope.launch { _presenceEvents.emit(PresenceEvent(uid, status)) }
                }
                // ---- 私聊已读（对方已读） ----
                "direct_read", "DIRECT_READ" -> {
                    val threadId = payload.get("thread_id")?.asString
                    val fromUid = payload.get("from_uid")?.asString
                    val readAt = payload.get("read_at")?.asLong ?: 0L
                    scope.launch {
                        _readEvents.emit(ReadEvent(chatId = threadId ?: "", fromUid = fromUid ?: "", readAt = readAt, isGroup = false))
                    }
                }
                // ---- 群已读 ----
                "group_read", "GROUP_READ" -> {
                    val groupId = payload.get("group_id")?.asString ?: ""
                    val fromUid = payload.get("from_uid")?.asString ?: ""
                    val readCount = payload.get("read_count")?.asLong ?: 0L
                    scope.launch {
                        _readEvents.emit(ReadEvent(chatId = groupId, fromUid = fromUid, readAt = readCount, isGroup = true))
                    }
                }
                // ---- 撤回 ----
                "recall", "DIRECT_MESSAGE_RECALL", "GROUP_MESSAGE_RECALL" -> {
                    val messageId = payload.get("message_id")?.asString ?: return
                    val chatId = payload.get("thread_id")?.asString
                        ?: payload.get("group_id")?.asString ?: return
                    val recallType = payload.get("recall_type")?.asString
                    scope.launch { _recallEvents.emit(RecallEvent(chatId, messageId, recallType)) }
                }
                // ---- 应用层心跳响应（真实保活走 RFC 6455 控制帧，这里仅兼容） ----
                "pong" -> { /* 忽略 */ }
                // ---- 好友 / 红包 / 动态 / 频道 / 通知（杂项） ----
                else -> {
                    // FRIEND_* / RED_PACKET_* / MOMENT_* / CHANNEL_* / NOTIFICATION_* 等
                    // 统一走 miscEvents，附带原始类型，供 UI 层按需消费
                    if (type.startsWith("FRIEND_") || type.startsWith("RED_PACKET_") ||
                        type.startsWith("MOMENT_") || type.startsWith("CHANNEL_") ||
                        type.startsWith("NOTIFICATION_") || type == "friend" ||
                        type == "red_packet" || type == "moment" || type == "channel" ||
                        type == "notification"
                    ) {
                        val raw = payload.toString()
                        scope.launch { _miscEvents.emit(MiscEvent(type, raw)) }
                    } else {
                        Log.d(TAG, "Unknown message type: $type")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error dispatching message", e)
        }
    }

    // ---- 客户端主动发送（§5.1 / §8.6） ----

    /**
     * 上报「正在输入」状态（§8.6 POST /v2/chats/typing）。
     * @param chatType "direct" 或 "group"
     */
    fun sendTyping(chatType: String, peerUid: String? = null, groupId: String? = null) {
        scope.launch {
            try {
                val apiClient = ApiClient(serverConfig, authManager, gson)
                val body = if (chatType == "group") {
                    mapOf("chat_type" to "group", "group_id" to (groupId ?: ""))
                } else {
                    mapOf("chat_type" to "direct", "peer_uid" to (peerUid ?: ""))
                }
                apiClient.post("/chats/typing", gson.toJson(body))
            } catch (e: Exception) {
                Log.w(TAG, "sendTyping failed", e)
            }
        }
    }

    /**
     * 刷新频道订阅（§5.1：客户端发送 {"type":"channel_subscriptions_refresh"}）。
     */
    fun refreshChannelSubscriptions() {
        val ws = webSocket ?: return
        try {
            ws.send("{\"type\":\"channel_subscriptions_refresh\"}")
        } catch (e: Exception) {
            Log.w(TAG, "refreshChannelSubscriptions failed", e)
        }
    }

    // ---- Unread Sync Compensation (§7.2) ----

    private fun syncUnread() {
        scope.launch {
            try {
                val apiClient = ApiClient(serverConfig, authManager, gson)
                // Sync direct unread (POST，见 routes.md)
                apiClient.post("/direct/unread", gson.toJson(emptyMap<String, String>()))
                // Sync group unread (POST)
                apiClient.post("/groups/unread", gson.toJson(emptyMap<String, String>()))
                // Results are handled by respective caches/ViewModels
            } catch (e: Exception) {
                Log.w(TAG, "Unread sync failed", e)
            }
        }
    }

    // ---- Listener Management ----

    private val listeners = ConcurrentLinkedQueue<WSListener>()

    /**
     * Listener interface for WebSocket events.
     * All callbacks are delivered on the Main thread.
     */
    interface WSListener {
        fun onConnectionChanged(state: ConnectionState) {}
        fun onUnreadSyncComplete() {}
        fun onMessageSent(messageId: String) {}
    }

    fun destroy() {
        stop()
        scope.cancel()
        listeners.clear()
    }
}

// ---- Event Data Classes ----

data class TypingEvent(
    val chatId: String,
    val uid: String,
    val isTyping: Boolean
)

data class PresenceEvent(
    val uid: String,
    val status: String  // online/offline/away
)

data class RecallEvent(
    val chatId: String,
    val messageId: String,
    val recallType: String?
)

/** 已读/回执事件（DIRECT_READ / GROUP_READ）。 */
data class ReadEvent(
    val chatId: String,      // 私聊为 thread_id，群聊为 group_id
    val fromUid: String,     // 触发已读的用户（对方）
    val readAt: Long,        // 私聊为 read_at 时间戳；群聊为 read_count
    val isGroup: Boolean
)

/** 杂项事件（好友/红包/动态/频道/通知等），rawJson 为原始 payload JSON。 */
data class MiscEvent(
    val type: String,        // FRIEND_* / RED_PACKET_* / MOMENT_* / CHANNEL_* / NOTIFICATION_*
    val rawJson: String
)
