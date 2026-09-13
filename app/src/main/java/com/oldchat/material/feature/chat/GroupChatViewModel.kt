package com.oldchat.material.feature.chat

import android.content.Context
import android.util.Log
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.oldchat.material.OldChatApplication
import com.oldchat.material.core.model.*
import com.oldchat.material.core.media.MediaUploader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicLong

/**
 * ViewModel for group chat.
 * Mirrors GroupReliableSync + GroupMessageSender + GroupChatLoadDelegate from §4.
 *
 * Key design (§4.2):
 * - watermark = (group_seq, anchor_message_id)
 * - WS pushes real-time, HTTP /groups/messages/after compensates missed
 * - watermark advances only after full HTTP page merged & anchored
 * - If server data reset (server_seq < after_seq) → fallback legacyUntilOverlap
 */
class GroupChatViewModel : ViewModel() {

    private val app = OldChatApplication.instance
    private val apiClient = app.apiClient
    private val gson: Gson = app.gson
    private val cache = app.cacheManager

    // Group identity
    private var groupId: String = ""
    private var groupName: String = ""

    // ---- Messages ----
    private val _messages = MutableStateFlow<List<GroupMessage>>(emptyList())
    val messages: StateFlow<List<GroupMessage>> = _messages.asStateFlow()
    private val messageIds = mutableSetOf<String>()
    private val pendingRequests = mutableSetOf<String>()

    // ---- Watermark (§4.2) ----
    private var watermarkSeq: Long = 0
    private var watermarkAnchorId: String = ""

    // ---- Reliable Sync State ----
    private var reliableSyncRunning = false
    private var reliableSyncPending = false
    // 追踪当前群的同步协程，切换群时取消，避免旧群消息混入新群界面（串群根因）
    private var syncJob: Job? = null

    // ---- Pagination ----
    private var hasMoreBefore = true
    private var isLoadingMore = false
    private var useCursorPagination = true
    private var oldestCreatedAt: Long = 0
    private var oldestId: String = ""
    private var offset = 0

    // ---- Members ----
    private val _members = MutableStateFlow<List<GroupMember>>(emptyList())
    val members: StateFlow<List<GroupMember>> = _members.asStateFlow()
    private var memberCount: Int = 0

    // 自己的头像（用于气泡右侧/左侧自己的头像，Bug: 群聊不显示自己头像）
    private val _myAvatarUrl = MutableStateFlow<String?>(null)
    val myAvatarUrl: StateFlow<String?> = _myAvatarUrl.asStateFlow()

    // Scroll
    private val _scrollToBottom = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val scrollToBottom: SharedFlow<Unit> = _scrollToBottom
    private val localIdCounter = AtomicLong(System.currentTimeMillis())

    // ---- Init ----

    fun init(gId: String, gName: String) {
        if (groupId == gId) return
        groupId = gId
        groupName = gName

        // 切换群：取消上一个群的同步协程，防止旧群消息被 pullAfter / pullLegacy 混入新群
        syncJob?.cancel()

        // Reset all per-group state when switching groups — otherwise messages
        // from the previous group would leak into the new one.
        _messages.value = emptyList()
        messageIds.clear()
        pendingRequests.clear()
        watermarkSeq = 0
        watermarkAnchorId = ""
        hasMoreBefore = true
        isLoadingMore = false
        oldestCreatedAt = 0
        oldestId = ""
        offset = 0
        _members.value = emptyList()
        memberCount = 0

        // Load from cache
        val cached = cache.loadGroupMessages(groupId)
        if (cached.isNotEmpty()) {
            _messages.value = cached.filter { it.groupId == groupId || it.groupId.isEmpty() }
            messageIds.addAll(_messages.value.map { it.id })
        }

        // Load watermark from store (§4.2)
        syncJob = viewModelScope.launch {
            val (seq, anchor) = cache.preferences.getGroupSyncWatermark(groupId)
            if (anchor.isNotEmpty() && messageIds.contains(anchor)) {
                watermarkSeq = seq
                watermarkAnchorId = anchor
            } else {
                watermarkSeq = 0
                watermarkAnchorId = ""
            }
            startReliableSync()
        }

        // Load members
        loadMembers()

        // 订阅实时群消息：WS 或 HTTP 轮询注入的群消息，经 groupId 过滤后合入本群。
        // 过滤保证了即使收到异群消息也不会「串群」。
        if (wsSubscription == null) {
            val ws = app.wsManager
            wsSubscription = viewModelScope.launch {
                ws.groupMessages.collect { msg -> onWsMessage(msg) }
            }
        }

        loadMyAvatar()
    }

