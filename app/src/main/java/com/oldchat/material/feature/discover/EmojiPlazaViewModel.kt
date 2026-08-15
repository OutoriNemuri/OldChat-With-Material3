package com.oldchat.material.feature.discover

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.oldchat.material.OldChatApplication
import com.oldchat.material.core.cache.EmojiStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 表情广场 ViewModel（服务端 /emoji/plaza）。
 *
 * 实测契约（Python 验证）：
 * - 列表：GET /emoji/plaza?limit=&offset= → {items:[{id,name,media_url,cover_url,item_count,is_gif,size_bytes,created_at,owner_uid,owner_name,owner_title?,owner_avatar}], total, has_more}
 * - 搜索：GET /emoji/plaza?q=<keyword>（注意是 q，不是 keyword！keyword 会被忽略返回全量）
 * - 我的上传：GET /emoji/plaza/mine → {items:[...]}
 * - 上传：POST /emoji/plaza/upload (multipart, name=标题 + file=文件)
 * - 保存：POST /emoji/plaza/save body {"item_id":"..."}
 * - 删除：POST /emoji/plaza/delete body {"item_id":"..."}
 */
class EmojiPlazaViewModel : ViewModel() {

    private val app = OldChatApplication.instance
    private val apiClient = app.apiClient
    private val gson: Gson = app.gson
    private val emojiStore: EmojiStore = app.cacheManager.emojiStore

    data class EmojiItem(
        val id: String = "",
        val name: String = "",
        val mediaUrl: String = "",
        val coverUrl: String = "",
        val itemCount: Int = 0,
        val isGif: Boolean = false,
        val sizeBytes: Long = 0,
        val createdAt: Long = 0,
        val ownerUid: String = "",
        val ownerName: String = "",
        val ownerTitle: String? = null,
        val ownerAvatar: String = ""
    )

    /** 当前我的 uid（用于判断"自己上传的"）。 */
    private val myUid: String = app.authManager.myUid ?: ""

    // ---- 状态 ----
    private val _items = MutableStateFlow<List<EmojiItem>>(emptyList())
    val items: StateFlow<List<EmojiItem>> = _items.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _total = MutableStateFlow(0)
    val total: StateFlow<Int> = _total.asStateFlow()

    private val _page = MutableStateFlow(1)
    val page: StateFlow<Int> = _page.asStateFlow()

    // 0 = 广场；1 = 我上传的
    private val _tab = MutableStateFlow(0)
    val tab: StateFlow<Int> = _tab.asStateFlow()

    private val _keyword = MutableStateFlow("")
    val keyword: StateFlow<String> = _keyword.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _savedIds = MutableStateFlow<Set<String>>(emptySet())
    val savedIds: StateFlow<Set<String>> = _savedIds.asStateFlow()

    private val PAGE_SIZE = 50

    init {
        // 初始化已保存表情集合
        refreshSavedIds()
        load()
    }

    private fun refreshSavedIds() {
        _savedIds.value = emojiStore.getAll().map { it.id }.toSet()
    }

    /** 切换到广场/我的上传，并重置分页后加载。 */
    fun switchTab(newTab: Int) {
        if (_tab.value == newTab) return
        _tab.value = newTab
        _page.value = 1
        _items.value = emptyList()
        load()
    }

    /** 搜索（广场 tab 下按 q 搜索）。 */
    fun search(q: String) {
        _keyword.value = q
        _page.value = 1
        _items.value = emptyList()
        load()
    }

