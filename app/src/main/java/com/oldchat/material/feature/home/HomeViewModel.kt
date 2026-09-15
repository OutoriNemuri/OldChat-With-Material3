@file:OptIn(kotlinx.coroutines.FlowPreview::class)
package com.oldchat.material.feature.home

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.sync.withPermit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oldchat.material.OldChatApplication
import com.oldchat.material.core.model.RecentChatItem
import com.oldchat.material.core.model.User
import com.oldchat.material.core.model.Group
import com.oldchat.material.core.network.WebSocketManager
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel for the home screen (chats list, friends list, connection status).
 */
class HomeViewModel : ViewModel() {

    private val app = OldChatApplication.instance
    private val cacheManager = app.cacheManager
    private val wsManager = app.wsManager

    // Connection state
    val connectionState: StateFlow<WebSocketManager.ConnectionState> = wsManager.connectionState
        .stateIn(viewModelScope, SharingStarted.Eagerly, WebSocketManager.ConnectionState.DISCONNECTED)

    // Chats tab — recent chat list
    private val _recentChats = MutableStateFlow<List<RecentChatItem>>(emptyList())
    val recentChats: StateFlow<List<RecentChatItem>> = _recentChats.asStateFlow()

    // Friends tab — friends + groups
    private val _friends = MutableStateFlow<List<User>>(emptyList())
    val friends: StateFlow<List<User>> = _friends.asStateFlow()

    private val _groups = MutableStateFlow<List<Group>>(emptyList())
    val groups: StateFlow<List<Group>> = _groups.asStateFlow()

    // Profile tab
    private val _profileJson = MutableStateFlow("")
    val profileJson: StateFlow<String> = _profileJson.asStateFlow()

    // Unread counts
    private val _friendRequestCount = MutableStateFlow(0)
    val friendRequestCount: StateFlow<Int> = _friendRequestCount.asStateFlow()

    // ---- 构造期就要用到的字段必须声明在最上面（见下方 init）----
    // 说明：ALIGN-01/ALIGN-12 的 previewFetched / previewGate / recentChatsRefresh
    // 会被 init → loadCache() / refreshFriends() 直接用到；若声明在使用点之后，
    // 构造期读到的是 null，会抛
    //   NullPointerException: Flow.collect(...) on a null object reference
    // （线上崩溃即由此产生，故统一前移到 init 之前）。

    // ---- ALIGN-01：会话列表预览的「反滥用」闸门 ----
    // 规范 §0：不允许朴素全量刷新（每个会话都回源一次历史）——会被监测/限流/封禁。
    // §7.1：lastMessage 优先用服务端随列表下发的字段与本地缓存，只有确实没有预览时
    // 才回源，且并发受限、每个会话最多补一次。
    private val previewFetched: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val previewGate = kotlinx.coroutines.sync.Semaphore(4)

    // ALIGN-12：会话列表刷新的 220ms 合并窗口。
    // 原实现每收到一条 WS 消息就重排/重发一次列表状态 —— 消息成串到达时
    // Compose 会连续重组 N 次。这里统一走这个触发器，合并成一次。
    private val recentChatsRefresh = MutableSharedFlow<Unit>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private fun requestRecentChatsRefresh() {
        recentChatsRefresh.tryEmit(Unit)
    }

    init {
        loadCache()
        observeWebSocket()
        // Load all network data immediately after login
        refreshChats()
        refreshFriends()
        refreshGroups()
        loadProfile()
    }

    private fun loadCache() {
        // Load recent chats
        cacheManager.recentChats.loadFromDisk()
        // 会话列表 = 缓存的投影：任何写操作都会推一次，UI 立刻更新。
        // （修「收到消息后首页预览/未读气泡不更新」：以前只在被显式通知时才刷新，
        //   且刷新读的是可能过期的内存快照。220ms 去抖只为合并消息突发。）
        viewModelScope.launch {
            cacheManager.recentChats.itemsFlow
                .debounce(RECENT_CHATS_MERGE_MS)
                .collect { _recentChats.value = it }
        }

        _recentChats.value = cacheManager.recentChats.getAll()

        // Load friends
        cacheManager.friends.loadFromDisk()
        _friends.value = cacheManager.friends.getAll()

        // Load groups
        cacheManager.groups.loadFromDisk()
        _groups.value = cacheManager.groups.getAll()
    }

