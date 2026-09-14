package com.oldchat.material.core.network

import android.content.Context
import android.content.SharedPreferences

/**
 * 服务器地址与 API 版本配置（**唯一真相源**）。
 *
 * ## 为什么有「版本」这一维
 * 依据 `shared/v2-selftest-20260915/报告-V2全量测试.md`（2026-09-15 实测 114 项）：
 *   - **业务接口**分 v1 / v2 两套：`/v1/direct/send` 与 `/v2/direct/send`、`/v2/friends`、
 *     `/v2/gateway`、`/v2/files/check`、`/v2/updates/difference` …（v2 为当前主力）
 *   - **基础设施接口固定在 v1**：`/v1/auth/handshake`、`/v1/auth/login`、`/v1/auth/refresh`、
 *     `/v1/me`、`/v1/ws`、`POST /v1/media`（上传，X-Enc 豁免）、`/v1/uploads/...`（媒体下载）
 *     —— 报告里的 v2 全量测试就是「业务打 /v2、认证与媒体打 /v1」跑通的。
 *
 * 所以本类提供两个基址：
 *   - [businessBase]：**随所选版本变化**，业务请求用它
 *   - [infraBase]：**始终 v1**，认证 / WebSocket / 媒体用它
 *
 * ## 关于「不硬编码」
 * 官方主机在这里定义**一次**（[OFFICIAL_HOST]），其余任何地方都不得再出现字面量；
 * `/v1/`、`/v2/` 只以 [API_V1] / [API_V2] 常量出现，媒体路径前缀用 [MEDIA_PATH_PREFIX]。
 */
