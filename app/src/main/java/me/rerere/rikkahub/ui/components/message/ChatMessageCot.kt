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
    // 【判据统一 · 2026-10-05】原来只认 content=="true"。但搭车那侧（GenerationHandler）
    // 写进去的是一串 id（"aaa,bbb"），于是"她插的那句"在渲染层根本不被认——
    // 会被当普通正文画一遍，而且 else 分支顺手把思考链切断，工具块和插话分到两张卡上。
    // 跟数据层的 isInterjectMarked 统一成"有这个 key 就算"。
    // 【拆 key · 2026-10-05】位置锚点已搬到 interjectAnchor，这个 key 只表示"这句是她的话"。
    // 收窄回只认 true：宽判据会让带位置锚点的 part 被误当插话，把工具块和思考链切坏。
    val v = metadata?.get("interject")
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
            // 【同轮并排合成一条 · 2026-10-05 宝实测】她连着打两句时，数据里是两条独立 part（两个标），
            // 原来一个标画一个条 → 两条内容一样的折叠条，看着像坏了。
            // 渲染层把相邻的合成一条：文本用空行接起来，metadata 沿用后一条。数据层一个字不动。
            part is UIMessagePart.Text && part.isInterjectPart() -> {
                val prevPart = (currentThinkingSteps.lastOrNull() as? ThinkingStep.InterjectStep)?.part
                if (prevPart is UIMessagePart.Text) {
                    val merged = UIMessagePart.Text(
                        text = prevPart.text + "\n\n" + part.text,
                        metadata = part.metadata,
                    )
                    currentThinkingSteps[currentThinkingSteps.lastIndex] =
                        ThinkingStep.InterjectStep(merged)
                } else {
                    currentThinkingSteps.add(ThinkingStep.InterjectStep(part))
                }
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
