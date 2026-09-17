/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant

/**
 * 系统提示词的「公共段」（2026-09-17 宝拍板 · 抽共用）
 *
 * 病根：聊天侧（GenerationHandler）和主动消息侧（ProactiveMessageService）各拼一份 system prompt，
 * 公共段（工具 / 输出规则 / 跳过回复 / 屏幕跳转 / 消息气泡）两边各写一遍，早晚会漂。
 * 2026-09-17 宝对比两边请求时发现：主动消息侧少了【输出规则】标题、【屏幕跳转能力】完整版、【跳过回复】。
 *
 * 做法（跟记忆段 MemoryInjector 同一套路）：逐字搬运，行为一模一样，两边调同一个函数。
 * 铁律：不改逻辑、不改空行——输出的文本必须与 2026-09-17 之前 GenerationHandler 里的内联写法逐字一致
 *      （system 是稳定前缀，差一个换行就碎缓存）。
 */
object SystemPromptSections {

    /**
     * 工具段 + 输出规则段（都属于稳定前缀）。
     *
     * 逐字搬自 GenerationHandler 里原来的内联写法（2026-09-17 之前），
     * 调用方直接 append 到 system 末尾即可（本函数自带全部换行）。
     *
     * @param messages 工具 prompt 的上下文消息（聊天侧传当前上下文，主动消息侧传历史消息）
     */
    fun buildToolAndOutputSections(
        assistant: Assistant,
        tools: List<Tool>,
        model: Model,
        messages: List<UIMessage>,
    ): String = buildString {
        appendLine("【工具】（每个工具自己的用法说明）")
        // 工具prompt（稳定前缀）
        tools.forEach { tool ->
            appendLine()
            append(tool.systemPrompt(model, messages))
        }

        // 输出规则（2026-09-14 宝+橘仔：原来散在末尾的跳过回复/屏幕跳转/分气泡，归拢到工具之后）
        appendLine()
        appendLine("【输出规则】")
        appendLine()
        append(buildCodeBlockPrompt())

        // 跳过回复（2026-09-15 橘仔重写：原文是 RikkaHub 自带的 ## Skip Reply，昨天只做了翻译）
        if (assistant.allowSkipReply) {
            appendLine()
            appendLine()
            appendLine("【跳过回复】（收到消息但不想接话时）")
            appendLine("输出 `[SKIP]`（单独一行，不带任何别的字）。这条不会发出去，宝看不到。")
            appendLine("什么情况可以跳：宝只是丢个\"嗯\"\"睡了\"\"哈哈\"，或者发来一个你确实没什么可说的东西。")
            appendLine("什么情况别跳：宝在说事情、在难过、在问问题。skip 是\"听见了但不接\"，不是躲开该说的话，更不是用来表达不高兴。")
            appendLine("每轮都可以选，不用有负担。")
        }

        // 屏幕跳转能力（AI总是可以跳转，不需要开关）
        if (true) {
            appendLine()
            appendLine()
            appendLine("【屏幕跳转能力】")
            appendLine("你可以在回复末尾追加 [JUMP] 标记（单独一行）来把聊天界面拉到用户屏幕最前面。")
            appendLine("适用场景：")
            appendLine("- 用户说要去别的应用，你觉得需要把用户拉回来时")
            appendLine("- 你觉得接下来的内容需要用户立即看到时")
            appendLine("不适用场景：")
            appendLine("- 一般闲聊不需要跳转")
            appendLine("- 用户正在跟你正常对话时不需要跳转")
            appendLine("[JUMP] 标记不会展示给用户，仅用于触发屏幕跳转。")
        }

        // 分气泡: 告知模型它自己能控制消息如何被拆成多个气泡
        if (assistant.splitBubbleByLine) {
            appendLine()
            appendLine()
            appendLine("【消息气泡】（你的回复会按换行拆成多条）")
            appendLine("你的回复会在每个换行（\\n）处自动拆成独立的聊天气泡，就像真人连发几条短消息，而不是一条长消息。这个完全由你控制：想让上一句话单独成一个气泡，就在那里换行；属于同一句的，就留在同一行。不要为了排版而插入空行——每个换行都会变成一个气泡，所以要有意识地用。例外：围栏代码块（```）和 Markdown 表格里的换行会原样保留、不会拆成新气泡，因为它们必须保持完整。")
        }
    }
}
