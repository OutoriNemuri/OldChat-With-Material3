package com.oldchat.material.core.network

import com.google.gson.Gson
import com.oldchat.material.OldChatApplication
import com.oldchat.material.core.auth.AuthManager
import com.oldchat.material.core.crypto.CryptoUtil
import com.oldchat.material.core.model.AuthResponse
import com.oldchat.material.core.model.HandshakeResponse
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.*
import io.ktor.utils.io.streams.asInput
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.KeyPair

/**
 * Central HTTP API client with automatic token refresh and ECDH encryption.
 * Mirrors HttpUtil + HttpUtilSupport0 + HttpAuthHelper from client-guide.md §2.3.
 *
 * Key behaviors:
 * - All business requests go through get/post; never bypass this class
 * - Token refresh uses single-flight (REFRESH_LOCK) + authGeneration
 * - 401 → auto refresh → retry once
 * - invalid_session → clear session → retry once
 * - 403 + user_banned → clear auth → trigger login
 * - Refresh token invalid → password fallback login
 * - Encryption: if session established, encrypt body + add X-Enc/X-Session/X-Mac headers
 */
class ApiClient(
    private val serverConfig: ServerConfig,
    private val authManager: AuthManager,
    private val gson: Gson
) {
    private val ktorClient get() = HttpClientProvider.ktorClient

    // Exclude these paths from automatic token refresh (would cause infinite loop)
    private val refreshExcludePaths = setOf(
        "/auth/login",
        "/auth/register",
        "/auth/refresh",
        "/auth/handshake"
    )

    // Single-flight lock for ECDH handshake (max ~25 second wait)
    private val handshakeLock = Mutex()
    private var handshakeKeyPair: KeyPair? = null

    // ---- Public API ----

    /**
     * Perform HTTP GET request with automatic auth.
     *
     * @param path API path, e.g. "/me"
     * @param params optional query parameters
     * @return raw JSON response body string
     */
    suspend fun get(path: String, params: Map<String, String> = emptyMap()): Result<String> {
        return executeWithAuth(path) { token ->
            val response = ktorClient.get("${serverConfig.resolveApiBase()}$path") {
                if (token != null) {
                    header("Authorization", "Bearer $token")
                }
                params.forEach { (key, value) ->
                    parameter(key, value)
                }
            }
            response
        }
    }

    /**
     * Perform HTTP POST request with automatic auth.
     *
     * @param path API path
     * @param body JSON body string (nullable)
     * @param headers extra headers
     * @return raw JSON response body string
     */
    suspend fun post(
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap()
    ): Result<String> {
        return executeWithAuth(path) { token ->
            val response = ktorClient.post("${serverConfig.resolveApiBase()}$path") {
                if (token != null) {
                    header("Authorization", "Bearer $token")
                }
                headers.forEach { (key, value) ->
                    header(key, value)
                }
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
            response
        }
    }

    /**
     * Perform HTTP POST with multipart/form-data (for file/media upload).
     *
     * @param path API path, e.g. "/media"
     * @param formParts list of form data parts
     * @return raw JSON response body string
     */
    suspend fun postMultipart(
        path: String,
        formParts: List<FormPartData>
    ): Result<String> {
        return executeWithAuth(path) { token ->
            val response = ktorClient.submitFormWithBinaryData(
                url = "${serverConfig.resolveApiBase()}$path",
                formData = formData {
                    formParts.forEach { part ->
                        when (part) {
                            is FormPartData.FilePart -> {
                                append(
                                    key = part.key,
                                    value = part.bytes,
                                    headers = Headers.build {
                                        append(HttpHeaders.ContentType, part.mimeType)
                                        append(HttpHeaders.ContentDisposition, "filename=\"${part.fileName}\"")
                                    }
                                )
                            }
                            is FormPartData.StreamPart -> {
                                // BUG-10：流式发送，不在内存里持有整个文件
                                append(
                                    key = part.key,
                                    value = io.ktor.client.request.forms.InputProvider(part.size) {
                                        part.openStream().asInput()
                                    },
                                    headers = Headers.build {
                                        append(HttpHeaders.ContentType, part.mimeType)
                                        append(HttpHeaders.ContentDisposition, "filename=\"${part.fileName}\"")
                                    }
                                )
                            }
                            is FormPartData.TextPart -> {
                                append(part.key, part.value)
                            }
                        }
                    }
                }
            ) {
                if (token != null) {
                    header("Authorization", "Bearer $token")
                }
            }
            response
        }
    }

    // ---- Auth + Encryption Execution ----

    private suspend fun executeWithAuth(
        path: String,
        request: suspend (token: String?) -> HttpResponse
    ): Result<String> {
        val generationSnapshot = authManager.authGeneration

        var response = try {
            request(authManager.accessToken)
        } catch (e: Exception) {
            return Result.failure(e)
        }

        // response body 只能读一次，读过的内容缓存在这里，最后统一返回（BUG-12）
        var bodyText: String? = null

        // BUG-12：400 不一定是会话问题（invalid_input / invalid_json 都是 400）。
        // 原实现对任何 400/401 都 clearSession 并用「同一个旧 token」重发一次，
        // 等于给每次失败白送一个 RTT，而且清掉会话后不重新握手，重试必然还是失败。
        // 现在：只有 401，或 400 且错误体确实是会话/加密相关，才清会话并重新握手后重试。
        if (response.status.value == 400 || response.status.value == 401) {
            bodyText = runCatching { response.bodyAsText() }.getOrNull()
            val sessionBroken = response.status.value == 401 ||
                looksLikeSessionError(bodyText) ||
                looksLikeEncryptedEnvelope(bodyText)
            if (sessionBroken && authManager.sessionId != null) {
                authManager.clearSession()
                ensureSession()
                response = try {
                    request(authManager.accessToken)
                } catch (e: Exception) {
                    return Result.failure(e)
                }
                bodyText = null
            } else if (response.status.value == 400) {
                // 明确的参数类错误：原样返回错误体，不做任何重试
                return Result.success(bodyText ?: "")
            } else {
                bodyText = null
            }
        }

        // 401 → token refresh
        if (response.status.value == 401 && path !in refreshExcludePaths) {
            val refreshResult = refreshToken(generationSnapshot)
            if (refreshResult.isSuccess) {
                // Retry with new token
                response = try {
                    request(authManager.accessToken)
                } catch (e: Exception) {
                    return Result.failure(e)
                }
                bodyText = null
            } else {
                return refreshResult.map { "" } // Propagate failure
            }
        }

        // 403 + user_banned → clear auth + trigger login
        if (response.status.value == 403) {
            try {
                val body = response.bodyAsText()
                // TODO: parse for user_banned in the exact response format
            } catch (_: Exception) { /* ignore */ }
        }

        // Success
        bodyText?.let { return Result.success(it) }
        return try {
            Result.success(response.bodyAsText())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ---- Token Refresh (single-flight) ----

    private suspend fun refreshToken(expectedGeneration: Int): Result<Unit> {
        return authManager.refreshLock.withLock {
            // Check if another coroutine already refreshed
            if (authManager.authGeneration != expectedGeneration) {
                if (authManager.accessToken != null) {
                    return@withLock Result.success(Unit)
                }
                return@withLock Result.failure(AuthException("Token refresh invalidated"))
            }

            val currentRefreshToken = authManager.refreshToken
            if (currentRefreshToken == null) {
                // Try password fallback
                return@withLock passwordFallbackLogin()
            }

            try {
                val response = ktorClient.post("${serverConfig.resolveApiBase()}/auth/refresh") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"refresh_token":"$currentRefreshToken"}""")
                    // No Authorization header for refresh
                }

                if (response.status.value == 200) {
                    val body = response.bodyAsText()
                    // Parse response - adapt to actual response format
                    val authResp = parseAuthResponse(body)
                    if (authResp != null &&
                        authManager.authGeneration == expectedGeneration
                    ) {
                        authManager.accessToken = authResp.accessToken
                        authManager.refreshToken = authResp.refreshToken
                        return@withLock Result.success(Unit)
                    }
                }

                // Refresh failed → try password fallback
                return@withLock passwordFallbackLogin()
            } catch (e: Exception) {
                return@withLock Result.failure(e)
            }
        }
    }

    /**
     * Password fallback login when refresh token is invalid.
     * Uses saved credentials from auth SP (§2.3).
     */
    private suspend fun passwordFallbackLogin(): Result<Unit> {
        val username = authManager.savedUsername ?: return Result.failure(
            AuthException("No saved credentials for password fallback")
        )
        val password = authManager.savedPassword ?: return Result.failure(
            AuthException("No saved credentials for password fallback")
        )

        return try {
            login(username, password)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Login with username and password.
     *
     * @return Result with Unit on success
     */
    suspend fun login(username: String, password: String): Result<Unit> {
        authManager.invalidateAuthOperations()

        return try {
            val response = ktorClient.post("${serverConfig.resolveApiBase()}/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(
                    gson.toJson(
                        mapOf(
                            "username" to username,
                            "password" to password,
                            "platform" to "android",
                            // BUG-20：版本号单一来源（原来这里、设置页、gradle 三处各写各的）
                            "app_version" to com.oldchat.material.BuildConfig.VERSION_NAME
                        )
                    )
                )
                // No auth header for login
            }

            if (response.status.value == 200) {
                val body = response.bodyAsText()
                val authResp = parseAuthResponse(body)
                if (authResp != null) {
                    authManager.onLoginSuccess(
                        accessToken = authResp.accessToken,
                        refreshToken = authResp.refreshToken,
                        userId = authResp.userId,
                        uid = authResp.uid
                    )
                    authManager.savedUsername = username
                    authManager.savedPassword = password
                    return Result.success(Unit)
                }
            }

            Result.failure(AuthException("Login failed: ${response.status.value}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ---- ECDH Handshake (§2.3) ----

    /**
     * Ensure ECDH session is established.
     * Called before encrypted requests. Uses lock to merge concurrent calls.
     * Max wait ~25 seconds.
     */
    /**
     * BUG-12：判断错误体是否属于「会话/加密层失效」（需要清会话重新握手），
     * 而不是调用方的参数错误。
     */
    private fun looksLikeSessionError(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        // 服务端在会话失效时也会返回「加密信封」形式的错误体，先尝试解开再看
        val plain = if (looksLikeEncryptedEnvelope(body)) decryptResponse(body) ?: body else body
        val lower = plain.lowercase()
        return lower.contains("invalid_session") ||
            lower.contains("missing_session") ||
            lower.contains("bad_encryption") ||
            lower.contains("session expired")
    }

    /** BUG-12：错误响应也可能是 {"iv","data","mac"} 信封（/v2 及部分 /v1 错误）。 */
    private fun looksLikeEncryptedEnvelope(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        return body.contains("\"iv\"") && body.contains("\"data\"") && body.contains("\"mac\"")
    }

    suspend fun ensureSession(): Result<Unit> {
        if (authManager.sessionId != null && authManager.sessionEncKey != null) {
            return Result.success(Unit) // Already established
        }

        return handshakeLock.withLock {
            // Double check after acquiring lock
            if (authManager.sessionId != null && authManager.sessionEncKey != null) {
                return@withLock Result.success(Unit)
            }

            try {
                // Generate client ephemeral ECDH key pair
                val keyPair = CryptoUtil.generateKeyPair()
                val clientPublicKey = CryptoUtil.publicKeyToBase64(keyPair.public)

                // POST /auth/handshake（明文，字段名 client_pub，需带 Authorization 头）
                val response = ktorClient.post("${serverConfig.resolveApiBase()}/auth/handshake") {
                    contentType(ContentType.Application.Json)
                    header("Authorization", "Bearer ${authManager.accessToken ?: ""}")
                    setBody("""{"client_pub":"$clientPublicKey"}""")
                }

                if (response.status.value != 200) {
                    return@withLock Result.failure(AuthException("Handshake failed: ${response.status.value}"))
                }

                val body = response.bodyAsText()
                // Parse: {session_id, server_pub}
                val handshakeMap = gson.fromJson(body, Map::class.java) as? Map<*, *>
                val sessionId = handshakeMap?.get("session_id") as? String
                val serverPublicKeyBase64 = handshakeMap?.get("server_pub") as? String

                if (sessionId == null || serverPublicKeyBase64 == null) {
                    return@withLock Result.failure(AuthException("Invalid handshake response"))
                }

                // Derive shared keys
                val serverPublicKey = CryptoUtil.base64ToPublicKey(serverPublicKeyBase64)
                val (encKey, macKey) = CryptoUtil.deriveKeys(keyPair, serverPublicKey)

                // Save session
                authManager.sessionId = sessionId
                authManager.sessionEncKey = encKey
                authManager.sessionMacKey = macKey
                handshakeKeyPair = keyPair

                return@withLock Result.success(Unit)
            } catch (e: Exception) {
                return@withLock Result.failure(e)
            }
        }
    }

    /**
     * Encrypt request body with session key.
     *
     * Returns headers that should be added:
     * - X-Enc: "1"
     * - X-Session: sessionId
     * - X-Mac: base64 HMAC of encrypted body
     *
     * @param plainBody raw JSON body to encrypt
     * @return Triple(encryptedBody, headers)
     */
    fun encryptRequest(plainBody: String): Triple<String, Map<String, String>, ByteArray>? {
        val encKey = authManager.sessionEncKey ?: return null
        val macKey = authManager.sessionMacKey ?: return null
        val sessionId = authManager.sessionId ?: return null

        val plainBytes = plainBody.toByteArray(Charsets.UTF_8)
        val encryptedPayload = CryptoUtil.aesEncrypt(plainBytes, encKey)
        val hmac = CryptoUtil.hmacSha256(encryptedPayload.toByteArray(), macKey)

        val headers = mapOf(
            "X-Enc" to "1",
            "X-Session" to sessionId,
            "X-Mac" to hmac,
            "Content-Encoding" to "encrypted"
        )

        return Triple(encryptedPayload, headers, macKey)
    }

    /**
     * Decrypt response body with session key.
     */
    fun decryptResponse(encryptedBody: String): String? {
        val encKey = authManager.sessionEncKey ?: return null
        return try {
            val decryptedBytes = CryptoUtil.aesDecrypt(encryptedBody, encKey)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    // ---- Helpers ----

    /**
     * Parse auth response from JSON.
     * Real server shape:
     *   { "access_token": "...", "refresh_token": "...",
     *     "user": { "id": "...", "uid": "...", ... } }
     * Tries multiple key name formats for compatibility.
     */
    private fun parseAuthResponse(body: String): AuthResponse? {
        // NOTE: Gson tolerates missing fields (returns defaults) so the direct
        // class parse may silently yield empty tokens. Guard by requiring a
        // non-empty access token before accepting it.
        val direct = try {
            gson.fromJson(body, AuthResponse::class.java)
        } catch (_: Exception) {
            null
        }
        if (direct != null && direct.accessToken.isNotEmpty()) {
            return direct
        }
        // Fall back to generic map parse for snake_case keys and nested user object.
        return try {
            val map = gson.fromJson(body, Map::class.java) as? Map<*, *> ?: return null
            val data = map["data"] as? Map<*, *>
            val source = data ?: map
            // The nested `user` object carries id/uid on the real server.
            val user = source["user"] as? Map<*, *>
            // Fall back to reading id/uid from the `user` object.
            val userId = (source["user_id"] ?: user?.get("id"))?.toString()
            val uid = (source["uid"]
                ?: source["my_uid"]
                ?: user?.get("uid"))?.toString()
            AuthResponse(
                accessToken = source["access_token"] as? String
                    ?: source["accessToken"] as? String ?: "",
                refreshToken = source["refresh_token"] as? String
                    ?: source["refreshToken"] as? String ?: "",
                userId = userId ?: "",
                uid = uid ?: ""
            )
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Exception for auth-related errors.
 */
class AuthException(message: String) : Exception(message)

/**
 * Form part data for multipart requests.
 */
sealed class FormPartData {
    data class TextPart(val key: String, val value: String) : FormPartData()
    /**
     * BUG-10：流式文件分片。大文件（视频/语音/任意文件）不再先 readBytes() 全量进内存，
     * 而是把「打开输入流」的工厂交给 Ktor，由网络层边读边发。
     */
    data class StreamPart(
        val key: String,
        val fileName: String,
        val mimeType: String,
        val size: Long,
        val openStream: () -> java.io.InputStream
    ) : FormPartData()

    data class FilePart(
        val key: String,
        val bytes: ByteArray,
        val fileName: String,
        val mimeType: String
    ) : FormPartData()
}