    private fun observeWebSocket() {
        viewModelScope.launch {
            // Observe direct messages → update recent chats
            wsManager.directMessages.collect { message ->
                // 会话归属：自己发的消息回显 fromUid=自己、peerUid=对方；对方发的 fromUid=对方。
                val peer = message.peerUid.ifEmpty { message.fromUid }
                if (peer.isEmpty()) return@collect
                val isOwn = message.fromUid == app.authManager.myUid
                // 从**缓存**读当前项（_recentChats 是去抖后的快照，连续消息会读到过期值
                // → 未读计数少加、预览回退；之前就是这个原因）
                val existing = cacheManager.recentChats.getByChatId(peer)
                    ?: cacheManager.recentChats.getByChatId(message.fromUid)
                val preview = extractPreview(message.msgType, message.body)
                if (existing != null) {
                    val updated = existing.copy(
                        lastMessage = preview,
                        lastMessageType = message.msgType,
                        lastTime = message.createdAt,
                        // 自己发的消息不增加未读
                        unreadCount = if (isOwn) existing.unreadCount else existing.unreadCount + 1
                    )
                    cacheManager.recentChats.upsert(updated)
                    requestRecentChatsRefresh()   // ALIGN-12：合并刷新
                } else {
                    val newItem = RecentChatItem(
                        type = "direct",
                        chatId = peer,
                        name = _friends.value.find { it.uid == peer }?.nickname ?: peer,
                        avatarUrl = _friends.value.find { it.uid == peer }?.avatarUrl
                            ?.let { resolveAvatarUrl(it) },
                        lastMessage = preview,
                        lastMessageType = message.msgType,
                        lastTime = message.createdAt,
                        unreadCount = if (isOwn) 0 else 1
                    )
                    cacheManager.recentChats.upsert(newItem)
                    requestRecentChatsRefresh()   // ALIGN-12：合并刷新
                }
            }
        }

        viewModelScope.launch {
            wsManager.groupMessages.collect { message ->
                val gid = message.groupId
                if (gid.isEmpty()) return@collect
                val isOwn = message.fromUid == app.authManager.myUid
                val preview = extractPreview(message.msgType, message.body)
                val existing = cacheManager.recentChats.getByChatId(gid)
                if (existing != null) {
                    val updated = existing.copy(
                        lastMessage = preview,
                        lastMessageType = message.msgType,
                        lastTime = message.createdAt,
                        unreadCount = if (isOwn) existing.unreadCount else existing.unreadCount + 1
                    )
                    cacheManager.recentChats.upsert(updated)
                    requestRecentChatsRefresh()   // ALIGN-12：合并刷新
                } else {
                    // 新群会话也新建/更新条目，避免预览不刷新
                    val newItem = RecentChatItem(
                        type = "group",
                        chatId = gid,
                        name = _groups.value.find { it.id == gid }?.name ?: gid,
                        avatarUrl = _groups.value.find { it.id == gid }?.avatarUrl
                            ?.let { resolveAvatarUrl(it) },
                        lastMessage = preview,
                        lastMessageType = message.msgType,
                        lastTime = message.createdAt,
                        unreadCount = if (isOwn) 0 else 1
                    )
                    cacheManager.recentChats.upsert(newItem)
                    requestRecentChatsRefresh()   // ALIGN-12：合并刷新
                }
            }
        }

        viewModelScope.launch {
            wsManager.presenceEvents.collect { event ->
                _friends.update { list ->
                    list.map { user ->
                        if (user.uid == event.uid) user.copy(presenceStatus = event.status)
                        else user
                    }
                }
                cacheManager.recentChats.updatePresence(event.uid, event.status)
            }
        }
    }

    // （ALIGN-01 的 previewFetched / previewGate 与 ALIGN-12 的 recentChatsRefresh
    //   已前移到 init 之前声明，见文件上方。）

