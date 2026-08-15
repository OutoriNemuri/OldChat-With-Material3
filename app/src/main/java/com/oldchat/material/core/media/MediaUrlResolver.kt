package com.oldchat.material.core.media

import android.net.Uri
import com.oldchat.material.core.network.ServerConfig

/**
 * Resolves media URLs with multi-candidate fallback.
 * Mirrors MediaUrlResolver from client-guide.md §6.1.
 *
 * Candidate order:
 * 1. https://files.mcl0.dpdns.org/{oss_path}
 * 2. http://60.205.94.101:8080/v1/uploads/{path}
 * 3. https://oc.mcl0.dpdns.org/v1/uploads/{path}
 * 4. {HttpUtil.BASE_URL}/v1/uploads/{path}
 *
 * Rules:
 * - Relative paths (/v1/uploads/media/a.jpg) → generate all 4 candidates
 * - Absolute URLs from known sources → rewrite to 4 candidates
 * - Third-party absolute URLs → keep original, don't rewrite
 */
object MediaUrlResolver {

    private val KNOWN_HOSTS = setOf(
        "files.mcl0.dpdns.org",
        "60.205.94.101",
        "oc.mcl0.dpdns.org",
        "aliyuncs.com"
    )

    // 动态读取当前登录服务器与文件服务器配置（跟随登录/设置）。
    private fun serverConfig(): ServerConfig = com.oldchat.material.OldChatApplication.instance.serverConfig

    // 优先级最高的媒体根：文件服务器 > API 服务器主机。
    private fun primaryMediaBase(): String = serverConfig().resolveMediaBase()

    // 兜底候选（保持多线路容错）：当前文件服务器 + 旧主站 + CF 主站 + OSS。
    private val FALLBACK_BASES: List<String> get() = listOf(
        serverConfig().mediaHostBase(),            // 登录 API 服务器主机
        "https://oc.mcl0.dpdns.org",               // CF 主站
        "https://files.mcl0.dpdns.org"             // OSS 自定义域名
    )

    /**
     * Resolve a media URL to its primary candidate.
     * Returns the first candidate (fastest path).
     */
    fun resolve(url: String?): String? {
        if (url == null) return null
        return resolveCandidates(url).firstOrNull()
    }

    /**
     * Resolve a media URL to all candidates.
     * Consumers should iterate through candidates on failure.
     */
    fun resolveCandidates(url: String?): List<String> {
        if (url == null) return emptyList()

        // If it's an absolute URL from a known source
        if (url.startsWith("http://") || url.startsWith("https://")) {
            val uri = Uri.parse(url)
            val host = uri.host ?: return listOf(url)

            // Third-party URL: keep as-is
            if (!isKnownHost(host)) {
                return listOf(url)
            }

            // Known source: extract path and generate all candidates
            val path = uri.path ?: "/"
            val cleanPath = cleanPath(path)
            if (cleanPath.startsWith("/v1/uploads/")) {
                val relativePath = cleanPath.removePrefix("/v1/uploads/")
                return buildMediaCandidates(relativePath)
            }
            // OSS path
            return buildOssCandidates(cleanPath)
        }

        // Relative path
        if (url.startsWith("/v1/uploads/")) {
            val relativePath = url.removePrefix("/v1/uploads/")
            return buildMediaCandidates(relativePath)
        }

        // Other relative path: try as OSS
        return buildOssCandidates(url)
    }

    /**
     * Returns the next candidate after the current one.
     */
    fun resolveNextCandidate(currentUrl: String): String? {
        val candidates = resolveCandidates(currentUrl)
        val currentIndex = candidates.indexOf(currentUrl)
        if (currentIndex >= 0 && currentIndex < candidates.size - 1) {
            return candidates[currentIndex + 1]
        }
        return null
    }

    private fun buildMediaCandidates(relativePath: String): List<String> {
        val cleanPath = if (relativePath.startsWith("/")) relativePath else "/$relativePath"
        val primary = primaryMediaBase()
        val candidates = mutableListOf<String>()
        // 1. 首选：文件服务器 / 登录 API 服务器主机（动态跟随）
        candidates.add("$primary/v1/uploads$cleanPath")
        // 2. 兜底线路（去重后追加）
        FALLBACK_BASES.forEach { base ->
            val url = "$base/v1/uploads$cleanPath"
            if (url !in candidates) candidates.add(url)
        }
        return candidates
    }

    private fun buildOssCandidates(ossPath: String): List<String> {
        val cleanPath = if (ossPath.startsWith("/")) ossPath else "/$ossPath"
        // For OSS paths that don't have /v1/uploads prefix
        return buildMediaCandidates(cleanPath)
    }

    private fun cleanPath(path: String): String {
        var clean = path
        // Remove double slashes
        while (clean.contains("//")) {
            clean = clean.replace("//", "/")
        }
        // Remove trailing slash unless it's just "/"
        if (clean.length > 1 && clean.endsWith("/")) {
            clean = clean.dropLast(1)
        }
        return clean
    }

    private fun isKnownHost(host: String): Boolean {
        if (KNOWN_HOSTS.any { host == it || host.endsWith(".$it") }) {
            return true
        }
        // IoT servers (60.*)
        if (host.startsWith("60.")) {
            return true
        }
        // Data server
        if (host.contains("dpdns.org")) {
            return true
        }
        return false
    }

    /**
     * Determine if auth token should be attached for this URL.
     * Only for OldChat trusted servers; NOT for files OSS or third-party (§6.1).
     */
    fun shouldAttachAuth(url: String?): Boolean {
        if (url == null) return false
        val uri = Uri.parse(url)
        val host = uri.host ?: return false
        // Don't attach auth to OSS files server
        if (host == "files.mcl0.dpdns.org") return false
        // Attach auth to known OldChat servers
        return isKnownHost(host)
    }
}
