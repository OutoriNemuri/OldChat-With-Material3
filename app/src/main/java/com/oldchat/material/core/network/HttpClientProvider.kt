package com.oldchat.material.core.network

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Provides HTTP clients (Ktor for REST API, OkHttp for WebSocket).
 * Initialized once in OldChatApplication.onCreate().
 */
object HttpClientProvider {

    lateinit var serverConfig: ServerConfig
        private set

    // Ktor client for JSON REST API
    lateinit var ktorClient: HttpClient
        private set

    // OkHttp client for WebSocket and raw file uploads
    lateinit var okHttpClient: OkHttpClient
        private set

    fun init(config: ServerConfig) {
        serverConfig = config

        val jsonConfig = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        ktorClient = HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(jsonConfig)
            }
            engine {
                // Configure OkHttp engine
                config {
                    connectTimeout(30, TimeUnit.SECONDS)
                    readTimeout(30, TimeUnit.SECONDS)
                    writeTimeout(30, TimeUnit.SECONDS)
                }
            }
        }

        // BUG-15：这个 client 只用于 WebSocket（REST 走 Ktor）。
        // 原来给了 readTimeout(30s) 且没有 pingInterval → 空闲 30s 就被 OkHttp 判定读超时断开，
        // 于是「挂后台几分钟就掉线」。WS 需要 readTimeout=0（不超时）+ 25s 心跳保活。
        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
