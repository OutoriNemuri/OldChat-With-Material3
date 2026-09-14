package com.oldchat.material.core.media

import android.net.Uri
import com.oldchat.material.core.network.ServerConfig

/**
 * 媒体 URL 的候选线路解析（client-guide §6.1）。
 *
 * 本类**不再自己维护任何硬编码域名或 `/v1/` 前缀** —— 全部交给 [ServerConfig]：
 *   - 主机优先级：文件服务器（若配置）→ 当前 API 主机 → 官方主机
 *   - 路径前缀：`ServerConfig.MEDIA_PATH_PREFIX`（服务器规定媒体固定在 v1 的 uploads 下）
 *
 * 规则（保持与旧实现一致）：
 *   - 相对路径 `/v1/uploads/media/a.jpg` → 展开为多条候选线路
 *   - 来自已知线路的绝对 URL → 按同路径改写成多条候选
 *   - 第三方绝对 URL → 原样返回，不改写
 */
object MediaUrlResolver {

    private fun serverConfig(): ServerConfig =
        com.oldchat.material.OldChatApplication.instance.serverConfig

    /** 主线路：文件服务器 > API 主机 */
    private fun primaryMediaBase(): String = serverConfig().resolveMediaBase()

    /** 解析为主线路（第一个候选） */
    fun resolve(url: String?): String? {
        if (url == null) return null
        return resolveCandidates(url).firstOrNull()
    }

    /** 解析为全部候选线路（调用方按顺序回退） */
    fun resolveCandidates(url: String?): List<String> {
        if (url.isNullOrBlank()) return emptyList()

        if (url.startsWith("http://") || url.startsWith("https://")) {
            val host = Uri.parse(url).host ?: return listOf(url)
            // 第三方 URL：不改写
            if (!isKnownHost(host)) return listOf(url)
            // 已知线路：按同路径展开候选（由 ServerConfig 统一生成）
            return serverConfig().alternativeOriginsFor(url)
        }

        // 相对路径：交给 ServerConfig（它会带上 MEDIA_PATH_PREFIX）
        return serverConfig().resolveMediaUrlCandidates(url)
    }

    /** 当前 URL 的下一个候选线路（没有则 null） */
    fun resolveNextCandidate(currentUrl: String): String? {
        val candidates = resolveCandidates(currentUrl)
        val currentIndex = candidates.indexOf(currentUrl)
        return if (currentIndex in 0 until candidates.size - 1) candidates[currentIndex + 1] else null
    }

    /**
     * 是否属于 OldChat 可信线路 —— 决定要不要带 Authorization。
     * 判定依据改为「与当前配置的主机同源 / 官方主机同源」，不再列举域名。
     */
    private fun isKnownHost(host: String): Boolean {
        val known = buildList {
            add(runCatching { Uri.parse(serverConfig().mediaHostBase()).host }.getOrNull())
            add(runCatching { Uri.parse(serverConfig().resolveMediaBase()).host }.getOrNull())
            add(runCatching { Uri.parse(ServerConfig.OFFICIAL_HOST).host }.getOrNull())
            val files = serverConfig().filesBaseUrl
            if (files.isNotBlank()) add(runCatching { Uri.parse(files).host }.getOrNull())
        }.filterNotNull()
        return known.any { host == it || host.endsWith(".$it") }
    }

    /**
     * 是否给该 URL 附带登录令牌。
     * 规则：可信线路带；文件/OSS 服务器不带（§6.1 明确要求不外泄令牌）。
     */
    fun shouldAttachAuth(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val host = Uri.parse(url).host ?: return false

        val filesHost = serverConfig().filesBaseUrl
            .takeIf { it.isNotBlank() }
            ?.let { runCatching { Uri.parse(it).host }.getOrNull() }
        if (filesHost != null && host == filesHost) return false

        return isKnownHost(host)
    }
}
