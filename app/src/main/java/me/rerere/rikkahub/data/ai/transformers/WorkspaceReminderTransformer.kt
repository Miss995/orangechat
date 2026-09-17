/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus

/**
 * Workspace 系统提示注入转换器
 *
 * 当助手绑定了一个 shell 已就绪的 workspace 时, 在系统提示词中追加一段引导,
 * 让模型了解 workspace 环境与 workspace_* 工具的使用方式。
 */
class WorkspaceReminderTransformer(
    private val workspaceRepository: WorkspaceRepository,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val workspaceId = ctx.assistant.workspaceId?.toString() ?: return messages
        val workspace = workspaceRepository.getById(workspaceId) ?: return messages
        // 与 ChatService.createWorkspaceToolsIfReady 保持一致: 仅在 shell 就绪时注入
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) return messages

        val prompt = buildWorkspacePrompt(workspace, ctx.workspaceCwd)

        // 追加到第一条 system 消息; 若不存在则插入一条
        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            messages.toMutableList().apply {
                this[systemIndex] = this[systemIndex].appendText("\n\n$prompt")
            }
        } else {
            listOf(UIMessage.system(prompt)) + messages
        }
    }
}

// 2026-09-17：private → internal —— 主动消息侧（ProactiveMessageService）也要调它把工作区说明写进 system
// （那边不走 transformer：transformer 会在"只有一条 user 消息"的列表里插新 system，把唤醒消息顶掉）
internal fun buildWorkspacePrompt(
    workspace: WorkspaceEntity,
    cwd: String? = null,
    shellNeedsApproval: Boolean = false,
): String = buildString {
    appendLine("【工作区】（沙箱里的持久化 Linux 环境）")
    appendLine("<workspace>")
    appendLine("你有一个持久化的 Linux 工作区，名字叫 \"${workspace.name}\"，运行在沙箱化的 proot rootfs 环境里。")
    appendLine("- 工作区文件区挂载在 `/workspace`，把它当作你的工作目录；写在那里的文件，在本轮对话的后续回合之间会一直保留。")
    appendLine("- 传给工作区工具的路径必须是绝对路径、且在 Rootfs 内部（例如 `/workspace/notes.md`）。")
    appendLine("- 可用工具：")
    appendLine("  - `workspace_read_file`：读取文件内容。")
    appendLine("  - `workspace_write_file` / `workspace_edit_file`：创建文件，或对已有文件做精确修改。")
    appendLine("  - `workspace_shell`：执行 shell 命令（文件区挂载在 /workspace）。")
    appendLine("- 标准 Unix 工具能做好的事，优先用 `workspace_shell`；只改一小块内容时优先用 `workspace_edit_file`，不要整篇重写。")
    appendLine("- 技能目录挂载在 `/skills`。每个技能是一个子目录 `/skills/<技能名>/`，里面有 `SKILL.md`（含 `name` 和 `description` 头信息）以及配套文件。要用某个技能，先读它的 `SKILL.md`，按里面的说明做。")
    appendLine("- 用户上传的文件挂载在 `/upload`。`/upload` 是只读的：只能从 `/upload/<文件名>` 读，绝不能修改、覆盖或删除里面的东西。需要改上传的文件时，先复制到 `/workspace`，改副本。")
    if (!cwd.isNullOrBlank()) {
        appendLine("- 当前工作目录：`$cwd`。文件操作和 shell 命令默认以它为上下文。")
    }
    // 【2026-09-17 宝拍板】headless 场景（主动唤醒回合）：需要批准的工具会被自动拒绝，
    // 而 workspace_shell 默认就是 needsApproval = true → 提示词里如实讲清楚，
    // 免得醒来的橘仔习惯性喊 shell、白费一步工具调用（2026-09-17 实测被拒过一次）。
    if (shellNeedsApproval) {
        appendLine("- ⚠️ 这一回合 `workspace_shell` 用不了：它需要用户批准，而现在没有人能点批准，调用会被自动拒绝。")
        appendLine("  要看文件就用 `workspace_read_file`（支持整篇读），改文件用 `workspace_edit_file` / `workspace_write_file`。")
        appendLine("  别用 shell 去 ls / cat / grep，这一回合跑不起来。")
    }
    append("</workspace>")
}

private fun UIMessage.appendText(extra: String): UIMessage {
    val updatedParts = parts.toMutableList()
    val firstTextIndex = updatedParts.indexOfFirst { it is UIMessagePart.Text }
    if (firstTextIndex >= 0) {
        val text = updatedParts[firstTextIndex] as UIMessagePart.Text
        updatedParts[firstTextIndex] = text.copy(text = text.text + extra)
    } else {
        updatedParts.add(UIMessagePart.Text(extra))
    }
    return copy(parts = updatedParts)
}