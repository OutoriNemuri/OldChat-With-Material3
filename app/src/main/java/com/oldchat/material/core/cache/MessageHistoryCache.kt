package com.oldchat.material.core.cache

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.oldchat.material.core.model.Message
import com.oldchat.material.core.model.GroupMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * Persists message history to SharedPreferences (last 200 messages per conversation).
 * Mirrors MessageHistoryCache from client-guide.md §12.2.
 *
 * Key behaviors:
 * - Max 200 tail messages retained
 * - localPending=true messages NOT persisted
 * - Async serial save (single-threaded executor)
 * - generation checksum prevents stale saves after clearAll
 * - Pause/resume save during scrolling
 * - Reads clean up stale pending messages (compat with old versions)
 */
class MessageHistoryCache(
    private val context: Context,
    private val gson: Gson
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("message_history_cache", Context.MODE_PRIVATE)

    // Single-thread executor for serial saves
    private val saveScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val saveMutex = Mutex()

    // Generation counter for staleness detection
    private val generation = AtomicInteger(0)

    // Pause state: during scroll, pause saves; resume after scroll stops
    @Volatile
    private var savePaused = false

    // BUG-17：暂停期间按「key」暂存最新快照。原实现只留一个 pendingSnapshot 且
    // resumeSave() 是个空壳（既不写盘也不清状态）——暂停期间的消息更新会被直接丢掉。
    private val pendingSaves = java.util.concurrent.ConcurrentHashMap<String, String>()

    // BUG-17：写盘合并。注释写着 coalesced，原实现却是每次调用都排队写一次
    // （消息密集时 SharedPreferences 序列化整表 N 次/秒）。这里按 key 去抖。
    private val flushJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    companion object {
        const val MAX_MESSAGES = 200

        /** 写盘去抖窗口（毫秒） */
        private const val SAVE_DEBOUNCE_MS = 500L
    }

    // ---- Direct Messages ----

    /**
     * Save direct message history (async, coalesced).
     * Filters out pending messages. Keeps last 200.
     */
    fun saveDirectMessages(uid: String, messages: List<Message>) {
        val snapshot = messages
            .filter { !it.isLocalPending }
            .takeLast(MAX_MESSAGES)
        if (snapshot.isEmpty()) return
        scheduleWrite("direct_$uid", gson.toJson(snapshot))
    }

    /** BUG-17：统一的去抖写盘（暂停时只暂存，恢复时一次性落盘）。 */
    private fun scheduleWrite(key: String, json: String) {
        if (savePaused) {
            pendingSaves[key] = json
            return
        }
        flushJobs.remove(key)?.cancel()
        val gen = generation.get()
        flushJobs[key] = saveScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            saveMutex.withLock {
                if (generation.get() != gen) return@withLock
                prefs.edit().putString(key, json).apply()
            }
            flushJobs.remove(key)
        }
    }

    /**
     * Load direct message history.
     * Cleans up any stale pending messages from old cache versions.
     */
    fun loadDirectMessages(uid: String): List<Message> {
        val json = prefs.getString("direct_$uid", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<Message>>() {}.type
            val messages: List<Message> = gson.fromJson(json, type)
            // Remove stale pending messages
            messages.filter { !it.isLocalPending }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Clear direct message history for a specific UID.
     */
    fun clearDirectMessages(uid: String) {
        prefs.edit().remove("direct_$uid").apply()
    }

    // ---- Group Messages ----

    /**
     * Save group message history (async).
     */
    fun saveGroupMessages(groupId: String, messages: List<GroupMessage>) {
        val snapshot = messages
            .filter { !it.isLocalPending }
            .takeLast(MAX_MESSAGES)
        if (snapshot.isEmpty()) return
        scheduleWrite("group_$groupId", gson.toJson(snapshot))
    }

    /**
     * Load group message history.
     */
    fun loadGroupMessages(groupId: String): List<GroupMessage> {
        val json = prefs.getString("group_$groupId", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<GroupMessage>>() {}.type
            val messages: List<GroupMessage> = gson.fromJson(json, type)
            messages.filter { !it.isLocalPending }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Clear group message history for a specific group.
     */
    fun clearGroupMessages(groupId: String) {
        prefs.edit().remove("group_$groupId").apply()
    }

    // ---- Lifecycle ----

    /**
     * Pause saves during scrolling.
     * Saves are held; resumeSave will flush the latest snapshot.
     */
    fun pauseSave() {
        savePaused = true
        pendingSaves.clear()
    }

    /**
     * Resume saves after scrolling stops.
     * Flushes the most recent pending snapshot if any.
     */
    fun resumeSave() {
        savePaused = false
        // BUG-17：把暂停期间攒下的最新快照真正落盘（原实现是空函数体，数据直接丢）
        if (pendingSaves.isEmpty()) return
        val snapshot = HashMap(pendingSaves)
        pendingSaves.clear()
        val gen = generation.get()
        saveScope.launch {
            saveMutex.withLock {
                if (generation.get() != gen) return@withLock
                val editor = prefs.edit()
                snapshot.forEach { (key, json) -> editor.putString(key, json) }
                editor.apply()
            }
        }
    }

    /**
     * Clear all message history and invalidate in-flight saves.
     * Called on account UID change.
     */
    fun clearAll() {
        generation.incrementAndGet()
        pendingSaves.clear()
        flushJobs.values.forEach { it.cancel() }
        flushJobs.clear()
        prefs.edit().clear().apply()
    }

    /**
     * Clear history for a specific set of keys.
     */
    fun clearForUid(uid: String) {
        clearDirectMessages(uid)
    }

    fun destroy() {
        saveScope.cancel()
    }
}
