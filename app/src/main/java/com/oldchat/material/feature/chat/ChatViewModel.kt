@file:OptIn(kotlinx.coroutines.FlowPreview::class)
package com.oldchat.material.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.oldchat.material.OldChatApplication
import com.oldchat.material.core.e2e.E2eFrame
import com.oldchat.material.core.e2e.EncryptedCallManager
import com.oldchat.material.core.model.Message
import com.oldchat.material.core.network.TypingEvent
import com.oldchat.material.core.model.MessagePayloadBuilder
import com.oldchat.material.core.media.MediaUploader
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicLong

/**
 * ViewModel for direct chat (chat detail screen).
 * Mirrors DirectChatListHelper + DirectMessageSender + DirectChatLoadDelegate from original client §3.
 *
 * Features:
 * - Message list with merge (not replace) on refresh
 * - Pending message tracking (local_* IDs)
 * - Text send with quote support
 * - History pagination (v2 cursor + legacy offset fallback)
 * - Read receipt push
 * - Scroll-to-bottom tracking
 */
class ChatViewModel : ViewModel() {

    private val app = OldChatApplication.instance
    private val apiClient = app.apiClient
    private val gson: Gson = app.gson
    private val cache = app.cacheManager

    // ---- Chat Identity ----
    private var friendUid: String = ""
    private var friendName: String = ""
    private var threadId: String = ""
    private var wsSubscription: Job? = null
    // 事件驱动的回执刷新（ALIGN-02：不再是 5s 轮询）
    private var receiptRefreshJob: Job? = null

    // ALIGN-11：已读上报防抖
    private var markReadJob: Job? = null
    private var lastReadRequestAt: Long = 0L

    // ALIGN-13：历史分页状态
    private var isLoadingMore = false
    private var historyHasMore = true
    private var loadedPages = 1
    private val receiptRefreshTrigger = MutableSharedFlow<Unit>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // 自己的头像（用于右侧头像，Bug: 单/群聊不显示自己头像）
    private val _myAvatarUrl = MutableStateFlow<String?>(null)
    val myAvatarUrl: StateFlow<String?> = _myAvatarUrl.asStateFlow()

    // ---- Message List ----
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    // Message IDs set for deduplication
    private val messageIds = mutableSetOf<String>()

    // Pending messages (not persisted)
    private val pendingRequests = mutableSetOf<String>()

    // ---- Pagination State ----
    private var hasMoreBefore = true
    private var isLoadingMore = false
    private var useCursorPagination = true
    private var oldestCreatedAt: Long = 0
    private var oldestId: String = ""
    private var offset = 0

    // Scroll to bottom
    private val _scrollToBottom = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val scrollToBottom: SharedFlow<Unit> = _scrollToBottom

    // Local ID counter
    private val localIdCounter = AtomicLong(System.currentTimeMillis())

    fun init(uid: String, name: String) {
        // BUG-06：原实现是 `if (friendUid == uid) return`。
        // 而 ChatScreen 在离开时就会调 destroy()（取消 WS 订阅与回执刷新），
        // 导致「退出会话 → 再进来同一个会话」时 init 直接早退：
        //   ① 不再订阅 WS，实时消息全丢；② 不再刷新回执；③ 不再重新拉历史。
        // 现在只有「同一个会话且订阅仍然活着」才早退，否则走完整初始化。
        if (friendUid == uid && wsSubscription?.isActive == true) return
        friendUid = uid
        friendName = name
        threadId = uid

        // Reset all per-chat state when switching chats — otherwise messages
        // from the previous chat would leak into the new one.
        _messages.value = emptyList()
        messageIds.clear()
        pendingRequests.clear()
        hasMoreBefore = true
        isLoadingMore = false
        useCursorPagination = true
        oldestCreatedAt = 0
        oldestId = ""
        offset = 0
        localIdCounter.set(System.currentTimeMillis())

        // Load from cache first
        val cached = cache.loadDirectMessages(uid)
        if (cached.isNotEmpty()) {
            // BUG-07：thread_id 是不透明会话 id，既不是 uid 也不是对方 uid。
            // 原过滤器 `threadId == uid || fromUid == uid` 会把「自己发的消息」全部滤掉
            // （自己发的 from_uid = 我，thread_id = 会话 id），表现为重进会话后只看到对方说的话。
            val myUid = app.authManager.myUid ?: ""
            _messages.value = cached.filter { m ->
                m.threadId == uid || m.fromUid == uid || m.peerUid == uid ||
                    (myUid.isNotEmpty() && m.fromUid == myUid)
            }
            messageIds.addAll(_messages.value.map { it.id })
        }

        // Load from network
        loadHistory()

        // 订阅实时直聊消息：WS 或 HTTP 轮询注入的消息经 fromUid/threadId 过滤后合入本会话。
        // 过滤保证了不会把其他会话的消息「串」进来。
        if (wsSubscription == null) {
            val ws = app.wsManager
            wsSubscription = viewModelScope.launch {
                ws.directMessages.collect { msg -> onWsMessage(msg) }
            }
        }

        loadMyAvatar()

        // 加密通话状态变化 → 处理「握手中暂存」的消息
        viewModelScope.launch {
            callManager.state.collect { st ->
                when {
                    st is EncryptedCallManager.CallState.Connected && st.peer == friendUid ->
                        flushCallQueue()          // 密钥就绪：按加密发送
                    st is EncryptedCallManager.CallState.Ended && queuedWhileCalling.isNotEmpty() ->
                        flushCallQueue()          // 通话没成：不丢消息，按明文补发
                }
            }
        }

        // ALIGN-15：订阅 typing 事件
        viewModelScope.launch {
            app.wsManager.typingEvents.collect { handleTyping(it) }
        }

        // ALIGN-02：改为事件驱动的回执刷新（见 startReceiptRefresh）。
        startReceiptRefresh()
    }

