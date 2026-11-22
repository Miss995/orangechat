/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.service.AppUsageService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun createAppUsageTool(context: Context): Tool = Tool(
    name = "get_app_usage",
    description = "Get today's app usage statistics. Returns app names, usage time, and last used time.",
    needsApproval = true,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "Maximum number of apps to return (default 10)")
                }
            }
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        try {
            val limit = params["limit"]?.jsonPrimitive?.intOrNull ?: 10
            // 【2026-11-22 宝发现"不准"】这里原来自己又查了一遍 queryUsageStats(INTERVAL_DAILY)，
            // 走的是"系统日桶"那套：给的时间范围只用来挑桶，返回的却是整个桶的统计 ——
            // 于是同一个 App 会出现两条（两个桶），加起来还能超过今天醒着的时长。
            // AppUsageService 里 2026-10-03 已经改成遍历事件流自己配对、灭屏就结段，那份才准。
            // 工具不再自己算，直接调它 —— 一份实现，别再分叉。
            val stats = AppUsageService(context).getTodayUsageStats().getOrNull()
                ?.take(limit)
                ?: emptyList()

            if (stats.isEmpty()) {
                return@Tool listOf(UIMessagePart.Text(
                    buildJsonObject {
                        put("success", true)
                        put("count", 0)
                        put("message", "No app usage data. Grant usage access permission.")
                    }.toString()
                ))
            }

            val arr = buildJsonArray {
                stats.forEach { stat ->
                    add(buildJsonObject {
                        put("app_name", stat.appName)
                        put("package_name", stat.packageName)
                        put("usage_minutes", (stat.totalTimeInForeground / 60000).toInt())
                        put("last_used", dateFormat.format(Date(stat.lastTimeUsed)))
                    })
                }
            }

            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("count", stats.size)
                    put("apps", arr)
                }.toString()
            ))
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", false)
                    put("error", e.message ?: "Unknown error")
                }.toString()
            ))
        }
    }
)