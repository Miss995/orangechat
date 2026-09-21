/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.miniapp

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.data.ai.tools.ToolSurfaceBuilder
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import org.koin.java.KoinJavaComponent.getKoin

/**
 * 工具面板的数据管道 —— 把 App 的工具面借给 MiniApp 网页按。
 *
 * 网页那边只要走已有的 bridge 通道：
 *   const r = await __bridgeCall('tools.list')                                   // 列出全部工具
 *   const r = await __bridgeCall('tools.call', { name: 'xxx', args: '{"k":"v"}' }) // 执行一个
 * 两个方法都返回 JSON 字符串，网页自己 JSON.parse。
 *
 * 工具面走的是 [ToolSurfaceBuilder] —— 跟 ChatService / WorkflowEngine 同一个真源，
 * 所以面板上看到的就是当前助手实际拥有的工具（local + system + search + workspace + skill + MCP + plugin），
 * 不会出现"面板里有、但猫那边没有"这种漂移。
 *
 * 执行语义：宝自己按的，不是系统流，所以 isHeadless = false 走完整工具面；
 * 需要审批的工具也直接执行（按下去的人就是批准的人）。
 * 结果超时 [EXEC_TIMEOUT_MS] 兜底，避免某个工具卡死把 WebView 的回调吊在那儿。
 */
object MiniAppToolBridge {
    private const val TAG = "MiniAppToolBridge"

    /** 单次工具执行的最长等待。超了就当失败返回，不让回调悬空。 */
    private const val EXEC_TIMEOUT_MS = 120_000L

    private val json = Json { ignoreUnknownKeys = true }

    /** 这个方法名是不是本桥管的。被 MiniAppPage 的 bridge 分发用来决定走哪儿。 */
    fun handles(method: String): Boolean = method == "tools.list" || method == "tools.call"

    /** 统一的入口。返回 JSON 字符串。 */
    suspend fun handle(method: String, params: Map<String, String>): String = when (method) {
        "tools.list" -> listTools()
        "tools.call" -> callTool(
            name = params["name"].orEmpty(),
            argsJson = params["args"].orEmpty(),
        )
        else -> errorJson("unknown method: $method")
    }

    /** 取当前助手的完整工具面（跟聊天那边同一个真源）。 */
    private suspend fun resolveTools(): List<Tool> {
        val koin = getKoin()
        val settingsStore = koin.get<SettingsStore>()
        val settings = settingsStore.settingsFlow.value
        val assistant = settings.getCurrentAssistant()
        return koin.get<ToolSurfaceBuilder>().build(
            assistant = assistant,
            settings = settings,
            invocationContext = ToolInvocationContext(
                callerAssistantId = assistant.id.toString(),
                callerConversationId = null,
                isHeadless = false,
                modelCanSeeImages = true,
            ),
        )
    }

    private suspend fun listTools(): String {
        val tools = try {
            resolveTools()
        } catch (e: Exception) {
            Log.w(TAG, "listTools: resolve failed", e)
            return errorJson("${e.javaClass.simpleName}: ${e.message}")
        }
        return buildJsonObject {
            put("ok", true)
            put("count", tools.size)
            put("tools", buildJsonArray {
                tools.forEach { tool ->
                    add(buildJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("needsApproval", tool.needsApproval)
                    })
                }
            })
        }.toString()
    }

    private suspend fun callTool(name: String, argsJson: String): String {
        if (name.isBlank()) return errorJson("empty tool name")

        val tools = try {
            resolveTools()
        } catch (e: Exception) {
            Log.w(TAG, "callTool: resolve failed", e)
            return errorJson("${e.javaClass.simpleName}: ${e.message}")
        }

        val tool = tools.firstOrNull { it.name == name }
            ?: return errorJson("tool not found: $name")

        val args = try {
            json.parseToJsonElement(argsJson.ifBlank { "{}" })
        } catch (e: Exception) {
            return errorJson("bad args json: ${e.message}")
        }

        return try {
            Log.i(TAG, "callTool: executing $name")
            val parts = withTimeoutOrNull(EXEC_TIMEOUT_MS) { tool.execute(args) }
                ?: return errorJson("timeout after ${EXEC_TIMEOUT_MS / 1000}s")
            buildJsonObject {
                put("ok", true)
                put("tool", name)
                put("output", partsToText(parts))
            }.toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "callTool: exec failed $name", e)
            return errorJson("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** 工具吐出的是 UIMessagePart 列表，这里挑人看得懂的部分拼成纯文本。 */
    private fun partsToText(parts: List<UIMessagePart>): String {
        val sb = StringBuilder()
        parts.forEach { part ->
            when (part) {
                is UIMessagePart.Text -> if (part.text.isNotBlank()) {
                    sb.append(part.text.trim()).append('\n')
                }
                is UIMessagePart.Reasoning -> Unit
                is UIMessagePart.Image -> sb.append("[图片] ").append(part.url).append('\n')
                else -> sb.append(part.toString()).append('\n')
            }
        }
        return sb.toString().trim().ifBlank { "(这个工具没有返回内容)" }
    }

    private fun errorJson(message: String?): String = buildJsonObject {
        put("ok", false)
        put("error", message ?: "unknown error")
    }.toString()
}
