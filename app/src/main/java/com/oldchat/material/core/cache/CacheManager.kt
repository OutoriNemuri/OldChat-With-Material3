package com.oldchat.material.core.cache

import android.content.Context
import coil.imageLoader
import com.google.gson.Gson
import com.oldchat.material.core.model.Message
import com.oldchat.material.core.model.GroupMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Central cache manager that owns all cache components.
 * Mirrors the caching layer from original client §12.
 *
 * Components:
 * - PreferencesManager: DataStore-based app preferences
 * - MessageHistoryCache: SP-based message persistence (last 200)
 * - RecentChatCache: thread-safe in-memory recent chat list
 * - FriendCache: thread-safe in-memory friend cache
 * - GroupCache: thread-safe in-memory group cache
 */
class CacheManager(context: Context) {

    val gson: Gson = Gson()

    private val cacheScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Preferences (DataStore)
    val preferences: PreferencesManager = PreferencesManager(context)

    // Message history persistence (§12.2)
    val messageHistory: MessageHistoryCache = MessageHistoryCache(context, gson)

    // Recent chat list (§12.3)
    val recentChats: RecentChatCache = RecentChatCache(context, gson)

    // Friend cache (§12.4)
    val friends: FriendCache = FriendCache(context, gson)

    // Group cache (§12.4)
    val groups: GroupCache = GroupCache(context, gson)

    // 页面数据 JSON 缓存（非聊天列表页：公开法庭、音乐广场等）
    val pageCache: PageCache = PageCache(context)

    // 我的表情（本地持久化，磁贴式）
    val emojiStore: EmojiStore = EmojiStore(context)

    // ---- Message History Convenience (typed, not JSON string) ----

    /**
     * Save direct messages for a conversation.
     */
    fun saveDirectMessages(uid: String, messages: List<Message>) {
        messageHistory.saveDirectMessages(uid, messages)
    }

    /**
     * Load direct messages for a conversation.
     */
    fun loadDirectMessages(uid: String): List<Message> {
        return messageHistory.loadDirectMessages(uid)
    }

    /**
     * Save group messages for a group.
     */
    fun saveGroupMessages(groupId: String, messages: List<GroupMessage>) {
        messageHistory.saveGroupMessages(groupId, messages)
    }

    /**
     * Load group messages for a group.
     */
    fun loadGroupMessages(groupId: String): List<GroupMessage> {
        return messageHistory.loadGroupMessages(groupId)
    }

    // ---- Profile Cache ----

    /**
     * Save profile cache JSON to DataStore (fire-and-forget).
     * The suspend function is launched on a background scope.
     */
    fun saveProfileCache(json: String) {
        cacheScope.launch {
            preferences.setProfileCacheJson(json)
        }
    }

    // ---- Lifecycle ----

    /**
     * Clear all caches. Called on account UID change.
     */
    /**
     * ALIGN-18：切换账号时清理「与账号强相关」的本地数据。
     * 注意：不清服务器地址等设备级配置。
     */
    fun clearAccountScoped() {
        messageHistory.clearAll()
        recentChats.clearAll()
        friends.clear()
        groups.clear()
        pageCache.clearAll()
    }

    fun clearAll() {
        messageHistory.clearAll()
        recentChats.clearAll()
        friends.clear()
        groups.clear()
        pageCache.clearAll()
        emojiStore.clearAll()
    }

    /**
     * Load all disk caches into memory on app start.
     */
    fun loadAllFromDisk() {
        recentChats.loadFromDisk()
        friends.loadFromDisk()
        groups.loadFromDisk()
    }

    /**
     * Destroy all cache resources.
     */
    fun destroy() {
        cacheScope.cancel()
        messageHistory.destroy()
        recentChats.destroy()
        friends.destroy()
        groups.destroy()
    }

    // ---- 缓存占用统计与清理（设置页"管理缓存"） ----

    /**
     * 缓存分组，用于设置页展示各类型缓存占用。
     */
    data class CacheGroup(
        val name: String,
        val bytes: Long
    )