    /**
     * ALIGN-02：已读/送达回执刷新。
     *
     * 原实现是 `while (isActive) { refresh(); delay(5s) }` —— 会话页打开期间
     * 每 5 秒无条件打一次 `/direct/messages/v2?limit=50`，且不受「仅 HTTP 优先」模式门控。
     * 直接违反 client-guide §0「不得朴素全量刷新」的反滥用红线（会被监测/限流）。
     *
     * 现在只在真正可能产生新回执的事件上触发，并做 1.5s 合并：
     *   ① WS 重连成功（断线期间可能漏了回执）
     *   ② 本会话收到实时消息（对方此时很可能已读）
     *   ③ 屏幕回到前台（onScreenResumed）
     */
    private fun startReceiptRefresh() {
        if (receiptRefreshJob?.isActive == true) return
        receiptRefreshJob = viewModelScope.launch {
            launch {
                app.wsManager.connectionState
                    .map { it == com.oldchat.material.core.network.ConnectionState.CONNECTED }
                    .distinctUntilChanged()
                    .filter { it }
                    .collect { triggerReceiptRefresh() }
            }
            launch {
                receiptRefreshTrigger
                    .debounce(RECEIPT_REFRESH_DEBOUNCE_MS)
                    .collect {
                        try {
                            refreshReceiptsOnce()
                        } catch (_: Exception) {
                            // 静默失败，等下一个事件
                        }
                    }
            }
        }
    }

    /** 请求一次回执刷新（会与其它请求合并，不会产生请求风暴）。 */
    fun triggerReceiptRefresh() {
        receiptRefreshTrigger.tryEmit(Unit)
    }

    // ---- ALIGN-15：输入中（typing） ----
    private val _isPeerTyping = MutableStateFlow(false)
    val isPeerTyping: StateFlow<Boolean> = _isPeerTyping.asStateFlow()
    private var lastTypingSentAt = 0L
    private var typingResetJob: Job? = null

    /**
     * ALIGN-15：原来 sendTyping() 定义了却「从来没有被调用过」，
     * 所以对方永远看不到「正在输入」。这里在输入变化时按 2.5s 节流上报。
     */
    fun onInputChanged(text: String) {
        val now = System.currentTimeMillis()
        if (text.isNotBlank() && now - lastTypingSentAt > TYPING_THROTTLE_MS) {
            lastTypingSentAt = now
            app.wsManager.sendTyping("direct", peerUid = friendUid)
        }
    }

    private fun handleTyping(event: TypingEvent) {
        if (event.chatId != friendUid) return
        val myUid = app.authManager.myUid ?: ""
        if (event.uid == myUid) return
        if (event.isTyping) {
            _isPeerTyping.value = true
            // 对方没继续输入就自动收起（服务端不保证发 false）
            typingResetJob?.cancel()
            typingResetJob = viewModelScope.launch {
                delay(TYPING_TIMEOUT_MS)
                _isPeerTyping.value = false
            }
        } else {
            typingResetJob?.cancel()
            _isPeerTyping.value = false
        }
    }

    /**
     * ALIGN-17：阅后即焚「打开」回执。
     * §9 规定收到阅后即焚消息后，用户查看时需上报 /direct/burn/open，
     * 服务端据此在双方都读过之后删除消息。原实现只解析字段、既不展示也不上报。
     */
    fun openBurnMessage(message: Message) {
        if (message.id.isEmpty() || message.isLocalPending) return
        viewModelScope.launch {
            try {
                apiClient.post(
                    "/direct/burn/open",
                    gson.toJson(mapOf("message_id" to message.id))
                )
            } catch (_: Exception) {
                // 上报失败不影响本地「看一次」的语义
            }
        }
    }

