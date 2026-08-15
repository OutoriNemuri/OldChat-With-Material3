package com.oldchat.material.core.network

import android.util.Log
import com.google.gson.Gson
import com.oldchat.material.OldChatApplication
import com.oldchat.material.core.auth.AuthManager
import com.oldchat.material.core.cache.PreferencesManager
import com.oldchat.material.core.model.Message
import com.oldchat.material.core.model.GroupMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 消息接收协调器 — 统一管理 WebSocket 与 HTTP 轮询。
 *
 * 三种接收方式（由设置偏好 `message_receive_mode` 决定）：
 * - [Mode.WS_PRIORITY]：WS 连接时用 WS 实时收消息，WS 断开时降级为每 5s HTTP 轮询。
 * - [Mode.WS_ONLY]：只用 WebSocket 收消息。
 * - [Mode.HTTP_ONLY]：只用 HTTP 每 5s 轮询。
 *
 * 前后台均持续运行（使用独立的 Application 级 CoroutineScope，不依赖 UI 生命周期）。
 * Android 后台限制下 HTTP 轮询仍可行；WS 在后台可能被系统挂起，由降级轮询兜底。
 */
class MessageReceiver(
    private val wsManager: WebSocketManager,
    private val authManager: AuthManager,
    private val preferences: PreferencesManager,
    private val gson: Gson
) {
    companion object {
        private const val TAG = "MessageReceiver"
        private const val POLL_INTERVAL_MS = 5_000L
    }

    enum class Mode(val key: String, val label: String) {
        WS_PRIORITY("ws_priority", "WebSocket优先"),
        WS_ONLY("ws_only", "仅WebSocket"),
        HTTP_ONLY("http_only", "仅HTTP");

        companion object {
            fun fromKey(key: String?): Mode =
                entries.firstOrNull { it.key == key } ?: WS_PRIORITY
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollJob: Job? = null
    private var modeJob: Job? = null
    private var pollingObserverJob: Job? = null

    // 已投递消息去重（避免轮询重复触发）
    private val seenDirectIds = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )
    private val seenGroupIds = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    /** 当前生效的模式（供 UI 观察） */
    private val _currentMode = MutableStateFlow(Mode.WS_PRIORITY)
    val currentMode: StateFlow<Mode> = _currentMode.asStateFlow()

    /** 启动协调器：订阅模式偏好，按需启动 WS / HTTP 轮询。 */
    fun start() {
        if (!authManager.isLoggedIn) return
        modeJob?.cancel()
        modeJob = scope.launch {
            preferences.messageReceiveMode.distinctUntilChanged().collect { key ->
                applyMode(Mode.fromKey(key))
            }
        }
    }

    private fun applyMode(mode: Mode) {
        _currentMode.value = mode
        Log.d(TAG, "Apply message receive mode: ${mode.label}")
        when (mode) {
            Mode.WS_PRIORITY -> {
                wsManager.start()
                // 仅在 WS 未连接时轮询（连接成功后降级停止，见 connectionState 观察）
                ensurePollingObserved()
            }
            Mode.WS_ONLY -> {
                stopPolling()
                wsManager.start()
            }
            Mode.HTTP_ONLY -> {
                wsManager.stop()
                startPolling()
            }
        }
    }

    /**
     * WS 优先模式下，保持 HTTP 轮询始终运行作为兜底：
     * 即使 WS 已连接，若 WS 推送因加密/格式等问题丢消息，轮询仍能可靠拉到 unread。
     * dispatchDirectUnread/dispatchGroupUnread 内部有 seenDirectIds/seenGroupIds 去重，
     * 轮询与 WS 同时投递同一条消息不会造成重复。
     */
    private fun ensurePollingObserved() {
        if (pollingObserverJob?.isActive == true) return
        pollingObserverJob = scope.launch {
            wsManager.connectionState.collect { state ->
                when {
                    currentMode.value != Mode.WS_PRIORITY -> { /* 其余模式由 applyMode 处理 */ }
                    else -> startPolling() // WS 连接/断开都保持轮询兜底
                }
            }
        }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                pollOnce()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /** 执行一次 HTTP 轮询：拉直聊 + 群聊未读消息。 */
    private suspend fun pollOnce() {
        try {
            val apiClient = ApiClient(
                OldChatApplication.instance.serverConfig, authManager, gson
            )
            apiClient.ensureSession()

            // 直聊未读
            runCatching {
                apiClient.post("/direct/unread", gson.toJson(emptyMap<String, String>()))
            }.getOrNull()?.getOrNull()?.let { body ->
                dispatchDirectUnread(body)
            }

            // 群聊未读
            runCatching {
                apiClient.post("/groups/unread", gson.toJson(emptyMap<String, String>()))
            }.getOrNull()?.getOrNull()?.let { body ->
                dispatchGroupUnread(body)
            }
        } catch (e: Exception) {
            // 静默失败，下一轮继续
            Log.d(TAG, "pollOnce failed: ${e.message}")
        }
    }

    private fun dispatchDirectUnread(body: String) {
        val messages = parseMessages(body)
        messages.forEach { msg ->
            if (seenDirectIds.add(msg.id)) {
                wsManager.emitHttpDirectMessage(msg)
            }
        }
    }

    private fun dispatchGroupUnread(body: String) {
        val messages = parseGroupMessages(body)
        messages.forEach { msg ->
            if (seenGroupIds.add(msg.id)) {
                wsManager.emitHttpGroupMessage(msg)
            }
        }
    }

    /** 解析 {messages:[...]} wrapper 里的直聊消息。 */
    private fun parseMessages(body: String): List<Message> {
        return try {
            val root = gson.fromJson(body, Map::class.java) as? Map<*, *> ?: return emptyList()
            val list = root["messages"] as? List<*> ?: return emptyList()
            list.mapNotNull { item ->
                val json = gson.toJson(item)
                runCatching { gson.fromJson(json, Message::class.java) }.getOrNull()
            }
        } catch (_: Exception) { emptyList() }
    }

    /** 解析 {messages:[...]} wrapper 里的群消息。 */
    private fun parseGroupMessages(body: String): List<GroupMessage> {
        return try {
            val root = gson.fromJson(body, Map::class.java) as? Map<*, *> ?: return emptyList()
            val list = root["messages"] as? List<*> ?: return emptyList()
            list.mapNotNull { item ->
                val json = gson.toJson(item)
                val msg = runCatching { gson.fromJson(json, GroupMessage::class.java) }.getOrNull()
                    ?: return@mapNotNull null
                // group_seq 缺失时兜底用 sort_seq（WS 实时推送可能只带 sort_seq），
                // 避免 groupSeq=0 导致排序时被错误排到列表顶部。
                if (msg.groupSeq == 0L) {
                    val m = (item as? Map<*, *>) ?: return@mapNotNull msg
                    val sortSeq = (m["sort_seq"] as? Number)?.toLong() ?: 0L
                    if (sortSeq != 0L) msg.copy(groupSeq = sortSeq) else msg
                } else msg
            }
        } catch (_: Exception) { emptyList() }
    }

    fun destroy() {
        stopPolling()
        modeJob?.cancel()
        scope.cancel()
    }
}
