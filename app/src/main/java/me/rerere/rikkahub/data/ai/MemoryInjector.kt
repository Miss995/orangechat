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
}
