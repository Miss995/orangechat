/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.utils.toLocalString
import java.time.LocalDate

fun buildMemoryTools(
    json: Json,
    onCreation: suspend (String) -> AssistantMemory,
    onUpdate: suspend (Int, String) -> AssistantMemory,
    onDelete: suspend (Int) -> Unit
): List<Tool> = listOf(
    Tool(
        name = "memory_tool",
        description = """
            The memory tool stores long-term information across conversations.
            Use `action` to control the operation: `create` (add), `edit` (update), `delete` (remove).
            - No relevant record: `create` + `content`
            - Existing relevant record: `edit` + `id` + `content`
            - Outdated/irrelevant record: `delete` + `id`
            Memories will automatically appear in the <memories> tag in later conversations.
            Do not store sensitive information (e.g., ethnicity, religion, sexual orientation, political views, sex life, criminal records).
            You may store: preferred name, preferences, plans, work-related notes, chat style preferences, first chat time, etc.
            Do not show memory content directly in the conversation unless the user explicitly asks.
            Today is ${LocalDate.now().toLocalString(true)}.
            Similar memories should be merged; prefer updating existing records.

            Examples:
            {"action":"create","content":"User prefers brief replies and is more active on weekends."}
            {"action":"edit","id":12,"content":"User’s preferred name updated to “A-Xing”, prefers Chinese replies."}
            {"action":"delete","id":7}
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("create")
                                add("edit")
                                add("delete")
                            }
                        )
                        put("description", "Operation to perform: create, edit, or delete")
                    })
                    put("id", buildJsonObject {
                        put("type", "integer")
                        put("description", "The id of the memory record (required for edit/delete)")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "The content of the memory record (required for create/edit)")
                    })
                },
                required = listOf("action")
            )
        },
        execute = {
            val params = it.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
            val payload = when (action) {
                "create" -> {
                    val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                    json.encodeToJsonElement(AssistantMemory.serializer(), onCreation(content))
                }

                "edit" -> {
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                    val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                    json.encodeToJsonElement(AssistantMemory.serializer(), onUpdate(id, content))
                }

                "delete" -> {
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                    onDelete(id)
                    buildJsonObject {
                        put("success", true)
                        put("id", id)
                    }
                }

                else -> error("unknown action: $action, must be one of [create, edit, delete]")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )
)

/**
 * 查原文工具（主动版）：按「日期 + 消息号」从外置记忆库（聊天记录档案）拉取那天的原始聊天记录。
 * 模型在 OB/Mem0/外置库召回命中事件（看到某年某月某日或 sourceDate/sourceIds）后，
 * 想深挖更多原文细节时主动调用；被动版由召回逻辑自动展开（fetchEventSources take(4)）。
 */
fun buildFetchChatSourcesTool(
    onFetch: suspend (date: String, ids: List<Int>) -> List<String>,
): Tool = Tool(
    name = "fetch_chat_sources",
    description = """
        查原文工具：按「日期 + 消息号」从外置记忆库（聊天记录档案）拉取那天的原始聊天记录。
        当你在召回内容（OB/Mem0/外置库事件）里看到某条记忆提到某年某月某日（或带 sourceDate/sourceIds）时，
        如果想重温当时的对话原文、把细节讲给用户听，就主动调用本工具。
        - date: 必填，格式 yyyy-MM-dd（如 2026-08-13）
        - ids: 可选，消息号列表（当天 1-based 序号，对应事件的 sourceIds）；不传则返回当天开头 30 条
        返回格式：[用户]/[AI] 原文内容，按时间顺序。拿到原文后自然转述即可，不需要刻意修饰。
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("date", buildJsonObject {
                    put("type", "string")
                    put("description", "日期，格式 yyyy-MM-dd")
                })
                put("ids", buildJsonObject {
                    put("type", "array")
                    put("description", "消息号列表（当天1-based序号，对应事件sourceIds），可选")
                    put("items", buildJsonObject { put("type", "integer") })
                })
            },
            required = listOf("date")
        )
    },
    execute = { input ->
        val params = input.jsonObject
        val date = params["date"]?.jsonPrimitive?.contentOrNull ?: error("date is required (yyyy-MM-dd)")
        val ids = params["ids"]?.jsonArray?.mapNotNull { it.jsonPrimitive.intOrNull } ?: emptyList()
        val lines = onFetch(date, ids)
        listOf(UIMessagePart.Text(if (lines.isEmpty()) "没有找到 $date 的聊天记录" else lines.joinToString("\n")))
    }
)
