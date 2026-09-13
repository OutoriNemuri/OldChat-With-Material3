package com.oldchat.material.core.auth

import android.content.Context
import android.content.SharedPreferences
import com.oldchat.material.core.network.ServerConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages authentication state: tokens, ECDH session keys, and refresh logic.
 * Mirrors HttpUtil + HttpAuthHelper from original client-guide.md §2.3.
 *
 * Token refresh uses single-flight (REFRESH_LOCK) and authGeneration to
 * invalidate stale refresh results after logout/account switch.
 */
class AuthManager(
    private val context: Context,
    private val serverConfig: ServerConfig
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("auth", Context.MODE_PRIVATE)

    // Single-flight lock for token refresh
    val refreshLock = Mutex()

    // Monotonically increasing generation; incremented on login/logout
    @Volatile
    var authGeneration: Int = 0
        private set

    // ---- Access Token ----

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_ACCESS_TOKEN, value).apply()

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()

    var userId: String?
        get() = prefs.getString(KEY_USER_ID, null)
        set(value) = prefs.edit().putString(KEY_USER_ID, value).apply()

    var myUid: String?
        get() = prefs.getString(KEY_MY_UID, null)
        set(value) = prefs.edit().putString(KEY_MY_UID, value).apply()

    // Saved credentials for password fallback during refresh
    var savedUsername: String?
        get() = prefs.getString(KEY_SAVED_USERNAME, null)
        set(value) = prefs.edit().putString(KEY_SAVED_USERNAME, value).apply()

    var savedPassword: String?
        get() = prefs.getString(KEY_SAVED_PASSWORD, null)
        set(value) = prefs.edit().putString(KEY_SAVED_PASSWORD, value).apply()

    val isLoggedIn: Boolean
        get() = accessToken != null

    /**
     * Called on login success. Stores tokens and increments generation.
     */
    /**
     * ALIGN-18：账号切换回调。登录成功且 uid 与上次不同 → 需要清掉上一个账号的本地数据，
     * 否则新账号会看到旧账号的会话列表/消息/页面缓存。
     */
    var onAccountSwitched: (() -> Unit)? = null

    fun onLoginSuccess(accessToken: String, refreshToken: String, userId: String, uid: String) {
        val previousUid = this.myUid
        this.accessToken = accessToken
        this.refreshToken = refreshToken
        this.userId = userId
        this.myUid = uid
        authGeneration++
        clearSession()

        if (!previousUid.isNullOrEmpty() && previousUid != uid) {
            onAccountSwitched?.invoke()
        }
    }

    /**
     * Called on logout or account switch. Clears all auth state and
     * invalidates in-flight refreshes by incrementing generation.
     */
    fun clearAuth() {
        authGeneration++
        prefs.edit().clear().apply()
    }

    /**
     * Invalidate in-flight auth operations (e.g., before login/logout).
     */
    fun invalidateAuthOperations() {
        authGeneration++
    }

    // ---- ECDH Session (to be implemented in Phase 1) ----

    var sessionId: String? = null
    var sessionEncKey: ByteArray? = null
    var sessionMacKey: ByteArray? = null

    fun clearSession() {
        sessionId = null
        sessionEncKey = null
        sessionMacKey = null
    }

    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_MY_UID = "my_uid"
        private const val KEY_SAVED_USERNAME = "saved_username"
        private const val KEY_SAVED_PASSWORD = "saved_password"
    }
}