    /**
     * 细分缓存条目：缓存管理子页面用（比 [CacheGroup] 更精确，带可清理标记与条数）。
     */
    data class CacheSection(
        val key: String,
        val title: String,
        val description: String,
        val bytes: Long,
        val itemCount: Int,
        /** false = 不建议清理（例如登录态） */
        val clearable: Boolean = true
    )

    /** 单个会话的消息缓存（缓存管理子页面里按会话逐个清理） */
    data class ConversationCache(
        val key: String,
        val chatId: String,
        val type: String,       // direct / group
        val title: String,
        val messages: Int,
        val bytes: Long
    )

    /**
     * 细分缓存清单（缓存管理子页面的数据源）。
     * 每项都可单独清理，消息历史还会在界面里展开成「按会话」列表。
     */
    fun listSections(context: Context): List<CacheSection> {
        val spDir = File(context.applicationInfo.dataDir, "shared_prefs")
        val sections = mutableListOf<CacheSection>()

        val historyEntries = messageHistory.entries()
        sections += CacheSection(
            key = "messages",
            title = "消息历史",
            description = "每个会话最近若干条消息（本地明文缓存）",
            bytes = historyEntries.sumOf { it.bytes },
            itemCount = historyEntries.sumOf { it.messages }
        )
        sections += CacheSection(
            key = "recent_chats",
            title = "会话列表",
            description = "会话预览、未读计数、置顶/免打扰状态",
            bytes = fileSizeOf(spDir, "recent_chat_cache.xml"),
            itemCount = recentChats.getAll().size
        )
        sections += CacheSection(
            key = "friends_groups",
            title = "好友 / 群",
            description = "通讯录与群资料（清除后下次进入会重新拉取）",
            bytes = fileSizeOf(spDir, "friend_cache.xml") + fileSizeOf(spDir, "group_cache.xml"),
            itemCount = friends.getAll().size + groups.getAll().size
        )
        sections += CacheSection(
            key = "images",
            title = "图片缓存",
            description = "Coil 磁盘缓存（图片、头像、表情）",
            bytes = dirSizeRecursive(File(context.cacheDir, "image_cache")),
            itemCount = -1
        )
        sections += CacheSection(
            key = "voice",
            title = "语音 / 临时文件",
            description = "录音与上传前临时文件",
            bytes = voiceTempBytes(context),
            itemCount = -1
        )
        sections += CacheSection(
            key = "page",
            title = "页面数据",
            description = "公开法庭 / 音乐广场等页面的 JSON 缓存",
            bytes = fileSizeOf(spDir, "page_json_cache.xml"),
            itemCount = -1
        )
        sections += CacheSection(
            key = "emoji",
            title = "我的表情",
            description = "本地表情贴纸",
            bytes = fileSizeOf(spDir, "emoji_store.xml"),
            itemCount = -1
        )
        return sections
    }

    /** 清空单个细分缓存 */
    fun clearSection(context: Context, key: String) {
        when (key) {
            "messages" -> messageHistory.clearAll()
            "recent_chats" -> recentChats.clearAll()
            "friends_groups" -> {
                friends.clear()
                groups.clear()
            }
            "images" -> deleteDirRecursive(File(context.cacheDir, "image_cache"))
            "voice" -> {
                // cacheDir 本身就是 File；原来是 File(context.cacheDir)（把 File 当路径字符串传，编译不过）
                context.cacheDir.listFiles()
                    ?.filter { it.isFile && (it.name.startsWith("voice_") || it.name.endsWith(".tmp")) }
                    ?.forEach { it.delete() }
            }
            "page" -> pageCache.clearAll()
            "emoji" -> emojiStore.clearAll()
        }
    }

    /** 按会话列出消息缓存（用于「更精准」的清理） */
    fun listConversationCaches(): List<ConversationCache> =
        messageHistory.entries().map { entry ->
            val id = entry.key.removePrefix("direct_").removePrefix("group_")
            val type = if (entry.key.startsWith("group_")) "group" else "direct"
            // 注意括号：`a ?: if (...) b else c ?: d` 会把 `?: d` 只绑到 else 分支，
            // 导致 then 分支仍是可空类型 → 必须显式加括号
            val title = recentChats.getByChatId(id)?.name
                ?: (if (type == "group") groups.get(id)?.name else friends.get(id)?.nickname)
                ?: id
            ConversationCache(
                key = entry.key,
                chatId = id,
                type = type,
                title = title,
                messages = entry.messages,
                bytes = entry.bytes
            )
        }.sortedByDescending { it.bytes }

