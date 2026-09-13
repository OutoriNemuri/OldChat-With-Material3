package com.oldchat.material.core.network

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages server base URL configuration.
 * Mirrors ServerBaseUrlManager from original client.
 */
class ServerConfig(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) = prefs.edit().putString(KEY_BASE_URL, value).apply()

    var filesBaseUrl: String
        get() = prefs.getString(KEY_FILES_BASE_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_FILES_BASE_URL, value).apply()

    /**
     * Resolve the final API base URL.
     * If user has configured a custom server, use it; otherwise use default.
     */
    fun resolveApiBase(): String {
        val custom = baseUrl
        return if (custom.isNotBlank() && custom != DEFAULT_BASE_URL) custom else DEFAULT_BASE_URL
    }

    /**
     * 当前 API 服务器的主机根（去掉 /v1 后缀），例如 http://60.205.94.101:8080。
     * 用于把 /v1/uploads/... 相对路径解析成完整 URL —— 跟随登录后实际使用的服务器。
     */
    fun mediaHostBase(): String {
        val base = baseUrl.ifBlank { DEFAULT_BASE_URL }
        // 去掉尾部的 /v1 或 /v1/
        return when {
            base.endsWith("/v1/") -> base.removeSuffix("/v1/")
            base.endsWith("/v1") -> base.removeSuffix("/v1")
            base.endsWith("/") -> base.trimEnd('/')
            else -> base
        }
    }

    /**
     * 解析媒体/头像根地址：优先使用用户配置的文件服务器，否则跟随 API 服务器主机。
     */
    fun resolveMediaBase(): String {
        val files = filesBaseUrl.trim().trimEnd('/')
        if (files.isNotEmpty()) return files
        return mediaHostBase()
    }

    /**
     * 将一个相对媒体/头像路径解析为完整 URL（跟随登录/文件服务器动态配置）。
     * 处理三种形式：
     *   - 已是绝对 URL（http/https）→ 原样返回
     *   - 以 / 开头（如 /v1/uploads/x.jpg）→ mediaBase + path
     *   - 纯文件名（如 x.jpg）→ mediaBase + /v1/uploads/ + path
     */
    fun resolveMediaUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val base = resolveMediaBase()
        return if (path.startsWith("/")) "$base$path"
        else "$base/v1/uploads/$path"
    }

    /**
     * ALIGN-25：注册已迁移到网页端，服务端会在 POST /auth/register 的响应里给出
     * register_url；本地按同一主机推导，避免硬编码域名。
     */
    fun resolveRegisterUrl(): String = "${mediaHostBase()}/register"

    /**
     * ALIGN-07：§6.1 要求媒体/头像按「候选线路」顺序尝试（文件服务器 → API 主机 → 已知镜像）。
     * 返回去重后的有序候选列表，调用方逐个回退（加载失败就换下一个）。
     */
    fun resolveMediaUrlCandidates(path: String?): List<String> {
        if (path.isNullOrBlank()) return emptyList()
        if (path.startsWith("http://") || path.startsWith("https://")) return listOf(path)

        val suffix = if (path.startsWith("/")) path else "/v1/uploads/$path"
        val bases = buildList {
            val files = filesBaseUrl.trim().trimEnd('/')
            if (files.isNotEmpty()) add(files)
            add(mediaHostBase())
            addAll(MEDIA_SERVER_CANDIDATES)
        }
        return bases.map { it.trimEnd('/') + suffix }.distinct()
    }

    /**
     * ALIGN-07：给定一个「已拼好的绝对媒体 URL」，给出同路径的备用线路。
     * 由 Coil 拦截器在加载失败时按顺序重试，实现 §6.1 的候选线路回退。
     */
    fun alternativeOriginsFor(url: String): List<String> {
        if (!url.startsWith("http")) return listOf(url)
        return try {
            val uri = java.net.URI(url)
            val suffix = (uri.rawPath ?: "") + (uri.rawQuery?.let { "?$it" } ?: "")
            val bases = buildList {
                add("${uri.scheme}://${uri.rawAuthority}")
                val files = filesBaseUrl.trim().trimEnd('/')
                if (files.isNotEmpty()) add(files)
                add(mediaHostBase())
                MEDIA_SERVER_CANDIDATES.forEach { add(it) }
            }
            bases.map { it.trimEnd('/') + suffix }.distinct()
        } catch (_: Exception) {
            listOf(url)
        }
    }

    companion object {
        // Default server (from client-guide.md §18: HttpUtil.BASE_URL 默认为 http://60.205.94.101:8080/v1)
        const val DEFAULT_BASE_URL = "http://60.205.94.101:8080/v1"

        // Known fallback servers for media URL resolution (§6.1)
        val MEDIA_SERVER_CANDIDATES = listOf(
            "https://files.mcl0.dpdns.org",
            "http://60.205.94.101:8080",
            "https://oc.mcl0.dpdns.org"
        )

        private const val KEY_BASE_URL = "server_base_url"
        private const val KEY_FILES_BASE_URL = "files_server_base_url"
    }
}
