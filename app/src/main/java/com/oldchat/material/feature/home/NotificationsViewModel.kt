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

        // 异步决定是否弹重要通知：最新一条 important 且未被"不再提示"
        viewModelScope.launch { resolveImportantNotice() }
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

    /** 用户勾选"不再提示"后，记录该条 important 通知 id。 */
    fun dismissImportantNotice(noticeId: String) {
        viewModelScope.launch {
            app.cacheManager.preferences.setDismissedImportantNotificationId(noticeId)
            _importantNotice.value = null
        }
    }
}
