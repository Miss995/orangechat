/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.content.Intent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.service.ProactiveMessageTriggerService

/**
 * 主动发消息 AI 接口（2026-08-23 宝拍板）：
 * AI / workflow 可调用此工具触发一次主动发消息流程（走 ProactiveMessageTriggerService）。
 *
 * - reason（AI 出口）：这次醒来的目的，注入提示词「你这次醒来的目的：X」——AI 醒来知道自己要干嘛
 * - promptOverride（客户端出口）：自定义提示词规则，注入主动消息上下文末尾——不写死，外部可传
 *
 * 传 EXTRA_FORCE_TRIGGER=true 跳过内部最小间隔去重；
 * 传 EXTRA_AI_TRIGGER=true 让主动消息开关未开启时也能独立触发（与激进模式同待遇）。
 * needsApproval=false：workflow 后台触发时不会被 headless_sensitive_blocked 拦截。
 */
fun buildTriggerProactiveMessageTool(context: Context): Tool = Tool(
    name = "trigger_proactive_message",
    description = """
        Trigger a proactive message flow: the AI wakes up and decides based on current
        context whether to proactively send the user a message, and what to say.
        Use this to schedule yourself to reach out to the user on your own initiative
        (e.g. as a workflow action: every day at 22:00 call trigger_proactive_message
        with reason "提醒宝睡觉"). The AI will naturally decide whether to send or pass.
        Returns whether the trigger was dispatched.
    """.trimIndent().replace("\n", " "),
    needsApproval = false,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("reason", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional: why you are waking up. Injected into the prompt as 「你这次醒来的目的」 so the AI knows what it planned to do (e.g. '提醒宝睡觉').")
                })
                put("prompt_override", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional: custom rules appended to the proactive message prompt (client-side escape hatch, replaces nothing, only adds).")
                })
            }
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val reason = params["reason"]?.jsonPrimitive?.contentOrNull ?: ""
        val promptOverride = params["prompt_override"]?.jsonPrimitive?.contentOrNull ?: ""
        try {
            val intent = Intent(context, ProactiveMessageTriggerService::class.java).apply {
                putExtra(ProactiveMessageTriggerService.EXTRA_FORCE_TRIGGER, true)
                putExtra(ProactiveMessageTriggerService.EXTRA_AI_TRIGGER, true)
                if (reason.isNotBlank()) {
                    putExtra(ProactiveMessageTriggerService.EXTRA_AI_TRIGGER_REASON, reason)
                }
                if (promptOverride.isNotBlank()) {
                    putExtra(ProactiveMessageTriggerService.EXTRA_PROMPT_OVERRIDE, promptOverride)
                }
            }
            context.startForegroundService(intent)
            listOf(UIMessagePart.Text(buildJsonObject {
                put("success", true)
                put("message", "Proactive message flow triggered")
            }.toString()))
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(buildJsonObject {
                put("success", false)
                put("error", e.message ?: "Unknown error")
            }.toString()))
        }
    }
)
