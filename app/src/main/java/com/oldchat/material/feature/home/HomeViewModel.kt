package com.oldchat.material.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oldchat.material.OldChatApplication
import com.oldchat.material.core.model.RecentChatItem
import com.oldchat.material.core.model.User
import com.oldchat.material.core.model.Group
import com.oldchat.material.core.network.WebSocketManager
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
                val existing = _recentChats.value.find {
                    it.type == "direct" && (it.chatId == peer || it.chatId == message.fromUid)
                }
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
                    _recentChats.value = cacheManager.recentChats.getAll()
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
                    _recentChats.value = cacheManager.recentChats.getAll()
                }
            }
        }

        viewModelScope.launch {
            wsManager.groupMessages.collect { message ->
                val gid = message.groupId
                if (gid.isEmpty()) return@collect
                val isOwn = message.fromUid == app.authManager.myUid
                val preview = extractPreview(message.msgType, message.body)
                val existing = _recentChats.value.find {
                    it.type == "group" && it.chatId == gid
                }
                if (existing != null) {
                    val updated = existing.copy(
                        lastMessage = preview,
                        lastMessageType = message.msgType,
                        lastTime = message.createdAt,
                        unreadCount = if (isOwn) existing.unreadCount else existing.unreadCount + 1
                    )
                    cacheManager.recentChats.upsert(updated)
                    _recentChats.value = cacheManager.recentChats.getAll()
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
                    _recentChats.value = cacheManager.recentChats.getAll()
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

    // ---- Actions ----

    // NOTE: The server has no /me/recents HTTP endpoint (verified via live API).
    // The chat list is assembled from local caches (§7.1) and updated by inbound
    // WebSocket messages. So we just re-read the cache here.
    fun refreshChats() {
        cacheManager.recentChats.loadFromDisk()
        _recentChats.value = cacheManager.recentChats.getAll()
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
                        syncChatListFromFriends(list)
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
    private fun syncChatListFromFriends(friends: List<User>) {
        val existing = cacheManager.recentChats.getAll().associateBy { it.chatId }
        val existingIds = existing.keys
        var changed = false
        friends.forEach { friend ->
            if (friend.uid.isEmpty()) return@forEach
            val resolvedAvatar = resolveAvatarUrl(friend.avatarUrl)
            val current = existing[friend.uid]
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
            // 所有会话都刷新最新预览（不限于新会话），修复"会话列表最新一条不新"
            fillDirectPreview(friend.uid)
        }
        if (changed) {
            _recentChats.value = cacheManager.recentChats.getAll()
        }
    }

    /**
     * 服务端无会话列表端点，初始会话的 lastMessage 为空。
     * 为每个会话异步拉取最新一条消息填充预览。
     */
    private fun fillDirectPreview(uid: String) {
        viewModelScope.launch {
            try {
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
                        _recentChats.value = cacheManager.recentChats.getAll()
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
    private fun syncChatListFromGroups(groups: List<Group>) {
        val existing = cacheManager.recentChats.getAll().associateBy { it.chatId }
        val existingIds = existing.keys
        var changed = false
        groups.forEach { group ->
            if (group.id.isEmpty()) return@forEach
            val resolvedAvatar = resolveAvatarUrl(group.avatarUrl)
            val current = existing[group.id]
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
            // 所有群会话都刷新最新预览（不限于新会话），修复"会话列表最新一条不新"
            fillGroupPreview(group.id, group.name)
        }
        if (changed) {
            _recentChats.value = cacheManager.recentChats.getAll()
        }
    }

    /**
     * 异步拉取群会话最新一条消息作为预览。
     */
    private fun fillGroupPreview(gid: String, name: String) {
        viewModelScope.launch {
            try {
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
                        _recentChats.value = cacheManager.recentChats.getAll()
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
                        syncChatListFromGroups(list)
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
        _recentChats.value = cacheManager.recentChats.getAll()
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
}
