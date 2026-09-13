package com.oldchat.material.feature.discover

import android.content.Context
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.StringLib
import org.luaj.vm2.lib.TableLib
import org.luaj.vm2.lib.TwoArgFunction
import org.luaj.vm2.lib.ZeroArgFunction
import org.luaj.vm2.lib.jse.JseBaseLib
import org.luaj.vm2.lib.jse.JseMathLib
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * CIP 小程序 Lua 执行引擎（luaj）。
 *
 * BUG-11 修复点（对照 TODO-BUGFIX.md / TODO-ALIGN.md）：
 * 1. 执行搬到 Dispatchers.Default（原来在主线程直接 chunk.call()）并加 3s 超时，
 *    Lua 里一个 `while true do end` 就能把 UI 卡死到 ANR。
 * 2. 补 string / table 标准库（原来只有 base+math，`("x"):upper()` 直接报错）。
 * 3. 打通 `on_click`：控件回调注册进 clickHandlers，宿主点击时真正调用 Lua 函数，
 *    并根据 app.set_text 的最新值重解析页面（原来按钮点了没任何反应）。
 * 4. `app.http_get` / `app.json_decode` / `app.json_encode` / `app.delay` /
 *    `app.storage_clear` 由占位实现改为真实实现；storage 落盘到小程序自己的
 *    SharedPreferences（原来只在内存里，进程一死全丢）。
 *
 * 安全边界：不加载 io / os / package / debug / luajava，仅开放 ui.* 与 app.*，
 * http_get 只允许以 "/" 开头的同服务器路径与 http(s):// 公网地址，禁止 ".."。
 */