class ServerConfig(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 服务器选择模式 */
    enum class Mode(val key: String, val label: String) {
        OFFICIAL_V1("official_v1", "官方（v1）"),
        OFFICIAL_V2("official_v2", "官方（v2）"),
        CUSTOM("custom", "自定义");

        companion object {
            fun fromKey(key: String?): Mode =
                entries.firstOrNull { it.key == key } ?: OFFICIAL_V1
        }
    }

    /** 当前模式；默认走官方 v1（历史兼容：老用户原来保存的就是 .../v1） */
    var mode: Mode
        get() {
            val saved = prefs.getString(KEY_MODE, null)
            if (saved != null) return Mode.fromKey(saved)
            // 迁移：早期版本把自定义地址直接存成 baseUrl，这里据此判断
            val legacy = prefs.getString(KEY_BASE_URL, null)
            return if (legacy.isNullOrBlank()) Mode.OFFICIAL_V1 else Mode.CUSTOM
        }
        set(value) {
            prefs.edit().putString(KEY_MODE, value.key).apply()
            // 切到官方时清掉遗留的自定义地址，避免两套状态打架
            if (value != Mode.CUSTOM) prefs.edit().remove(KEY_BASE_URL).apply()
        }

    /** 自定义服务器地址（模式为 [Mode.CUSTOM] 时生效），应带版本段，如 https://host/v1 */
    var customBaseUrl: String
        get() = prefs.getString(KEY_BASE_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BASE_URL, value.trim()).apply()

    /** 文件/媒体服务器（可选）。留空表示跟随当前 API 主机。 */
    var filesBaseUrl: String
        get() = prefs.getString(KEY_FILES_BASE_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_FILES_BASE_URL, value.trim()).apply()

    // ---- 基址 ----

    /** 业务请求基址（随版本变化）。自定义模式下原样使用用户填写的地址。 */
    fun businessBase(): String = when (mode) {
        Mode.OFFICIAL_V1 -> "$OFFICIAL_HOST/$API_V1"
        Mode.OFFICIAL_V2 -> "$OFFICIAL_HOST/$API_V2"
        Mode.CUSTOM -> customBaseUrl.ifBlank { "$OFFICIAL_HOST/$API_V1" }.trimEnd('/')
    }

    /**
     * 基础设施基址（认证 / WebSocket / 媒体上传，**固定 v1**）。
     * 自定义模式下：若用户填的是 .../v2，则把版本段换成 v1（服务器同源，v1 侧仍然存在）；
     * 其它情况原样使用。
     */
    fun infraBase(): String {
        val base = businessBase()
        return when {
            !base.endsWith("/$API_V2") -> base
            else -> base.removeSuffix("/$API_V2") + "/$API_V1"
        }
    }

    /** 当前 API 版本标签（"v1" / "v2"），用于界面展示与诊断 */
    val apiVersionLabel: String
        get() = if (businessBase().endsWith("/$API_V2")) API_V2 else API_V1

    /** 历史命名兼容：等同 [businessBase] */
    fun resolveApiBase(): String = businessBase()

    /** 当前 API 服务器的主机根（去掉版本段），用于拼接媒体 / 注册页地址 */
    fun mediaHostBase(): String {
        val base = businessBase()
        return when {
            base.endsWith("/$API_V2") -> base.removeSuffix("/$API_V2")
            base.endsWith("/$API_V1") -> base.removeSuffix("/$API_V1")
            else -> base.trimEnd('/')
        }
    }

    /** 媒体根：优先用户配置的文件服务器，否则跟随 API 主机 */
    fun resolveMediaBase(): String {
        val files = filesBaseUrl.trim().trimEnd('/')
        if (files.isNotEmpty()) return files
        return mediaHostBase()
    }

    /**
     * 相对媒体路径 → 完整 URL。
     *   - 绝对 URL 原样返回
     *   - 以 `/` 开头（如 [MEDIA_PATH_PREFIX] + `/media/x.png`）→ mediaBase + path
     *   - 纯文件名 → mediaBase + [MEDIA_PATH_PREFIX] + / + filename
     */
    fun resolveMediaUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val base = resolveMediaBase()
        return if (path.startsWith("/")) "$base$path" else "$base$MEDIA_PATH_PREFIX/$path"
    }

    /** 注册页（注册已迁到网页端；按当前主机推导，不硬编码域名） */
    fun resolveRegisterUrl(): String = "${mediaHostBase()}/register"

    /**
     * 媒体候选线路：文件服务器 → 当前 API 主机 → 官方主机。
     * 全部由配置推导，不再列举写死的域名。
     */
    fun resolveMediaUrlCandidates(path: String?): List<String> {
        if (path.isNullOrBlank()) return emptyList()
        if (path.startsWith("http://") || path.startsWith("https://")) return listOf(path)

        val suffix = if (path.startsWith("/")) path else "$MEDIA_PATH_PREFIX/$path"
        return mediaBases().map { it.trimEnd('/') + suffix }.distinct()
    }

    /** 给定一个已拼好的绝对 URL，给出同路径的备用线路（供图片加载失败时回退） */
    fun alternativeOriginsFor(url: String): List<String> {
        if (!url.startsWith("http")) return listOf(url)
        return try {
            val uri = java.net.URI(url)
            val suffix = (uri.rawPath ?: "") + (uri.rawQuery?.let { "?$it" } ?: "")
            mediaBases().map { it.trimEnd('/') + suffix }.distinct()
        } catch (_: Exception) {
            listOf(url)
        }
    }

    /** 候选主机根（去重、保持优先级） */
    private fun mediaBases(): List<String> = buildList {
        val files = filesBaseUrl.trim().trimEnd('/')
        if (files.isNotEmpty()) add(files)
        add(mediaHostBase())
        add(OFFICIAL_HOST)
    }.distinct()

    /** 供界面显示的当前生效地址 */
    fun displayUrl(): String = businessBase()

    /** 一次性保存「模式 + 自定义地址」（界面统一入口，避免两处状态不一致） */
    fun saveSelection(mode: Mode, customUrl: String = "") {
        if (mode == Mode.CUSTOM) customBaseUrl = customUrl
        this.mode = mode
    }

    /** 规范化用户输入的自定义地址：去空格、去尾斜杠；未带版本段时补上 v1（服务器默认版本） */
    fun normalizeCustomInput(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        val hasVersion = trimmed.endsWith("/$API_V1") || trimmed.endsWith("/$API_V2")
        return if (hasVersion) trimmed else "$trimmed/$API_V1"
    }

    /**
     * 规范化「文件服务器」输入。文件服务器是**媒体主机根**（不是 API 基址），
     * 媒体路径由本类拼 `MEDIA_PATH_PREFIX`，因此：
     *   - 缺协议头 → 自动补 https://（否则拼出来的地址无法加载，等于配置不生效）
     *   - 去掉尾部斜杠与误填的版本段（/v1、/v2），避免出现 host/v1/v1/uploads
     *   - 末尾若已带 MEDIA_PATH_PREFIX 也去掉（避免重复）
     * 空串表示「跟随登录服务器」。
     */
    fun normalizeFilesServerInput(input: String): String {
        var v = input.trim()
        if (v.isEmpty()) return ""
        if (!v.startsWith("http://") && !v.startsWith("https://")) v = "https://$v"
        v = v.trimEnd('/')
        if (v.endsWith("/$API_V1")) v = v.removeSuffix("/$API_V1")
        if (v.endsWith("/$API_V2")) v = v.removeSuffix("/$API_V2")
        if (v.endsWith(MEDIA_PATH_PREFIX)) v = v.removeSuffix(MEDIA_PATH_PREFIX)
        return v.trimEnd('/')
    }

    /** 界面用：当前媒体实际生效的根地址（文件服务器为空时即 API 主机） */
    fun effectiveMediaBase(): String = resolveMediaBase()

    companion object {
        /**
         * 官方主机 —— **全工程唯一定义处**。
         * 其余代码一律通过 [businessBase] / [infraBase] / [mediaHostBase] 拼接，
         * 不得再出现这个域名或写死的 `/v1/`。
         */
        const val OFFICIAL_HOST = "https://oc.mcl0.dpdns.org"

        /** API 版本段（不带斜杠） */
        const val API_V1 = "v1"
        const val API_V2 = "v2"

        /** 媒体相对路径前缀（服务器规定为 v1 下的 uploads，与 API 版本无关） */
        const val MEDIA_PATH_PREFIX = "/v1/uploads"

        private const val KEY_MODE = "server_mode"
        private const val KEY_BASE_URL = "server_base_url"
        private const val KEY_FILES_BASE_URL = "files_server_base_url"
    }
}
