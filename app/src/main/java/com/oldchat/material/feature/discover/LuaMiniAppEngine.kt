package com.oldchat.material.feature.discover

import org.luaj.vm2.Globals
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.TwoArgFunction
import org.luaj.vm2.lib.ZeroArgFunction
import org.luaj.vm2.lib.jse.JseBaseLib
import org.luaj.vm2.lib.jse.JseMathLib

/**
 * CIP 小程序 Lua 执行引擎 —— 用 luaj-core 解释器，注册 ui / app 桥接，
 * 把 main.lua 返回的 `ui.page({...})` 描述解析为可渲染的页面模型。
 *
 * 安全模型（对齐 lua-cip.md §2）：
 * - 仅注册 base/string/math 基础库，不注册 io/os/package/debug/require/dofile/loadfile/luajava。
 * - 宿主只开放 ui.*（页面描述）与 app.*（toast/文本读写/存储/http_get 白名单桥接）。
 */
class LuaMiniAppEngine {

    /** 页面控件模型（供 Compose 渲染）。 */
    sealed class Node {
        data class Text(val id: String?, val text: String, val size: Float, val color: String?) : Node()
        data class Button(val id: String?, val text: String) : Node()
        data class Spacer(val height: Float) : Node()
        // 命名用 Group 避免与 kotlin.collections.List 冲突
        data class Group(val children: List<Node>) : Node()
    }

    data class Page(val title: String, val children: List<Node>)

    /** 桥接运行时的副作用状态（由 ViewModel 读取）。 */
    var toastMessage: String? = null
        private set
    val storage = mutableMapOf<String, String>()

    /** 执行 main.lua，返回页面描述；脚本错误或未 return ui.page 时抛异常。 */
    fun run(script: String): Page {
        val globals = buildGlobals()
        val chunk = globals.load(script, "main.lua")
        val result = chunk.call()
        return parsePage(result)
    }

    private fun buildGlobals(): Globals {
        val globals = Globals()
        globals.load(JseBaseLib())
        globals.load(JseMathLib())

        globals.set("ui", buildUiTable())
        globals.set("app", buildAppTable())
        return globals
    }

    private fun buildUiTable(): LuaValue {
        val ui = LuaTable()
        // 所有 ui.* 控件函数都只是把传入的 table 原样返回，
        // 由 main.lua 把它们组织进 ui.page 的 children 数组，最终整体返回。
        ui.set("page", IdentityFunction())
        ui.set("text", IdentityFunction())
        ui.set("button", IdentityFunction())
        ui.set("spacer", IdentityFunction())
        ui.set("list", IdentityFunction())
        ui.set("input", IdentityFunction())
        ui.set("image", IdentityFunction())
        ui.set("checkbox", IdentityFunction())
        return ui
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

        val textStore = mutableMapOf<String, String>()
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
                storage[key.tojstring()] = value.tojstring()
                return LuaValue.NIL
            }
        })
        app.set("storage_remove", object : OneArgFunction() {
            override fun call(key: LuaValue): LuaValue {
                storage.remove(key.tojstring())
                return LuaValue.NIL
            }
        })

        // http_get(path, callback)：异步占位（占位实现：回调 err，真实网络桥接后续补充）
        app.set("http_get", object : TwoArgFunction() {
            override fun call(path: LuaValue, callback: LuaValue): LuaValue {
                val cb = callback
                if (cb.isfunction()) {
                    cb.invoke(LuaValue.NIL, LuaValue.valueOf("network not wired"))
                }
                return LuaValue.NIL
            }
        })

        app.set("json_decode", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue = LuaValue.NIL
        })
        app.set("json_encode", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue = LuaValue.valueOf("{}")
        })
        app.set("back", object : ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.NIL
        })

        return app
    }

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
            hasText -> Node.Text(
                id = strOrNull(t, "id"),
                text = t.get("text").tojstring() ?: "",
                size = if (t.get("size").isnumber()) t.get("size").tofloat() else 15f,
                color = strOrNull(t, "color")
            )
            else -> null
        }
    }

    private fun strOrNull(t: LuaTable, key: String): String? {
        val v = t.get(key)
        return if (v.isnil()) null else v.tojstring()
    }
}