    /**
     * 加载自己的头像（供「自己」消息气泡头像显示）。优先读缓存，否则拉 /me。
     */
    private fun loadMyAvatar() {
        if (_myAvatarUrl.value != null) return
        viewModelScope.launch {
            val cachedJson = try { cache.preferences.profileCacheJson.first() } catch (_: Exception) { "" }
            val cachedAvatar = resolveMyAvatar(cachedJson)
            if (cachedAvatar != null) { _myAvatarUrl.value = cachedAvatar; return@launch }
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

    private var wsSubscription: Job? = null

    // ---- Reliable Sync (§4.2) ----

    private suspend fun startReliableSync() {
        if (reliableSyncRunning) {
            reliableSyncPending = true
            return
        }
        reliableSyncRunning = true
        reliableSyncPending = false
        try {
            if (watermarkSeq > 0 && watermarkAnchorId.isNotEmpty()) {
                pullAfter(watermarkSeq)
            } else {
                pullLegacyUntilOverlap()
            }
        } finally {
            reliableSyncRunning = false
            // 若仍处于当前群（协程未被取消），才重触发挂起的同步
            if (reliableSyncPending && syncJob?.isActive != false) startReliableSync()
        }
    }

    /**
     * Normal compensation: GET /groups/messages/after?group_id=&after_seq=&limit=100
     *
     * BUG-04 / ALIGN-03：规范 §4.2 的水位契约是
     *   新水位 = 本页最大 group_seq（或服务端下发的 next_group_seq）
     *   新锚点 = 就是那条 group_seq == 新水位的消息
     * 原实现写成「水位 = 最大 seq + 1，锚点 = 本页里 seq == 水位 的消息」——
     * 锚点构造上永远不存在（find 必然返回 null）→ 水位永不推进 →
     * 下一次仍从同一个 after_seq 拉，has_more=true 时变成无限递归。
     * 另外改成迭代 + 进度保护，杜绝递归爆栈与死循环。
     */
    private suspend fun pullAfter(afterSeq: Long) {
        var cursor = afterSeq
        var rounds = 0

        while (rounds < MAX_PULL_ROUNDS) {
            rounds++

            val result = apiClient.get(
                "/groups/messages/after",
                mapOf("group_id" to groupId, "after_seq" to cursor.toString(), "limit" to "100")
            )

            val body = result.getOrNull()
            if (body == null) {
                // 路由不可用（404 等）→ 回退 legacy 补拉
                pullLegacyUntilOverlap()
                return
            }

            val incoming = parseGroupMessages(body)
            if (incoming.isEmpty()) return

            val serverSeq = incoming.maxOfOrNull { it.groupSeq } ?: 0L

            // 服务端重置检测：整页都比本地水位旧 → 本地水位无效，清掉走 legacy
            if (serverSeq < cursor) {
                cache.preferences.clearGroupSyncWatermark(groupId)
                watermarkSeq = 0
                watermarkAnchorId = null
                pullLegacyUntilOverlap()
                return
            }

            mergeGroupMessages(incoming, appendToFront = false)

            // 新水位：优先用服务端 next_group_seq，否则用本页最大 seq
            val nextSeq = extractLong(body, "next_group_seq")?.takeIf { it > 0L } ?: serverSeq
            val anchor = findAnchorForSeq(incoming, nextSeq)

            val noProgress = nextSeq <= cursor
            if (!noProgress && anchor != null) {
                cursor = nextSeq
                watermarkSeq = nextSeq
                watermarkAnchorId = anchor.id
                cache.preferences.saveGroupSyncWatermark(groupId, watermarkSeq, watermarkAnchorId)
            }

            val hasMore = extractHasMore(body, incoming.size)
            // 进度保护：服务端说还有更多、但水位没动（或拿不到锚点）时直接停，避免死循环
            if (!hasMore || noProgress || anchor == null) return
        }

        Log.w(TAG, "pullAfter hit MAX_PULL_ROUNDS=$MAX_PULL_ROUNDS, stop at cursor=$cursor")
    }

    /**
     * Legacy fallback: GET /groups/messages/v2 until overlap found.
     */
    private suspend fun pullLegacyUntilOverlap() {
        var pageCreatedAt: Long? = null
        var pageId: String? = null
        var highestSeq = 0L
        var highestSeqId = ""

        while (true) {
            val params = mutableMapOf(
                "group_id" to groupId,
                "limit" to "100",
                "mark_read" to "0"
            )
            if (pageCreatedAt != null && pageId != null) {
                params["before_created_at"] = pageCreatedAt.toString()
                params["before_id"] = pageId
            }

            val result = apiClient.get("/groups/messages/v2", params)
            val body = result.getOrNull() ?: break

            // Parse pagination cursor from the wrapper (next_before_*).
            val nextCreatedAt = extractLong(body, "next_before_created_at")
            val nextId = extractString(body, "next_before_id")

            val incoming = parseGroupMessages(body)
            if (incoming.isEmpty()) break

            // Track highest seq in this page
            incoming.forEach { msg ->
                if (msg.groupSeq > highestSeq) {
                    highestSeq = msg.groupSeq
                    highestSeqId = msg.id
                }
            }

            // Check overlap with local messages
            val localAnchorIds = _messages.value.takeLast(20).map { it.id }.toSet()
            val hasOverlap = incoming.any { it.id in localAnchorIds }

            mergeGroupMessages(incoming, appendToFront = true)

            if (hasOverlap || incoming.size < 100) {
                // Save watermark
                if (highestSeq > 0 && highestSeqId.isNotEmpty()) {
                    watermarkSeq = highestSeq + 1
                    watermarkAnchorId = highestSeqId
                    cache.preferences.saveGroupSyncWatermark(groupId, watermarkSeq, watermarkAnchorId)
                }
                break
            }

            // Next page using server-provided cursor
            if (nextCreatedAt != null && nextId != null) {
                pageCreatedAt = nextCreatedAt
                pageId = nextId
            } else {
                pageCreatedAt = incoming.firstOrNull()?.createdAt ?: break
                pageId = incoming.firstOrNull()?.id ?: break
            }
        }
    }

    private fun extractLong(body: String, key: String): Long? {
        return try {
            val map = gson.fromJson(body, Map::class.java) as? Map<*, *>
            (map?.get(key) as? Number)?.toLong()
        } catch (_: Exception) { null }
    }

    private fun extractString(body: String, key: String): String? {
        return try {
            val map = gson.fromJson(body, Map::class.java) as? Map<*, *>
            map?.get(key)?.toString()
        } catch (_: Exception) { null }
    }

    /** 锚点 = group_seq 正好等于水位的那个消息（BUG-04 修正后语义） */
    private fun findAnchorForSeq(messages: List<GroupMessage>, seq: Long): GroupMessage? {
        return messages.find { it.groupSeq == seq }
    }

    private fun extractHasMore(body: String, incomingCount: Int): Boolean {
        return try {
            val map = gson.fromJson(body, Map::class.java) as? Map<*, *>
            (map?.get("has_more") as? Boolean) ?: (incomingCount >= 100)
        } catch (_: Exception) {
            incomingCount >= 100
        }
    }

    // ---- Merge Group Messages ----

    private fun mergeGroupMessages(incoming: List<GroupMessage>, appendToFront: Boolean) {
        // 核心防御：只合并属于当前群的消息。任何来源（历史补全/WS/HTTP轮询/缓存）
        // 若 groupId 与当前群不一致就丢弃，杜绝「串群」。
        // 加密通话控制帧（PQC_BEGIN / PQC_REPLY / ENC）不是聊天消息：
        // 单聊的帧不该出现在群聊记录里，历史回源时一并过滤。
        val filtered = incoming.filter {
            (it.groupId.isEmpty() || it.groupId == groupId) &&
                !com.oldchat.material.core.e2e.E2eFrame.isE2e(it.body)
        }
        if (filtered.isEmpty()) return

        val current = _messages.value.toMutableList()
        val wasEmpty = current.isEmpty()
        var changed = false

        for (msg in filtered) {
            if (messageIds.contains(msg.id)) continue

            if (msg.localRequestId != null && pendingRequests.contains(msg.localRequestId)) {
                val idx = current.indexOfFirst { it.localRequestId == msg.localRequestId }
                if (idx >= 0) {
                    current[idx] = msg.copy(isLocalPending = false, localRequestId = null, isLocalFailed = false)
                    pendingRequests.remove(msg.localRequestId)
                    changed = true
                    continue
                }
            }

            messageIds.add(msg.id)
            if (appendToFront) current.add(0, msg) else current.add(msg)
            changed = true
        }

        if (changed) {
            current.sortWith(compareBy<GroupMessage> { it.groupSeq }.thenBy { it.createdAt }.thenBy { it.id })
            if (current.size > MAX_WINDOW) {
                val trimmed = current.takeLast(MAX_WINDOW)
                trimmed.forEach { messageIds.add(it.id) }
                _messages.value = trimmed
            } else {
                _messages.value = current
            }
            cache.saveGroupMessages(groupId, _messages.value)
            // 首次加载（此前为空）时滚动到底，显示最新消息
            if (wasEmpty) {
                viewModelScope.launch { _scrollToBottom.emit(Unit) }
            }
        }
    }

    // ---- Sending (§4.3) ----

    fun sendText(text: String, mentions: List<Mention> = emptyList(), quote: GroupMessage? = null) {
        if (text.isBlank()) return

        val body = MessagePayloadBuilder.buildBody(
            text = text,
            mentions = mentions,
            quote = quote?.let { MessagePayloadBuilder.buildGroupQuote(it) }
        )
        val localId = "local_${localIdCounter.incrementAndGet()}"
        val myUid = app.authManager.myUid ?: ""

        val pending = GroupMessage(
            id = localId, groupId = groupId, fromUid = myUid, body = body,
            msgType = "text", groupSeq = Long.MAX_VALUE,
            createdAt = System.currentTimeMillis() / 1000,
            isLocalPending = true, localRequestId = localId
        )
        addGroupPending(pending)
        pendingRequests.add(localId)

        viewModelScope.launch {
            val result = apiClient.post(
                "/groups/message/send",
                gson.toJson(mapOf("group_id" to groupId, "body" to body, "msg_type" to "text"))
            )
            result.fold(
                onSuccess = { resp ->
                    val sent = parseSingleGroupMessage(resp)
                    if (sent != null) completeGroupPending(localId, sent)
                    else failGroupPending(localId)
                },
                onFailure = { failGroupPending(localId) }
            )
        }
    }

    fun sendAtAllText(text: String) {
        // Mention all: mention with uid="all"
        sendText("@所有人 $text", listOf(Mention(uid = "all", name = "所有人")))
    }

    /**
     * 发送红包（群聊）。服务端 /redpackets/send 直接创建并发送红包消息。
     */
    fun sendRedPacket(totalAmount: Int, totalCount: Int) {
        if (totalAmount <= 0 || totalCount <= 0) return
        viewModelScope.launch {
            val body = gson.toJson(
                mapOf(
                    "group_id" to groupId,
                    "total_amount" to totalAmount,
                    "total_count" to totalCount
                )
            )
            val result = apiClient.post("/redpackets/send", body)
            result.fold(
                onSuccess = { resp ->
                    val sent = parseSingleGroupMessage(resp)
                    if (sent != null) mergeGroupMessages(listOf(sent), appendToFront = false)
                    cache.saveGroupMessages(groupId, _messages.value)
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
                onSuccess = { /* 领取成功后服务端推送/轮询更新 */ },
                onFailure = { /* TODO: show error */ }
            )
        }
    }

    /**
     * 发送表情（群聊）。表情消息即 image 消息，body 的 media_kind=emoji。
     * 本地 content:// 路径先上传为 media 再发送。
     */
    fun sendEmoji(mediaUrl: String) {
        if (mediaUrl.isBlank()) return
        val body = MessagePayloadBuilder.buildBody("", mediaKind = "emoji")
        if (mediaUrl.startsWith("content://") || mediaUrl.startsWith("file://")) {
            viewModelScope.launch {
                val upload = MediaUploader.uploadImage(
                    context = app.applicationContext,
                    uri = Uri.parse(mediaUrl)
                )
                upload.fold(
                    onSuccess = { r -> sendMedia("image", r.url, r.thumbUrl ?: r.url, body, 0) },
                    onFailure = { /* TODO */ }
                )
            }
        } else {
            sendMedia("image", mediaUrl, mediaUrl, body, 0)
        }
    }

    fun sendMedia(msgType: String, mediaUrl: String, thumbUrl: String?, body: String, durationMs: Int = 0) {
        val localId = "local_${localIdCounter.incrementAndGet()}"
        val myUid = app.authManager.myUid ?: ""

        val pending = GroupMessage(
            id = localId, groupId = groupId, fromUid = myUid, body = body,
            msgType = msgType, mediaUrl = mediaUrl, thumbUrl = thumbUrl,
            durationMs = durationMs, groupSeq = Long.MAX_VALUE,
            createdAt = System.currentTimeMillis() / 1000,
            isLocalPending = true, localRequestId = localId, localProgress = 100
        )
        addGroupPending(pending)
        pendingRequests.add(localId)

        viewModelScope.launch {
            val sendBody = gson.toJson(mapOf(
                "group_id" to groupId, "body" to body, "msg_type" to msgType,
                "media_url" to mediaUrl, "thumb_url" to (thumbUrl ?: ""), "duration_ms" to durationMs
            ))
            val result = apiClient.post("/groups/message/send", sendBody)
            result.fold(
                onSuccess = { resp ->
                    val sent = parseSingleGroupMessage(resp)
                    if (sent != null) completeGroupPending(localId, sent)
                    else failGroupPending(localId)
                },
                onFailure = { failGroupPending(localId) }
            )
        }
    }

    /**
     * 上传图片并作为群消息发送。
     */
    fun uploadAndSendImage(context: Context, uri: Uri) {
        viewModelScope.launch {
            val result = MediaUploader.uploadImage(context, uri)
            result.fold(
                onSuccess = { upload ->
                    sendMedia("image", upload.url, upload.thumbUrl, MessagePayloadBuilder.buildBody("", mediaKind = "image"), 0)
                },
                onFailure = { /* TODO: show error */ }
            )
        }
    }

    /**
     * 上传文件并作为群消息发送。
     */
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

    fun retryMessage(message: GroupMessage) {
        if (!message.isLocalFailed) return
        when (message.msgType) {
            "text" -> sendText(MessagePayloadBuilder.parse(message.body).text)
            else -> sendMedia(message.msgType, message.mediaUrl ?: "", message.thumbUrl, message.body, message.durationMs)
        }
    }

    private fun addGroupPending(msg: GroupMessage) {
        _messages.update { current ->
            val updated = current.toMutableList()
            // 发送中的本地消息 groupSeq=Long.MAX_VALUE，靠 createdAt 置底：
            // 若本机时间戳 ≤ 当前最大 createdAt（本机/服务端可能不同步），提升到"最大 + 1"，
            // 避免后续 mergeGroupMessages 按 groupSeq/createdAt 重排时被错误排到上方。
            val maxCreatedAt = current.maxOfOrNull { it.createdAt } ?: 0L
            val adjusted = if (msg.createdAt <= maxCreatedAt) msg.copy(createdAt = maxCreatedAt + 1) else msg
            updated.add(adjusted)
            updated.sortedBy { it.groupSeq }
        }
        viewModelScope.launch { _scrollToBottom.emit(Unit) }
    }

    private fun completeGroupPending(localId: String, serverMsg: GroupMessage) {
        _messages.update { it.map { m -> if (m.localRequestId == localId) serverMsg.copy(isLocalPending = false, localRequestId = null) else m } }
        pendingRequests.remove(localId)
        messageIds.remove(localId)
        messageIds.add(serverMsg.id)
        cache.saveGroupMessages(groupId, _messages.value)
    }

    private fun failGroupPending(localId: String) {
        _messages.update { it.map { m -> if (m.localRequestId == localId) m.copy(isLocalPending = false, isLocalFailed = true) else m } }
        pendingRequests.remove(localId)
    }

    // ---- History & Load More ----

    fun loadMoreHistory() {
        if (isLoadingMore || !hasMoreBefore) return
        isLoadingMore = true
        viewModelScope.launch {
            try {
                loadMoreByCursor()
            } finally {
                isLoadingMore = false
            }
        }
    }

    /**
     * 群聊"加载更多"：用 before_created_at + before_id 游标分页（与单聊一致）。
     * 实测 /groups/messages/v2 返回 {messages, has_more, next_before_created_at, next_before_id}。
     */
    private suspend fun loadMoreByCursor() {
        val params = mutableMapOf(
            "group_id" to groupId,
            "limit" to "50",
            "mark_read" to "0"
        )
        if (oldestCreatedAt > 0 && oldestId.isNotEmpty()) {
            params["before_created_at"] = oldestCreatedAt.toString()
            params["before_id"] = oldestId
        }

        val result = apiClient.get("/groups/messages/v2", params)
        val body = result.getOrNull() ?: run {
            hasMoreBefore = false
            return
        }

        val incoming = parseGroupMessages(body)
        if (incoming.isEmpty()) {
            hasMoreBefore = false
            return
        }

        mergeGroupMessages(incoming, appendToFront = true)

        // 更新游标
        val map = gson.fromJson(body, Map::class.java) as? Map<*, *>
        val hasMore = (map?.get("has_more") as? Boolean) ?: false
        hasMoreBefore = hasMore
        val nextCreatedAt = (map?.get("next_before_created_at") as? Number)?.toLong()
        val nextId = map?.get("next_before_id")?.toString()
        if (nextCreatedAt != null && nextId != null) {
            oldestCreatedAt = nextCreatedAt
            oldestId = nextId
        } else {
            // 用 incoming 最旧一条作为下一游标
            incoming.firstOrNull()?.let { m ->
                oldestCreatedAt = m.createdAt
                oldestId = m.id
            }
        }
        cache.saveGroupMessages(groupId, _messages.value)
    }

    // ---- Members (§5) ----

    private fun loadMembers() {
        viewModelScope.launch {
            val result = apiClient.get("/groups/members", mapOf("group_id" to groupId))
            result.onSuccess { body ->
                try {
                    val list = parseGroupMembers(body)
                    if (list.isNotEmpty()) {
                        _members.value = list
                        memberCount = list.size
                    }
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * 解析群成员。服务端返回 wrapper：{"members":[{...}]}。
     * 成员字段为 snake_case：uid / display_name / username / avatar_url / role（数字 0/1/2）。
     */
    private fun parseGroupMembers(body: String): List<GroupMember> {
        return try {
            val root = gson.fromJson(body, Map::class.java) as? Map<*, *> ?: return emptyList()
            val rawList = (root["members"] ?: root["data"] ?: root["list"]) as? List<*> ?: return emptyList()
            rawList.mapNotNull { item ->
                val m = item as? Map<*, *> ?: return@mapNotNull null
                val uid = m["uid"]?.toString() ?: return@mapNotNull null
                val roleNum = (m["role"] as? Number)?.toInt() ?: 0
                GroupMember(
                    uid = uid,
                    nickname = (m["display_name"] ?: m["nickname"] ?: m["username"])?.toString() ?: "",
                    avatarUrl = m["avatar_url"]?.toString(),
                    role = when (roleNum) {
                        0 -> "member"
                        1 -> "admin"
                        2 -> "owner"
                        else -> "member"
                    }
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    fun getMemberCount(): Int = memberCount.coerceAtLeast(_members.value.size)

    // ---- Read ----

    fun markRead() {
        viewModelScope.launch {
            apiClient.post("/groups/read", gson.toJson(mapOf("group_id" to groupId)))
        }
    }

    /**
     * 顶栏「更新消息」按钮：拉取最新一页消息并 merge（不整页替换）。
     * 复用 /groups/messages/v2 拉取最新一页，经 groupId 过滤后合入。
     */
    fun refreshLatest() {
        viewModelScope.launch {
            try {
                val params = mapOf("group_id" to groupId, "limit" to "50", "mark_read" to "1")
                val result = apiClient.get("/groups/messages/v2", params)
                result.onSuccess { body ->
                    val incoming = parseGroupMessages(body)
                    if (incoming.isNotEmpty()) {
                        mergeGroupMessages(incoming, appendToFront = false)
                        cache.saveGroupMessages(groupId, _messages.value)
                        _scrollToBottom.emit(Unit)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    // ---- WS Receive ----

    fun onWsMessage(message: GroupMessage) {
        if (message.groupId != groupId) return
        // WS does not advance watermark (§4.2)
        mergeGroupMessages(listOf(message), appendToFront = false)
        cache.saveGroupMessages(groupId, _messages.value)
        viewModelScope.launch { _scrollToBottom.emit(Unit) }
        markRead()
    }

    fun isOwnMessage(message: GroupMessage): Boolean = message.fromUid == app.authManager.myUid

    fun destroy() {
        cache.saveGroupMessages(groupId, _messages.value)
    }

    private fun parseGroupMessages(body: String): List<GroupMessage> {
        return try {
            // Server returns {"messages":[...], "has_more":..., "next_..."} wrapper.
            val root = gson.fromJson(body, Map::class.java) as? Map<*, *> ?: return emptyList()
            val rawList = (root["messages"] ?: root["data"]) as? List<*>
                ?: return emptyList()
            rawList.mapNotNull { item ->
                (item as? Map<*, *>)?.let(::mapGroupMessage)
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun mapGroupMessage(map: Map<*, *>): GroupMessage {
        fun str(key: String) = map[key]?.toString()
        fun long(key: String) = (map[key] as? Number)?.toLong() ?: 0L
        return GroupMessage(
            id = str("id") ?: "",
            groupId = str("group_id") ?: str("groupId") ?: groupId,
            groupSeq = long("group_seq").takeIf { it != 0L } ?: long("sort_seq"),
            fromUid = str("from_uid") ?: str("fromUid") ?: "",
            body = str("body") ?: "",
            msgType = str("msg_type") ?: str("msgType") ?: "text",
            mediaUrl = str("media_url") ?: str("mediaUrl"),
            thumbUrl = str("thumb_url") ?: str("thumbUrl"),
            durationMs = (map["duration_ms"] as? Number)?.toInt() ?: 0,
            burnAfterSeconds = (map["burn_after_seconds"] as? Number)?.toInt() ?: 0,
            createdAt = long("created_at"),
            sortSeq = long("sort_seq"),
            cachedSenderName = str("from_name") ?: str("fromName")
        )
    }

    private fun parseSingleGroupMessage(body: String): GroupMessage? {
        return try {
            val json = gson.fromJson(body, Map::class.java) as? Map<*, *> ?: return null
            // Might be wrapped in {"message":{...}} or a bare message object.
            val msg = (json["message"] ?: json["data"] ?: json) as? Map<*, *> ?: return null
            mapGroupMessage(msg)
        } catch (_: Exception) { null }
    }

    companion object {
        const val MAX_WINDOW = 200

        /** 单次补拉最多翻页轮数（BUG-04：防止服务端异常时无限翻页） */
        private const val MAX_PULL_ROUNDS = 20
        private const val TAG = "GroupChatViewModel"
    }
}

/**
 * Group member model for member list.
 */
data class GroupMember(
    val uid: String = "",
    val nickname: String = "",
    val avatarUrl: String? = null,
    val role: String = "member"  // owner/admin/member
)
