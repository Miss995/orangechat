/* 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai

import java.time.LocalDate

/**
 * 时间范围（yyyy-MM-dd，北京时间口径；与橘瓣 chat_messages 存储同口径）
 */
data class TimeRange(
    val dateFrom: String? = null,
    val dateTo: String? = null,
) {
    val hasRange: Boolean get() = dateFrom != null || dateTo != null
}

/**
 * 时间范围解析（「时间定位」功能核心）：
 * 从用户消息里解析出 dateFrom/dateTo，给外置库事件召回 / OB breath_search 加时间过滤。
 * 用户说"上周说的那个事"时只召回该时间范围内的记忆，而不是全库乱捞。
 *
 * 覆盖常见口语时间表达（阿拉伯数字+中文数字）；解析不到明确时间时返回空 TimeRange（=不限时间，保持旧行为）。
 */
object TimeRangeParser {

    private fun fmt(d: LocalDate): String = "%04d-%02d-%02d".format(d.year, d.monthValue, d.dayOfMonth)

    private fun cnNum(s: String): Int? = when (s) {
        "一" -> 1; "二" -> 2; "三" -> 3; "四" -> 4; "五" -> 5; "六" -> 6; "七" -> 7; "八" -> 8; "九" -> 9; "十" -> 10
        "十一" -> 11; "十二" -> 12
        else -> null
    }

    /** 解析 月/日 数字（支持 "8月13号" "八月十三号"） */
    private fun parseMonthDay(t: String, cn: Boolean): Triple<Int?, Int?, Int?> {
        val pat = if (cn) {
            Regex("([一二三四五六七八九十]{1,2})月([一二三四五六七八九十]{1,2})[号日]")
        } else {
            Regex("(\\d{1,2})月(\\d{1,2})[号日]")
        }
        val m = pat.find(t)
        if (m != null) {
            val month = if (cn) cnNum(m.groupValues[1]) else m.groupValues[1].toIntOrNull()
            val day = if (cn) cnNum(m.groupValues[2]) else m.groupValues[2].toIntOrNull()
            if (month != null && day != null && month in 1..12 && day in 1..31) {
                return Triple(month, day, 1)
            }
        }
        return Triple(null, null, 0)
    }

    private fun parseMonth(t: String, cn: Boolean): Int? {
        val pat = if (cn) Regex("([一二三四五六七八九十]{1,2})月") else Regex("(\\d{1,2})月")
        val m = pat.find(t) ?: return null
        val month = if (cn) cnNum(m.groupValues[1]) else m.groupValues[1].toIntOrNull()
        return if (month != null && month in 1..12) month else null
    }

    fun parse(text: String, today: LocalDate = LocalDate.now()): TimeRange {
        if (text.isBlank()) return TimeRange()
        val t = text.trim()

        // 具体日期：X月X号 / X月X日（阿拉伯 + 中文数字）
        for (cn in listOf(false, true)) {
            val (month, day, hit) = parseMonthDay(t, cn)
            if (hit == 1 && month != null && day != null) {
                val target = fmt(LocalDate.of(today.year, month, day))
                return TimeRange(target, target)
            }
        }

        // 整月：X月（如 "8月" "八月"）
        for (cn in listOf(false, true)) {
            val month = parseMonth(t, cn)
            if (month != null) {
                val first = fmt(LocalDate.of(today.year, month, 1))
                if (month > today.monthValue) {
                    return TimeRange(first, fmt(today)) // 未来月份收敛到今天
                }
                val lastOfMonth = LocalDate.of(today.year, month, 1).lengthOfMonth()
                return TimeRange(first, fmt(LocalDate.of(today.year, month, lastOfMonth)))
            }
        }

        // 今天 / 今日
        if (t.contains("今天") || t.contains("今日")) return TimeRange(fmt(today), fmt(today))
        // 昨天 / 昨日
        if (t.contains("昨天") || t.contains("昨日")) return TimeRange(fmt(today.minusDays(1)), fmt(today.minusDays(1)))
        // 前天
        if (t.contains("前天")) return TimeRange(fmt(today.minusDays(2)), fmt(today.minusDays(2)))
        // 大前天
        if (t.contains("大前天")) return TimeRange(fmt(today.minusDays(3)), fmt(today.minusDays(3)))

        // N天前 / N日前
        Regex("(\\d{1,3})(?:天|日)前").find(t)?.let { m ->
            val n = m.groupValues[1].toIntOrNull()
            if (n != null && n in 1..3650) {
                val target = fmt(today.minusDays(n.toLong()))
                return TimeRange(target, target)
            }
        }

        // 最近N天 / 近N天 / N天内（如 "最近3天"）
        Regex("(?:最近|近|过去)(\\d{1,3})(?:天|日)(?:内|以来)?").find(t)?.let { m ->
            val n = m.groupValues[1].toIntOrNull()
            if (n != null && n in 1..3650) {
                return TimeRange(fmt(today.minusDays(n.toLong() - 1)), fmt(today))
            }
        }

        // 上周X（上周一/上周五/上周日）
        Regex("上周([一二三四五六五六日天])").find(t)?.let { m ->
            val dow = when (m.groupValues[1]) {
                "一" -> 1; "二" -> 2; "三" -> 3; "四" -> 4; "五" -> 5; "六" -> 6; else -> 7
            }
            val thisMonday = today.minusDays((today.dayOfWeek.value - 1).toLong())
            val target = thisMonday.minusDays((8 - dow).toLong())
            return TimeRange(fmt(target), fmt(target))
        }
        // 上周（整周：上周一到上周日）
        if (t.contains("上周") || t.contains("上礼拜") || t.contains("上个星期")) {
            val thisMonday = today.minusDays((today.dayOfWeek.value - 1).toLong())
            val lastMonday = thisMonday.minusDays(7)
            return TimeRange(fmt(lastMonday), fmt(lastMonday.plusDays(6)))
        }

        // 前几天 / 前些天 / 前两天
        if (t.contains("前几天") || t.contains("前些天") || t.contains("前两天")) {
            return TimeRange(fmt(today.minusDays(7)), fmt(today.minusDays(1)))
        }
        // 这几天 / 这两天
        if (t.contains("这几天") || t.contains("这两天")) {
            return TimeRange(fmt(today.minusDays(3)), fmt(today))
        }
        // 最近（单独出现，模糊：近一周）
        if (t.contains("最近")) {
            return TimeRange(fmt(today.minusDays(6)), fmt(today))
        }

        // 上个月 / 上月
        if (t.contains("上个月") || t.contains("上月")) {
            val lastOfLastMonth = today.withDayOfMonth(1).minusDays(1)
            val firstOfLastMonth = lastOfLastMonth.withDayOfMonth(1)
            return TimeRange(fmt(firstOfLastMonth), fmt(lastOfLastMonth))
        }
        // 这个月 / 本月
        if (t.contains("这个月") || t.contains("本月")) {
            return TimeRange(fmt(today.withDayOfMonth(1)), fmt(today))
        }

        // 今年 / 去年
        if (t.contains("今年")) return TimeRange(fmt(LocalDate.of(today.year, 1, 1)), fmt(today))
        if (t.contains("去年")) return TimeRange(
            fmt(LocalDate.of(today.year - 1, 1, 1)),
            fmt(LocalDate.of(today.year - 1, 12, 31))
        )

        return TimeRange()
    }
}
