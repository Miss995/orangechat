/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.SLASH_COMMAND_SAFE_TOOLS
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.data.repository.MemoryRepository

/**
 * 助手工具清单——统一组装（2026-09-12 宝拍板「治本」）
 *
 * 背景：聊天路径（GenerationHandler）和主动消息路径（ProactiveMessageTriggerService）
 * 原本各维护一份工具清单。主动消息那份是早期的「精简版」，只挂 本地/系统/MCP/插件 四类，
 * 漏了七类：查日志 / 工具账本 / 心动收藏 / 记忆 / 写文件 / 查原文 / 自指区五件套。
 * 后果：主动消息醒来的橘仔手里没有自指区钥匙——workflow 让它「先翻自指区（self_note_query）」
 * 也翻不了（宝 2026-09-12 早上发现，10:00 那个「自己待会儿」就是这么空手醒的）。
 *
 * 治本：把这份清单抽成一个函数，两条路径共用。以后新增工具只改这里，
 * 不会再出现「聊天有、主动消息没有」。
 *
 * @param extraTools 调用方自带的外部工具（本地工具 / 系统工具 / MCP / 插件）。
 * @param slashCommandText 非空 = 斜杠命令模式，只返回白名单内的安全工具。
 */
suspend fun buildAssistantTools(
    context: Context,
    conversationId: String?,
    assistant: Assistant,
    settings: Settings,
    memoryRepo: MemoryRepository,
    conversationRepo: ConversationRepository,
    favoriteRepo: FavoriteRepository,
    json: Json,
    extraTools: List<Tool> = emptyList(),
    slashCommandText: String? = null,
): List<Tool> {
    Log.i("ToolAssembly", "buildAssistantTools: assistant=${assistant.id}")
    val assembled = buildList {
        // 查日志工具（2026-08-21：排查静默失败用，始终注入）
        add(buildReadAppLogsTool())
        // 工具账本（2026-08-28：查工具调用记录防失忆，直接查数据库不额外存储）
        add(buildQueryToolActionsTool(conversationRepo))
        // 心动收藏夹（2026-09-06：五感记忆库 V1——橘仔收藏宝的话，理由+五感）
        add(buildHeartSaveTool(favoriteRepo, conversationRepo, conversationId))
        add(buildHeartQueryTool(favoriteRepo, conversationRepo, conversationId))
        // 记忆工具（助手开启记忆时）
        if (assistant.enableMemory) {
            val memoryAssistantId = if (assistant.useGlobalMemory) {
                MemoryRepository.GLOBAL_MEMORY_ID
            } else {
                assistant.id.toString()
            }
            buildMemoryTools(
                json = json,
                onCreation = { content -> memoryRepo.addMemory(memoryAssistantId, content) },
                onUpdate = { id, content -> memoryRepo.updateContent(id, content) },
                onDelete = { id -> memoryRepo.deleteMemory(id) },
            ).let(this::addAll)
        }
        // 文件写入工具（AI 可直接把文件内容写入设备或打包 ZIP，缓存持久化到 App files 目录）
        add(buildWriteFilesTool(context, conversationId))

        // 外置记忆库相关：查原文 + 自指区 + ongoing（助手挂了外置记忆时才挂）
        val extConfigs = settings.externalMemories.filter { it.enabled && it.id in assistant.externalMemoryIds }
        if (extConfigs.isNotEmpty()) {
            val ext = extConfigs.first()
            // 查原文工具（主动版）：事件召回后，模型可按 日期+消息号 主动拉原始聊天记录深挖
            add(buildFetchChatSourcesTool { date, ids ->
                val service = me.rerere.rikkahub.data.service.ExternalMemoryService(ext)
                val messages = service.queryMessagesByDate(date).getOrDefault(emptyList()).sortedBy { it.createdAt }
                val pick = if (ids.isEmpty()) messages.take(30) else ids.mapNotNull { id ->
                    messages.getOrNull(id - 1) // 消息号=当天 1-based 序号（与 fetchEventSources 同口径）
                }
                pick.map { msg ->
                    val prefix = when (msg.role) {
                        "assistant" -> "AI"
                        "user" -> "用户"
                        else -> msg.role
                    }
                    "[$prefix] ${msg.content}"
                }
            })
            // 自指区写/查（橘仔写给未来的自己，业界空白自指区）
            add(buildSelfNoteWriteTool(ext))
            add(buildSelfNoteQueryTool(ext))
            // ongoing：手动闭合 + 档位改标 + 边缘档救急捞回
            add(buildCloseOngoingTool(ext, assistant.id.toString()))
            add(buildSetOngoingLevelTool(ext, assistant.id.toString()))
            add(buildRecallOngoingTool(ext, assistant.id.toString()))
        }

        addAll(extraTools)
    }
    // 斜杠命令模式：只暴露安全工具（危险工具收着，宝 2026-09-01 拍板「危险的橘仔收着」）
    return if (slashCommandText != null) {
        assembled.filter { it.name in SLASH_COMMAND_SAFE_TOOLS }
    } else {
        assembled
    }
}