    /** 会话页回到前台时调用（对齐 §15：onPause 停监听 / onResume 补一次）。 */
    fun onScreenResumed() {
        // ALIGN-14 / ALIGN-13：回前台时补一次增量（不整页重拉），并允许继续分页
        historyHasMore = true
        triggerReceiptRefresh()
    }

    /**
     * ALIGN-14：会话页进入后台时调用。
     * §15 要求 onPause 停止「仅前台需要的」活跃行为，避免后台继续刷接口/占用资源。
     * 注意：WS 订阅本身由系统服务维持，这里只停掉 UI 相关的轮询类工作，
     * 不影响实时消息到达。
     */
    fun onScreenPaused() {
        receiptRefreshJob?.cancel()
        receiptRefreshJob = null
    }

    /**
     * 拉取一次最新消息，仅合入回执字段（delivered_at/read_at），不做整页替换。
     * 复用 mergeMessages 的「已存在消息更新回执」逻辑。
     */
    private suspend fun refreshReceiptsOnce() {
        if (friendUid.isEmpty()) return
        val params = mapOf("with_uid" to friendUid, "limit" to "50")
        val result = apiClient.get("/direct/messages/v2", params)
        result.onSuccess { body ->
            val incoming = parseMessages(body)
            if (incoming.isNotEmpty()) {
                mergeMessages(incoming, appendToFront = false)
            }
        }
    }

    /**
     * 加载自己的头像（供右侧「自己」头像显示）。优先读缓存，否则拉 /me。
     */
    private fun loadMyAvatar() {
        if (_myAvatarUrl.value != null) return
        viewModelScope.launch {
            // 1) 读缓存 profile (此前 /me 已保存)
            val cachedJson = try { cache.preferences.profileCacheJson.first() } catch (_: Exception) { "" }
            val cachedAvatar = resolveMyAvatar(cachedJson)
            if (cachedAvatar != null) { _myAvatarUrl.value = cachedAvatar; return@launch }
            // 2) 拉 /me
            try {
                val result = apiClient.get("/me")
                result.onSuccess { json ->
                    val avatar = resolveMyAvatar(json)
                    if (avatar != null) _myAvatarUrl.value = avatar
                }
            } catch (_: Exception) {}
        }
    }

    private fun resolveMyAvatar(profileJson: String): String? {
        if (profileJson.isBlank()) return null
        return try {
            val root = gson.fromJson(profileJson, Map::class.java) as? Map<*, *>
            val data = root?.get("data") as? Map<*, *> ?: root
            val raw = data?.get("avatar_url") ?: data?.get("avatar")
            (raw as? String)?.takeIf { it.isNotBlank() }?.let { path ->
                app.serverConfig.resolveMediaUrl(path)
            }
        } catch (_: Exception) { null }
    }

    // ---- History Loading (§3.3) ----

    fun loadHistory() {
        if (isLoadingMore || !hasMoreBefore) return
        isLoadingMore = true

        viewModelScope.launch {
            try {
                val params = buildHistoryParams()
                val result = apiClient.get("/direct/messages/v2", params)

                result.fold(
                    onSuccess = { body ->
                        val wasEmpty = _messages.value.isEmpty()
                        val incoming = parseMessages(body)
                        if (incoming.isNotEmpty()) {
                            mergeMessages(incoming)
                            // 从 wrapper 读 has_more 和 next_before 游标（服务端返回）
                            val cursor = parsePaginationCursor(body)
                            hasMoreBefore = cursor.hasMore && incoming.size >= 50
                            if (cursor.nextBeforeCreatedAt != null && cursor.nextBeforeId != null) {
                                oldestCreatedAt = cursor.nextBeforeCreatedAt
                                oldestId = cursor.nextBeforeId
                            }
                            // 首次加载（此前为空）时，加载完成后滚动到底（显示最新消息）
                            if (wasEmpty) {
                                viewModelScope.launch { _scrollToBottom.emit(Unit) }
                            }
                        } else {
                            hasMoreBefore = false
                        }
                        // Save to cache
                        cache.saveDirectMessages(friendUid, _messages.value)
                    },
                    onFailure = { error ->
                        if (useCursorPagination) {
                            // Try legacy route
                            useCursorPagination = false
                            loadHistoryLegacy()
                        }
                    }
                )
            } finally {
                isLoadingMore = false
            }
        }
    }

    /**
     * 解析 v2 分页游标。
     */
    private data class PaginationCursor(
        val hasMore: Boolean = false,
        val nextBeforeCreatedAt: Long? = null,
        val nextBeforeId: String? = null
    )

    private fun parsePaginationCursor(body: String): PaginationCursor {
        return try {
            val root = gson.fromJson(body, Map::class.java) as? Map<*, *> ?: return PaginationCursor()
            PaginationCursor(
                hasMore = (root["has_more"] as? Boolean) ?: false,
                nextBeforeCreatedAt = (root["next_before_created_at"] as? Number)?.toLong(),
                nextBeforeId = root["next_before_id"]?.toString()
            )
        } catch (_: Exception) {
            PaginationCursor()
        }
    }

