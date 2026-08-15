package com.oldchat.material.core.cache

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.oldchat.material.core.model.User
import com.oldchat.material.core.model.Group
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory cache for friends with background Gson persistence.
 * Mirrors FriendCache from client-guide.md §12.4.
 *
 * Features:
 * - ConcurrentHashMap for thread-safe reads
 * - Background coalesced Gson persistence
 * - Debounced saves (220ms)
 */
class FriendCache(
    private val context: Context,
    private val gson: Gson
) {
    private val friends = ConcurrentHashMap<String, User>()

    private val prefs: SharedPreferences =
        context.getSharedPreferences("friend_cache", Context.MODE_PRIVATE)

    private val saveScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val saveMutex = Mutex()
    private var saveJob: Job? = null

    fun getAll(): List<User> = friends.values.toList()

    fun get(uid: String): User? = friends[uid]

    fun put(user: User) {
        friends[user.uid] = user
        scheduleSave()
    }

    fun putAll(users: List<User>) {
        users.forEach { friends[it.uid] = it }
        scheduleSave()
    }

    fun replaceAll(users: List<User>) {
        friends.clear()
        users.forEach { friends[it.uid] = it }
        scheduleSave()
    }

    fun remove(uid: String) {
        friends.remove(uid)
        scheduleSave()
    }

    fun updatePresence(uid: String, status: String) {
        friends[uid]?.let {
            friends[uid] = it.copy(presenceStatus = status)
            scheduleSave()
        }
    }

    fun clear() {
        friends.clear()
        scheduleSave()
    }

    fun loadFromDisk() {
        val json = prefs.getString(KEY_FRIENDS, null) ?: return
        try {
            val type = object : TypeToken<List<User>>() {}.type
            val saved: List<User> = gson.fromJson(json, type)
            friends.clear()
            saved.forEach { friends[it.uid] = it }
        } catch (_: Exception) { /* corrupted */ }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = saveScope.launch {
            delay(220)
            saveMutex.withLock {
                val json = gson.toJson(friends.values.toList())
                prefs.edit().putString(KEY_FRIENDS, json).apply()
            }
        }
    }

    fun destroy() { saveScope.cancel() }

    companion object {
        private const val KEY_FRIENDS = "friends"
    }
}

/**
 * Thread-safe in-memory cache for groups with background Gson persistence.
 * Mirrors GroupCache from client-guide.md §12.4.
 */
class GroupCache(
    private val context: Context,
    private val gson: Gson
) {
    private val groups = ConcurrentHashMap<String, Group>()

    private val prefs: SharedPreferences =
        context.getSharedPreferences("group_cache", Context.MODE_PRIVATE)

    private val saveScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val saveMutex = Mutex()
    private var saveJob: Job? = null

    fun getAll(): List<Group> = groups.values.toList()

    fun get(groupId: String): Group? = groups[groupId]

    fun put(group: Group) {
        groups[group.id] = group
        scheduleSave()
    }

    fun putAll(newGroups: List<Group>) {
        newGroups.forEach { groups[it.id] = it }
        scheduleSave()
    }

    fun replaceAll(newGroups: List<Group>) {
        groups.clear()
        newGroups.forEach { groups[it.id] = it }
        scheduleSave()
    }

    fun remove(groupId: String) {
        groups.remove(groupId)
        scheduleSave()
    }

    fun clear() {
        groups.clear()
        scheduleSave()
    }

    fun loadFromDisk() {
        val json = prefs.getString(KEY_GROUPS, null) ?: return
        try {
            val type = object : TypeToken<List<Group>>() {}.type
            val saved: List<Group> = gson.fromJson(json, type)
            groups.clear()
            saved.forEach { groups[it.id] = it }
        } catch (_: Exception) { /* corrupted */ }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = saveScope.launch {
            delay(220)
            saveMutex.withLock {
                val json = gson.toJson(groups.values.toList())
                prefs.edit().putString(KEY_GROUPS, json).apply()
            }
        }
    }

    fun destroy() { saveScope.cancel() }

    companion object {
        private const val KEY_GROUPS = "groups"
    }
}
