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

    data class RequestEditData(
        val sections: List<EditSection>,
        val history: List<EditItem>,
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

    /** 把内部消息转成可编辑数据（system 分段 + 历史列表） */
    fun toEditData(messages: List<UIMessage>): RequestEditData {
        val systemText = messages.firstOrNull { it.role == MessageRole.SYSTEM }?.toText() ?: ""
        val sections = splitSystem(systemText)
        val history = messages.filter { it.role != MessageRole.SYSTEM }.map { msg ->
            EditItem(
                role = msg.role.name.lowercase(),
                text = msg.toText().take(120),
            )
        }
        return RequestEditData(sections = sections, history = history)
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
        return result
    }

    /**
     * 把 system 大字符串按 "## " 分节：
     * 第一段（人设开头）没有标题标记，后续每段以 "## 标题" 开头。
     * content 保存完整原文（含 "## 标题" 行），重组时直接拼接。
     */
    private fun splitSystem(systemText: String): List<EditSection> {
        if (systemText.isBlank()) return emptyList()
        val parts = systemText.split("\n## ")
        return parts.mapIndexed { index, part ->
            if (index == 0) {
                EditSection(title = "开头（人设/规则）", content = part.trim())
            } else {
                val firstLine = part.substringBefore("\n")
                val title = firstLine.trim().removePrefix("## ").trim()
                EditSection(title = title.ifBlank { "第${index + 1}节" }, content = ("## " + part).trim())
            }
        }
    }
}
