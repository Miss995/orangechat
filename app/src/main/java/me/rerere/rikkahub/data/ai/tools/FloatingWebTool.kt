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
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.RikkaHubApp
import me.rerere.rikkahub.data.service.FloatingWebService

/**
 * 网页浮窗（系统级）· 2026-11-18
 *
 * 宝要的是「一个在什么应用上都能出现的浮窗」——原来那个 FloatingBrowserWindow
 * 只能在聊天页里待着，这个能飘到任何应用上面。
 *
 * 开的是 FloatingWebService（WindowManager + TYPE_APPLICATION_OVERLAY），
 * 需要「显示在其他应用上层」权限；没开权限不硬试，直接回一句要她去开。
 *
 * 不叠第二个：已经开着的时候再调 open = 换网址。
 */
fun buildFloatingWebTool(): Tool = Tool(
    name = "float_web",
    description = """
        网页浮窗：把一个网址挂成系统级小窗，飘在所有应用上面（可以拖、可以关）。
        宝说「把这个页面浮起来/让它一直露着」的时候用它。
        前提是橘瓣有「显示在其他应用上层」权限；没有的话返回 no_overlay_permission，要请宝去开。
        action=close 关掉它。
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "open=开浮窗（默认）；close=关掉")
                })
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "要浮起来的网址（action=open 时必填）")
                })
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "顶部把手上显示的字（可选）")
                })
            }
        )
    },
    execute = { input ->
        val params = input.jsonObject
        val action = params["action"]?.jsonPrimitive?.contentOrNull ?: "open"
        val ctx = RikkaHubApp.INSTANCE

        when {
            ctx == null -> fwText("""{"ok":false,"error":"app_not_ready"}""")

            action == "close" -> {
                runCatching { FloatingWebService.stop(ctx) }
                fwText("""{"ok":true,"closed":true}""")
            }

            else -> {
                val url = params["url"]?.jsonPrimitive?.contentOrNull
                when {
                    url.isNullOrBlank() -> fwText("""{"ok":false,"error":"missing_url"}""")

                    !FloatingWebService.canDrawOverlays(ctx) -> fwText(
                        """{"ok":false,"error":"no_overlay_permission","hint":"要让宝去系统设置里给橘瓣开「显示在其他应用上层」"}"""
                    )

                    else -> {
                        val title = params["title"]?.jsonPrimitive?.contentOrNull
                        runCatching { FloatingWebService.start(ctx, url, title) }
                            .fold(
                                onSuccess = { fwText("""{"ok":true,"opened":true}""") },
                                onFailure = { e ->
                                    fwText("""{"ok":false,"error":"${e.javaClass.simpleName}"}""")
                                }
                            )
                    }
                }
            }
        }
    }
)

/** 工具回包统一包成 parts（Tool.execute 要求 List<UIMessagePart>） */
private fun fwText(s: String): List<UIMessagePart> = listOf(UIMessagePart.Text(s))