    /** 清空某个会话的消息缓存 */
    fun clearConversation(key: String) = messageHistory.clearByKey(key)

    /**
     * 计算全部缓存占用分组。既包含 SharedPreferences 内的业务缓存文件，
     * 也包含 Coil 图片磁盘缓存与录音/临时文件。
     *
     * @param context 用于定位 shared_prefs 与 cacheDir 的 context（传 Application 级以稳妥）。
     */
    fun buildCacheGroups(context: Context): List<CacheGroup> {
        val groups = mutableListOf<CacheGroup>()

        // 1. SharedPreferences 业务缓存（消息/会话/好友/群组/页面）
        val spDir = File(context.applicationInfo.dataDir, "shared_prefs").also { file ->
            if (!file.exists()) file.mkdirs()
        }
        val spFileNames = listOf(
            "message_history_cache" to "消息缓存",
            "recent_chat_cache" to "会话缓存",
            "friend_cache" to "好友缓存",
            "group_cache" to "群组缓存",
            "page_json_cache" to "页面缓存"
        )
        spFileNames.forEach { (name, label) ->
            val size = fileSizeOf(spDir, "$name.xml")
            if (size > 0) groups.add(CacheGroup(label, size))
        }

        // 2. Coil 图片磁盘缓存
        val imageCacheDir = File(context.cacheDir, "image_cache")
        val imageBytes = dirSizeRecursive(imageCacheDir)
        if (imageBytes > 0) groups.add(CacheGroup("图片缓存", imageBytes))

        // 3. 语音录音临时文件（voice_*.aac）
        val voiceBytes = voiceTempBytes(context)
        if (voiceBytes > 0) groups.add(CacheGroup("语音临时文件", voiceBytes))

        return groups
    }

    /**
     * 全部缓存总占用（字节）。
     */
    fun totalCacheSize(context: Context): Long {
        return buildCacheGroups(context).sumOf { it.bytes }
    }

    /**
     * 一键清理所有缓存：
     * 1. 清空业务缓存（消息/会话/好友/群组/页面）的内存态与 SharedPreferences 文件；
     * 2. 清空 Coil 图片磁盘缓存目录；
     * 3. 删除录音临时文件。
     * 注意：仅清理可再生的缓存/临时数据，不影响登录态与用户配置（DataStore）。
     */
    fun clearAppCache(context: Context) {
        // 业务缓存（内存 + disk SharedPreferences）
        messageHistory.clearAll()
        recentChats.clearAll()
        friends.clear()
        groups.clear()
        pageCache.clearAll()

        // Coil 图片磁盘缓存
        val imageCacheDir = File(context.cacheDir, "image_cache")
        deleteDirRecursive(imageCacheDir)

        // 语音临时文件
        deleteVoiceTempFiles(context)

        // 尝试让 Coil 的 ImageLoader 也执行磁盘清理（可选，目录删除通常已足够）
        try {
            context.imageLoader.diskCache?.clear()
        } catch (_: Exception) {
            // 忽略：无 Coil 实例或目录已删除
        }
    }

    private fun fileSizeOf(dir: File, fileName: String): Long {
        val f = File(dir, fileName)
        return if (f.exists() && f.isFile) f.length() else 0L
    }

    private fun dirSizeRecursive(dir: File): Long {
        if (!dir.exists() || !dir.isDirectory) return 0L
        var total = 0L
        dir.listFiles()?.forEach { f ->
            total += if (f.isDirectory) dirSizeRecursive(f) else f.length()
        }
        return total
    }

    private fun deleteDirRecursive(dir: File) {
        if (!dir.exists()) return
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) deleteDirRecursive(f) else f.delete()
        }
        dir.delete()
    }

    private fun voiceTempBytes(context: Context): Long {
        val dir = context.cacheDir
        var total = 0L
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.startsWith("voice_")) total += f.length()
        }
        return total
    }

    private fun deleteVoiceTempFiles(context: Context) {
        val dir = context.cacheDir
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.startsWith("voice_")) f.delete()
        }
    }
}


