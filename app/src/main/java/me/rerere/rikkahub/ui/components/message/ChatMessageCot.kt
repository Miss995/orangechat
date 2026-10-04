/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.message

import androidx.compose.ui.util.fastForEachIndexed
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.ui.UIMessagePart

/** 【插话记号 · 2026-10-01】这个 part 是不是宝插进来的那句（收尾合并时打的 metadata{"interject": true}）。
 *  2026-10-04 从 ChatMessage.kt 挪出来共用（分组和渲染两边都要认它）。 */
internal fun UIMessagePart.isInterjectPart(): Boolean {
    val v = metadata?.get("interject") ?: return false
    return v is JsonPrimitive && v.content == "true"
}

/**
 * 思考步骤类型，用于分组 Reasoning 和 Tool
 */
sealed interface ThinkingStep {
    data class ReasoningStep(
        val reasoning: UIMessagePart.Reasoning,
    ) : ThinkingStep

    data class ToolStep(
        val tool: UIMessagePart.Tool,
    ) : ThinkingStep

    /**
     * 【插话步骤 · 2026-10-04 宝定的】带着 interject 记号的正文（宝插进来说的那句）。
     * 它不再"打断"思考链：分组时不另起一块，直接作为一步收进同一张卡片——
     * 于是"工具 / 插话 / 思考"是一整块，宽度天然一致，不用再判谁跟在谁后面。
     */
    data class InterjectStep(
        val part: UIMessagePart,
    ) : ThinkingStep
}

/**
 * 消息部分块类型，用于保持渲染顺序
 */
sealed interface MessagePartBlock {
    data class ThinkingBlock(val steps: List<ThinkingStep>) : MessagePartBlock
    data class ContentBlock(val part: UIMessagePart, val index: Int) : MessagePartBlock
}

/**
 * 将 parts 分组成 ThinkingBlock 和 ContentBlock
 * 连续的 Reasoning 和 Tool 会被分组到一个 ThinkingBlock 中
 */
fun List<UIMessagePart>.groupMessageParts(): List<MessagePartBlock> {
    val result = mutableListOf<MessagePartBlock>()
    var currentThinkingSteps = mutableListOf<ThinkingStep>()

    fun flushThinkingSteps() {
        if (currentThinkingSteps.isNotEmpty()) {
            result.add(MessagePartBlock.ThinkingBlock(currentThinkingSteps.toList()))
            currentThinkingSteps = mutableListOf()
        }
    }

    this.fastForEachIndexed { index, part ->
        when {
            part is UIMessagePart.Reasoning -> {
                currentThinkingSteps.add(ThinkingStep.ReasoningStep(part))
            }

            part is UIMessagePart.Tool -> {
                currentThinkingSteps.add(ThinkingStep.ToolStep(part))
            }

            // 【插话不打断 · 2026-10-04 宝定的】宝插的那句跟着思考链走：
            // 不 flush、不另起一块，这样它和前后两段共用同一张卡片。
            part is UIMessagePart.Text && part.isInterjectPart() -> {
                currentThinkingSteps.add(ThinkingStep.InterjectStep(part))
            }

            else -> {
                flushThinkingSteps()
                result.add(MessagePartBlock.ContentBlock(part, index))
            }
        }
    }
    flushThinkingSteps()
    return result
}
