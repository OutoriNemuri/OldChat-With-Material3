package com.oldchat.material.core.cache

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.oldchat.material.core.model.RecentChatItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe in-memory cache for recent chat list with background persistence.
 * Mirrors RecentChatCache + GroupRecentChatCache from client-guide.md §12.3.
 *
 * Features:
 * - Thread-safe reads via CopyOnWriteArrayList
 * - Deep copy on read to prevent mutation
 * - Background coalesced Gson persistence
 * - Debounced saves (220ms)
 */
class RecentChatCache(
    private val context: Context,
    private val gson: Gson
) {
    // Thread-safe in-memory list
    private val items = CopyOnWriteArrayList<RecentChatItem>()

    // 对外暴露的列表快照流：**任何**写操作（upsert/remove/incrementUnread/loadFromDisk…）
    // 都会推一次，UI 直接订阅它即可，不再依赖调用方记得去刷新 —— 这是
    // 「收到消息后首页预览/未读气泡不更新」的根因修法（之前 UI 只在被显式通知时才更新）。
    private val _itemsFlow = MutableStateFlow<List<RecentChatItem>>(emptyList())
    val itemsFlow: StateFlow<List<RecentChatItem>> = _itemsFlow.asStateFlow()

    private fun publish() {
        _itemsFlow.value = items.toList()
    }

    // Persistence
    private val prefs: SharedPreferences =
        context.getSharedPreferences("recent_chat_cache", Context.MODE_PRIVATE)

    private val saveScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val saveMutex = Mutex()
    private var saveJob: Job? = null

    /**
     * Get a deep copy of the current list (thread-safe).
     */
    fun getAll(): List<RecentChatItem> {
        return items.toList() // CopyOnWriteArrayList.toList() creates a snapshot
    }

    /**
     * Get a single item by chatId.
     */
    fun getByChatId(chatId: String): RecentChatItem? {
        return items.find { it.chatId == chatId }
    }

    /**
     * Replace the entire list with a new list.
     */
    fun replaceAll(newItems: List<RecentChatItem>) {
        items.clear()
        items.addAll(newItems)
        publish()
        scheduleSave()
    }

    /**
     * Update or insert a single item.
     */
    fun upsert(item: RecentChatItem) {
        val index = items.indexOfFirst { it.chatId == item.chatId }
        if (index >= 0) {
            items[index] = item
        } else {
            items.add(0, item) // Insert at top
        }
        publish()
        scheduleSave()
    }

    /**
     * Remove an item by chatId.
     */
    fun remove(chatId: String) {
        items.removeAll { it.chatId == chatId }
        publish()
        scheduleSave()
    }

    /**
     * Update presence status for a user.
     */
    fun updatePresence(uid: String, status: String) {
        val index = items.indexOfFirst { it.type == "direct" && it.chatId == uid }
        if (index >= 0) {
            items[index] = items[index].copy(presenceStatus = status)
            publish()
            scheduleSave()
        }
    }

    /**
     * Increment unread count for a chat.
     */
    fun incrementUnread(chatId: String) {
        val index = items.indexOfFirst { it.chatId == chatId }
        if (index >= 0) {
            val item = items[index]
            items[index] = item.copy(unreadCount = item.unreadCount + 1)
            publish()
            scheduleSave()
        }
    }

    /**
     * Clear unread for a chat.
     */
    fun clearUnread(chatId: String) {
        val index = items.indexOfFirst { it.chatId == chatId }
        if (index >= 0) {
            items[index] = items[index].copy(unreadCount = 0)
            publish()
            scheduleSave()
        }
    }

    /**
     * Clear all items.
     */
    fun clearAll() {
        items.clear()
        publish()
        scheduleSave()
    }

    /**
     * Load persisted state from SharedPreferences.
     */
    fun loadFromDisk() {
        val json = prefs.getString(KEY_RECENT_CHATS, null) ?: return
        try {
            val type = object : TypeToken<List<RecentChatItem>>() {}.type
            val saved: List<RecentChatItem> = gson.fromJson(json, type)
            items.clear()
            items.addAll(saved)
            publish()
        } catch (_: Exception) {
            // Corrupted cache, ignore
        }
    }

    /**
     * Save to disk with 220ms debounce (coalescing).
     */
    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = saveScope.launch {
            delay(220) // Debounce
            saveMutex.withLock {
                val json = gson.toJson(items.toList())
                prefs.edit().putString(KEY_RECENT_CHATS, json).apply()
            }
        }
    }

    /**
     * Force save immediately.
     */
    fun saveNow() {
        saveJob?.cancel()
        saveScope.launch {
            saveMutex.withLock {
                val json = gson.toJson(items.toList())
                prefs.edit().putString(KEY_RECENT_CHATS, json).apply()
            }
        }
    }

    fun destroy() {
        saveScope.cancel()
    }

    companion object {
        private const val KEY_RECENT_CHATS = "recent_chats"
    }
}
