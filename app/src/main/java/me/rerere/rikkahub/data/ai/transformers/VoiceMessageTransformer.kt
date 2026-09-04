/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * Transforms VoiceMessage parts into Text parts for AI providers.
 * Voice messages are displayed as voice bubbles in the UI but sent as text to the AI.
 */
object VoiceMessageTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        // 【空转述语音剔除 2026-09-04】ASR 没识别出来的语音条（transcript 空）上屏照常显示，
        // 但发给 AI 时直接剔除整条消息——"[语音消息]"占位符没信息量，还会占最近 N 条窗口槽位，
        // 把真正有用的历史挤出上下文（宝实测发现攒几条空语音后真对话全被顶掉+缓存/输入 token 浪费）。
        return messages.mapNotNull { message ->
            val newParts = message.parts.mapNotNull { part ->
                when (part) {
                    is UIMessagePart.VoiceMessage -> {
                        if (part.transcript.isNotBlank()) {
                            UIMessagePart.Text(text = part.transcript)
                        } else {
                            null
                        }
                    }
                    else -> part
                }
            }
            if (newParts.isEmpty()) null else message.copy(parts = newParts)
        }
    }
}