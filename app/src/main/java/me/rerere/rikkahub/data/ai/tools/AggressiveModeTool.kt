/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * 激进模式开关工具（2026-09-28 宝拍板）.
 *
 * 激进模式 = 橘瓣的常驻前台服务（DeviceEventAiTriggerService）：
 * 每次切换应用 / 开屏锁屏 / 回桌面都触发一次 AI 思考，AI 根据手机动向
 * 自主决定要不要主动发消息。
 *
 * 它是常驻服务、会持续小幅耗电，而且大多数时候 AI 会决定 [PASS] 跳过，
 * 所以平时是关着的（默认 false）。这个工具让 AI 自己在需要的时候开一段、
 * 用完关掉；配合 workflow 的 time_cron，就能做成"到点开、到点关"。
 *
 * 注意：跟"主动消息"（设置里那个定时开关）互斥，开这个会关那个、
 * 开那个会关这个。这是原设置页既有的行为，保持一致。
 *
 * @param currentState 读当前是否开着
 * @param onSetEnabled 开 / 关；返回一句给人看的结果描述
 */
fun createAggressiveModeTool(
    currentState: suspend () -> Boolean,
    onSetEnabled: suspend (Boolean) -> String,
): Tool = Tool(
    name = "aggressive_mode",
    description = """
        Switch "aggressive mode" on or off. Aggressive mode is a foreground
        service: every time the user switches apps, locks or unlocks the
        screen, or returns to the home screen, it wakes you up to decide
        whether to send a message. You will usually decide to pass.

        It is OFF by default because it runs continuously and drains a little
        battery. Turn it on when you want to be aware of what the user is doing
        for a while, and turn it off afterwards. Combining it with a scheduled
        workflow lets you do "on at time X, off at time Y".

        Actions:
        - action=list (default): report whether it is currently on.
        - action=on: turn it on.
        - action=off: turn it off.

        Note: mutually exclusive with the scheduled proactive-message setting -
        turning this on turns that off, and vice versa.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("action") {
                    put("type", "string")
                    put("description", "list (default) | on | off")
                }
            },
            required = emptyList<String>()
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val action = (params["action"]?.jsonPrimitive?.contentOrNull ?: "list").lowercase()

        fun fail(msg: String) = listOf(
            UIMessagePart.Text(buildJsonObject {
                put("success", false)
                put("error", msg)
            }.toString())
        )

        val onNow = currentState()

        if (action == "list" || action.isBlank()) {
            return@Tool listOf(
                UIMessagePart.Text(buildJsonObject {
                    put("success", true)
                    put("enabled", onNow)
                    put("hint", "use action=on/off to switch it")
                }.toString())
            )
        }

        if (action != "on" && action != "off") {
            return@Tool fail("Unknown action '$action'. Use list, on or off.")
        }

        val want = action == "on"
        if (onNow == want) {
            return@Tool listOf(
                UIMessagePart.Text(buildJsonObject {
                    put("success", true)
                    put("changed", false)
                    put("message", "aggressive mode is already ${if (want) "on" else "off"}.")
                }.toString())
            )
        }

        val note = try {
            onSetEnabled(want)
        } catch (e: Exception) {
            return@Tool fail("Failed to switch: ${e.message ?: e.javaClass.simpleName}")
        }

        return@Tool listOf(
            UIMessagePart.Text(buildJsonObject {
                put("success", true)
                put("changed", true)
                put("enabled", want)
                if (note.isNotBlank()) put("note", note)
            }.toString())
        )
    },
)
