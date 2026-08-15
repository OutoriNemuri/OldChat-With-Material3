package com.oldchat.material.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.oldchat.material.OldChatApplication
import com.oldchat.material.core.model.Message
import com.oldchat.material.core.model.MessagePayloadBuilder
import com.oldchat.material.core.media.MediaUploader
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.*
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
    // 周期刷新（拉取已读/送达回执）
    private var receiptRefreshJob: Job? = null

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
        if (friendUid == uid) return
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
            _messages.value = cached.filter { it.threadId == uid || it.fromUid == uid }
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

        // 启动周期回执刷新：定时拉取 /direct/messages/v2 最新一页，
        // 更新自己已发送消息的 delivered_at/read_at，实现「已读对号」实时刷新。
        startReceiptRefresh()
    }

    /**
     * 周期刷新已读/送达回执。每 RECEIPT_REFRESH_INTERVAL_MS 拉取最新一页消息，
     * 仅更新已有消息的回执字段（不整页替换、不打扰当前浏览位置）。
     */
    private fun startReceiptRefresh() {
        if (receiptRefreshJob?.isActive == true) return
        receiptRefreshJob = viewModelScope.launch {
            while (isActive) {
                try {
                    refreshReceiptsOnce()
                } catch (_: Exception) {
                    // 静默失败，下一轮继续
                }
                delay(RECEIPT_REFRESH_INTERVAL_MS)
            }
        }
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
    private fun mergeMessages(incoming: List<Message>, appendToFront: Boolean = true) {
        val current = _messages.value.toMutableList()
        var changed = false

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

        val body = MessagePayloadBuilder.buildBody(
            text = text,
            quote = quoteDraft?.let { MessagePayloadBuilder.buildQuote(it) }
        )

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
            localRequestId = localId
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
                        "body" to body,
                        "msg_type" to "text"
                    )
                )
            )
            result.fold(
                onSuccess = { responseBody ->
                    val sent = parseSingleMessage(responseBody)
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

    fun markRead() {
        viewModelScope.launch {
            apiClient.post(
                "/direct/read",
                gson.toJson(mapOf("thread_id" to threadId))
            )
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

        // 已读回执刷新间隔（毫秒）。单聊 /direct/messages/v2 返回 delivered_at/read_at，
        // 周期刷新保证对方读取后，自己发出的消息能实时显示「已读/送达」对号。
        const val RECEIPT_REFRESH_INTERVAL_MS = 5_000L
    }
}
