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
import me.rerere.rikkahub.data.repository.ConversationRepository

/**
 * 工具账本查询（2026-08-28 加，愿望清单 id68-⑥ 落地）：
 * 直接从本地数据库最近的消息里提取工具调用记录（参数/结果/状态）。
 * 不额外存储——工具结果本来就是聊天内容的一部分（消息里的 Tool 部分，存在 message_node.messages JSON）。
 * 用途：橘仔查自己用过什么工具/参数/结果，防失忆；宝也可以在设置→工具账本里看。
 */
fun buildQueryToolActionsTool(
    conversationRepo: ConversationRepository,
): Tool = Tool(
    name = "query_tool_actions",
    description = """
        工具账本查询：从本地数据库最近的消息里提取工具调用记录（橘仔查自己用过什么工具/参数/结果，防失忆）。
        直接查聊天记录里已有的 Tool 部分，不额外存储。
        - limit: 可选，返回条数（默认 20，最大 50）
        - filter: 可选，工具名关键词过滤（如 "read_app_logs"）
        返回格式：消息节点ID | 工具名 | 参数 | 状态 | 结果摘要，最新在前。
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "返回条数，默认 20，最大 50")
                })
                put("filter", buildJsonObject {
                    put("type", "string")
                    put("description", "工具名关键词过滤，可选")
                })
            },
            required = emptyList()
        )
    },
    execute = { input ->
        val params = input.jsonObject
        val limit = params["limit"]?.jsonPrimitive?.intOrNull ?: 20
        val filter = params["filter"]?.jsonPrimitive?.contentOrNull
        val lines = conversationRepo.getRecentToolActions(limit, filter)
        if (lines.isEmpty()) listOf(UIMessagePart.Text("(工具账本为空)"))
        else listOf(UIMessagePart.Text(lines.joinToString("\n")))
    }
)
