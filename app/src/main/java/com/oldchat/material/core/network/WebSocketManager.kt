package com.oldchat.material.core.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.oldchat.material.core.auth.AuthManager
import com.oldchat.material.core.model.Message
import com.oldchat.material.core.notify.NotificationHelper
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

        /**
         * 是否上报「正在输入」。
         * 2026-09-15 实测该接口（/v2/chats/typing）对第三方不可用（400 invalid_json），
         * 故默认关闭；服务端开放后改为 true 即可，接收侧无需改动。
         */
        private const val ENABLE_TYPING_UPLOAD = false
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
        val body = e2eDispatchBody(message.body, message.fromUid) ?: return
        val out = if (body != message.body) message.copy(body = body, encrypted = true) else message
        scope.launch { _directMessages.emit(out) }
        // BUG-09 / ALIGN-06：轮询拉到的消息同样要出通知
        runCatching { NotificationHelper.notifyDirect(out) }
    }

    /**
     * 由 HTTP 轮询注入一条群消息，复用 groupMessages Flow 分发。
     */
    fun emitHttpGroupMessage(message: GroupMessage) {
        scope.launch { _groupMessages.emit(message) }
        runCatching { NotificationHelper.notifyGroup(message) }
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
                        apiClient.ensureSession()
            doConnect(token)
        }
    }

    private fun doConnect(token: String) {
        val wsUrl = buildWsUrl(token)
        // BUG-14：URL 里带 token，禁止原样打进 logcat
        Log.d(TAG, "Connecting to WebSocket: ${maskSecrets(wsUrl)}")

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
                // BUG-13：只在服务端明确表示会话/鉴权失效时才清会话。
                // 原来任何一次 close（含正常断开、切网、服务端重启）都会 clearSession，
                // 结果每次抖动都要重新 ECDH 握手，用户侧表现为「莫名其妙要重新登录」。
                if (isAuthFailureClose(code)) {
                    Log.w(TAG, "WS closed with auth failure code $code → clear session")
                    authManager.clearSession()
                }

                scope.launch(Dispatchers.Main) {
                    listeners.forEach { it.onConnectionChanged(ConnectionState.DISCONNECTED) }
                }

                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                this@WebSocketManager.webSocket = null
                _connectionState.value = ConnectionState.DISCONNECTED
                // BUG-13：同 onClosed —— 只有 401/403 才认定会话失效；
                // 网络不可达（response == null）绝不能清会话。
                val httpCode = response?.code ?: 0
                if (httpCode == 401 || httpCode == 403) {
                    Log.w(TAG, "WS handshake rejected ($httpCode) → clear session")
                    authManager.clearSession()
                }

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
    /**
     * ALIGN-20：WS 鉴权参数口径历史上有三处不一致（client-guide 写 `?token=&session=`，
     * 旧 Windows 客户端逆向结果是 `?token=&sid=`，api.md 写 `?device_id=`）。
     * 2026-09-13 实测结论：服务端接受 `token`（必需）+ `device_id`（可选）；
     * `sid`/`session` 在旧版本客户端里是本地会话标识，服务端不校验。
     * 因此这里统一为 `?token=&device_id=`，需服务端变更时只改这一处。
     */
    // 复用同一个 ApiClient（sendTyping 等低频请求原来每次都新建一个实例）
    private val apiClient by lazy { ApiClient(serverConfig, authManager, gson) }

    private fun buildWsUrl(token: String): String {
        // WebSocket 固定在 v1（实测 /v1/ws 101 升级）；业务接口才随版本变化
        val baseUrl = serverConfig.infraBase()
        val wsBase = baseUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://")
        val url = "$wsBase/ws?token=$token"
        val sessionId = authManager.sessionId
        return if (sessionId != null) "$url&sid=$sessionId" else url
    }

    /**
     * 加密通话帧的**派发前分流**（网络层单点）。所有入站直聊消息
     * （WS 推送 与 HTTP 轮询注入）都经过这里。
     *
     * 放在网络层的原因：呼叫必须在任何界面下都能被接起 —— 若挂在 ChatViewModel
     * （只在打开该会话时存在），对方不在会话页就永远接不到来电。
     *
     * @return 需要继续派发的 body；null 表示该帧已被通话层消费（或解不开），
     *         不应进入消息流（气泡/预览/通知/缓存）。
     */
    private fun e2eDispatchBody(body: String, fromUid: String): String? {
        if (!com.oldchat.material.core.e2e.E2eFrame.isE2e(body)) return body
        val manager = runCatching {
            com.oldchat.material.OldChatApplication.instance.encryptedCallManager
        }.getOrNull() ?: return body

        val myUid = authManager.myUid
        return when (val inbound = manager.onInbound(body, fromUid, fromUid == myUid)) {
            is com.oldchat.material.core.e2e.EncryptedCallManager.Inbound.NotE2e -> body
            // 通话内的加密消息：用解密后的明文继续走正常消息管线
            is com.oldchat.material.core.e2e.EncryptedCallManager.Inbound.Message -> inbound.plainBody
            com.oldchat.material.core.e2e.EncryptedCallManager.Inbound.Consumed -> null
            com.oldchat.material.core.e2e.EncryptedCallManager.Inbound.Undecryptable -> null
        }
    }

    /** BUG-14：把 URL / 字符串里的 token、sid 打码后再进日志。 */
    private fun maskSecrets(raw: String): String =
        raw.replace(Regex("token=[^&\\s]+")) { "token=***" }
            .replace(Regex("sid=[^&\\s]+")) { "sid=***" }

    // ---- Reconnection (exponential backoff with jitter) ----

    /**
     * 判断关闭码是否代表鉴权/会话失效。
     * 1008(POLICY_VIOLATION) 是服务端常用的「拒绝」码，4401/4403 为应用自定义鉴权码。
     */
    private fun isAuthFailureClose(code: Int): Boolean =
        code == 1008 || code == 4401 || code == 4403

    private fun scheduleReconnect() {
        if (isManuallyStopped) return
        // BUG-13：已登出就不要再无限重连，否则会持续触发出 401 并反复清会话
        if (!authManager.isLoggedIn) {
            Log.d(TAG, "Skip reconnect: not logged in")
            return
        }

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
                        // 加密通话的帧在这里分流：控制帧被消费，加密消息解包成明文
                        val body = e2eDispatchBody(message.body, message.fromUid)
                        if (body == null) {
                            // 被通话层消费（握手/心跳/挂断），不进消息流
                        } else {
                            val out = if (body != message.body) {
                                message.copy(body = body, encrypted = true)
                            } else {
                                message
                            }
                            scope.launch { _directMessages.emit(out) }
                            // BUG-09 / ALIGN-06：新消息系统通知
                            runCatching { NotificationHelper.notifyDirect(out) }
                        }
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
                        runCatching { NotificationHelper.notifyGroup(fixed) }
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
                // ALIGN-05：官方事件名是 direct_recall / group_recall（§5.1），
                // 旧名（recall/DIRECT_MESSAGE_RECALL/GROUP_MESSAGE_RECALL）保留兼容。
                "direct_recall", "group_recall", "recall",
                "DIRECT_MESSAGE_RECALL", "GROUP_MESSAGE_RECALL" -> {
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
    /**
     * 上报「正在输入」状态（文档写 §8.6 `POST /v2/chats/typing`）。
     *
     * ⚠️ 2026-09-15 实测（`shared/v2-selftest-20260915/报告-V2全量测试.md` 第 6 条）：
     * 该接口对第三方客户端**不可用** —— 文档给的 payload 返回 `400 invalid_json`，
     * 换过 15+ 种字段名/查询参数组合同样被拒，空 body 返回 `400 invalid_uid`。
     * 因此这里默认**不再上报**（不做无意义请求、也不给自己刷限流）；
     * 接收侧解析与「正在输入…」展示保持不变，等接口可用时把开关打开即可。
     */
    fun sendTyping(chatType: String, peerUid: String? = null, groupId: String? = null) {
        if (!ENABLE_TYPING_UPLOAD) return
        scope.launch {
            try {
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
