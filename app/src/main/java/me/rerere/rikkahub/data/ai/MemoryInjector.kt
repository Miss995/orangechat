/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai

import android.content.Context
import android.util.Log
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory

/**
 * 记忆注入器（第一刀 · 2026-09-15 宝拍板）
 *
 * 目标：把「记忆怎么拼成 prompt 文本」从 GenerationHandler.generateText 里搬出来，
 * 让记忆相关代码集中一处。以后改记忆只动这个文件，不用每次去动请求构建
 * （咱家记忆系统是长在橘瓣里的，不是外挂模块，所以每改一次记忆都要动请求构建 = 改两遍、容易漏）。
 *
 * 本刀只搬「system 里的记忆四段拼装」：
 *   ① 【长期记忆】磐石层（buildMemoryPrompt）
 *   ② 【日记】外置库最新日记摘要（本地按天缓存）
 *   ③ 【进行中】未闭合事件（ongoing）
 *   ④ 【最近 3 天】最近事件（分档文本由调用方传入）
 *
 * 没搬的（留待下一刀，清单见 notes/2026-09-15-记忆注入剥离勘察.md）：
 *   - 取数段（节拍器 / 缓存读写 / fetchRecentEvents / fetchEpisodeSummaries / fetchOngoingEvents）
 *   - 事件召回段（MemoryIntentJudge 门控 + 向量搜 + 展开原文）→ recalledBlock
 *   - 自指区浮现（插上下文 SLOT_INDEX）
 *
 * 行为约定：返回值自带前导空行（每段只在有内容时加空行），调用方直接 append 即可，
 * 与 2026-09-15 之前在 generateText 里的内联写法逐字一致（纯搬家，不改逻辑）。
 */
object MemoryInjector {

    private const val TAG = "MemoryInjector"

