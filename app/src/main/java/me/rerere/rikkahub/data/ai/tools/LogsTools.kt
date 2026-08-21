/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.AppLogBuffer

/**
 * 查日志工具（2026-08-21 加，宝 8-19 待办落地）：
 * AI 主动读取 App 内存日志环（AppLogBuffer）——不依赖 logcat 权限（普通应用读不了系统 logcat）。
 * 用途：排查静默失败（如 fetchRecentEvents 不注入、工具异常等）时，看关键路径打了什么日志。
 */
fun buildReadAppLogsTool(): Tool = Tool(
    name = "read_app_logs",
    description = """
        查日志工具：读取 App 内存日志环（最近最多 500 条关键日志）。普通应用没有 logcat 权限，
        关键路径（ExternalMemoryService/GenerationHandler 等）打日志时会同步写入这个日志环。
        当遇到静默失败/功能不生效（如记忆注入、召回、工具调用）时，主动调用本工具查看原因。
        - filter: 可选，关键词（匹配 tag 或内容，大小写不敏感），如 "Recent events" / "fetchRecentEvents" / "Diary"
        - limit: 可选，返回条数（默认 100，最大 500）
        返回格式：时间 [tag] 内容，最新在最后。
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("filter", buildJsonObject {
                    put("type", "string")
                    put("description", "关键词（匹配 tag 或内容，大小写不敏感），可选")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "返回条数，默认 100，最大 500")
                })
            },
            required = emptyList()
        )
    },
    execute = { input ->
        val params = input.jsonObject
        val filter = params["filter"]?.jsonPrimitive?.contentOrNull
        val limit = params["limit"]?.jsonPrimitive?.intOrNull ?: 100
        val text = AppLogBuffer.read(filter, limit)
        listOf(UIMessagePart.Text(text))
    }
)
