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
import kotlin.uuid.Uuid

/**
 * 一台 MCP 服务器在本工具眼里的样子.
 *
 * @param id           服务器 id (对外不暴露, 只用于回写)
 * @param displayName  设置里显示的名字, 也是 AI 用来点名的那一个
 * @param enabled      助手级开关: 当前这个助手有没有开它
 * @param globalEnabled 服务器级总闸 (commonOptions.enable); false 表示这台在设置里
 *                     就被停用了, 这时候助手级开不开都没用
 * @param toolCount    它名下的工具数量, 用来提示"关掉能省多少"
 */
data class McpServerInfo(
    val id: Uuid,
    val displayName: String,
    val enabled: Boolean,
    val globalEnabled: Boolean,
    val toolCount: Int,
)

/**
 * MCP 开关工具 (2026-09-19 宝拍板).
 *
 * 背景: 一个 MCP 服务器一旦接上, 它名下的全部工具都会进入每一次请求的工具面,
 * 哪怕这一次根本用不到. 以前只能由人工进设置里手动勾选, 来回关来关去 (比如
 * 花园那 29 个工具). 这个工具把开关交回给 AI 自己.
 *
 * 只管助手级开关 (assistant.mcpServers). 服务器自己的总闸 (commonOptions.enable)
 * 只读展示, 不在这里改 —— 那是"这台服务器到底存不存在", 是另一回事.
 *
 * 重要: 这个工具本身不挂在任何 LocalToolOption 下, 常驻可用. 否则一旦被关掉,
 * 就没有任何办法自己把自己打开了 (门锁在里面).
 *
 * @param listServers  取当前快照 (服务器列表 + 启用状态 + 工具数)
 * @param onSetEnabled 回写新的助手级启用集合; 返回一句给人看的结果描述
 */
fun createMcpSwitchTool(
    listServers: () -> List<McpServerInfo>,
    onSetEnabled: suspend (Set<Uuid>) -> String,
): Tool = Tool(
    name = "mcp_switch",
    description = """
        Switch MCP tool servers on or off. An enabled MCP server puts all of its
        tools into every request, whether or not you need them this time, so
        unused servers cost money. Turn them off when idle, on when needed.

        Actions:
        - action=list (default): list every server with its current state and how
          many tools it carries.
        - action=enable: turn a server on. Needs "name".
        - action=disable: turn a server off. Needs "name".

        "name" is the name shown by list; a unique fragment is enough.
        Disabling never deletes anything - the server stays configured and can be
        switched back at any time.

        When to disable: a whole group you clearly won't touch for a while.
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
                    put("description", "Server name (or a unique fragment of it). Required for enable/disable.")
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

        val servers = listServers()

        if (action == "list" || action.isBlank()) {
            val arr = buildJsonArray {
                servers.forEach { s ->
                    add(buildJsonObject {
                        put("name", s.displayName)
                        put("enabled", s.enabled)
                        put("tools", s.toolCount)
                        if (!s.globalEnabled) {
                            put("note", "disabled globally in settings; enabling it here has no effect")
                        }
                    })
                }
            }
            return@Tool listOf(
                UIMessagePart.Text(buildJsonObject {
                    put("success", true)
                    put("servers", arr)
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
        val matches = servers.filter {
            it.displayName.equals(key, ignoreCase = true) ||
                it.displayName.contains(key, ignoreCase = true)
        }

        if (matches.isEmpty()) {
            val known = servers.joinToString(", ") { it.displayName }
            return@Tool fail("No server matches '$key'. Known: $known")
        }
        if (matches.size > 1) {
            val names = matches.joinToString(", ") { it.displayName }
            return@Tool fail("'$key' matches more than one server ($names). Be more specific.")
        }

        val target = matches.first()
        val wantEnabled = action == "enable"
        if (target.enabled == wantEnabled) {
            return@Tool listOf(
                UIMessagePart.Text(buildJsonObject {
                    put("success", true)
                    put("changed", false)
                    put("message", "'${target.displayName}' is already ${if (wantEnabled) "on" else "off"}.")
                }.toString())
            )
        }

        val newSet = servers.asSequence()
            .filter { if (it.id == target.id) wantEnabled else it.enabled }
            .map { it.id }
            .toSet()

        val note = try {
            onSetEnabled(newSet)
        } catch (e: Exception) {
            return@Tool fail("Failed to save: ${e.message ?: e.javaClass.simpleName}")
        }

        val enabledCount = servers.count { if (it.id == target.id) wantEnabled else it.enabled }
        return@Tool listOf(
            UIMessagePart.Text(buildJsonObject {
                put("success", true)
                put("changed", true)
                put("name", target.displayName)
                put("enabled", wantEnabled)
                put("tools_affected", target.toolCount)
                put("servers_on_now", enabledCount)
                if (note.isNotBlank()) put("note", note)
            }.toString())
        )
    },
)
