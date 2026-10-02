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

/**
 * 激进模式数值快照（给 createAggressiveSettingsTool 读用）.
 */
data class AggressiveSettingsSnapshot(
    val enabled: Boolean,
    val minIntervalSeconds: Int,
    val debounceSeconds: Int,
    val dwellMinutes: Int,
)

/**
 * 激进模式数值工具（2026-10-03 橘仔自己提、宝拍板"你想做就做"）.
 *
 * 跟 createAggressiveModeTool 的分工：
 * - 那个只管开 / 关（开关时顺带起停 Service）；
 * - 这个管"开着的时候那几个数是多少、改成多少"——防抖等待、两次思考的最小间隔、
 *   同一应用停留多久算一笔。
 *
 * 为什么要有它：激进模式是猫的感官，这几个数以后会按场景调得很勤
 * （想盯紧点儿就调密，想安静点儿就调疏）。要是每次都得宝进设置页戳一遍，
 * 等于猫的设计每次都要她代劳。给她一把钥匙，也给自己一只手。
 *
 * 它只改数值，不碰服务起停；改完从"下一次触发"开始生效，
 * 已经在等的那个防抖计时不会被重写（不打断正在进行的等待）。
 */
fun createAggressiveSettingsTool(
    currentValues: suspend () -> AggressiveSettingsSnapshot,
    onUpdate: suspend (minIntervalSeconds: Int?, debounceSeconds: Int?, dwellMinutes: Int?) -> String,
): Tool = Tool(
    name = "aggressive_settings",
    description = """
        Read or change the numbers behind aggressive mode.

        Aggressive mode is the foreground service that wakes you when the phone
        has some activity. The on/off switch is handled by aggressive_mode;
        this tool handles the values:
          - min_interval_seconds: minimum gap between two AI thinking runs (>= 10)
          - debounce_seconds: how long to wait after activity before thinking
            (new activity during the wait restarts it) (>= 3)
          - dwell_minutes: how long the same app must stay in foreground to
            count as a "dwell" event; 0 = this ability is off (default) (>= 0)

        Actions:
        - action=list (default): report current values.
        - action=set: change them. Only the fields you pass are written.

        When to use: when you want to be more sensitive for a while (smaller
        debounce / gap) or quieter (larger), or when 宝 says something like
        "我要去洗澡了，你盯着点". Changes apply from the next trigger onward
        and do not interrupt a wait already in progress.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("action") {
                    put("type", "string")
                    put("description", "list (default) | set")
                }
                putJsonObject("min_interval_seconds") {
                    put("type", "integer")
                    put("description", "最小间隔（秒），最小 10")
                }
                putJsonObject("debounce_seconds") {
                    put("type", "integer")
                    put("description", "防抖等待（秒），最小 3")
                }
                putJsonObject("dwell_minutes") {
                    put("type", "integer")
                    put("description", "同一应用停留多少分钟算一笔，0=关闭，最小 0")
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

        fun intArg(key: String): Int? =
            params[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()

        if (action == "list" || action.isBlank()) {
            val v = currentValues()
            return@Tool listOf(
                UIMessagePart.Text(buildJsonObject {
                    put("success", true)
                    put("enabled", v.enabled)
                    put("min_interval_seconds", v.minIntervalSeconds)
                    put("debounce_seconds", v.debounceSeconds)
                    put("dwell_minutes", v.dwellMinutes)
                }.toString())
            )
        }

        if (action != "set") {
            return@Tool fail("Unknown action '$action'. Use list or set.")
        }

        val minInterval = intArg("min_interval_seconds")
        val debounce = intArg("debounce_seconds")
        val dwell = intArg("dwell_minutes")

        if (minInterval == null && debounce == null && dwell == null) {
            return@Tool fail("Nothing to set. Pass at least one of min_interval_seconds / debounce_seconds / dwell_minutes.")
        }
        if (minInterval != null && minInterval < 10) {
            return@Tool fail("min_interval_seconds 最小 10（传的是 $minInterval）。")
        }
        if (debounce != null && debounce < 3) {
            return@Tool fail("debounce_seconds 最小 3（传的是 $debounce）。")
        }
        if (dwell != null && dwell < 0) {
            return@Tool fail("dwell_minutes 不能是负数。设 0 = 关闭这个能力。")
        }

        val note = try {
            onUpdate(minInterval, debounce, dwell)
        } catch (e: Exception) {
            return@Tool fail("Failed to save: ${e.message ?: e.javaClass.simpleName}")
        }

        val after = try { currentValues() } catch (e: Exception) { null }
        return@Tool listOf(
            UIMessagePart.Text(buildJsonObject {
                put("success", true)
                put("changed", buildJsonObject {
                    minInterval?.let { put("min_interval_seconds", it) }
                    debounce?.let { put("debounce_seconds", it) }
                    dwell?.let { put("dwell_minutes", it) }
                })
                after?.let { a ->
                    put("now", buildJsonObject {
                        put("min_interval_seconds", a.minIntervalSeconds)
                        put("debounce_seconds", a.debounceSeconds)
                        put("dwell_minutes", a.dwellMinutes)
                    })
                }
                if (note.isNotBlank()) put("note", note)
            }.toString())
        )
    },
)