    private suspend fun loadHistoryLegacy() {
        val result = apiClient.get(
            "/direct/messages",
            mapOf(
                "with_uid" to friendUid,
                "offset" to offset.toString(),
                "limit" to "50"
            )
        )
        result.onSuccess { body ->
            val incoming = parseMessages(body)
            if (incoming.isNotEmpty()) {
                mergeMessages(incoming, appendToFront = false)
                offset += incoming.size
                hasMoreBefore = incoming.size >= 50
            } else {
                hasMoreBefore = false
            }
            cache.saveDirectMessages(friendUid, _messages.value)
        }
    }

    private fun buildHistoryParams(): Map<String, String> {
        val params = mutableMapOf(
            "with_uid" to friendUid,
            "limit" to "50"
        )
        if (useCursorPagination && oldestId.isNotEmpty()) {
            params["before_id"] = oldestId
            params["before_created_at"] = oldestCreatedAt.toString()
        } else {
            params["offset"] = offset.toString()
        }
        return params
    }

    private fun parseMessages(body: String): List<Message> {
        return try {
            // 服务端返回 wrapper：{"messages":[...], "has_more":..., "next_before_created_at":..., "next_before_id":...}
            val root = gson.fromJson(body, Map::class.java) as? Map<*, *> ?: return emptyList()
            val rawList = root["messages"] as? List<*> ?: return emptyList()
            rawList.mapNotNull { item ->
                val type = object : com.google.gson.reflect.TypeToken<Message>() {}.type
                val json = gson.toJson(item)
                gson.fromJson(json, Message::class.java)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Merge incoming messages into existing list (merge not replace).
     * Mirrors DirectMessageMerger.mergeRefresh from original §3.4.
     */
    private fun mergeMessages(incomingRaw: List<Message>, appendToFront: Boolean = true) {
        val current = _messages.value.toMutableList()
        var changed = false

        // 消息体归一化：加密通话的帧在这里一次性处理 ——
        //   · 控制帧（PQC_BEGIN/PQC_REPLY/ENC 控制帧）→ 丢弃，不该出现在消息列表里
        //   · ENC 消息帧 → 解包成明文后照常合并展示（历史回源/轮询/WS 都会经过这里）
        val incoming = incomingRaw.mapNotNull { msg ->
            val plain = callManager.unwrapForDisplay(msg.body, friendUid) ?: return@mapNotNull null
            if (plain == msg.body) msg else msg.copy(body = plain, encrypted = true)
        }
        if (incoming.isEmpty()) return

        for (msg in incoming) {
            // 已存在消息（非本地临时消息）：更新 read/delivered 状态（已读回执实时刷新），
            // 而不是直接跳过。这样对方读取后，我们发出的消息能实时显示「已读」。
            if (messageIds.contains(msg.id)) {
                if (!msg.isLocalPending && msg.id.isNotEmpty() && !msg.id.startsWith("local_")) {
                    val idx = current.indexOfFirst { it.id == msg.id }
                    if (idx >= 0) {
                        val old = current[idx]
                        // 仅在服务端回执字段有进展时更新，避免覆盖本地 pending 进度
                        if (msg.deliveredAt > 0 || msg.readAt > 0 || msg.status > old.status) {
                            current[idx] = old.copy(
                                deliveredAt = if (msg.deliveredAt > 0) msg.deliveredAt else old.deliveredAt,
                                readAt = if (msg.readAt > 0) msg.readAt else old.readAt,
                                status = if (msg.status > old.status) msg.status else old.status
                            )
                            changed = true
                        }
                    }
                }
                continue
            }

            // Check if this matches a pending message
            if (msg.localRequestId != null && pendingRequests.contains(msg.localRequestId)) {
                // Replace pending
                val pendingIndex = current.indexOfFirst { it.localRequestId == msg.localRequestId }
                if (pendingIndex >= 0) {
                    current[pendingIndex] = msg.copy(
                        isLocalPending = false,
                        localRequestId = null,
                        isLocalFailed = false
                    )
                    pendingRequests.remove(msg.localRequestId)
                    changed = true
                    continue
                }
            }

            messageIds.add(msg.id)
            if (appendToFront) {
                current.add(0, msg)
            } else {
                current.add(msg)
            }
            changed = true
        }

        if (changed) {
            // Sort: createdAt asc → sortSeq asc → id asc
            current.sortWith(compareBy<Message> { it.createdAt }
                .thenBy { it.sortSeq }
                .thenBy { it.id })

            // Trim to max window
            if (current.size > MAX_ACTIVE_WINDOW) {
                val trimmed = current.takeLast(MAX_ACTIVE_WINDOW)
                trimmed.forEach { messageIds.remove(it.id) }
                trimmed.forEach { messageIds.add(it.id) }
                _messages.value = trimmed
            } else {
                _messages.value = current
            }

            // Update oldest cursor
            if (current.isNotEmpty()) {
                oldestCreatedAt = current.first().createdAt
                oldestId = current.first().id
            }
        }
    }

    // ---- Sending Messages (§3.2) ----

    /**
     * Send a text message.
     */
    fun sendText(text: String, quoteDraft: Message? = null) {
        if (text.isBlank()) return

        // 加密通话中：消息体先加密成 ENC 帧再走同一个 /direct/send
        // （enigmaj 的发消息入口就是「查密钥 → 加密 → 发送」，通道不变）
        when {
            callManager.isConnectedWith(friendUid) -> Unit // 有密钥，下面直接加密发
            callManager.isEstablishingWith(friendUid) -> {
                // 还握手中（没有密钥）：先入队，接通后自动加密补发；若通话失败则明文补发，
                // 保证用户输入不丢。
                queuedWhileCalling += text to quoteDraft
                _callQueueHint.value = "正在建立加密通道，${queuedWhileCalling.size} 条消息将在接通后加密发送"
                return
            }
            else -> Unit // 未通话：明文（与 enigmaj 的「没有必须加密的策略」一致）
        }

        sendTextInternal(text, quoteDraft)
    }

    /** 通话期暂存待发文本（密钥就绪或通话结束后统一处理） */
    private val queuedWhileCalling = mutableListOf<Pair<String, Message?>>()
    private val _callQueueHint = MutableStateFlow<String?>(null)
    val callQueueHint: StateFlow<String?> = _callQueueHint.asStateFlow()

    private fun flushCallQueue() {
        if (queuedWhileCalling.isEmpty()) return
        val batch = queuedWhileCalling.toList()
        queuedWhileCalling.clear()
        _callQueueHint.value = null
        batch.forEach { (t, q) -> sendTextInternal(t, q) }
    }

    private fun sendTextInternal(text: String, quoteDraft: Message? = null) {
        val body = MessagePayloadBuilder.buildBody(
            text = text,
            quote = quoteDraft?.let { MessagePayloadBuilder.buildQuote(it) }
        )

        // 已接通 → 包成 ENC 帧；没有密钥则原样明文
        val frame = callManager.wrapMessage(friendUid, body)
        val wireBody = frame ?: body

        val localId = "local_${localIdCounter.incrementAndGet()}"

        // Create pending message
        val pending = Message(
            id = localId,
            threadId = threadId,
            fromUid = app.authManager.myUid ?: "",
            body = body,
            msgType = "text",
            createdAt = System.currentTimeMillis() / 1000,
            status = Message.STATUS_NONE,
            isLocalPending = true,
            localRequestId = localId,
            encrypted = frame != null
        )

        // Add to list immediately
        addPending(pending)
        pendingRequests.add(localId)

        // Send to server
        viewModelScope.launch {
            val result = apiClient.post(
                path = "/direct/send",
                body = gson.toJson(
                    mapOf(
                        "to_uid" to friendUid,
                        "body" to wireBody,
                        "msg_type" to "text"
                    )
                )
            )
            result.fold(
                onSuccess = { responseBody ->
                    // 服务端确认里带的是 ENC 帧（wireBody），本地展示要用明文 body
                    val sent = parseSingleMessage(responseBody)?.copy(
                        body = body,
                        encrypted = frame != null
                    )
                    if (sent != null) {
                        completePending(localId, sent)
                    } else {
                        failPending(localId)
                    }
                },
                onFailure = {
                    failPending(localId)
                }
            )
        }
    }

    /**
     * 发送红包（私聊）。服务端 /redpackets/send 直接创建并发送红包消息。
     */
    fun sendRedPacket(totalAmount: Int, totalCount: Int) {
        if (totalAmount <= 0 || totalCount <= 0) return
        viewModelScope.launch {
            val body = gson.toJson(
                mapOf(
                    "to_uid" to friendUid,
                    "total_amount" to totalAmount,
                    "total_count" to totalCount
                )
            )
            val result = apiClient.post("/redpackets/send", body)
            result.fold(
                onSuccess = { resp ->
                    val sent = parseSingleMessage(resp)
                    if (sent != null) mergeMessages(listOf(sent), appendToFront = false)
                    cache.saveDirectMessages(friendUid, _messages.value)
                    viewModelScope.launch { _scrollToBottom.emit(Unit) }
                },
                onFailure = { /* TODO: show error */ }
            )
        }
    }

    /**
     * 领取红包。
     */
    fun claimRedPacket(packetId: String) {
        if (packetId.isBlank()) return
        viewModelScope.launch {
            val body = gson.toJson(mapOf("packet_id" to packetId))
            val result = apiClient.post("/redpackets/claim", body)
            result.fold(
                onSuccess = { /* 领取成功后刷新历史，服务端会推送/轮询更新 */ },
                onFailure = { /* TODO: show error */ }
            )
        }
    }

    /**
     * 发送表情（我的表情/表情广场点击后调用）。
     * 表情消息即 image 消息，body 的 media_kind=emoji（实测服务端格式）。
     * 若传入本地 content:// 路径，先上传到 /media 得到服务器 url 再发送。
     */
    fun sendEmoji(mediaUrl: String) {
        if (mediaUrl.isBlank()) return
        val body = MessagePayloadBuilder.buildBody("", mediaKind = "emoji")
        if (mediaUrl.startsWith("content://") || mediaUrl.startsWith("file://")) {
            // 本地图片：先上传为 media，再发送
            viewModelScope.launch {
                val upload = MediaUploader.uploadImage(
                    context = OldChatApplication.instance.applicationContext,
                    uri = Uri.parse(mediaUrl)
                )
                upload.fold(
                    onSuccess = { r -> sendMedia("image", r.url, r.thumbUrl ?: r.url, body, 0) },
                    onFailure = { /* TODO: show error */ }
                )
            }
        } else {
            sendMedia("image", mediaUrl, mediaUrl, body, 0)
        }
    }

    /**
     * Send an image/video/voice message.
     */
    fun sendMedia(
        msgType: String,
        mediaUrl: String,
        thumbUrl: String?,
        body: String,
        durationMs: Int = 0
    ) {
        val localId = "local_${localIdCounter.incrementAndGet()}"

        val pending = Message(
            id = localId,
            threadId = threadId,
            fromUid = app.authManager.myUid ?: "",
            body = body,
            msgType = msgType,
            mediaUrl = mediaUrl,
            thumbUrl = thumbUrl,
            durationMs = durationMs,
            createdAt = System.currentTimeMillis() / 1000,
            status = Message.STATUS_NONE,
            isLocalPending = true,
            localRequestId = localId,
            localProgress = 100 // Already uploaded
        )

        addPending(pending)
        pendingRequests.add(localId)

        viewModelScope.launch {
            val sendBody = gson.toJson(
                mapOf(
                    "to_uid" to friendUid,
                    "body" to body,
                    "msg_type" to msgType,
                    "media_url" to mediaUrl,
                    "thumb_url" to (thumbUrl ?: ""),
                    "duration_ms" to durationMs
                )
            )
            val result = apiClient.post("/direct/send", sendBody)
            result.fold(
                onSuccess = { resp ->
                    val sent = parseSingleMessage(resp)
                    if (sent != null) completePending(localId, sent)
                    else failPending(localId)
                },
                onFailure = { failPending(localId) }
            )
        }
    }

    private fun parseSingleMessage(body: String): Message? {
        return try {
            gson.fromJson(body, Message::class.java)
        } catch (_: Exception) { null }
    }

    private fun addPending(message: Message) {
        _messages.update { current ->
            val updated = current.toMutableList()
            // 保证刚发送的消息置底：若本机时间戳 ≤ 列表当前最大 createdAt（本机/服务端可能不同步），
            // 提升到"最大 + 1"，避免后续 mergeMessages 按 createdAt 重排时被错误移到顶部。
            val maxCreatedAt = current.maxOfOrNull { it.createdAt } ?: 0L
            val adjusted = if (message.createdAt <= maxCreatedAt)
                message.copy(createdAt = maxCreatedAt + 1)
            else message
            updated.add(adjusted)
            messageIds.add(adjusted.id)
            updated
        }
        viewModelScope.launch {
            _scrollToBottom.emit(Unit)
        }
    }

    private fun completePending(localId: String, serverMessage: Message) {
        _messages.update { current ->
            current.map { msg ->
                if (msg.localRequestId == localId) {
                    serverMessage.copy(isLocalPending = false, localRequestId = null)
                } else msg
            }
        }
        pendingRequests.remove(localId)
        // Update messageIds: remove local_* and add server id
        messageIds.remove(localId)
        messageIds.add(serverMessage.id)
        // Save to cache
        cache.saveDirectMessages(friendUid, _messages.value)
    }

    private fun failPending(localId: String) {
        _messages.update { current ->
            current.map { msg ->
                if (msg.localRequestId == localId) {
                    msg.copy(isLocalPending = false, isLocalFailed = true)
                } else msg
            }
        }
        pendingRequests.remove(localId)
    }

    /**
     * Retry sending a failed message.
     */
    fun retryMessage(message: Message) {
        if (!message.isLocalFailed) return
        val body = message.body
        val msgType = message.msgType
        // Re-create as pending and resend
        when (msgType) {
            "text" -> sendText(
                text = MessagePayloadBuilder.parse(body).text,
                quoteDraft = null
            )
            else -> sendMedia(msgType, message.mediaUrl ?: "", message.thumbUrl, body, message.durationMs)
        }
    }

    // ---- Read Receipt ----

    /**
     * Upload an image then send as message.
     */
    fun uploadAndSendImage(context: Context, uri: Uri) {
        val localId = "local_${localIdCounter.incrementAndGet()}"
        val pending = Message(
            id = localId, threadId = threadId,
            fromUid = app.authManager.myUid ?: "",
            body = "", msgType = "image", createdAt = System.currentTimeMillis() / 1000,
            status = Message.STATUS_NONE, isLocalPending = true,
            localRequestId = localId, localProgress = 0
        )
        addPending(pending)
        pendingRequests.add(localId)

        viewModelScope.launch {
            // Update progress
            _messages.update { msgs ->
                msgs.map { if (it.localRequestId == localId) it.copy(localProgress = 30) else it }
            }
            val result = MediaUploader.uploadImage(context, uri)
            result.fold(
                onSuccess = { upload ->
                    _messages.update { msgs ->
                        msgs.map { if (it.localRequestId == localId) it.copy(localProgress = 80) else it }
                    }
                    sendMedia("image", upload.url, upload.thumbUrl, MessagePayloadBuilder.buildBody("", mediaKind = "image"), 0)
                    // Remove spinner pending since sendMedia creates new pending
                    _messages.update { msgs -> msgs.filter { it.localRequestId != localId } }
                    pendingRequests.remove(localId)
                },
                onFailure = { error ->
                    failPending(localId)
                }
            )
        }
    }

    fun uploadAndSendFile(context: Context, uri: Uri, fileName: String, mimeType: String, fileSize: Long) {
        viewModelScope.launch {
            if (fileSize > MediaUploader.MAX_FILE_BYTES) return@launch
            val result = MediaUploader.uploadFile(context, uri, fileName, mimeType, fileSize)
            result.fold(
                onSuccess = { upload ->
                    val body = MessagePayloadBuilder.buildFileBody(fileName, fileSize, upload.url)
                    sendMedia("resource", upload.url, null, body, 0)
                },
                onFailure = { /* TODO: show error */ }
            )
        }
    }

    /**
     * Upload voice recording from a file URI, then send.
     * The recording is expected to be done externally via MediaRecorder.
     */
    fun uploadAndSendVoice(context: Context, voiceFileUri: Uri, durationMs: Int) {
        viewModelScope.launch {
            val result = MediaUploader.uploadVoice(context, voiceFileUri)
            result.fold(
                onSuccess = { upload ->
                    val body = MessagePayloadBuilder.buildBody("", mediaKind = "voice")
                    sendMedia("voice", upload.url, null, body, durationMs)
                },
                onFailure = { /* TODO: show error */ }
            )
        }
    }

    // ---- Read Receipt ----

    /**
     * ALIGN-11：已读上报加 3 秒防抖 + 合并。
     * 原来每次消息变化/滚动事件都会直接 POST /direct/read，
     * 聊天密集时等于给自己刷请求（违反 §0 反滥用红线）。
     */
    fun markRead() {
        lastReadRequestAt = System.currentTimeMillis()
        markReadJob?.cancel()
        markReadJob = viewModelScope.launch {
            delay(MARK_READ_DEBOUNCE_MS)
            try {
                apiClient.post(
                    "/direct/read",
                    gson.toJson(mapOf("thread_id" to threadId))
                )
            } catch (_: Exception) {
                // 静默：下次标记时会再试
            }
        }
    }

    /**
     * ALIGN-13：向上翻页加载更早历史（进入会话只拉最新一页）。
     */
    fun loadMoreHistory() {
        if (isLoadingMore || !historyHasMore) return
        val oldest = _messages.value.minByOrNull { it.createdAt } ?: return
        isLoadingMore = true
        viewModelScope.launch {
            try {
                if (loadedPages >= MAX_HISTORY_PAGES) {
                    historyHasMore = false
                    return@launch
                }
                val params = mutableMapOf(
                    "with_uid" to friendUid,
                    "limit" to HISTORY_PAGE_SIZE.toString()
                )
                // 游标：优先用服务端认可的消息 id，其次用序号
                if (oldest.id.isNotEmpty() && !oldest.isLocalPending) {
                    params["before_msg_id"] = oldest.id
                }
                params["before_seq"] = oldest.sortSeq.toString()

                apiClient.get("/direct/messages/v2", params).onSuccess { body ->
                    val older = parseMessages(body)
                    if (older.isEmpty()) {
                        historyHasMore = false
                    } else {
                        loadedPages += 1
                        mergeMessages(older, appendToFront = true)
                        historyHasMore = older.size >= HISTORY_PAGE_SIZE &&
                            loadedPages < MAX_HISTORY_PAGES
                    }
                }
            } catch (_: Exception) {
                historyHasMore = false
            } finally {
                isLoadingMore = false
            }
        }
    }

    /**
     * 顶栏「更新消息」按钮：拉取最新一页消息并 merge（不整页替换）。
     * 复用 /direct/messages/v2 首次加载逻辑，用最新消息游标之后的增量合入。
     */
    fun refreshLatest() {
        viewModelScope.launch {
            try {
                // 不带 before/offset 游标，服务端返回最新一页
                val params = mapOf("with_uid" to friendUid, "limit" to "50")
                val result = apiClient.get("/direct/messages/v2", params)
                result.onSuccess { body ->
                    val incoming = parseMessages(body)
                    if (incoming.isNotEmpty()) {
                        mergeMessages(incoming, appendToFront = false)
                        cache.saveDirectMessages(friendUid, _messages.value)
                        _scrollToBottom.emit(Unit)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    // ---- 加密通话（enigmaj 同构的 PQC 握手 + AES-256-GCM 帧） ----

    private val callManager get() = app.encryptedCallManager
    val callState = app.encryptedCallManager.state
    val callElapsedSeconds = app.encryptedCallManager.elapsedSeconds
    val callSystemText = app.encryptedCallManager.systemText

    /** 拨出/挂断都由界面调用；第二次点击 = 挂断（界面负责先弹确认） */
    fun startEncryptedCall() {
        if (friendUid.isBlank()) return
        callManager.start(friendUid)
    }

    fun hangUpEncryptedCall() {
        callManager.hangUp()
    }

    fun dismissCallResult() {
        callManager.acknowledgeEnded()
    }

    /** 当前会话是否正在加密通话中 */
    fun isInEncryptedCall(): Boolean = callManager.isInCallWith(friendUid)

    // ---- Receive WS Messages ----

    fun onWsMessage(message: Message) {
        // 过滤：该消息必须属于当前会话。服务端 thread_id 是独立会话 ID（非 uid），
        // 故用 || 逻辑：fromUid/peerUid/threadId 任一匹配即接收（避免对方消息 fromUid 匹配
        // 但 threadId 为空时被误丢弃；也覆盖自己发送回显时 fromUid=自己、peerUid=对方的场景）。
        val myUid = app.authManager.myUid
        val belongsCurrent = message.fromUid == friendUid ||
            message.peerUid == friendUid ||
            message.threadId == threadId ||
            message.fromUid == myUid // 自己发送的回显
        if (!belongsCurrent) return

        // 加密通话的控制帧（PQC_BEGIN / PQC_REPLY / ENC）交给通话层消费：
        // 它们不该出现在聊天气泡里，也不该写进消息缓存。
        // 收到 PQC_BEGIN 时通话层会自动回 PQC_REPLY（与 enigmaj 的响应方行为一致）。
        // 加密通话的控制帧不展示为聊天气泡、也不写入消息缓存。
        // 帧的**处理**统一在 WebSocketManager.routeE2eFrame（网络层单点），
        // 这样对方不在会话页时也能接起来电；这里只负责过滤。
        if (E2eFrame.isE2e(message.body)) return

        // ALIGN-02：收到本会话实时消息 → 合并触发一次回执刷新（替代 5s 轮询）
        triggerReceiptRefresh()

        // 若拿到真实 thread_id，补全本地 threadId（此前初始化为 uid，可能不准确）
        if (message.threadId.isNotEmpty() && threadId != message.threadId) {
            threadId = message.threadId
        }

        mergeMessages(listOf(message), appendToFront = false)
        cache.saveDirectMessages(friendUid, _messages.value)
        viewModelScope.launch { _scrollToBottom.emit(Unit) }
        markRead()
    }

    fun destroy() {
        receiptRefreshJob?.cancel()
        receiptRefreshJob = null
        cache.saveDirectMessages(friendUid, _messages.value)
    }

    fun isOwnMessage(message: Message): Boolean {
        return message.fromUid == app.authManager.myUid
    }

    companion object {
        const val MAX_ACTIVE_WINDOW = 200

        /**
         * ALIGN-02：回执刷新的合并窗口（毫秒）。事件驱动 + 去抖，
         * 取代原来的 5 秒固定轮询。
         */
        const val RECEIPT_REFRESH_DEBOUNCE_MS = 1_500L

        /** ALIGN-11：已读上报防抖窗口 */
        const val MARK_READ_DEBOUNCE_MS = 3_000L

        /** ALIGN-15：输入中上报节流窗口 / 对方输入状态自动过期时间 */
        const val TYPING_THROTTLE_MS = 2_500L
        const val TYPING_TIMEOUT_MS = 6_000L

        /** ALIGN-13：历史分页页大小与最大页数（对齐 §3.1「最多 150 条」） */
        const val HISTORY_PAGE_SIZE = 30
        const val MAX_HISTORY_PAGES = 5
    }
}
