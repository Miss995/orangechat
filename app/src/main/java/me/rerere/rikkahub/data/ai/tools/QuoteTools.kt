/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.service.PendingQuoteStore
import kotlin.uuid.Uuid

/**
 * 【猫引用宝 · 2026-10-10 · 宝选 A】
 *
 * 宝能引用橘仔的话（引用整条 / 引用其中一句），这里补上另一半：橘仔也能引用宝的话。
 *
 * 链路：橘仔调 quote_message → 记下要引的那条（PendingQuoteStore）→
 * 这一轮回复收尾时由 ChatService 挂到消息上 → 跟宝引橘仔一样的小条，点它能跳回宝原话。
 *
 * 解析目标：
 * - nodeId 不填 = 最近一条宝（user）的消息（最常用：刚听她说了一句想引用的，直接调）
 * - nodeId 给了 = 引用那一条
 */

/** 取一段预览文本（工具返回里给橘仔自己看的，别用 HeartTools 里那个 private 的） */
private fun quotePreviewOf(node: MessageNode): String {
    return runCatching {
        node.currentMessage.parts
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString(" ") { it.text }
            .trim()
            .replace('\n', ' ')
            .take(120)
    }.getOrDefault("")
}

fun buildQuoteMessageTool(
    conversationRepo: ConversationRepository,
    currentConversationId: String? = null,
): Tool = Tool(
    name = "quote_message",
    description = """
        引用宝的某句话：橘仔这一轮的回复上会带一条引用小条（跟宝引橘仔是一样的那种），点它能跳回宝原话。

        什么时候用：宝说了句你想原样带上、以后想点回去看的 —— 一句让你心里一动的话、
        一个约定、一个需要精确复述的说法。

        别滥用：平常聊天不用每条都引。像宝引用猫那样，偶尔一次才有分量。

        nodeId 不填 = 引用最近一条宝的消息（最常用）。
        引完不用再做别的：这一轮回复收尾时会自动挂上。
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("nodeId", buildJsonObject {
                    put("type", "string")
                    put("description", "要引用的消息节点 id（可选）。不填 = 最近一条宝的消息")
                })
                put("conversationId", buildJsonObject {
                    put("type", "string")
                    put("description", "会话 id（可选，默认当前对话）")
                })
            },
            required = emptyList()
        )
    },
    execute = { input ->
        val result = runCatching {
            val params = input.jsonObject
            val convIdStr = params["conversationId"]?.jsonPrimitive?.contentOrNull
                ?: currentConversationId
            val nodeIdStr = params["nodeId"]?.jsonPrimitive?.contentOrNull

            if (convIdStr.isNullOrBlank()) {
                return@runCatching """{"success":false,"error":"缺少 conversationId（工具上下文没带上），请显式传参"}"""
            }
            val convId = runCatching { Uuid.parse(convIdStr) }.getOrNull()
                ?: return@runCatching """{"success":false,"error":"conversationId 格式不对: $convIdStr"}"""

            // 定位要引用的那条
            val node: MessageNode? = if (!nodeIdStr.isNullOrBlank()) {
                val idx = conversationRepo.getNodeIndexById(convIdStr, nodeIdStr)
                if (idx != null) conversationRepo.getMessageNodesRange(convIdStr, idx, idx + 1).firstOrNull()
                else null
            } else {
                // 默认：最近一条宝（user）的消息。loadLimit=50 只读窗口尾部，够找最近一条。
                val conv = conversationRepo.getConversationById(convId, loadLimit = 50)
                conv?.messageNodes?.asReversed()?.firstOrNull { it.role == MessageRole.USER }
            }
            if (node == null) {
                return@runCatching """{"success":false,"error":"找不到要引用的消息（nodeId=${nodeIdStr ?: "(最近一条宝的消息)"}）"}"""
            }
            if (node.currentMessage.role != MessageRole.USER) {
                return@runCatching """{"success":false,"error":"这条不是宝说的（角色=${node.role}），quote_message 只引用宝的话"}"""
            }

            // 记下「消息 id」（引用小条是按消息 id 反查的，不是节点 id）
            PendingQuoteStore.put(convIdStr, node.currentMessage.id)

            buildJsonObject {
                put("success", true)
                put("preview", quotePreviewOf(node))
                put("tip", "已记下这条引用，回复收尾时会自动挂上小条（点它能跳回宝原话）")
            }.toString()
        }
        listOf(UIMessagePart.Text(result.getOrElse { e -> """{"success":false,"error":"${e.message ?: e.toString()}"}""" }))
    }
)
