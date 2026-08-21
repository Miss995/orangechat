/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * App 内存日志环（2026-08-21 加：查日志工具 read_app_logs 的数据源）
 *
 * 背景：普通 Android 应用没有 READ_LOGS 权限读不了系统 logcat（尤其 Android 11+），
 * 而橘仔排查问题（如 fetchRecentEvents 静默失败导致实时事件不注入）时看不到 App 日志。
 * 方案：关键路径（ExternalMemoryService / GenerationHandler 等）打日志时同步写入这个环形缓冲，
 * 查日志工具直接读它——不依赖 logcat 权限。
 *
 * 环形：最多保留 MAX_ENTRIES 条（默认 500），满则覆盖最旧。线程安全：CopyOnWriteArrayList。
 */
object AppLogBuffer {
    private const val MAX_ENTRIES = 500
    private val entries = CopyOnWriteArrayList<String>()
    private val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun log(tag: String, msg: String) {
        entries.add("[${sdf.format(Date())}] [$tag] $msg")
        while (entries.size > MAX_ENTRIES) {
            entries.removeAt(0)
        }
    }

    /** 读取：按关键词过滤（匹配 tag 或内容，大小写不敏感），返回最近 limit 条（最新在后） */
    fun read(filter: String?, limit: Int): String {
        val safeLimit = limit.coerceIn(1, 500)
        val f = filter?.trim().orEmpty()
        val matched = entries.asReversed()
            .filter { line -> f.isEmpty() || line.contains(f, ignoreCase = true) }
            .take(safeLimit)
            .reversed()
        if (matched.isEmpty()) {
            return "(日志环为空" + if (f.isEmpty()) ")" else "，没有匹配 '$f')"
        }
        return matched.joinToString("\n")
    }
}
