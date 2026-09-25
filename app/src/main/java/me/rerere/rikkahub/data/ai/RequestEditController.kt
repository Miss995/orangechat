/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage

/**
 * 请求编辑模式控制器：
 * 生成流程构建完 internalMessages 后、真正发给 Provider 之前，
 * 如果开启「请求编辑模式」，把待发送的请求交给 UI 弹窗编辑，
 * 用户确认后才继续发送 —— 实现「手动控制上下文」。
 */
object RequestEditController {

    /**
     * 后台触发的回合（定时发送等）跳过请求编辑。
     * 那种场景界面上没人在，弹出来只会卡住等一个永远不来的确认。
     * 调用方发之前置 true，GenerationHandler 读完立刻清掉（一次性）。
     */
    @Volatile
    var bypassNextRequestEdit: Boolean = false

    /** 读走「跳过」标记（一次性，读完即清） */
    fun consumeBypass(): Boolean {
        val value = bypassNextRequestEdit
        bypassNextRequestEdit = false
        return value
    }

    /** system 里的一节（按 "## " 标题分段） */
    data class EditSection(
        val title: String,
        val content: String,
        val enabled: Boolean = true,
    )

    /** 一条历史消息（可勾选是否带上） */
    data class EditItem(
        val role: String,
        val text: String,
        val enabled: Boolean = true,
    )

    /** 一个可勾选的工具（勾选 = 本轮注入给 AI） */
    data class ToolEditItem(
        val name: String,
        val enabled: Boolean = true,
    )

    data class RequestEditData(
        val sections: List<EditSection>,
        val history: List<EditItem>,
        val tools: List<ToolEditItem> = emptyList(),
        // 【2026-09-13 宝的方案】本次召回内容（请求末尾的【背景补充】那段）：
        // 单独拎出来给 UI 看、单独给一个开关 —— 召回不准时可以一键关掉再发送。
        // 关掉只剥【背景补充】，时间和时刻感保留（那两个是每轮都要的）。
        val recall: String? = null,
        val recallEnabled: Boolean = true,
        /**
         * 【临时附言 2026-09-25 宝的需求】请求编辑弹窗里手写的一段话，
         * 拼到「系统消息注入」那一块的末尾，只对本次请求生效，不落库。
         * 空着 = 什么都不加（请求跟以前完全一致，不打乱缓存前缀）。
         */
        val appendix: String = "",
    )

    private val _pending = MutableStateFlow<RequestEditData?>(null)

    /** UI 层收集这个流：非空时弹出编辑界面 */
    val pending: StateFlow<RequestEditData?> = _pending.asStateFlow()

    private var deferred: CompletableDeferred<RequestEditData?>? = null

    /**
     * 挂起等待用户编辑。返回 null 表示用户取消本次发送（调用方应中止生成）。
     */
    suspend fun waitForEdit(data: RequestEditData): RequestEditData? {
        _pending.value = data
        val d = CompletableDeferred<RequestEditData?>()
        deferred = d
        return try {
            d.await()
        } finally {
            deferred = null
            _pending.value = null
        }
    }

    /** UI 编辑完成后调用：result 为编辑后的数据；null = 取消发送 */
    fun submit(result: RequestEditData?) {
        deferred?.complete(result)
    }

    /**
     * 把内部消息转成可编辑数据（system 分段 + 历史列表 + 工具勾选列表）。
     * @param toolNames 本轮将要注入的工具名列表（默认全勾选，用户可取消）。
     */
    fun toEditData(
        messages: List<UIMessage>,
        toolNames: List<String> = emptyList(),
        recall: String? = null,
    ): RequestEditData {
        val systemText = messages.firstOrNull { it.role == MessageRole.SYSTEM }?.toText() ?: ""
        val sections = splitSystem(systemText)
        val history = messages.filter { it.role != MessageRole.SYSTEM }.map { msg ->
            EditItem(
                role = msg.role.name.lowercase(),
                text = msg.toText().take(120),
            )
        }
        val tools = toolNames.map { ToolEditItem(name = it) }
        return RequestEditData(
            sections = sections,
            history = history,
            tools = tools,
            recall = recall?.takeIf { it.isNotBlank() },
            recallEnabled = true,
        )
    }

