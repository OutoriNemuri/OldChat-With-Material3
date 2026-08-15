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
            .build()
    }

    companion object {
        lateinit var instance: OldChatApplication
            private set
    }
}