    /**
     * 服务端随 /friends、/groups 下发的 last_message 解析结果：
     * uid/gid -> Triple(body, msgType, createdAt)
     */
    private fun parseServerLastMessages(json: String, key: String = "last_message"):
        Map<String, Triple<String, String, Long>> {
        val out = mutableMapOf<String, Triple<String, String, Long>>()
        return try {
            val root = app.gson.fromJson(json, Map::class.java) as? Map<*, *> ?: return out
            val list = (root["friends"] ?: root["groups"] ?: root["items"] ?: root["list"]) as? List<*>
                ?: return out
            list.filterIsInstance<Map<*, *>>().forEach { item ->
                val id = (item["uid"] ?: item["id"] ?: item["group_id"])?.toString()
                val last = item[key] as? Map<*, *> ?: return@forEach
                if (id.isNullOrEmpty()) return@forEach
                out[id] = Triple(
                    last["body"]?.toString() ?: "",
                    last["msg_type"]?.toString() ?: "text",
                    (last["created_at"] as? Number)?.toLong() ?: 0L
                )
            }
            out
        } catch (_: Exception) { out }
    }

    // ---- Actions ----

    // NOTE: The server has no /me/recents HTTP endpoint (verified via live API).
    // The chat list is assembled from local caches (§7.1) and updated by inbound
    // WebSocket messages. So we just re-read the cache here.
    fun refreshChats() {
        cacheManager.recentChats.loadFromDisk()
        requestRecentChatsRefresh()   // ALIGN-12：合并刷新
    }

    // ---- 首次使用：一次性把基础数据灌进本地缓存 ----

    /** 本机是否已有可用缓存（会话列表 / 好友 / 消息历史任一非空即视为有） */
    fun hasBasicCache(): Boolean =
        cacheManager.recentChats.getAll().isNotEmpty() ||
            cacheManager.friends.getAll().isNotEmpty()

    private val _prefetchState = MutableStateFlow(PrefetchState())
    val prefetchState: StateFlow<PrefetchState> = _prefetchState.asStateFlow()

    data class PrefetchState(
        val running: Boolean = false,
        val finished: Boolean = false,
        val friends: Int = 0,
        val groups: Int = 0,
        val previews: Int = 0,
        val skipped: Boolean = false,
        val message: String? = null
    )

    /**
     * 首次启动引导用：**一次性、有上限**地拉取基础数据进缓存。
     *
     * 内容：
     *   1. 通讯录：`/friends` + `/groups/list`（含头像相对路径、群名、头像）
     *   2. 每个会话的最新一条消息预览（把「最近消息」落到会话列表）
     *   3. 少量历史消息：每个会话最多 [PREFETCH_MESSAGES_PER_CHAT] 条，
     *      总会话数上限 [PREFETCH_MAX_CHATS]、并发 [PREFETCH_CONCURRENCY]
     *
     * 为什么有上限：规范 §0 明确禁止朴素全量刷新（会被监测/限流）。
     * 这里是用户明确同意的一次性动作，所以允许回源，但必须**有界**。
     */
    fun prefetchBasics() {
        if (_prefetchState.value.running) return
        _prefetchState.value = PrefetchState(running = true)

        viewModelScope.launch {
            var friends = 0
            var groups = 0
            var previews = 0
            try {
                // 1) 通讯录
                app.apiClient.get("/friends").onSuccess { json ->
                    val list = parseFriends(json)
                    if (list.isNotEmpty()) {
                        friends = list.size
                        cacheManager.friends.replaceAll(list)
                        _friends.value = list
                        syncChatListFromFriends(list, parseServerLastMessages(json))
                    }
                }
                app.apiClient.get("/groups/list").onSuccess { json ->
                    val list = parseGroups(json)
                    if (list.isNotEmpty()) {
                        groups = list.size
                        cacheManager.groups.replaceAll(list)
                        _groups.value = list
                        syncChatListFromGroups(list, parseServerLastMessages(json))
                    }
                    _prefetchState.value = _prefetchState.value.copy(friends = friends, groups = groups)
                }

                // 2) + 3) 会话预览与少量历史（有界 + 受限并发）
                val targets = cacheManager.recentChats.getAll()
                    .filter { it.chatId.isNotEmpty() }
                    .take(PREFETCH_MAX_CHATS)
                val gate = kotlinx.coroutines.sync.Semaphore(PREFETCH_CONCURRENCY)
                val jobs = targets.map { item ->
                    async {
                        gate.withPermit {
                            val ok = when (item.type) {
                                "group" -> prefetchGroupHistory(item.chatId)
                                else -> prefetchDirectHistory(item.chatId)
                            }
                            if (ok) previews++
                        }
                    }
                }
                jobs.forEach { it.await() }
                _prefetchState.value = PrefetchState(
                    running = false,
                    finished = true,
                    friends = friends,
                    groups = groups,
                    previews = previews,
                    message = "已缓存 $friends 位好友 · $groups 个群 · $previews 个会话的消息"
                )
            } catch (e: Exception) {
                _prefetchState.value = PrefetchState(
                    running = false,
                    finished = true,
                    friends = friends,
                    groups = groups,
                    previews = previews,
                    message = "部分数据获取失败：${e.message ?: "网络异常"}"
                )
            }
        }
    }