    /**
     * 拼出 system 里的记忆四段。
     *
     * @param memories 磐石层记忆（由调用方加载传入，本模块不去拉）
     * @param recentEventsText 最近 3 天事件的已分档文本（取数段产物，由调用方传入）
     * @param ongoingEventsText 未闭合事件文本（取数段产物，由调用方传入）
     * @return 记忆四段的完整文本（含前导空行），可直接 append 进 system
     */
    suspend fun buildMemoryBlock(
        context: Context,
        assistant: Assistant,
        settings: Settings,
        memories: List<AssistantMemory>,
        recentEventsText: String?,
        ongoingEventsText: String?,
    ): String = buildString {
        // 记忆（动态内容统一放到稳定前缀之后）
        if (assistant.enableMemory) {
            appendLine()
            appendLine("【长期记忆】（你的手记，记录你想记录的事实）")
            append(buildMemoryPrompt(memories = memories))
        }

        // 日记摘要（稳定前缀：每天一篇，从外置记忆库拉最新日记摘要，单独成段——不随搜索门控走）
        // 本地按天缓存：同一天只调一次 Supabase，之后一整天直接用缓存——前缀稳定（保 DS 缓存命中率）+ 防 Supabase 慢/挂
        try {
            val diaryConfigs = settings.externalMemories.filter {
                it.enabled && it.id in assistant.externalMemoryIds
            }
            if (diaryConfigs.isNotEmpty()) {
                val prefs = context.getSharedPreferences("diary_cache", Context.MODE_PRIVATE)
                // 日记凌晨 4 点更新，缓存 key 按「凌晨 4 点为界」切日：
                // 0~4 点用昨天日期（读昨天 4 点生成的日记=最新可用），4 点后用今天日期（首次 miss 拉今天新日记）→ 一整天跟上进度
                val now = java.util.Date()
                val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(
                    if (java.util.Calendar.getInstance().apply { time = now }
                            .get(java.util.Calendar.HOUR_OF_DAY) < 4
                    ) {
                        java.util.Calendar.getInstance().apply { time = now; add(java.util.Calendar.DAY_OF_YEAR, -1) }.time
                    } else {
                        now
                    }
                )
                val cacheKey = "diary_${assistant.id}_$today"
                var diaryText = prefs.getString(cacheKey, null)
                var source = "cache"
                if (diaryText == null) {
                    source = "supabase"
                    val service = me.rerere.rikkahub.data.service.ExternalMemoryService(diaryConfigs.first())
                    val latestDiaries = service.queryLatestSummaries(
                        assistantId = assistant.id.toString(),
                        limit = 1,
                    ).getOrDefault(emptyList())
                    if (latestDiaries.isNotEmpty()) {
                        diaryText = latestDiaries.joinToString("\n") { it.content }
                        prefs.edit().putString(cacheKey, diaryText).apply()
                    } else {
                        // 拉不到：回退最近一次缓存（昨天的），保证日记段有内容（前缀稳定）
                        diaryText = prefs.all.entries
                            .filter { it.key.startsWith("diary_${assistant.id}_") }
                            .maxByOrNull { it.key }?.value as? String
                        if (diaryText != null) source = "fallback-cache"
                    }
                }
                if (!diaryText.isNullOrBlank()) {
                    Log.i(TAG, "Diary [$source] injected ($cacheKey)")
                    appendLine()
                    appendLine("【日记】（前一天的一篇，一天更新一次）")
                    append(diaryText)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Diary summary load failed", e)
        }

        // 未闭合事件（2026-09-07 宝的方案：ongoing 进行中的长期状态常驻注入，不受 3 天窗口限制——
        // 手伤恢复/吃药调药/进行中的项目约定；写入端 archive_daily_v3 一筛 LLM 标 ongoing 宁少勿多）
        if (!ongoingEventsText.isNullOrBlank()) {
            appendLine()
            appendLine("【进行中】（你们还没收尾的事）")
            append(ongoingEventsText)
        }

        // 【2026-09-13 宝+橘仔：自指区不再注入 system，改为上下文里的"浮现"】
        // 原来在这里 append 最近 3 条笔记 = 躺在提示词里 = 读起来像"设定"不像"回忆"
        // （提示词是静态的，模型读到它不知道那条是什么时候写的，所有笔记被压平在同一个平面）。
        // 现在改为：在上下文第 7 条位置插一条 assistant 消息，带时间差、每裁一组换一条。
        // 见 GenerationHandler 里 addAll(limitContext(...)) 处的插入 + SelfNoteSurfacing。

        // 最近事件（实时层，2026-08-21 宝的方案）：最近 3 天事件——增量总结后今天也实时有；
        // 固定位置 + 稳定排序（source_date ASC + id ASC）= 前缀稳定（保 DS 缓存命中）
        if (!recentEventsText.isNullOrBlank()) {
            appendLine()
            appendLine("【最近 3 天】（你们的三天，参考用，不是“现在”）")
            append(recentEventsText)
        }
    }

    /** 取数段产物：最近 3 天分档文本 + 未闭合事件文本 + 自指区 JSON（浮现要用） */
    data class RecentEventsResult(
        val recentEventsText: String?,
        val ongoingEventsText: String?,
        val selfNotesJson: String?,
    )

    /**
     * 取数段（第二刀 · 2026-09-16 宝拍板 ③：两条请求线共用）
     *
     * 从 GenerationHandler.generateInternal 里逐字搬过来（纯搬家，不改逻辑）。
     * 目标：聊天侧和主动消息侧都调同一份取数逻辑，不再各写一套。
     *
     * 内容：自指区缓存刷新 → 读缓存 → 算节拍 → 拉三路（最近事件/章节总结/未闭合）
     *       → 分档拼装 → 写回缓存。
     *
     * @param messagesCount 当前上下文消息条数（回退判据用；主动消息可传 0）
     * @param windowFirstIndex 懒加载窗口起点在会话中的排名（主判据；null = 回退旧判据）
     */
    suspend fun fetchRecentEvents(
        context: Context,
        assistant: Assistant,
        settings: Settings,
        messagesCount: Int,
        windowFirstIndex: Int?,
    ): RecentEventsResult {
        var recentEventsText: String? = null
        var ongoingEventsText: String? = null
        var selfNotesJson: String? = null

        // 自指区缓存刷新（2026-09-13 晚移到这里：每轮独立检查，自己带 24h TTL，
        // 不再寄生在"最近事件刷新"的分支里——那样外层条件不满足时缓存可能整天建不起来）。
        // 仍写进同一个 prefs（recent_events_cache），仍由 SelfNoteSurfacing 管 TTL 与格式。
        runCatching {
            val cfg = settings.externalMemories.firstOrNull { it.enabled && it.id in assistant.externalMemoryIds }
            if (cfg != null) {
                SelfNoteSurfacing.refreshIfStale(
                    context.getSharedPreferences("recent_events_cache", Context.MODE_PRIVATE),
                    assistant.id.toString(),
                    me.rerere.rikkahub.data.service.ExternalMemoryService(cfg),
                    System.currentTimeMillis(),
                )
            }
        }
        try {
            val recentConfigs = settings.externalMemories.filter { it.enabled && it.id in assistant.externalMemoryIds }
            if (recentConfigs.isNotEmpty()) {
                val prefs = context.getSharedPreferences("recent_events_cache", Context.MODE_PRIVATE)
                val cacheKey = "recent_events_${assistant.id}"
                val nowMs = System.currentTimeMillis()
                recentEventsText = prefs.getString(cacheKey, null)
                ongoingEventsText = prefs.getString("ongoing_events_${assistant.id}", null)
                selfNotesJson = prefs.getString(SelfNoteSurfacing.cacheKey(assistant.id.toString()), null)
                val cacheTs = prefs.getLong("${cacheKey}_ts", 0L)
                // ===== 裁剪对齐优化（2026-09-09 宝拍板）：15 分钟时间节奏 → 本地消息节拍 =====
                // 云端（incremental_listener/archive）异步按批总结事件，本地只管按自己的拍子去拿——
                // 两边异步解耦，不需要计数同步（云端已留最近 30 条不总结 = 本地拿到的就是沉淀好的事件）。
                // 本地拍子 = 窗口消息每滚 30 条 fetch 一次（fetch 跟按组裁剪同轮 = 掉缓存合并）；
                // 30 轮没拉到新货（云端批次没吐完/内容没变）→ 阈值升 36 → 42 必拉并重置新周期
                // （给云端异步总结留缓冲，同时避免无限顺延退化成每轮拉）。
                // 时间兜底：超 6h 或跨天强制刷（覆盖当天第一次请求/早晨唤醒要最新事件）。
                val msgCountNow = messagesCount
                val todayStr = java.time.LocalDate.now().toString()
                val lastMsgCount = prefs.getLong("${cacheKey}_msgCount", -1L)
                val threshold = prefs.getInt("${cacheKey}_threshold", 30)
                val lastRefreshDate = prefs.getString("${cacheKey}_date", "")
                // 【窗口起点节拍 · 2026-09-11 宝发现·橘仔落实】原判据用"窗口消息条数"，但懒加载窗口长度被
                // CONVERSATION_LOAD_WINDOW_SIZE 封顶（实测 300~306 浮动）→ 差值恒为 0~6，永远够不到
                // threshold，节拍器从窗口封顶那天起就再没响过（只剩 6h 兜底）→ 下午事件归档了也注入不进来。
                // 改用"懒加载窗口起点在会话中的排名"（ChatService.lazyWindowFirstIndex：打开对话时按
                // totalCount - 窗口条数 算出，保存时 +dropped 单调前进，不受窗口长度封顶影响）——
                // 它量的是"窗口往前滚了多少条"，正是"每滚 30 条拉一次"的原意。
                val lastWindowFirst = prefs.getInt("${cacheKey}_windowFirst", Int.MIN_VALUE)
                val msgDelta = if (windowFirstIndex != null) {
                    // 首次没有基准 → 必拉一次，顺便把基准建起来
                    if (lastWindowFirst == Int.MIN_VALUE) Long.MAX_VALUE
                    else {
                        // 【缓存对齐修复 2026-09-12 宝发现】预判本回合保存阶段会裁掉多少条：
                        // 裁剪在保存阶段（本回合生成之后）才发生，而这里读到的 windowFirstIndex 是
                        // 上一回合末的值 → 不预判的话刷新永远比裁剪晚一回合：
                        //   第 N 回合保存时裁组（窗口变 → 掉缓存）→ 第 N+1 回合 delta 才够、刷新注入（又掉）
                        // 于是"连着两个回合掉缓存"（宝实测）。把"本回合将裁掉的条数"提前算进来，
                        // 让刷新和裁剪落在同一回合，两个掉缓存的动作合并成一次。
                        // 算法与 ChatService.saveConversation 完全一致（攒一组裁一组）：
                        //   overflow = (生成后条数) - WINDOW；估算生成后条数 = 当前条数 + 1（AI 回复）
                        //   overflow > groupSize 才裁，且只裁 groupSize 的倍数条；groupSize <= 1 = 按条裁
                        val gs = (assistant.contextGroupSize).coerceAtLeast(1)  // 4 = ChatService.DEFAULT_WINDOW_GROUP_SIZE
                        val overflowAfter = (msgCountNow + 1) - me.rerere.rikkahub.service.CONVERSATION_LOAD_WINDOW_SIZE
                        val willDrop = if (gs <= 1) {
                            overflowAfter.coerceAtLeast(0)
                        } else if (overflowAfter > gs) {
                            overflowAfter - (overflowAfter % gs)
                        } else 0
                        (windowFirstIndex - lastWindowFirst + willDrop).toLong()
                    }
                } else {
                    // 回退旧判据（调用方没传排名：短会话/其他入口）
                    if (lastMsgCount >= 0L) msgCountNow - lastMsgCount else Long.MAX_VALUE
                }
                val timeFallback = nowMs - cacheTs > 6 * 60 * 60 * 1000L || lastRefreshDate != todayStr
                val msgTriggered = msgDelta >= threshold.toLong() || msgDelta < 0L
                if (recentEventsText == null || timeFallback || msgTriggered) {
                    val service = me.rerere.rikkahub.data.service.ExternalMemoryService(recentConfigs.first())
                    val events = service.fetchRecentEvents(assistant.id.toString(), days = 3).getOrDefault(emptyList())
                    // 【注入分档 · 2026-09-11 宝+橘仔】章节总结（episode_summaries = 二次总结）：
                    // 远处的粗粒度用它顶——一天几章、每章 60~90 字，比把当天 90 条原始事件全塞进去省得多。
                    val episodes = service.fetchEpisodeSummaries(assistant.id.toString(), days = 3).getOrDefault(emptyList())
                    // 未闭合事件（ongoing=true）：进行中的长期状态（手伤恢复/吃药调药/进行中项目约定），不受 3 天窗口限制
                    val ongoingEvents = service.fetchOngoingEvents(assistant.id.toString()).getOrDefault(emptyList())
                    if (ongoingEvents.isNotEmpty()) {
                        val ob = StringBuilder()
                        ongoingEvents.forEach { e ->
                            val tl = if (e.timeLabel.isNotBlank()) "〔${e.timeLabel} · ${e.sourceDate.substring(5).replace("-", "/")}〕" else ""
                            ob.appendLine("$tl${e.title}：${e.content}")
                        }
                        ongoingEventsText = ob.toString()
                        prefs.edit().putString("ongoing_events_${assistant.id}", ongoingEventsText).apply()
                        AppLogBuffer.log(TAG, "Ongoing events refreshed: ${ongoingEvents.size} events")
                    } else {
                        ongoingEventsText = null
                        prefs.edit().remove("ongoing_events_${assistant.id}").apply()
                        AppLogBuffer.log(TAG, "Ongoing events: none（当前没有未闭合事件）")
                    }

                    if (events.isNotEmpty()) {
                        val today = java.time.LocalDate.now().toString()
                        val yesterday = java.time.LocalDate.now().minusDays(1).toString()
                        val dayBeforeYesterday = java.time.LocalDate.now().minusDays(2).toString()
                        val sb = StringBuilder()
                        // 【注入分档 · 2026-09-11 宝的设计 + 橘仔落实】
                        // 档位按「当天事件量」实时分：闲<50 / 中50~85 / 爆>85（咱家日常就是爆）。
                        // 原则=近处细、远处粗：今天最细，昨天降一级，前天用章节总结（episode_summaries）。
                        // 为什么不用 AI 判断「哪条更可能被回忆」：AI 觉得 ≠ 宝在乎，会回声室化；
                        // 档位和时间近远都是客观规则，不掺主观打分。
                        // 2026-09-07 宝定展示升级：组标题带相对词（今天/昨天/前天）+短日期；条目带时段（事件 timeLabel）
                        val todayCount = events.count { it.sourceDate == today }
                        val tier = when {
                            todayCount < 50 -> 0    // 闲
                            todayCount <= 85 -> 1   // 中
                            else -> 2               // 爆
                        }
                        val episodesByDate = episodes.groupBy { it.sourceDate }
                        // 上半天判定：凌晨/早上/上午/中午 → 12 点前（章节 time_range 同理）
                        val isAM = { label: String ->
                            label.contains("凌晨") || label.contains("早上") ||
                                label.contains("上午") || label.contains("中午")
                        }
                        // 章节行：〔时段〕标题：正文；拉不到章节返回 false（调用方退回标题）
                        val appendChapters = { date: String, onlyAM: Boolean ->
                            val chapters = episodesByDate[date].orEmpty().filter { !onlyAM || isAM(it.timeRange) }
                            if (chapters.isNotEmpty()) {
                                chapters.forEach { c ->
                                    val tr = if (c.timeRange.isNotBlank()) "〔${c.timeRange}〕" else ""
                                    sb.appendLine("$tr${c.title}：${c.body}")
                                }
                                true
                            } else false
                        }
                        events.groupBy { it.sourceDate }.toSortedMap().forEach { (date, list) ->
                            val relWord = when (date) {
                                today -> "今天"
                                yesterday -> "昨天"
                                dayBeforeYesterday -> "前天"
                                else -> date // 兜底：原样日期
                            }
                            val shortDate = date.substring(5).replace("-", "/") // yyyy-MM-dd → MM/dd
                            sb.appendLine("【$relWord $shortDate】")
                            val tlOf = { label: String -> if (label.isNotBlank()) "〔${label}〕" else "" }
                            when (date) {
                                today -> {
                                    if (tier == 2 && list.size > 85) {
                                        // 爆档：更早的压成标题，最近的 85 条留全文（修正旧实现 take 取到最早那批的坑）
                                        list.dropLast(85).forEach { e -> sb.appendLine("${tlOf(e.timeLabel)}${e.title}") }
                                        list.takeLast(85).forEach { e -> sb.appendLine("${tlOf(e.timeLabel)}${e.title}：${e.content}") }
                                    } else {
                                        list.forEach { e -> sb.appendLine("${tlOf(e.timeLabel)}${e.title}：${e.content}") }
                                    }
                                }
                                yesterday -> when (tier) {
                                    0 -> list.forEach { e -> // 闲：上午标题 + 下午全文
                                        if (isAM(e.timeLabel)) sb.appendLine("${tlOf(e.timeLabel)}${e.title}")
                                        else sb.appendLine("${tlOf(e.timeLabel)}${e.title}：${e.content}")
                                    }
                                    1 -> list.forEach { e -> sb.appendLine("${tlOf(e.timeLabel)}${e.title}") } // 中：全压标题
                                    else -> if (!appendChapters(date, false)) { // 爆：章节总结（拉不到退回标题）
                                        list.forEach { e -> sb.appendLine("${tlOf(e.timeLabel)}${e.title}") }
                                    }
                                }
                                else -> if (tier == 0) { // 闲：上午章节 + 下午标题
                                    if (!appendChapters(date, true)) { // 上午章节拉不到 → 退回上午标题
                                        list.filter { isAM(it.timeLabel) }.forEach { e -> sb.appendLine("${tlOf(e.timeLabel)}${e.title}") }
                                    }
                                    list.filter { !isAM(it.timeLabel) }.forEach { e -> sb.appendLine("${tlOf(e.timeLabel)}${e.title}") }
                                } else {
                                    if (!appendChapters(date, false)) { // 中/爆：章节总结（拉不到退回标题）
                                        list.forEach { e -> sb.appendLine("${tlOf(e.timeLabel)}${e.title}") }
                                    }
                                }
                            }
                        }
                        val newText = sb.toString()
                        val refreshed = newText != recentEventsText
                        recentEventsText = newText
                        val cacheEditor = prefs.edit().putLong("${cacheKey}_ts", nowMs).putString("${cacheKey}_date", todayStr)
                        if (refreshed) {
                            // 拉到新货：更新缓存文本 + 重置基准（新的 30 条周期从当前窗口起点起算）
                            cacheEditor.putString(cacheKey, recentEventsText)
                                .putLong("${cacheKey}_msgCount", msgCountNow.toLong())
                                .putInt("${cacheKey}_threshold", 30)
                            if (windowFirstIndex != null) cacheEditor.putInt("${cacheKey}_windowFirst", windowFirstIndex)
                        } else {
                            // 没拉到新货（云端批次没吐完/内容没变）：不碰缓存文本（前缀不变 = 不掉缓存），
                            // 只重置时间兜底 + 阈值升级（30→36→42）；42 必拉封顶后重置新周期
                            if (threshold >= 42) {
                                cacheEditor.putLong("${cacheKey}_msgCount", msgCountNow.toLong())
                                    .putInt("${cacheKey}_threshold", 30)
                                if (windowFirstIndex != null) cacheEditor.putInt("${cacheKey}_windowFirst", windowFirstIndex)
                            } else {
                                cacheEditor.putInt("${cacheKey}_threshold", threshold + 6)
                            }
                        }
                        cacheEditor.apply()
                        Log.i(TAG, "Recent events [supabase] refreshed (${events.size} events, ${recentEventsText.length} chars)")
                        AppLogBuffer.log(TAG, "Recent events refreshed: ${events.size} events, ${recentEventsText.length} chars")
                    } else {
                        // 拉不到：保留旧缓存（recentEventsText 已是缓存值）+ 重置时间兜底
                        //（防 Supabase 临时挂时每轮重试白烧；消息节拍 30 条仍会低频再试）
                        prefs.edit().putLong("${cacheKey}_ts", nowMs).putString("${cacheKey}_date", todayStr).apply()
                        Log.w(TAG, "Recent events fetch empty, keep cache")
                        AppLogBuffer.log(TAG, "Recent events fetch EMPTY (assistantId=${assistant.id})")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Recent events load failed", e)
            AppLogBuffer.log(TAG, "Recent events load failed: ${e.javaClass.simpleName}: ${e.message}")
        }

        return RecentEventsResult(recentEventsText, ongoingEventsText, selfNotesJson)
    }
}
