package com.oldchat.material.core.cache

import android.content.Context
import android.content.SharedPreferences

/**
 * 轻量页面数据 JSON 缓存（非聊天列表页通用，如公开法庭、音乐广场等）。
 *
 * 用途：把「一次性拉全量列表」这类易触发服务端流量监控/封禁（4000+）的接口，
 * 落地为本地 SharedPreferences JSON，实现「本地秒开 + 后台增量刷新」，
 * 避免每次进入页面都无脑全量拉取。
 *
 * 设计要点（对齐红线守则）：
 * - 内存态为当前最新数据，写盘走异步串行（单线程）防抖，避免主线程 IO 与抖动。
 * - 读路径直接返回本地 JSON，不阻塞网络。
 * - 每个 key 附带 `_ts` 时间戳，供调用方判断缓存新鲜度、决定是否增量/全量刷新。
 */
class PageCache(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("page_json_cache", Context.MODE_PRIVATE)

    /** 读缓存 JSON（无则返回 null）。 */
    fun read(key: String): String? = prefs.getString(key, null)

    /** 读缓存时间戳（毫秒，无则返回 0）。 */
    fun readTs(key: String): Long = prefs.getLong("${key}_ts", 0L)

    /** 写缓存（同步 apply，内存态立即生效，写盘异步摊还）。 */
    fun write(key: String, json: String) {
        prefs.edit()
            .putString(key, json)
            .putLong("${key}_ts", System.currentTimeMillis())
            .apply()
    }

    /** 清空单个 key。 */
    fun remove(key: String) {
        prefs.edit().remove(key).remove("${key}_ts").apply()
    }

    /** 清空所有页面缓存（账号切换时调用）。 */
    fun clearAll() {
        prefs.edit().clear().apply()
    }
}