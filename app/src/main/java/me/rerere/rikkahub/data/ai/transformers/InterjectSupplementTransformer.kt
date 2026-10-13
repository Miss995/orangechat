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
 * 【插话补充 · 2026-10-13 宝定的】
 *
 * 宝的观察：猫回得快，她回得慢。她在猫说话中间发的那句，形状是"打断"，
 * 内容却常常是"把刚才那句补完整" —— 物理上落在猫的回复后面，逻辑上接她自己上一句。
 * 猫按形状处理（当成一句新话题去接），于是内容和反应错位。
 *
 * 做法：她在输入框上方按一下「这句是补充上文那条？」，那条消息就带上
 * interjectKind = "supplement"；这个转换器在它前面插一条短消息，
 * 告诉模型这句接的是她自己上一条，不是接猫刚说的那句。
 *
 * 为什么放这儿、而不是散在三条路上（搭车 / 兜底 extra request / 落成正式消息）：
 * 三条路最后都要把消息拼进请求，而输入转换器是它们的公共必经之路 —— 一处就够。
 *
 * 那条前缀不落库：它是拼请求时现插的局部值，转换器只改本次请求的消息列表。
 */
object InterjectSupplementTransformer : InputMessageTransformer {
    /** 宝定的八个字。加括号 = 一眼能认出是系统标注，不是她说的话。 */
    private const val NOTE = "（做上文用户消息补充）"

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        // 没有带标的，原样返回（绝大多数字符串走这条，零开销）
        if (messages.none { it.interjectKind == INTERJECT_KIND_SUPPLEMENT }) return messages
        val out = ArrayList<UIMessage>(messages.size + 2)
        for (msg in messages) {
            if (msg.interjectKind == INTERJECT_KIND_SUPPLEMENT) {
                out.add(
                    UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Text(NOTE)),
                    )
                )
            }
            out.add(msg)
        }
        return out
    }
}

/** 插话补充的标记值（写进 UIMessage.interjectKind）。 */
const val INTERJECT_KIND_SUPPLEMENT = "supplement"