    /** 把编辑结果还原成内部消息；edited 为 null 时返回原始消息（调用方应已处理取消） */
    fun toMessages(edited: RequestEditData?, original: List<UIMessage>): List<UIMessage> {
        if (edited == null) return original
        val result = mutableListOf<UIMessage>()
        // 重组 system：只拼接启用的分节
        val systemText = edited.sections.filter { it.enabled }
            .joinToString("\n") { it.content }
            .trim()
        if (systemText.isNotBlank()) {
            result.add(UIMessage.system(prompt = systemText))
        }
        // 历史消息：按启用的顺序保留
        val originalHistory = original.filter { it.role != MessageRole.SYSTEM }
        edited.history.forEachIndexed { index, item ->
            if (item.enabled && index < originalHistory.size) {
                result.add(originalHistory[index])
            }
        }
        // 【2026-09-13 宝的方案】召回开关：关掉就把末尾注入消息里的【背景补充】剥掉。
        // 只剥那一段（时间和时刻感照常保留）；命中不了就原样返回，不冒风险。
        if (!edited.recallEnabled && !edited.recall.isNullOrBlank()) {
            val marker = "\n【背景补充】\n" + edited.recall
            val stripped = result.map { msg ->
                if (msg.role != MessageRole.USER) msg
                else {
                    val text = msg.toText()
                    if (!text.contains(marker)) msg
                    else UIMessage.user(prompt = text.replace(marker, ""))
                }
            }
            return applyAppendix(stripped, edited.appendix)
        }
        return applyAppendix(result, edited.appendix)
    }

    /**
     * 【临时附言 2026-09-25 宝的需求】把编辑框里手写的一段拼到「系统消息注入」块末尾。
     * 只改发给模型的这份副本，不落库、不碰历史前缀（所以不打乱缓存）。
     * 空着、或者找不到注入块时，原样返回。
     */
    private fun applyAppendix(messages: List<UIMessage>, appendix: String): List<UIMessage> {
        if (appendix.isBlank()) return messages
        val marker = "以下是系统消息注入:"
        val idx = messages.indexOfLast {
            it.role == MessageRole.USER && it.toText().startsWith(marker)
        }
        if (idx < 0) return messages
        val old = messages[idx]
        return messages.toMutableList().also {
            it[idx] = UIMessage.user(
                prompt = old.toText().trimEnd() + "\n【临时附言】" + appendix.trim()
            )
        }
    }

    /**
     * 把 system 大字符串分节。支持两种节标题标记：
     *  - Markdown 风格：行首 "## 标题"
     *  - 方括号风格：行首 "【标题】……"（2026-09 提示词结构改造后启用）
     * 第一段（人设/规则开头）没有标题标记时单独成一节。
     * content 保存完整原文（含标题行），重组时直接拼接。
     */
    private fun splitSystem(systemText: String): List<EditSection> {
        if (systemText.isBlank()) return emptyList()
        // 只在换行后紧跟 "## " 或 "【" 处切分，避免误伤正文里的中文方括号
        val parts = systemText.split(Regex("\n(?=## |【)"))
        return parts.mapIndexed { index, part ->
            val text = part.trim()
            val firstLine = text.lineSequence().firstOrNull().orEmpty()
            val title = when {
                firstLine.startsWith("## ") -> firstLine.removePrefix("## ").trim()
                firstLine.startsWith("【") -> {
                    val end = firstLine.indexOf('】')
                    if (end > 0) firstLine.substring(0, end + 1) else firstLine.trim()
                }
                else -> ""
            }
            if (index == 0 && title.isEmpty()) {
                EditSection(title = "开头（人设/规则）", content = text)
            } else {
                EditSection(title = title.ifBlank { "第${index + 1}节" }, content = text)
            }
        }
    }
}
