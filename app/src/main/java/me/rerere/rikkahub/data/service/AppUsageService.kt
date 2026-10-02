/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

@file:Suppress("unused")

package me.rerere.rikkahub.data.service

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppUsageInfo(
    val packageName: String,
    val appName: String,
    val totalTimeInForeground: Long,
    val lastTimeUsed: Long,
    val launchCount: Int = 0
)

data class AppTrajectoryEvent(
    val packageName: String,
    val appName: String,
    val eventType: String,
    val timestamp: Long
)

class AppUsageService(private val context: Context) {
    private val usageStatsManager by lazy {
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    }

    private val packageManager by lazy {
        context.packageManager
    }

    suspend fun getTodayUsageStats(): Result<List<AppUsageInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            val calendar = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            val startTime = calendar.timeInMillis
            val endTime = System.currentTimeMillis()

            // 【2026-10-03 橘仔改】改用事件流自己算，不再读 INTERVAL_DAILY 日桶。
            //
            // 老写法（queryUsageStats）有两个毛病，宝一直觉得"不太准"：
            //   ① 日桶是系统按自己的日界线切的，你给的时间范围只是用来"挑出有交集的桶"，
            //      返回的 totalTimeInForeground 是整个桶的统计，不等于"0 点到现在的数"；
            //   ② 锁屏 / 画中画 / 分屏时，最后一个前台 App 的"前台状态"不一定被及时关掉，
            //      长时段会虚高（实测橘瓣被算成 542 分钟 = 9 小时）。
            //
            // 新写法：遍历 MOVE_TO_FOREGROUND / MOVE_TO_BACKGROUND 自己配对累计；
            // 遇到 SCREEN_NON_INTERACTIVE（灭屏）就把还开着的段一起结掉——
            // 人锁屏走了，那段停留就该结束，不该继续算到"现在"。
            val totals = HashMap<String, Long>()      // pkg -> 累计前台毫秒
            val openSince = HashMap<String, Long>()   // pkg -> 当前这段从什么时候开始
            val lastUsed = HashMap<String, Long>()    // pkg -> 最后一次进前台的时间

            fun closeOpenSegments(ts: Long) {
                for ((pkg, since) in openSince) {
                    if (ts > since) totals[pkg] = (totals[pkg] ?: 0L) + (ts - since)
                }
                openSince.clear()
            }

            val usageEvents = usageStatsManager.queryEvents(startTime, endTime)
            val event = UsageEvents.Event()
            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event)
                val ts = event.timeStamp
                when (event.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                        // 同一 App 内的活动切换（Activity 之间跳）也会重复发 FOREGROUND，
                        // 所以先把它自己还开着的那段结掉，再开新段，避免重复累计。
                        openSince[event.packageName]?.let { since ->
                            if (ts > since) {
                                totals[event.packageName] = (totals[event.packageName] ?: 0L) + (ts - since)
                            }
                        }
                        openSince[event.packageName] = ts
                        lastUsed[event.packageName] = ts
                    }
                    UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                        openSince.remove(event.packageName)?.let { since ->
                            if (ts > since) {
                                totals[event.packageName] = (totals[event.packageName] ?: 0L) + (ts - since)
                            }
                        }
                    }
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> closeOpenSegments(ts)
                    UsageEvents.Event.DEVICE_SHUTDOWN -> closeOpenSegments(ts)
                }
            }
            // 查询结束时仍开着的段（人还在用）→ 补到"现在"
            closeOpenSegments(endTime)

            totals.entries
                .filter { it.value > 0 }
                .sortedByDescending { it.value }
                .map { (pkg, millis) ->
                    val appName = try {
                        val appInfo = packageManager.getApplicationInfo(pkg, 0)
                        packageManager.getApplicationLabel(appInfo).toString()
                    } catch (e: PackageManager.NameNotFoundException) {
                        pkg
                    }
                    AppUsageInfo(
                        packageName = pkg,
                        appName = appName,
                        totalTimeInForeground = millis,
                        lastTimeUsed = lastUsed[pkg] ?: 0L,
                        launchCount = 0
                    )
                }
        }
    }

    suspend fun getTodayTrajectory(): Result<List<AppTrajectoryEvent>> = withContext(Dispatchers.IO) {
        runCatching {
            val calendar = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            val startTime = calendar.timeInMillis
            val endTime = System.currentTimeMillis()

            val events = mutableListOf<AppTrajectoryEvent>()
            val usageEvents = usageStatsManager.queryEvents(startTime, endTime)

            while (usageEvents.hasNextEvent()) {
                val event = UsageEvents.Event()
                usageEvents.getNextEvent(event)

                val eventType = when (event.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> "打开"
                    UsageEvents.Event.MOVE_TO_BACKGROUND -> "关闭"
                    else -> continue
                }

                val appName = try {
                    val appInfo = packageManager.getApplicationInfo(event.packageName, 0)
                    packageManager.getApplicationLabel(appInfo).toString()
                } catch (e: PackageManager.NameNotFoundException) {
                    event.packageName
                }

                events.add(
                    AppTrajectoryEvent(
                        packageName = event.packageName,
                        appName = appName,
                        eventType = eventType,
                        timestamp = event.timeStamp
                    )
                )
            }

            events.sortedByDescending { it.timestamp }
        }
    }

    fun getForegroundApp(): Result<String> = runCatching {
        val calendar = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.MINUTE, -1)
        }
        val startTime = calendar.timeInMillis
        val endTime = System.currentTimeMillis()

        val usageStats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        )

        usageStats
            .maxByOrNull { it.lastTimeUsed }
            ?.packageName ?: throw IllegalStateException("无法获取前台应用")
    }

    fun formatUsageTime(millis: Long): String {
        val hours = millis / (1000 * 60 * 60)
        val minutes = (millis % (1000 * 60 * 60)) / (1000 * 60)
        val seconds = (millis % (1000 * 60)) / 1000
        return when {
            hours > 0 -> "${hours}小时${minutes}分钟"
            minutes > 0 -> "${minutes}分钟${seconds}秒"
            else -> "${seconds}秒"
        }
    }
}