    private suspend fun prefetchDirectHistory(uid: String): Boolean {
        val result = app.apiClient.get(
            "/direct/messages/v2",
            mapOf("with_uid" to uid, "limit" to PREFETCH_MESSAGES_PER_CHAT.toString())
        )
        val body = result.getOrNull() ?: return false
        val list = runCatching {
            val root = app.gson.fromJson(body, Map::class.java) as? Map<*, *>
            val arr = root?.get("messages") as? List<*> ?: return false
            arr.mapNotNull { item ->
                runCatching {
                    app.gson.fromJson(app.gson.toJson(item), com.oldchat.material.core.model.Message::class.java)
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
        if (list.isEmpty()) return false
        cacheManager.saveDirectMessages(uid, list)
        // 用最新一条回填会话列表预览
        val latest = list.maxByOrNull { it.createdAt } ?: return false
        val current = cacheManager.recentChats.getByChatId(uid) ?: return false
        if (latest.createdAt >= current.lastTime) {
            cacheManager.recentChats.upsert(
                current.copy(
                    lastMessage = extractPreview(latest.msgType, latest.body),
                    lastMessageType = latest.msgType,
                    lastTime = latest.createdAt
                )
            )
        }
        return true
    }

    private suspend fun prefetchGroupHistory(groupId: String): Boolean {
        val result = app.apiClient.get(
            "/groups/messages/v2",
            mapOf("group_id" to groupId, "limit" to PREFETCH_MESSAGES_PER_CHAT.toString())
        )
        val body = result.getOrNull() ?: return false
        val list = runCatching {
            val root = app.gson.fromJson(body, Map::class.java) as? Map<*, *>
            val arr = root?.get("messages") as? List<*> ?: return false
            arr.mapNotNull { item ->
                runCatching {
                    app.gson.fromJson(app.gson.toJson(item),
                        com.oldchat.material.core.model.GroupMessage::class.java)
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
        if (list.isEmpty()) return false
        cacheManager.saveGroupMessages(groupId, list)
        val latest = list.maxByOrNull { it.createdAt } ?: return false
        val current = cacheManager.recentChats.getByChatId(groupId) ?: return false
        if (latest.createdAt >= current.lastTime) {
            cacheManager.recentChats.upsert(
                current.copy(
                    lastMessage = extractPreview(latest.msgType, latest.body),
                    lastMessageType = latest.msgType,
                    lastTime = latest.createdAt
                )
            )
        }
        return true
    }

    fun dismissPrefetch() {
        _prefetchState.value = _prefetchState.value.copy(skipped = true)
    }

    fun refreshFriends() {
        viewModelScope.launch {
            try {
                val result = app.apiClient.get("/friends")
                result.onSuccess { json ->
                    val list = parseFriends(json)
                    if (list.isNotEmpty()) {
                        _friends.value = list
                        cacheManager.friends.replaceAll(list)
                        // 用好友列表初始化聊天列表（服务端无独立会话列表端点）
                        syncChatListFromFriends(list, parseServerLastMessages(json))
                    }
                }
            } catch (_: Exception) { /* offline */ }
        }
    }

    /**
     * 服务端没有「会话列表」端点，聊天列表需由好友列表初始化：
     * 为每个好友 upsert 一个 direct 会话条目（仅当尚不存在时），
     * 这样即使从未收到过该好友的 WS 消息，列表也能显示出来。
     */
    private fun syncChatListFromFriends(
        friends: List<User>,
        serverLastMessages: Map<String, Triple<String, String, Long>> = emptyMap()
    ) {
        val existingIds = cacheManager.recentChats.getAll().map { it.chatId }.toSet()
        var changed = false
        friends.forEach { friend ->
            if (friend.uid.isEmpty()) return@forEach
            val resolvedAvatar = resolveAvatarUrl(friend.avatarUrl)
            // 逐条从缓存取（不要用函数开头那份快照：期间可能已被 WS 更新，
            // 用旧值回写会把刚收到的预览/未读覆盖掉）
            val current = cacheManager.recentChats.getByChatId(friend.uid)
            val isNew = current == null
            if (isNew) {
                // 新会话：插入完整条目
                cacheManager.recentChats.upsert(
                    RecentChatItem(
                        type = "direct",
                        chatId = friend.uid,
                        name = friend.nickname,
                        avatarUrl = resolvedAvatar,
                        presenceStatus = friend.presenceStatus
                    )
                )
                changed = true
            } else if (current.avatarUrl != resolvedAvatar) {
                // 已存在但头像缺失/过期：回填头像（修复旧缓存 avatarUrl=null 导致占位符不显示）
                cacheManager.recentChats.upsert(
                    current.copy(
                        avatarUrl = resolvedAvatar,
                        name = if (current.name.isNullOrEmpty()) friend.nickname else current.name,
                        presenceStatus = friend.presenceStatus
                    )
                )
                changed = true
            }
            // ALIGN-01 修正：原来对「所有」好友都回源一次 /direct/messages/v2。
            // 现在优先用服务端 last_message，其次用本地已有预览；
            // 只有两者都没有（真正没预览的新会话）才回源一次。
            val serverMsg = serverLastMessages[friend.uid]
            when {
                serverMsg != null -> {
                    val (body, msgType, createdAt) = serverMsg
                    val localTime = current?.lastTime ?: 0L
                    if (body.isNotEmpty() && createdAt >= localTime) {
                        cacheManager.recentChats.upsert(
                            (current ?: RecentChatItem(
                                type = "direct",
                                chatId = friend.uid,
                                name = friend.nickname,
                                avatarUrl = resolvedAvatar
                            )).copy(
                                lastMessage = extractPreview(msgType, body),
                                lastMessageType = msgType,
                                lastTime = createdAt
                            )
                        )
                        changed = true
                    }
                }
                current?.lastMessage.isNullOrEmpty() -> fillDirectPreview(friend.uid)
            }
        }
        if (changed) {
            requestRecentChatsRefresh()   // ALIGN-12：合并刷新
        }
    }

    /**
     * 服务端无会话列表端点，初始会话的 lastMessage 为空。
     * 为每个会话异步拉取最新一条消息填充预览。
     */
    private fun fillDirectPreview(uid: String) {
        if (uid.isEmpty()) return
        // 每个会话最多补一次，避免每次刷新都重复回源
        if (!previewFetched.add("d:$uid")) return
        viewModelScope.launch {
            try {
                previewGate.withPermit {
                val result = app.apiClient.get("/direct/messages/v2", mapOf("with_uid" to uid, "limit" to "1"))
                result.onSuccess { body ->
                    val msg = parseLatestMessage(body)
                    if (msg != null) {
                        cacheManager.recentChats.upsert(
                            RecentChatItem(
                                type = "direct",
                                chatId = uid,
                                name = _friends.value.find { it.uid == uid }?.nickname ?: uid,
                                avatarUrl = _friends.value.find { it.uid == uid }?.avatarUrl
                                    ?.let { resolveAvatarUrl(it) },
                                lastMessage = extractPreview(msg.msgType, msg.body),
                                lastMessageType = msg.msgType,
                                lastTime = msg.createdAt
                            )
                        )
                        requestRecentChatsRefresh()   // ALIGN-12：合并刷新
                    }
                }
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * 从 /direct/messages/v2 的 wrapper {"messages":[...]} 里取最新一条。
     * 服务端可能正序或倒序返回，故取 createdAt 最大的一条，不依赖数组顺序。
     */
    private fun parseLatestMessage(body: String): com.oldchat.material.core.model.Message? {
        return try {
            val root = app.gson.fromJson(body, Map::class.java) as? Map<*, *> ?: return null
            val list = root["messages"] as? List<*> ?: return null
            val last = list
                .filterIsInstance<Map<*, *>>()
                .maxByOrNull { (it["created_at"] as? Number)?.toLong() ?: 0L } ?: return null
            com.oldchat.material.core.model.Message(
                id = last["id"]?.toString() ?: "",
                body = last["body"]?.toString() ?: "",
                msgType = last["msg_type"]?.toString() ?: "text",
                createdAt = (last["created_at"] as? Number)?.toLong() ?: 0L
            )
        } catch (_: Exception) { null }
    }

    /**
     * 用群列表初始化聊天列表（显示群会话）。
     */
    private fun syncChatListFromGroups(
        groups: List<Group>,
        serverLastMessages: Map<String, Triple<String, String, Long>> = emptyMap()
    ) {
        val existingIds = cacheManager.recentChats.getAll().map { it.chatId }.toSet()
        var changed = false
        groups.forEach { group ->
            if (group.id.isEmpty()) return@forEach
            val resolvedAvatar = resolveAvatarUrl(group.avatarUrl)
            // 同样逐条从缓存取，避免用过期快照回写
            val current = cacheManager.recentChats.getByChatId(group.id)
            val isNew = current == null
            if (isNew) {
                // 新会话：插入完整条目
                cacheManager.recentChats.upsert(
                    RecentChatItem(
                        type = "group",
                        chatId = group.id,
                        name = group.name,
                        avatarUrl = resolvedAvatar
                    )
                )
                changed = true
            } else if (current.avatarUrl != resolvedAvatar) {
                // 已存在但头像缺失/过期：回填头像（修复旧缓存 avatarUrl=null 导致占位符不显示）
                cacheManager.recentChats.upsert(
                    current.copy(
                        avatarUrl = resolvedAvatar,
                        name = if (current.name.isNullOrEmpty()) group.name else current.name
                    )
                )
                changed = true
            }
            // ALIGN-01 修正：同私聊——优先服务端 last_message / 本地预览，
            // 只有确实没有预览的新群会话才回源一次。
            val serverMsg = serverLastMessages[group.id]
            when {
                serverMsg != null -> {
                    val (body, msgType, createdAt) = serverMsg
                    val localTime = current?.lastTime ?: 0L
                    if (body.isNotEmpty() && createdAt >= localTime) {
                        cacheManager.recentChats.upsert(
                            (current ?: RecentChatItem(
                                type = "group",
                                chatId = group.id,
                                name = group.name,
                                avatarUrl = resolvedAvatar
                            )).copy(
                                lastMessage = extractPreview(msgType, body),
                                lastMessageType = msgType,
                                lastTime = createdAt
                            )
                        )
                        changed = true
                    }
                }
                current?.lastMessage.isNullOrEmpty() -> fillGroupPreview(group.id, group.name)
            }
        }
        if (changed) {
            requestRecentChatsRefresh()   // ALIGN-12：合并刷新
        }
    }

    /**
     * 异步拉取群会话最新一条消息作为预览。
     */
    private fun fillGroupPreview(gid: String, name: String) {
        if (gid.isEmpty()) return
        if (!previewFetched.add("g:$gid")) return
        viewModelScope.launch {
            try {
                previewGate.withPermit {
                val result = app.apiClient.get("/groups/messages/v2", mapOf("group_id" to gid, "limit" to "1"))
                result.onSuccess { body ->
                    val (msgBody, msgType, createdAt) = parseLatestGroupMessage(body)
                    if (msgBody.isNotEmpty() || msgType != "text") {
                        cacheManager.recentChats.upsert(
                            RecentChatItem(
                                type = "group",
                                chatId = gid,
                                name = name,
                                avatarUrl = _groups.value.find { it.id == gid }?.avatarUrl
                                    ?.let { resolveAvatarUrl(it) },
                                lastMessage = extractPreview(msgType, msgBody),
                                lastMessageType = msgType,
                                lastTime = createdAt
                            )
                        )
                        requestRecentChatsRefresh()   // ALIGN-12：合并刷新
                    }
                }
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * 从 /groups/messages/v2 的 wrapper 取最新一条（body, msgType, createdAt）。
     * 服务端可能正序或倒序返回，取 createdAt 最大的一条，不依赖数组顺序。
     */
    private fun parseLatestGroupMessage(body: String): Triple<String, String, Long> {
        return try {
            val root = app.gson.fromJson(body, Map::class.java) as? Map<*, *> ?: return Triple("", "text", 0L)
            val list = root["messages"] as? List<*> ?: return Triple("", "text", 0L)
            val last = list
                .filterIsInstance<Map<*, *>>()
                .maxByOrNull { (it["created_at"] as? Number)?.toLong() ?: 0L }
                ?: return Triple("", "text", 0L)
            Triple(
                last["body"]?.toString() ?: "",
                last["msg_type"]?.toString() ?: "text",
                (last["created_at"] as? Number)?.toLong() ?: 0L
            )
        } catch (_: Exception) { Triple("", "text", 0L) }
    }

    /**
     * 头像 URL 为相对路径（/v1/uploads/avatars/...），拼成完整可访问 URL（动态跟随登录/文件服务器）。
     */
    private fun resolveAvatarUrl(path: String?): String? {
        return app.serverConfig.resolveMediaUrl(path)
    }

    /**
     * 提取会话列表预览文本：body 可能是 MessagePayload JSON，也可能是纯文本/竖线分隔。
     * 复用 MessagePayloadBuilder.extractPreviewText。
     */
    private fun extractPreview(msgType: String, body: String): String {
        if (body.isBlank()) return ""
        return try {
            com.oldchat.material.core.model.MessagePayloadBuilder.extractPreviewText(msgType, body)
        } catch (_: Exception) { body }
    }

    fun refreshGroups() {
        viewModelScope.launch {
            try {
                val result = app.apiClient.get("/groups/list")
                result.onSuccess { json ->
                    val list = parseGroups(json)
                    if (list.isNotEmpty()) {
                        _groups.value = list
                        cacheManager.groups.replaceAll(list)
                        // 用群列表初始化聊天列表（显示群会话）
                        syncChatListFromGroups(list, parseServerLastMessages(json))
                    }
                }
            } catch (_: Exception) { /* offline */ }
        }
    }

    // ---- Parsers (handle both wrapped {data:[...]} and plain [...] responses) ----

    private fun parseRecentChats(json: String): List<RecentChatItem> {
        return try {
            val list = extractList(json)
            list?.mapNotNull { map ->
                RecentChatItem(
                    type = (map["type"] as? String) ?: "direct",
                    chatId = (map["chat_id"] ?: map["id"])?.toString() ?: return@mapNotNull null,
                    name = (map["name"] ?: map["nickname"])?.toString() ?: "",
                    avatarUrl = map["avatar_url"]?.toString(),
                    lastMessage = map["last_message"]?.toString() ?: "",
                    lastMessageType = map["last_message_type"]?.toString() ?: "text",
                    lastTime = (map["last_time"] as? Number)?.toLong() ?: 0L,
                    unreadCount = (map["unread_count"] as? Number)?.toInt() ?: 0
                )
            } ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    private fun parseFriends(json: String): List<User> {
        return try {
            val list = extractList(json)
            list?.mapNotNull { map ->
                // Server returns: id/uid/username/display_name/remark_name/
                // user_title/avatar_url/is_online/presence_status/friend_added_at
                val uid = (map["uid"] ?: map["id"])?.toString() ?: return@mapNotNull null
                val displayName = map["display_name"]?.toString()
                val remark = map["remark_name"]?.toString()
                val username = map["username"]?.toString()
                User(
                    uid = uid,
                    nickname = displayName?.takeIf { it.isNotBlank() }
                        ?: remark?.takeIf { it.isNotBlank() }
                        ?: username ?: "",
                    avatarUrl = map["avatar_url"]?.toString(),
                    title = map["user_title"]?.toString(),
                    presenceStatus = if (map["is_online"] == true) "online"
                        else map["presence_status"]?.toString() ?: "offline",
                    bio = map["bio"]?.toString(),
                    creditScore = 0,
                    balance = 0.0
                )
            } ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    private fun parseGroups(json: String): List<Group> {
        return try {
            val list = extractList(json)
            list?.mapNotNull { map ->
                Group(
                    id = (map["id"] ?: map["group_id"])?.toString() ?: return@mapNotNull null,
                    name = map["name"]?.toString() ?: "",
                    avatarUrl = map["avatar_url"]?.toString(),
                    role = map["role"]?.toString() ?: "",
                    ownerUid = map["owner_uid"]?.toString() ?: "",
                    memberCount = (map["member_count"] as? Number)?.toInt() ?: 0,
                    description = map["description"]?.toString()
                )
            } ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    /**
     * Extract a JSON array from various response wrappers:
     * {data:[...]}, [...] (top-level array), {data:{list:[...]}},
     * {friends:[...]}, {groups:[...]}, {list:[...]}, {items:[...]}.
     */
    private fun extractList(json: String): List<Map<*, *>>? {
        val trimmed = json.trim()
        // Handle top-level JSON array directly: [{...}, {...}]
        if (trimmed.startsWith("[")) {
            return try {
                val type = object : com.google.gson.reflect.TypeToken<List<Map<String, Any>>>() {}.type
                app.gson.fromJson<List<Map<String, Any>>>(trimmed, type)?.filterIsInstance<Map<*, *>>()
            } catch (_: Exception) { emptyList() }
        }
        val root = app.gson.fromJson(trimmed, Map::class.java) ?: return null
        // 1. data is a list
        (root["data"] as? List<*>)?.let { return it.filterIsInstance<Map<*, *>>() }
        // 2. data is an object with a list field
        (root["data"] as? Map<*, *>)?.let { data ->
            data.values.forEach { v ->
                if (v is List<*>) return v.filterIsInstance<Map<*, *>>()
            }
        }
        // 3. Named array wrappers (real server uses {friends:[...]} and {groups:[...]})
        listOf("friends", "groups", "list", "items").forEach { key ->
            (root[key] as? List<*>)?.let { return it.filterIsInstance<Map<*, *>>() }
        }
        return null
    }

    fun clearUnread(chatId: String) {
        cacheManager.recentChats.clearUnread(chatId)
        requestRecentChatsRefresh()   // ALIGN-12：合并刷新
    }

    /**
     * Clear unread for a group chat and advance watermark.
     * Called when user opens a group chat.
     */
    fun clearGroupUnread(groupId: String) {
        clearUnread(groupId)
        viewModelScope.launch {
            // Advance watermark by triggering a read
            app.apiClient.post(
                "/groups/read",
                app.gson.toJson(mapOf("group_id" to groupId))
            )
        }
    }

    fun loadProfile() {
        viewModelScope.launch {
            try {
                val result = app.apiClient.get("/me")
                result.onSuccess { json ->
                    _profileJson.value = json
                    app.cacheManager.saveProfileCache(json)
                }
            } catch (_: Exception) {
                // Load from cache
                app.cacheManager.preferences.profileCacheJson.first().let {
                    _profileJson.value = it
                }
            }
        }
    }

    companion object {
        /** ALIGN-12：会话列表刷新合并窗口（毫秒）。消息成串到达时合并成一次重组。 */
        private const val RECENT_CHATS_MERGE_MS = 220L

        /** 首次预取：每个会话最多拉多少条历史（少量即可，目的是让列表有内容） */
        private const val PREFETCH_MESSAGES_PER_CHAT = 10

        /** 首次预取：最多处理多少个会话（有界，避免触发服务端反滥用） */
        private const val PREFETCH_MAX_CHATS = 30

        /** 首次预取并发上限 */
        private const val PREFETCH_CONCURRENCY = 4
    }
}
