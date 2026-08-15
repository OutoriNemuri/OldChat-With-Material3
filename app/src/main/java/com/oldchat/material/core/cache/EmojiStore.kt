package com.oldchat.material.core.cache

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 我的表情（本地持久化）存储。
 *
 * 用户在表情广场点「保存」，或通过「+ 添加」添加的表情，落地到本地
 * SharedPreferences（`my_emoji_store`），以磁贴式展示、点击即可发送。
 * 与「表情广场」（服务端 /emoji/plaza）解耦：广场负责浏览与上传，
 * 「我的表情」只保存到本机、供聊天输入即时调用。
 */
class EmojiStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("my_emoji_store", Context.MODE_PRIVATE)

    private val gson = Gson()

    data class StoredEmoji(
        val id: String,
        val name: String,
        val mediaUrl: String,
        val isGif: Boolean = false
    )

    /** 读取全部已保存表情（按保存顺序）。 */
    fun getAll(): List<StoredEmoji> {
        val json = prefs.getString(KEY_EMOJIS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<StoredEmoji>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 是否已保存某个表情（按 id 判断）。 */
    fun contains(id: String): Boolean {
        return getAll().any { it.id == id }
    }

    /**
     * 添加一个表情（幂等：同 id 已存在则忽略）。
     * @return 是否真正新增
     */
    fun add(emoji: StoredEmoji): Boolean {
        val current = getAll().toMutableList()
        if (current.any { it.id == emoji.id }) return false
        current.add(emoji)
        save(current)
        return true
    }

    /** 移除一个表情（按 id）。 */
    fun remove(id: String) {
        val current = getAll().filterNot { it.id == id }
        save(current)
    }

    /** 清空全部。 */
    fun clearAll() {
        prefs.edit().remove(KEY_EMOJIS).apply()
    }

    private fun save(list: List<StoredEmoji>) {
        prefs.edit().putString(KEY_EMOJIS, gson.toJson(list)).apply()
    }

    companion object {
        private const val KEY_EMOJIS = "my_emojis"
    }
}