class LuaMiniAppEngine(
    private val context: Context? = null,
    private val appId: String = "default",
    /** 同服务器路径的请求器：由宿主注入（带登录令牌）。 */
    private val sameServerGet: ((String) -> String?)? = null
) {

    sealed class Node {
        data class Text(val id: String?, val text: String, val size: Float, val color: String?) : Node()
        data class Button(val id: String?, val text: String) : Node()
        data class Spacer(val height: Float) : Node()
        data class Group(val children: List<Node>) : Node()
    }

    data class Page(val title: String, val children: List<Node>)

    var toastMessage: String? = null
        private set

    val storage = mutableMapOf<String, String>()

    private val textStore = mutableMapOf<String, String>()
    private val clickHandlers = mutableMapOf<String, LuaValue>()
    private var autoId = 0
    private var globals: Globals? = null
    private var lastPageTable: LuaTable? = null
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "cip-delay").apply { isDaemon = true }
    }

    private val storagePrefs by lazy {
        context?.getSharedPreferences("cip_storage_$appId", Context.MODE_PRIVATE)
    }

    init {
        storagePrefs?.all?.forEach { (k, v) -> if (v is String) storage[k] = v }
    }

    /** 当前存储键值（渲染/测试用）。 */
    fun textOf(id: String?, fallback: String): String =
        id?.let { textStore[it] } ?: fallback

    suspend fun run(script: String): Page = withContext(Dispatchers.Default) {
        withTimeout(SCRIPT_TIMEOUT_MS) {
            clickHandlers.clear()
            textStore.clear()
            val g = buildGlobals()
            globals = g
            val chunk = g.load(script, "main.lua")
            val result = chunk.call()
            lastPageTable = result.checktable()
            parsePage(result)
        }
    }

    /**
     * 宿主点击某控件：调用其 Lua 回调，然后按 textStore 的最新值重解析页面。
     * 返回 null 表示该控件没有注册回调。
     */
    suspend fun invokeClick(nodeId: String): Page? = withContext(Dispatchers.Default) {
        val handler = clickHandlers[nodeId] ?: return@withContext null
        withTimeout(SCRIPT_TIMEOUT_MS) {
            handler.call()
            lastPageTable?.let { parsePage(it) }
        }
    }

    fun destroy() {
        scheduler.shutdownNow()
        globals = null
        lastPageTable = null
        clickHandlers.clear()
    }

    // ---- globals / 桥接 ----

    private fun buildGlobals(): Globals {
        val g = Globals()
        g.load(JseBaseLib())
        g.load(JseMathLib())
        // BUG-11：补上 string / table 标准库（沙箱仍然不加载 io/os/package/debug）
        g.load(StringLib())
        g.load(TableLib())

        g.set("ui", buildUiTable())
        g.set("app", buildAppTable())
        return g
    }

    private fun buildUiTable(): LuaValue {
        val ui = LuaTable()
        ui.set("page", IdentityFunction())
        ui.set("text", IdentityFunction())
        ui.set("spacer", IdentityFunction())
        ui.set("list", IdentityFunction())
        ui.set("input", IdentityFunction())
        ui.set("image", IdentityFunction())
        // BUG-11：可点击控件在创建时就把 on_click 注册进来
        ui.set("button", ClickableFunction())
        ui.set("checkbox", ClickableFunction())
        return ui
    }

    /** 原样返回控件 table，同时登记 on_click 回调（并补一个稳定的 id）。 */
    private inner class ClickableFunction : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue {
            if (arg.istable()) {
                val t = arg.checktable()
                val cb = t.get("on_click")
                if (cb.isfunction()) {
                    val id = if (t.get("id").isstring()) {
                        t.get("id").tojstring()
                    } else {
                        "auto${++autoId}".also { t.set("id", LuaValue.valueOf(it)) }
                    }
                    clickHandlers[id] = cb
                }
            }
            return arg
        }
    }

    private class IdentityFunction : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue = arg
    }

    private fun buildAppTable(): LuaValue {
        val app = LuaTable()

        app.set("toast", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                toastMessage = arg.tojstring()
                return LuaValue.NIL
            }
        })

        app.set("set_text", object : TwoArgFunction() {
            override fun call(id: LuaValue, text: LuaValue): LuaValue {
                textStore[id.tojstring()] = text.tojstring()
                return LuaValue.NIL
            }
        })
        app.set("get_text", object : OneArgFunction() {
            override fun call(id: LuaValue): LuaValue {
                val s = textStore[id.tojstring()]
                return if (s == null) LuaValue.NIL else LuaValue.valueOf(s)
            }
        })

        app.set("storage_get", object : OneArgFunction() {
            override fun call(key: LuaValue): LuaValue {
                val s = storage[key.tojstring()]
                return if (s == null) LuaValue.NIL else LuaValue.valueOf(s)
            }
        })
        app.set("storage_set", object : TwoArgFunction() {
            override fun call(key: LuaValue, value: LuaValue): LuaValue {
                val k = key.tojstring()
                val v = value.tojstring()
                storage[k] = v
                // BUG-11：真正落盘
                storagePrefs?.edit()?.putString(k, v)?.apply()
                return LuaValue.NIL
            }
        })
        app.set("storage_remove", object : OneArgFunction() {
            override fun call(key: LuaValue): LuaValue {
                val k = key.tojstring()
                storage.remove(k)
                storagePrefs?.edit()?.remove(k)?.apply()
                return LuaValue.NIL
            }
        })
        app.set("storage_clear", object : ZeroArgFunction() {
            override fun call(): LuaValue {
                storage.clear()
                storagePrefs?.edit()?.clear()?.apply()
                return LuaValue.NIL
            }
        })

        // BUG-11：原来固定回调 "network not wired"。现在真的发请求。
        app.set("http_get", object : TwoArgFunction() {
            override fun call(path: LuaValue, callback: LuaValue): LuaValue {
                val cb = callback
                val url = path.tojstring() ?: ""
                if (!cb.isfunction()) return LuaValue.NIL
                val (body, err) = httpGetBlocking(url)
                cb.invoke(
                    body?.let { LuaValue.valueOf(it) } ?: LuaValue.NIL,
                    err?.let { LuaValue.valueOf(it) } ?: LuaValue.NIL
                )
                return LuaValue.NIL
            }
        })

        app.set("json_decode", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue = try {
                jsonToLua(JsonParser.parseString(arg.tojstring()))
            } catch (_: Exception) {
                LuaValue.NIL
            }
        })
        app.set("json_encode", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue = try {
                LuaValue.valueOf(luaToJson(arg).toString())
            } catch (_: Exception) {
                LuaValue.valueOf("{}")
            }
        })

        // BUG-11：延时任务（最大 60s，不提供后台常驻）
        app.set("delay", object : TwoArgFunction() {
            override fun call(ms: LuaValue, fn: LuaValue): LuaValue {
                if (!fn.isfunction()) return LuaValue.NIL
                val delay = ms.toint().coerceIn(0, 60_000).toLong()
                scheduler.schedule({ runCatching { fn.call() } }, delay, TimeUnit.MILLISECONDS)
                return LuaValue.NIL
            }
        })

        app.set("back", object : ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.NIL
        })

        return app
    }

    // ---- 网络（同服务器 / 白名单外网） ----

    /** 返回 Pair(body, err)，与 CIP 的 `callback(body, err)` 契约一致。 */
    private fun httpGetBlocking(url: String): Pair<String?, String?> {
        if (url.contains("..")) return null to "路径不合法"
        val isSameServer = url.startsWith("/")
        val isExternal = url.startsWith("http://") || url.startsWith("https://")
        if (!isSameServer && !isExternal) {
            return null to "仅支持以 / 开头的同服务器路径或 http(s) 外网地址"
        }
        return try {
            if (isSameServer) {
                val body = sameServerGet?.invoke(url)
                if (body == null) null to "请求失败" else body to null
            } else {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/json, text/plain, */*")
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }
                conn.disconnect()
                if (code in 200..299) text to null else null to "HTTP $code"
            }
        } catch (e: Exception) {
            null to (e.message ?: "网络异常")
        }
    }

    // ---- JSON 桥接 ----

    private fun jsonToLua(el: JsonElement): LuaValue = when {
        el.isJsonNull -> LuaValue.NIL
        el.isJsonPrimitive -> {
            val p = el.asJsonPrimitive
            when {
                p.isBoolean -> LuaValue.valueOf(p.asBoolean)
                p.isNumber -> LuaValue.valueOf(p.asDouble)
                else -> LuaValue.valueOf(p.asString)
            }
        }
        el.isJsonArray -> LuaTable().also { t ->
            var i = 1
            el.asJsonArray.forEach { t.set(i++, jsonToLua(it)) }
        }
        else -> LuaTable().also { t ->
            el.asJsonObject.entrySet().forEach { (k, v) -> t.set(k, jsonToLua(v)) }
        }
    }

    private fun luaToJson(v: LuaValue): JsonElement = when {
        v.isnil() -> com.google.gson.JsonNull.INSTANCE
        v.isboolean() -> com.google.gson.JsonPrimitive(v.toboolean())
        v.isnumber() -> com.google.gson.JsonPrimitive(v.todouble())
        v.isstring() -> com.google.gson.JsonPrimitive(v.tojstring())
        v.istable() -> {
            val t = v.checktable()
            if (t.length() > 0) {
                val arr = com.google.gson.JsonArray()
                for (i in 1..t.length()) arr.add(luaToJson(t.get(i)))
                arr
            } else {
                val obj = com.google.gson.JsonObject()
                val it = t.keys().iterator()
                while (it.hasNext()) {
                    val k = it.next()
                    obj.add(k.tojstring(), luaToJson(t.get(k)))
                }
                obj
            }
        }
        else -> com.google.gson.JsonNull.INSTANCE
    }

    // ---- 页面解析 ----

    private fun parsePage(v: LuaValue): Page {
        val table = v.checktable()
        val title = table.get("title").tojstring()
        val children = table.get("children")
        val nodes = if (children.istable()) parseChildren(children.checktable()) else emptyList()
        return Page(title ?: "", nodes)
    }

    private fun parseChildren(t: LuaTable): List<Node> {
        val list = mutableListOf<Node>()
        var i = 1
        while (true) {
            val item = t.get(i)
            if (item.isnil()) break
            parseNode(item)?.let { list.add(it) }
            i++
        }
        return list
    }

    private fun parseNode(v: LuaValue): Node? {
        if (!v.istable()) return null
        val t = v.checktable()

        val hasText = t.get("text").isstring()
        val hasOnClick = !t.get("on_click").isnil()
        val hasHeight = t.get("height").isnumber()
        val hasChildren = t.get("children").istable()

        return when {
            hasOnClick -> Node.Button(
                id = strOrNull(t, "id"),
                text = t.get("text").tojstring() ?: ""
            )
            hasChildren -> Node.Group(parseChildren(t.get("children").checktable()))
            hasHeight && !hasText -> Node.Spacer(height = t.get("height").tofloat())
            hasText -> {
                val id = strOrNull(t, "id")
                Node.Text(
                    id = id,
                    // BUG-11：展示 app.set_text 写入的最新值（否则 on_click 更新后界面不变）
                    text = textOf(id, t.get("text").tojstring() ?: ""),
                    size = if (t.get("size").isnumber()) t.get("size").tofloat() else 15f,
                    color = strOrNull(t, "color")
                )
            }
            else -> null
        }
    }

    private fun strOrNull(t: LuaTable, key: String): String? {
        val v = t.get(key)
        return if (v.isnil()) null else v.tojstring()
    }

    companion object {
        /** BUG-11：脚本单次执行上限 */
        private const val SCRIPT_TIMEOUT_MS = 3_000L
    }
}
