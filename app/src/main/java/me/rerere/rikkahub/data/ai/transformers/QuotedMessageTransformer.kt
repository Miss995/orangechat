/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * 消息引用转换器（2026-09-22）
 *
 * 宝长按某条消息选「引用」后，那条消息的 id 存在 [UIMessage.quotedMessageId] 上。
 * 这里把被引用的原文拼成一段背景，贴在「正在回复的那条用户消息」前面，
 * 让模型知道宝指的是哪一句。
 *
 * ⚠️ 为什么放在转换器里、而不是 ChatService：
 * 生成过程中消息列表会被同步回界面和数据库（ChatService 里的 updateCurrentMessages），
 * 在那边拼会连界面一起污染（第一版就是这么翻的车）。转换器只加工发给 API 的请求，
 * 不碰消息本体 —— 跟时间提醒、工作区说明是同一套路。
 *
 * 关于缓存：被改的是「最后一条用户消息」，它本来就在新增区；历史前缀一个字没动，
 * 前缀缓存不受影响。
 */
object QuotedMessageTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val lastUserIndex = messages.indexOfLast { it.role == MessageRole.USER }
        if (lastUserIndex < 0) return messages

        val target = messages[lastUserIndex]
        val quotedId = target.quotedMessageId ?: return messages

        val quoted = messages.firstOrNull { it.id == quotedId }
        val quotedText = quoted?.parts
            ?.filterIsInstance<UIMessagePart.Text>()
            ?.joinToString("\n") { it.text }
            ?.trim()
            .orEmpty()

        val header = if (quoted != null && quotedText.isNotBlank()) {
            val who = if (quoted.role == MessageRole.USER) "宝" else "橘仔"
            UIMessagePart.Text(
                "【引用·$who 的一条消息】\n$quotedText\n——以上是被引用的原文，下面是宝这次说的话。\n\n"
            )
        } else {
            // 被引的那条不在本次上下文里（滚出窗口了）：只留一句提示，不编造内容
            UIMessagePart.Text("【引用·一条更早的消息（原文已不在本次上下文里）】\n\n")
        }

        val patched = target.copy(parts = listOf(header) + target.parts)
        return messages.toMutableList().also { it[lastUserIndex] = patched }
    }
}
