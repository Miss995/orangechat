/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * 一个插件在这个工具眼里的样子.
 *
 * @param id        插件 id（反向域名格式；对外不暴露，只用于回写）
 * @param name      插件显示名，也是 AI 用来点名的那一个
 * @param enabled   当前是否启用
 * @param toolCount 它名下的工具数量，用来提示"关掉能省多少"
 */
data class PluginEntry(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val toolCount: Int,
)

/**
 * 插件开关工具（2026-09-28 宝拍板）.
 *
 * 背景：一个插件一旦启用，它名下的全部工具都会进入每一次请求的工具面，
 * 哪怕这一次根本用不到。以前只能进设置里手动点，来回关来关去。
 * 这个工具把开关交回给 AI 自己。
 *
 * 与 MCP 开关（mcp_switch）的区别：插件**没有助手级设置**，
 * 启停就是全局的，所以不用绕"当前助手 / 对话挂着的助手"那个坑，逻辑干净。
 *
 * 重要：这个工具本身不挂在任何 LocalToolOption 下，常驻可用。
 * 否则一旦被关掉，就没有任何办法自己把自己打开了（门锁在里面）。
 *
 * 关闭插件不会删除任何东西：插件仍然装着，随时可以再开。
 *
 * @param listPlugins  取当前快照（插件列表 + 启用状态 + 工具数）
 * @param onSetEnabled 回写单个插件的开/关；返回一句给人看的结果描述
 */
fun createPluginSwitchTool(
    listPlugins: suspend () -> List<PluginEntry>,
    onSetEnabled: suspend (String, Boolean) -> String,
): Tool = Tool(
    name = "plugin_switch",
    description = """
        Switch plugins on or off. An enabled plugin puts all of its tools into
        every request, whether or not you need them this time, so unused
        plugins cost money and bloat the prompt. Turn them off when idle, on
        when needed.

        Actions:
        - action=list (default): list every plugin with its current state and
          how many tools it carries.
        - action=enable: turn a plugin on. Needs "name".
        - action=disable: turn a plugin off. Needs "name".

        "name" is the name shown by list; a unique fragment is enough.
        Disabling never deletes anything - the plugin stays installed and can
        be switched back at any time. Enabling loads the plugin (may take a
        moment); disabling unloads it immediately.

        When to disable: a plugin you clearly won't touch for a while.
        When to leave alone: if you're not sure you're done with it, keep it on.
        Flipping back and forth costs more than it saves.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("action") {
                    put("type", "string")
                    put("description", "list (default) | enable | disable")
                }
                putJsonObject("name") {
                    put("type", "string")
                    put("description", "Plugin name (or a unique fragment of it). Required for enable/disable.")
                }
            },
            required = emptyList<String>()
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val action = (params["action"]?.jsonPrimitive?.contentOrNull ?: "list").lowercase()
        val rawName = params["name"]?.jsonPrimitive?.contentOrNull

        fun fail(msg: String) = listOf(
            UIMessagePart.Text(buildJsonObject {
                put("success", false)
                put("error", msg)
            }.toString())
        )

        val plugins = listPlugins()

        if (action == "list" || action.isBlank()) {
            val arr = buildJsonArray {
                plugins.forEach { p ->
                    add(buildJsonObject {
                        put("name", p.name)
                        put("enabled", p.enabled)
                        put("tools", p.toolCount)
                    })
                }
            }
            return@Tool listOf(
                UIMessagePart.Text(buildJsonObject {
                    put("success", true)
                    put("plugins", arr)
                    put("hint", "use action=enable/disable with name to switch one")
                }.toString())
            )
        }

        if (action != "enable" && action != "disable") {
            return@Tool fail("Unknown action '$action'. Use list, enable or disable.")
        }
        if (rawName.isNullOrBlank()) {
            return@Tool fail("'name' is required for $action.")
        }

        val key = rawName.trim()
        // 精确同名优先：否则「日记」可能同时命中「日记」和「日记本」。
        val exact = plugins.filter { it.name.equals(key, ignoreCase = true) }
        val matches = if (exact.isNotEmpty()) exact else plugins.filter {
            it.name.contains(key, ignoreCase = true)
        }

        if (matches.isEmpty()) {
            val known = plugins.joinToString(", ") { it.name }
            return@Tool fail("No plugin matches '$key'. Known: $known")
        }
        if (matches.size > 1) {
            val names = matches.joinToString(", ") { it.name }
            return@Tool fail("'$key' matches more than one plugin ($names). Be more specific.")
        }

        val target = matches.first()
        val wantEnabled = action == "enable"
        if (target.enabled == wantEnabled) {
            return@Tool listOf(
                UIMessagePart.Text(buildJsonObject {
                    put("success", true)
                    put("changed", false)
                    put("message", "'${target.name}' is already ${if (wantEnabled) "on" else "off"}.")
                }.toString())
            )
        }

        val note = try {
            onSetEnabled(target.id, wantEnabled)
        } catch (e: Exception) {
            return@Tool fail("Failed to save: ${e.message ?: e.javaClass.simpleName}")
        }

        val enabledCount = plugins.count {
            if (it.id == target.id) wantEnabled else it.enabled
        }
        return@Tool listOf(
            UIMessagePart.Text(buildJsonObject {
                put("success", true)
                put("changed", true)
                put("name", target.name)
                put("enabled", wantEnabled)
                put("tools_affected", target.toolCount)
                put("plugins_on_now", enabledCount)
                if (note.isNotBlank()) put("note", note)
            }.toString())
        )
    },
)