    /** 加载当前页。 */
    fun load() {
        _isLoading.value = true
        viewModelScope.launch {
            try {
                if (_tab.value == 0) {
                    // 广场
                    val params = mutableMapOf(
                        "limit" to PAGE_SIZE.toString(),
                        "offset" to ((_page.value - 1) * PAGE_SIZE).toString()
                    )
                    // 搜索用 q（实测 keyword 无效）
                    if (_keyword.value.isNotBlank()) {
                        params["q"] = _keyword.value
                    }
                    val result = apiClient.get("/emoji/plaza", params)
                    result.fold(
                        onSuccess = { json -> parsePlaza(json) },
                        onFailure = { _message.value = it.message ?: "加载失败" }
                    )
                } else {
                    // 我的上传
                    val result = apiClient.get("/emoji/plaza/mine")
                    result.fold(
                        onSuccess = { json -> parseMine(json) },
                        onFailure = { _message.value = it.message ?: "加载失败" }
                    )
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun parsePlaza(json: String) {
        val root = gson.fromJson(json, Map::class.java) as? Map<*, *> ?: return
        val list = (root["items"] as? List<*>)?.mapNotNull { parseItem(it) } ?: emptyList()
        _items.value = list
        _total.value = (root["total"] as? Number)?.toInt() ?: list.size
    }

    private fun parseMine(json: String) {
        val root = gson.fromJson(json, Map::class.java) as? Map<*, *> ?: return
        val list = (root["items"] as? List<*>)?.mapNotNull { parseItem(it) } ?: emptyList()
        _items.value = list
        _total.value = (root["total"] as? Number)?.toInt() ?: list.size
    }

    private fun parseItem(obj: Any?): EmojiItem? {
        val m = obj as? Map<*, *> ?: return null
        return EmojiItem(
            id = m["id"]?.toString() ?: "",
            name = m["name"]?.toString() ?: "",
            mediaUrl = m["media_url"]?.toString() ?: "",
            coverUrl = m["cover_url"]?.toString() ?: m["media_url"]?.toString() ?: "",
            itemCount = (m["item_count"] as? Number)?.toInt() ?: 1,
            isGif = (m["is_gif"] as? Boolean) ?: false,
            sizeBytes = (m["size_bytes"] as? Number)?.toLong() ?: 0L,
            createdAt = (m["created_at"] as? Number)?.toLong() ?: 0L,
            ownerUid = m["owner_uid"]?.toString() ?: "",
            ownerName = m["owner_name"]?.toString() ?: "",
            ownerTitle = m["owner_title"]?.toString(),
            ownerAvatar = m["owner_avatar"]?.toString() ?: ""
        )
    }

    /** 下一页。 */
    fun nextPage() {
        _page.value += 1
        load()
    }

    /** 上一页。 */
    fun prevPage() {
        if (_page.value <= 1) return
        _page.value -= 1
        load()
    }

    /** 保存到「我的表情」（本地持久化）。 */
    fun saveToMine(item: EmojiItem, onResult: (Boolean) -> Unit = {}) {
        // 同步到服务器「保存」接口（收藏），并本地持久化
        viewModelScope.launch {
            try {
                val body = gson.toJson(mapOf("item_id" to item.id))
                apiClient.post("/emoji/plaza/save", body)
            } catch (_: Exception) {
            }
            val added = emojiStore.add(
                EmojiStore.StoredEmoji(
                    id = item.id,
                    name = item.name,
                    mediaUrl = item.mediaUrl,
                    isGif = item.isGif
                )
            )
            refreshSavedIds()
            onResult(added)
        }
    }

    /** 上传表情到广场（multipart，name=标题 + file=文件）。 */
    fun upload(name: String, uri: Uri, context: Context, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            try {
                val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null) {
                    onResult(false, "无法读取文件")
                    return@launch
                }
                val parts = listOf(
                    com.oldchat.material.core.network.FormPartData.TextPart("name", name),
                    com.oldchat.material.core.network.FormPartData.FilePart("file", bytes, "emoji_upload", mimeType)
                )
                val result = apiClient.postMultipart("/emoji/plaza/upload", parts)
                result.fold(
                    onSuccess = {
                        onResult(true, null)
                        // 上传成功后刷新列表
                        _page.value = 1
                        load()
                    },
                    onFailure = { e -> onResult(false, e.message ?: "上传失败") }
                )
            } catch (e: Exception) {
                onResult(false, e.message ?: "上传失败")
            }
        }
    }

    /** 删除自己上传的表情（body {item_id}）。 */
    fun delete(item: EmojiItem, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            try {
                val body = gson.toJson(mapOf("item_id" to item.id))
                val result = apiClient.post("/emoji/plaza/delete", body)
                result.fold(
                    onSuccess = {
                        // 同步移除本地保存的该表情
                        emojiStore.remove(item.id)
                        refreshSavedIds()
                        onResult(true)
                        load()
                    },
                    onFailure = { onResult(false) }
                )
            } catch (_: Exception) {
                onResult(false)
            }
        }
    }

    /** 判断某个 item 是否是当前用户上传的（据此显示红色删除按钮）。 */
    fun isOwn(item: EmojiItem): Boolean {
        return item.ownerUid.isNotEmpty() && item.ownerUid == myUid
    }
}