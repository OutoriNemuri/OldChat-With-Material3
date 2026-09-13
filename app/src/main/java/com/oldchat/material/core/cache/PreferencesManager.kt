package com.oldchat.material.core.cache

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// Extension property for DataStore singleton
internal val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "oldchat_preferences")

/**
 * Centralized preferences manager using DataStore.
 * Replaces multiple SharedPreferences files from original client (§12.1):
 * - auth (handled by AuthManager using SharedPreferences for legacy compat)
 * - settings (server URL, night mode, DPI, home settings)
 * - notification (read state)
 * - oldchat_settings (general settings)
 * - profile_cache (user profile JSON)
 * - group_sync_watermarks (per-group sync watermark)
 *
 * Uses DataStore for non-auth preferences to leverage Flow-based reactive reads.
 */
class PreferencesManager(internal val context: Context) {

    // Expose the DataStore instance for DpiManager and other consumers
    val dataStore: DataStore<Preferences> = context.dataStore

    // ---- Server Settings ----



    // ---- Appearance ----

    val isDarkMode: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_DARK_MODE] ?: false
    }

    suspend fun setDarkMode(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_DARK_MODE] = enabled }
    }

    val useDynamicColor: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_DYNAMIC_COLOR] ?: true
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_DYNAMIC_COLOR] = enabled }
    }

    // ---- Home Settings ----

    val showNewsSection: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SHOW_NEWS] ?: true
    }

    suspend fun setShowNews(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_SHOW_NEWS] = enabled }
    }

    val showOldViewEntry: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SHOW_OLDVIEW] ?: true
    }

    suspend fun setShowOldView(show: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_SHOW_OLDVIEW] = show }
    }

    val showPublicCourtEntry: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SHOW_PUBLIC_COURT] ?: true
    }

    suspend fun setShowPublicCourt(show: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_SHOW_PUBLIC_COURT] = show }
    }

    // ---- Notification ----

    val notificationsEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_NOTIFICATIONS_ENABLED] ?: true
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_NOTIFICATIONS_ENABLED] = enabled }
    }

    val notificationSound: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_NOTIFICATION_SOUND] ?: true
    }

    suspend fun setNotificationSound(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_NOTIFICATION_SOUND] = enabled }
    }

    val notificationVibration: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_NOTIFICATION_VIBRATION] ?: true
    }

    suspend fun setNotificationVibration(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_NOTIFICATION_VIBRATION] = enabled }
    }

    // ---- Notification Read Store ----

    val lastReadNotificationId: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_READ_NOTIFICATION_ID] ?: 0L
    }

    suspend fun setLastReadNotificationId(id: Long) {
        context.dataStore.edit { prefs -> prefs[KEY_LAST_READ_NOTIFICATION_ID] = id }
    }

    // ---- Important Notice Dismissed Store ----

    /** 用户勾选"不再提示"的那条 important 通知 id（空串 = 未忽略）。 */
    val dismissedImportantNotificationId: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DISMISSED_IMPORTANT_NOTIFICATION_ID] ?: ""
    }

    suspend fun setDismissedImportantNotificationId(id: String) {
        context.dataStore.edit { prefs -> prefs[KEY_DISMISSED_IMPORTANT_NOTIFICATION_ID] = id }
    }

    // ---- Message Receive Mode ----

    val messageReceiveMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_MESSAGE_RECEIVE_MODE] ?: "ws_priority"
    }

    suspend fun setMessageReceiveMode(mode: String) {
        context.dataStore.edit { prefs -> prefs[KEY_MESSAGE_RECEIVE_MODE] = mode }
    }

    // ---- Group Sync Watermarks (§4.2) ----

    suspend fun getGroupSyncWatermark(groupId: String): Pair<Long, String> {
        val prefs = context.dataStore.data.first()
        val seq = prefs[longPreferencesKey("group_watermark_seq_$groupId")] ?: 0L
        val anchorId = prefs[stringPreferencesKey("group_watermark_anchor_$groupId")] ?: ""
        return Pair(seq, anchorId)
    }

    suspend fun saveGroupSyncWatermark(groupId: String, seq: Long, anchorMessageId: String) {
        context.dataStore.edit { prefs ->
            prefs[longPreferencesKey("group_watermark_seq_$groupId")] = seq
            prefs[stringPreferencesKey("group_watermark_anchor_$groupId")] = anchorMessageId
        }
    }

    suspend fun clearGroupSyncWatermark(groupId: String) {
        context.dataStore.edit { prefs ->
            prefs.remove(longPreferencesKey("group_watermark_seq_$groupId"))
            prefs.remove(stringPreferencesKey("group_watermark_anchor_$groupId"))
        }
    }

    // ---- Profile Cache ----

    val profileCacheJson: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_PROFILE_CACHE] ?: ""
    }

    suspend fun setProfileCacheJson(json: String) {
        context.dataStore.edit { prefs -> prefs[KEY_PROFILE_CACHE] = json }
    }

    // ---- Update Check ----

    suspend fun getLastUpdateCheck(): Long {
        return context.dataStore.data.first()[KEY_LAST_UPDATE_CHECK] ?: 0L
    }

    suspend fun setLastUpdateCheck(timestamp: Long) {
        context.dataStore.edit { prefs -> prefs[KEY_LAST_UPDATE_CHECK] = timestamp }
    }

    // ---- Keys ----

    companion object {
        // Appearance
        private val KEY_DARK_MODE = booleanPreferencesKey("dark_mode")
        private val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")

        // Server
        private val KEY_SERVER_BASE_URL = stringPreferencesKey("server_base_url")

        // Home
        private val KEY_SHOW_NEWS = booleanPreferencesKey("show_news")
        private val KEY_SHOW_OLDVIEW = booleanPreferencesKey("show_oldview")
        private val KEY_SHOW_PUBLIC_COURT = booleanPreferencesKey("show_public_court")

        // Notifications
        private val KEY_NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        private val KEY_NOTIFICATION_SOUND = booleanPreferencesKey("notification_sound")
        private val KEY_NOTIFICATION_VIBRATION = booleanPreferencesKey("notification_vibration")
        private val KEY_LAST_READ_NOTIFICATION_ID = longPreferencesKey("last_read_notification_id")
        private val KEY_DISMISSED_IMPORTANT_NOTIFICATION_ID = stringPreferencesKey("dismissed_important_notification_id")

        // Message receive mode
        private val KEY_MESSAGE_RECEIVE_MODE = stringPreferencesKey("message_receive_mode")

        // Profile cache
        private val KEY_PROFILE_CACHE = stringPreferencesKey("profile_cache_json")

        // Update
        private val KEY_LAST_UPDATE_CHECK = longPreferencesKey("last_update_check")
    }
}
