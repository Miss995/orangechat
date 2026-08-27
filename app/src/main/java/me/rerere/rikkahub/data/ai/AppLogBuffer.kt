/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai

import android.content.Context
import java.io.File
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
 * 2026-08-27 升级：落盘持久化。之前纯内存环 500 条滚动覆盖，正文被吃排查时
 * 关键日志（MessagePartsRender / StreamChunk）总被刷掉查不到（宝：扩大数量没用）。
 * 现在：log() 同时追加写文件（cacheDir/app_log_buffer.txt），read() 合并文件+内存，
 * 关键日志（GEN_RESULT 等）再也不丢。文件超过 MAX_FILE_ENTRIES 行截断保留最近一半。
 */
object AppLogBuffer {
    private const val MAX_ENTRIES = 500
    private const val MAX_FILE_ENTRIES = 6000
    private val entries = CopyOnWriteArrayList<String>()
    private val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    @Volatile
    private var logFile: File? = null

    /** 必须在 Application.onCreate 调用一次（RikkaHubApp.onCreate 已接） */
    fun init(context: Context) {
        if (logFile == null) {
            logFile = File(context.cacheDir, "app_log_buffer.txt")
        }
    }

    fun log(tag: String, msg: String) {
        val line = "[${sdf.format(Date())}] [$tag] $msg"
        entries.add(line)
        while (entries.size > MAX_ENTRIES) {
            entries.removeAt(0)
        }
        // 落盘：追加写文件（防内存环被刷丢——正文被吃排查的 GEN_RESULT 等关键日志不丢）
        try {
            val f = logFile ?: return
            synchronized(this) {
                f.appendText(line + "\n")
                // 简单限制：超过 MAX_FILE_ENTRIES 行就截断（保留最近一半）
                if (f.exists() && f.readLines().size > MAX_FILE_ENTRIES) {
                    val lines = f.readLines().takeLast(MAX_FILE_ENTRIES / 2)
                    f.writeText(lines.joinToString("\n") + "\n")
                }
            }
        } catch (_: Throwable) {
        }
    }

    /** 读取：按关键词过滤（匹配 tag 或内容，大小写不敏感），返回最近 limit 条（最新在后）。合并文件历史 + 内存最新 */
    fun read(filter: String?, limit: Int): String {
        val all = buildList {
            try {
                logFile?.let { f ->
                    if (f.exists()) addAll(f.readLines())
                }
            } catch (_: Throwable) {
            }
            addAll(entries)
        }
        val safeLimit = limit.coerceIn(1, 3000)
        val f = filter?.trim().orEmpty()
        val matched = all.asReversed()
            .filter { line -> f.isEmpty() || line.contains(f, ignoreCase = true) }
            .take(safeLimit)
            .reversed()
        if (matched.isEmpty()) {
            return "(日志环为空" + if (f.isEmpty()) ")" else "，没有匹配 '$f')"
        }
        return matched.joinToString("\n")
    }
}
