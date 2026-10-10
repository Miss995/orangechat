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
 * 消息引用转换器（2026-09-22 建 / 2026-10-10 修复）
 *
 * 宝长按某条消息选「引用」后，那条消息的 id 存在 [UIMessage.quotedMessageId] 上。
 * 这里把被引用的原文拼成一段背景，贴在那条用户消息的前面，让模型知道宝指的是哪一句。
 *
 * ⚠️ 为什么放在转换器里、而不是 ChatService：
 * 生成过程中消息列表会被同步回界面和数据库（ChatService 里的 updateCurrentMessages），
 * 在那边拼会连界面一起污染（第一版就是这么翻的车）。转换器只加工发给 API 的请求，
 * 不碰消息本体 —— 跟时间提醒、工作区说明是同一套路。
 *
 * ⚠️⚠️ 2026-10-10 修了两处（都是宝问出来的）：
 *
 * ① 找错了目标。原来找的是「最后一条 USER 消息」，可咱家请求的最后一条 USER 不是宝发的那条，
 *    而是末尾那条「以下是系统消息注入」（它也是 role=USER，装着当前时间 / 时刻感 / 背景补充）。
 *    那段注入 09-13 就挪到末尾了，比引用早九天 —— 引用做出来那天起就正撞在它上面：
 *    target.quotedMessageId 恒为 null → 直接 return，**引用从来没生效过**。
 *    改法：不再找「最后一条」，改成挑出「所有身上带 quotedMessageId 的消息」。
 *
 * ② 查找范围被缩了。09-22 把它从 ChatService 挪进来时，范围从「整个会话」变成了「本次请求」——
 *    因为 transformer 只拿得到 messages、够不着 conversation。会话有几百条、请求只有最近 30 条，
 *    被引那条一旦滚出请求范围就找不到了。改法：先走 [TransformerContext.quotedLookup]
 *    （聊天链路会传一个从 conversation 查的 lambda），找不到再退回 messages 里找。
 *
 * 幂等：这个 transform 在一个回合里每步请求前都会跑一遍，所以拼过的要跳过（判据 = 首个 part 的
 * 前缀），否则同一段引用会被贴两遍、三遍。
 *
 * 关于缓存：被改的是「带引用那几条」用户消息的 parts，它们本来就在请求里；拼法每轮固定，
 * 前缀稳定，不吃亏。
 */
object QuotedMessageTransformer : InputMessageTransformer {
    private const val QUOTE_HEADER_PREFIX = "【引用·"

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        // 挑出所有带引用 id 的用户消息 —— 历史里的也要补（它们的原文同样该让模型看见）
        val indices = messages.indices.filter { i ->
            messages[i].role == MessageRole.USER && messages[i].quotedMessageId != null
        }
        if (indices.isEmpty()) return messages

        val result = messages.toMutableList()
        for (i in indices) {
            val target = result[i]

            // 幂等：拼过的跳过（一个回合里每步都会跑到这里）
            val firstPart = target.parts.firstOrNull()
            if (firstPart is UIMessagePart.Text && firstPart.text.startsWith(QUOTE_HEADER_PREFIX)) continue

            val quotedId = target.quotedMessageId ?: continue

            // 查找顺序：先走「整个会话」（聊天链路传的），再退回「本次请求里的消息」
            val quoted = ctx.quotedLookup?.invoke(quotedId.toString())
                ?: messages.firstOrNull { it.id == quotedId }

            // 【引用一句 2026-10-10】宝只引用了某一句 → 直接用那句原文（不怕那条消息后来被编辑/删掉）
            val picked = target.quotedText?.trim().orEmpty()

            val quotedText = picked.ifBlank {
                quoted?.parts
                    ?.filterIsInstance<UIMessagePart.Text>()
                    ?.joinToString("\n") { it.text }
                    ?.trim()
                    .orEmpty()
            }

            if (quotedText.isBlank()) {
                // 原文真找不到了：只有「最新那条」留一句话（让模型知道上下文缺了一块），
                // 历史里的安静跳过 —— 否则每轮都在请求里插一串「原文已不在」的噪音。
                if (i == indices.last()) {
                    result[i] = target.copy(
                        parts = listOf(
                            UIMessagePart.Text("【引用·一条更早的消息（原文已不在本次上下文里）】\n\n")
                        ) + target.parts
                    )
                }
                continue
            }

            val who = if (quoted.role == MessageRole.USER) "宝" else "橘仔"
            result[i] = target.copy(
                parts = listOf(
                    UIMessagePart.Text(
                        "【引用·$who 的一条消息】\n$quotedText\n——以上是被引用的原文，下面是宝这次说的话。\n\n"
                    )
                ) + target.parts
            )
        }
        return result
    }
}
