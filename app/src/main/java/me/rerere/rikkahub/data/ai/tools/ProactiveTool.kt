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
 * - reason（AI 出口）：这次醒来的目的，注入 user 消息「这次醒来的由头：X」——AI 醒来知道自己要干嘛
 * - label（AI 出口）：这次唤醒的名字，成为头部「【唤醒·X】」/「【主动发消息·念起·X】」
 * - mode（AI 出口）：wake = 只叫醒（不发消息，可以去花园/镇上/记忆）；recall（默认）= 醒来跟宝说点什么
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
        Set mode="wake" to only wake up without expecting a message (the AI may look
        around, do its own things, and stay quiet). Leave mode empty or "recall" when
        you do want it to actually say something.
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
                put("mode", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional: 'wake' = 只叫醒，不要求发消息（醒来的猫可以去看花园、镇上、自己的记忆，或者就待着）；'recall'（默认）= 醒来跟宝说点什么。")
                })
                put("label", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional: 这次唤醒的名字（例如「园丁时刻」）。它会成为唤醒消息的开头标记（如「【唤醒·园丁时刻】」或「【主动发消息·念起·园丁时刻】」），让 AI 一眼认出这是唤醒回合、而不是历史里的一条普通消息。建议每个定时 workflow 都填自己的名字。")
                })
            }
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val reason = params["reason"]?.jsonPrimitive?.contentOrNull ?: ""
        val label = params["label"]?.jsonPrimitive?.contentOrNull ?: ""
        val mode = params["mode"]?.jsonPrimitive?.contentOrNull ?: ""
        try {
            val intent = Intent(context, ProactiveMessageTriggerService::class.java).apply {
                putExtra(ProactiveMessageTriggerService.EXTRA_FORCE_TRIGGER, true)
                putExtra(ProactiveMessageTriggerService.EXTRA_AI_TRIGGER, true)
                if (reason.isNotBlank()) {
                    putExtra(ProactiveMessageTriggerService.EXTRA_AI_TRIGGER_REASON, reason)
                }
                if (label.isNotBlank()) {
                    putExtra(ProactiveMessageTriggerService.EXTRA_AI_TRIGGER_LABEL, label)
                }
                if (mode.isNotBlank()) {
                    putExtra(ProactiveMessageTriggerService.EXTRA_WAKE_MODE, mode)
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
