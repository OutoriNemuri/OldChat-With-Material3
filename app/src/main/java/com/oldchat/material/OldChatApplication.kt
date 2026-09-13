package com.oldchat.material

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.google.gson.Gson
import com.oldchat.material.core.auth.AuthManager
import com.oldchat.material.core.cache.CacheManager
import com.oldchat.material.core.network.ApiClient
import com.oldchat.material.core.network.HttpClientProvider
import com.oldchat.material.core.network.ServerConfig
import com.oldchat.material.core.network.WebSocketManager

/**
 * Application entry point for OldChat Material Client.
 * Mirrors the original OldChatApplication.onCreate() sequence from client-guide.md §2.1.
 */
class OldChatApplication : Application(), ImageLoaderFactory {

    lateinit var gson: Gson
        private set
    lateinit var authManager: AuthManager
        private set
    lateinit var cacheManager: CacheManager
        private set
    lateinit var serverConfig: ServerConfig
        private set
    lateinit var apiClient: ApiClient
        private set
    lateinit var wsManager: WebSocketManager

    /** 加密通话：对端共享密钥存储（等价 enigmaj config.json 的 shared_secrets） */
    lateinit var e2eKeyStore: com.oldchat.material.core.e2e.E2eKeyStore

    /** 加密通话状态机（进程级，跨会话页存活） */
    lateinit var encryptedCallManager: com.oldchat.material.core.e2e.EncryptedCallManager
        private set
    lateinit var messageReceiver: com.oldchat.material.core.network.MessageReceiver
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 1. Initialize Gson
        gson = Gson()

        // 2. Initialize ServerConfig (restore custom base URL from preferences)
        serverConfig = ServerConfig(this)

        // 3. Initialize HTTP client provider (Ktor/OkHttp)
        HttpClientProvider.init(serverConfig)

        // 4. Initialize AuthManager (ECDH session, token management)
        authManager = AuthManager(this, serverConfig)

        // 5. Initialize CacheManager (memory + disk caches)
        cacheManager = CacheManager(this)

        // ALIGN-18：换账号就清掉上一个账号的本地业务数据
        authManager.onAccountSwitched = { cacheManager.clearAccountScoped() }

        // BUG-09：通知通道与通知偏好（开关/声音/震动）在这里初始化，
        // 保证后台服务/主界面都拿到同一份配置。
        com.oldchat.material.core.notify.NotificationHelper.init(this)

        // 加密通话（enigmaj 同构的 PQC 握手 + AES-256-GCM 帧）挂在 Application 上：
        // 通话不能在离开会话页时中断，必须比 ChatViewModel 活得久。
        e2eKeyStore = com.oldchat.material.core.e2e.E2eKeyStore(this)
        encryptedCallManager = com.oldchat.material.core.e2e.EncryptedCallManager(
            // 走与普通消息相同的发送通道（enigmaj 也是把帧当普通 body 发出去）
            sendRaw = { peer, body ->
                runCatching {
                    apiClient.post(
                        path = "/direct/send",
                        body = gson.toJson(
                            mapOf("to_uid" to peer, "body" to body, "msg_type" to "text")
                        )
                    ).isSuccess
                }.getOrDefault(false)
            },
            keyStore = e2eKeyStore
        )

        // 6. Initialize ApiClient (HTTP + auto-refresh + ECDH encryption)
        apiClient = ApiClient(serverConfig, authManager, gson)

        // 7. Initialize WebSocketManager
        wsManager = WebSocketManager(serverConfig, authManager, gson)

        // 8. Initialize MessageReceiver（统一管理 WS + HTTP 轮询）
        messageReceiver = com.oldchat.material.core.network.MessageReceiver(
            wsManager, authManager, cacheManager.preferences, gson
        )

        // 9. Start message receiving if logged in（由 MessageReceiver 按偏好模式启停 WS/轮询）
        if (authManager.isLoggedIn) {
            messageReceiver.start()
        }

        // Note: MessageService (foreground) is started from MainActivity.onResume,
        // NOT here — starting a foreground service from Application.onCreate on
        // Android 12+ throws ForegroundServiceStartNotAllowedException (app is
        // still in background when Application.onCreate runs).
    }

    /**
     * Provide Coil ImageLoader (default OkHttp-backed).
     * Candidate URL fallback is handled at the ViewModel/media layer via MediaUrlResolver.
     */
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(128L * 1024 * 1024) // 128 MB
                    .build()
            }
            // ALIGN-07：媒体/头像加载失败时按候选线路自动回退（§6.1）
            .components {
                add(com.oldchat.material.core.media.MediaCandidatesInterceptor(serverConfig))
            }
            .build()
    }

    companion object {
        lateinit var instance: OldChatApplication
            private set
    }
}
