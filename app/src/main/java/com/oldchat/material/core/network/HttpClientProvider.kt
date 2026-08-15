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

        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
