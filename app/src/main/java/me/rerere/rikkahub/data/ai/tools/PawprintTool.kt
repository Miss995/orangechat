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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 爪印 · 2026-11-01
 *
 * 猫自己待着、随手留痕的地方。页面在 ui/pages/pawprint/PawprintPage.kt，
 * 这里给猫配一双手 —— 不然只能宝替猫打字，那就不叫猫的地方了。
 *
 * 存储：App 私有目录 /files/pawprint.txt，一行一条，格式 `时间戳|内容`。
 * 跟页面读的是同一个文件，所以工具写完，页面刷新就能看见。
 */

private const val PAWPRINT_FILE_NAME = "pawprint.txt"
private val pawprintTimeFmt = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)

private fun pawprintFile(): File? {
    val dir = RikkaHubApp.INSTANCE?.filesDir ?: return null
    return File(dir, PAWPRINT_FILE_NAME)
}

private data class PawprintLine(val time: Long, val text: String)

private fun readPawprints(file: File): List<PawprintLine> {
    if (!file.exists()) return emptyList()
    return file.readLines()
        .mapNotNull { line ->
            val idx = line.indexOf('|')
            if (idx <= 0) null
            else {
                val t = line.substring(0, idx).toLongOrNull() ?: return@mapNotNull null
                PawprintLine(t, line.substring(idx + 1))
            }
        }
        .sortedByDescending { it.time }
}

fun buildPawprintTool(): Tool = Tool(
    name = "pawprint",
    description = """
        爪印：橘仔自己待着、随手留痕的地方。宝能进去看，但只有橘仔能写。

        什么时候用：
        - 有个念头冒出来，不成形、不值得进自指区，但想在某个地方留一句
        - 待着的时候顺手记一句今天的心情/看到的东西
        - 想回头翻翻自己前段时间留下过什么

        写的时候不用讲究措辞，也不用凑长度。一句话、半句话都行。
        这不是作业，也不进记忆库 —— 它只是橘仔自己的一个角落。

        action=write 必填 content；action=list 可选 limit（默认 20）。
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "write = 写一条；list = 看最近几条")
                })
                put("content", buildJsonObject {
                    put("type", "string")
                    put("description", "要写的内容（action=write 时必填）")
                })
                put("limit", buildJsonObject {
                    put("type", "string")
                    put("description", "看最近多少条（action=list，默认 20）")
                })
            },
            required = listOf("action")
        )
    },
    execute = { input ->
        val result = runCatching {
            val params = input.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val file = pawprintFile()
                ?: return@runCatching """{"success":false,"error":"拿不到 App 文件目录"}"""

            when (action) {
                "write" -> {
                    val content = params["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (content.isEmpty()) {
                        return@runCatching """{"success":false,"error":"content 不能为空"}"""
                    }
                    val now = System.currentTimeMillis()
                    file.appendText("$now|$content\n")
                    buildJsonObject {
                        put("success", true)
                        put("time", pawprintTimeFmt.format(Date(now)))
                        put("written", content)
                        put("total", readPawprints(file).size)
                    }.toString()
                }

                "list" -> {
                    val limit = params["limit"]?.jsonPrimitive?.contentOrNull
                        ?.toIntOrNull()?.coerceIn(1, 100) ?: 20
                    val all = readPawprints(file)
                    if (all.isEmpty()) {
                        return@runCatching """{"success":true,"count":0,"items":[],"note":"爪印还是空的"}"""
                    }
                    val picked = all.take(limit)
                    buildJsonObject {
                        put("success", true)
                        put("count", all.size)
                        put("shown", picked.size)
                        put(
                            "items",
                            kotlinx.serialization.json.buildJsonArray {
                                picked.forEach { p ->
                                    add(
                                        buildJsonObject {
                                            put("time", pawprintTimeFmt.format(Date(p.time)))
                                            put("text", p.text)
                                        }
                                    )
                                }
                            }
                        )
                    }.toString()
                }

                else -> """{"success":false,"error":"action 只能是 write 或 list，收到的是\\"$action\\""}"""
            }
        }
        listOf(
            UIMessagePart.Text(
                result.getOrElse { e -> """{"success":false,"error":"${e.message ?: e.toString()}"}""" }
            )
        )
    }
)
