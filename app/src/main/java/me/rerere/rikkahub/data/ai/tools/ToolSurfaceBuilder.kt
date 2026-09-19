/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import kotlinx.serialization.json.Json
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.plugin.provider.PluginToolProvider

/**
 * Builds the full tool surface for an assistant: search + local + system + workspace + skill
 * + MCP + plugin tools. This is the single source of truth shared by [me.rerere.rikkahub.service.ChatService]
 * (interactive) and [me.rerere.rikkahub.workflow.execution.WorkflowEngine] (headless fire), so a
 * workflow action can reference any tool the assistant actually has registered - not just the
 * local-tool subset. Without this, workflow_create would reject system/MCP/plugin tool names as
 * "unknown_tool" and the engine couldn't execute them at fire time.
 *
 * Headless callers (workflow fire) pass an empty [recentMessages] list and a null/assistant-default
 * [workspaceCwd]; the read-only context tools that consult recent messages simply see no history.
 */
class ToolSurfaceBuilder(
    private val context: Context,
    private val localTools: LocalTools,
    private val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val pluginToolProvider: PluginToolProvider,
    private val workspaceRepository: WorkspaceRepository,
    private val json: Json,
    private val memoryRepository: MemoryRepository,
    private val settingsStore: SettingsStore,
) {
    suspend fun build(
        assistant: me.rerere.rikkahub.data.model.Assistant,
        settings: Settings,
        invocationContext: ToolInvocationContext,
        recentMessages: List<UIMessage> = emptyList(),
        workspaceCwd: String? = null,
    ): List<Tool> = buildList {
        // Memory tools - mirror GenerationHandler: only when the assistant has memory enabled.
        if (assistant.enableMemory) {
            val memoryAssistantId = if (assistant.useGlobalMemory) {
                MemoryRepository.GLOBAL_MEMORY_ID
            } else {
                assistant.id.toString()
            }
            addAll(buildMemoryTools(
                json = json,
                onCreation = { content -> memoryRepository.addMemory(memoryAssistantId, content) },
                onUpdate = { id, content -> memoryRepository.updateContent(id, content) },
                onDelete = { id -> memoryRepository.deleteMemory(id) },
            ))
        }
        if (settings.enableWebSearch) {
            addAll(createSearchTools(settings))
        }
        addAll(localTools.getTools(assistant.localTools, invocationContext))
        val systemToolsOptions = settings.systemToolsSetting.getEnabledOptions()
        if (systemToolsOptions.isNotEmpty()) {
            addAll(SystemTools(context, settings).getTools(systemToolsOptions, recentMessages, filesManager))
        }
        addAll(createWorkspaceTools(assistant.workspaceId?.toString(), workspaceRepository, workspaceCwd))
        if (assistant.enabledSkills.isNotEmpty()) {
            addAll(createSkillTools(assistant.enabledSkills, skillManager.listSkills(), skillManager))
        }
        mcpManager.getAllAvailableTools().forEach { (serverId, tool) ->
            add(
                Tool(
                    name = ToolNaming.buildMcpToolName(serverId, tool.name),
                    description = tool.description ?: "",
                    parameters = { tool.inputSchema },
                    needsApproval = tool.needsApproval,
                    execute = {
                        mcpManager.callTool(serverId, tool.name, it.jsonObject)
                    },
                )
            )
        }
        // MCP 开关 (2026-09-19 宝拍板): 让 AI 自己启停 MCP 服务器.
        // 故意不挂 LocalToolOption —— 一旦被关掉就再也没办法自己打开 (门锁在里面).
        add(
            createMcpSwitchTool(
                listServers = {
                    // 实时读（2026-09-19 修）：settings 是本回合开始时的快照，
                    // 同回合内开关别的服务器后它不会变，会读到旧状态。
                    val live = settingsStore.settingsFlow.first()
                    val liveAssistant = live.assistants.firstOrNull { it.id == assistant.id } ?: assistant
                    live.mcpServers.map { server ->
                        McpServerInfo(
                            id = server.id,
                            displayName = server.commonOptions.name.ifBlank { "未命名服务器" },
                            enabled = server.id in liveAssistant.mcpServers,
                            globalEnabled = server.commonOptions.enable,
                            toolCount = server.commonOptions.tools.size,
                        )
                    }
                },
                onSetEnabled = { newSet ->
                    settingsStore.updateAssistantMcpServers(assistant.id, newSet)
                    val on = settings.mcpServers.count { it.id in newSet }
                    "已保存。这个助手现在开着 $on 个 MCP 服务器。"
                },
            )
        )
        addAll(pluginToolProvider.getTools())
    }
}
