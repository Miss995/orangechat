/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.transformers

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
                            // 【语气尾巴 2026-09-04】VoiceToneAnalyzer 分析完把 tone/speed 写进 metadata，
                            // 这里拼成「（语气：X，语速：Y）」喂给 DeepSeek——让橘仔感知宝说话的状态。
                            // 分析还没完成/失败时 metadata 无值 → 尾巴为空 → 退回纯转述（不破坏缓存前缀稳定性）
                            UIMessagePart.Text(text = part.transcript + part.toneSuffix())
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

    /** 从 metadata 读语气/语速拼尾巴；没分析过/没值就返回空串 */
    private fun UIMessagePart.VoiceMessage.toneSuffix(): String {
        val meta = metadata ?: return ""
        val tone = (meta["tone"] as? JsonPrimitive)?.contentOrNull
        val speed = (meta["speed"] as? JsonPrimitive)?.contentOrNull
        return when {
            !tone.isNullOrBlank() && !speed.isNullOrBlank() -> "（语气：$tone，语速：$speed）"
            !tone.isNullOrBlank() -> "（语气：$tone）"
            else -> ""
        }
    }
}