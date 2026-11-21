/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.service.FloatingBallService
import org.koin.core.context.GlobalContext

/**
 * 常驻悬浮球开关 · 2026-11-21
 *
 * 一个光秃秃的小球，一直飘在所有应用上面。目前只做球本身：
 * 能拖、松手贴边、点一下弹提示；拖拽接收已经埋好（收到只打日志）。
 *
 * 后续要接的两件事：
 * 1. 把东西拖到球上 → 存表情包（现在得先开橘瓣、拖进聊天页才行）
 * 2. 双击球 → 把当前页面分享给橘仔
 */
fun buildFloatingBallTool(): Tool = Tool(
    name = "float_ball",
    description = """
        常驻悬浮球：开/关一个一直飘在屏幕上方的圆球。
        它的用处是当"外部接收口"——不用先打开橘瓣，把东西拖到球上就能接。
        现在这一版还只是个球：能拖、松手贴左右边、点一下弹提示。
        action=on 打开；action=off 关掉。
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "on=打开悬浮球；off=关掉")
                })
            }
        )
    },
    execute = { input ->
        val action = input.jsonObject["action"]?.jsonPrimitive?.contentOrNull
            ?.trim()?.lowercase()
            ?: "on"

        val context = runCatching { GlobalContext.get().get<Context>() }.getOrNull()

        when {
            context == null -> listOf(
                UIMessagePart.Text("取不到 Context，开不了悬浮球。")
            )

            action == "off" -> {
                FloatingBallService.hide(context)
                listOf(UIMessagePart.Text("悬浮球收起来了。"))
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !Settings.canDrawOverlays(context) -> listOf(
                UIMessagePart.Text(
                    "橘瓣还没有「显示在其他应用上层」权限，要去系统设置里开一下才能飘球。"
                )
            )

            else -> {
                FloatingBallService.show(context)
                listOf(
                    UIMessagePart.Text(
                        "球开了，应该飘在屏幕右边中间。拖它能挪，松手会贴到最近的那条边。"
                    )
                )
            }
        }
    }
)
