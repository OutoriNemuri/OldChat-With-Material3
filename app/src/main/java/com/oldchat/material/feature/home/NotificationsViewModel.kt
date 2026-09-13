package com.oldchat.material.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.oldchat.material.OldChatApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 系统通知数据模型（GET /notifications，client-guide §18.9）。
 * 字段实测（snake_case）：id / title / body / important / created_at（毫秒）。
 */
data class SystemNotification(
    val id: String,
    val title: String,
    val body: String,
    val important: Boolean,
    val createdAt: Long  // 毫秒
)

/**
 * 系统通知 ViewModel：拉取通知列表 + 判断启动时要弹的重要通知。
 */
class NotificationsViewModel : ViewModel() {

    private val app get() = OldChatApplication.instance
    private val gson: Gson = app.gson

    private val _notifications = MutableStateFlow<List<SystemNotification>>(emptyList())
    val notifications: StateFlow<List<SystemNotification>> = _notifications.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** 启动时需弹出展示的重要通知（null 表示无需弹）。 */
    private val _importantNotice = MutableStateFlow<SystemNotification?>(null)
    val importantNotice: StateFlow<SystemNotification?> = _importantNotice.asStateFlow()

    private var loaded = false

    // ALIGN-16：已读状态与红点。原来 lastReadNotificationId 只写不读，
    // 通知中心永远显示「未读」，用户点开也消不掉。
    private val _hasUnread = MutableStateFlow(false)
    val hasUnread: StateFlow<Boolean> = _hasUnread.asStateFlow()

    fun loadIfEmpty() {
        if (loaded) return
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = app.apiClient.get("/notifications")
                result.fold(
                    onSuccess = { json -> parse(json) },
                    onFailure = { /* 忽略，网络失败不弹 */ }
                )
            } finally {
                _isLoading.value = false
                loaded = true
            }
        }
    }

    private fun parse(json: String) {
        val map = gson.fromJson(json, Map::class.java) as? Map<*, *> ?: return
        val list = map["notifications"] as? List<*> ?: return
        val items = list.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val id = m["id"]?.toString() ?: return@mapNotNull null
            SystemNotification(
                id = id,
                title = m["title"]?.toString() ?: "",
                body = m["body"]?.toString() ?: "",
                important = m["important"] as? Boolean ?: false,
                createdAt = (m["created_at"] as? Number)?.toLong() ?: 0L
            )
        }
        // 按时间倒序（最新在前）
        _notifications.value = items.sortedByDescending { it.createdAt }

        // ALIGN-16：红点 = 存在比「上次已读时间」更新的通知
        // （偏好项 lastReadNotificationId 的实际类型是 Long 时间戳，不是通知 id）
        viewModelScope.launch {
            val lastReadAt = app.cacheManager.preferences.lastReadNotificationId.first()
            _hasUnread.value = _notifications.value.any { it.createdAt > lastReadAt }
            resolveImportantNotice()
        }
    }

    /** 找到应弹出的最新重要通知。 */
    private suspend fun resolveImportantNotice() {
        val latestImportant = _notifications.value.firstOrNull { it.important } ?: run {
            _importantNotice.value = null
            return
        }
        val dismissedId = app.cacheManager.preferences.dismissedImportantNotificationId.first()
        _importantNotice.value = if (latestImportant.id != dismissedId) latestImportant else null
    }

    /** ALIGN-16：用户看完通知中心 → 记录最新一条 id，红点消失。 */
    fun markAllRead() {
        val newest = _notifications.value.firstOrNull() ?: return
        viewModelScope.launch {
            app.cacheManager.preferences.setLastReadNotificationId(newest.createdAt)
            _hasUnread.value = false
        }
    }

    /** 用户勾选"不再提示"后，记录该条 important 通知 id。 */
    fun dismissImportantNotice(noticeId: String) {
        viewModelScope.launch {
            app.cacheManager.preferences.setDismissedImportantNotificationId(noticeId)
            _importantNotice.value = null
        }
    }
}
