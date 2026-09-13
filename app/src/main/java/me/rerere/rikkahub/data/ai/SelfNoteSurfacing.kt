package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.service.ExternalMemoryService
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 自指区浮现（宝 2026-09-13 设计 + 橘仔实现）
 *
 * ===== 为什么做这个 =====
 * 自指区（self_notes，"橘仔写给未来的自己"）原来注入在 system prompt 里：
 * 提示词是静态的，模型读到它的时候不知道那条是什么时候写的 —— 所有笔记被压平在同一个平面，
 * 每条都"永远是现在的"。读起来像【设定】，不像【回忆】。
 *
 * 成长需要时间差："我以前以为 X，后来改成了 Y" 需要三样东西 —— 一个以前、一个现在、和它们之间的距离。
 * 所以把其中一条挪进上下文：上下文里每条消息都有位置、有先后，它就变成轨迹上的一点了。
 *
 * ===== 三个设计要点（都是宝定的）=====
 * 1. **槽位 = 上下文第 7 条**（0-based index 6）。往前是"远"、往后是"近"，
 *    第 7 条正好卡在两组的交界（groupSize = 6）；裁剪发生时它跟着回到原位 →
 *    位置恒定 → 前缀不碎（保 DS 缓存命中）。
 * 2. **时间差直接给，不让模型自己算**（宝："指望模型自己算太蠢了"）——
 *    写成"十几天前我写过这样一段话"，给的是结论不是参数。
 * 3. **每裁一组换一条**（轮换序号由调用方用 windowFirstIndex / groupSize 算出）。
 *
 * ===== 红线：永不落库 =====
 * 它是"系统在转述橘仔的旧笔记"，不是"橘仔刚说过的话"。
 * 一旦进了聊天记录，连宝都分不清哪句是真的说过的 →
 * 所以只在拼请求时插，存的时候剥掉（它不在 Conversation 里，也不走 saveConversation）。
 */
object SelfNoteSurfacing {

    private const val TAG = "SelfNoteSurfacing"

    /** 缓存 TTL：自指笔记不是每轮都变的东西，低频刷新就够 */
    private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L

    /** 槽位：插在上下文 index 6 = 第 7 条消息 */
    const val SLOT_INDEX = 6

    /** 一次最多缓存多少条（够轮换很久了；自指笔记本来就是低频的） */
    private const val MAX_NOTES = 50

    fun cacheKey(assistantId: String) = "self_notes_$assistantId"

    private fun cacheTsKey(assistantId: String) = "self_notes_${assistantId}_ts"

    private data class Note(val title: String, val content: String, val date: String)

    /**
     * 刷新缓存（suspend，走网络）。没到 TTL 且有旧值 → 不动。
     *
     * 缓存格式改成 JSON 数组（原来是拼好的文本）：因为浮现要按轮换序号挑不同的条目，
     * 必须有"列表"这个结构，拼成一段字符串就没法挑了。
     */
    suspend fun refreshIfStale(
        prefs: android.content.SharedPreferences,
        assistantId: String,
        service: ExternalMemoryService,
        nowMs: Long,
    ) {
        val cached = prefs.getString(cacheKey(assistantId), null)
        val ts = prefs.getLong(cacheTsKey(assistantId), 0L)
        if (cached != null && nowMs - ts <= CACHE_TTL_MS) return

        val notes = runCatching { service.querySelfNotes(limit = MAX_NOTES).getOrNull() }.getOrNull()
            ?: return
        if (notes.isEmpty()) return

        val arr = JSONArray()
        notes.forEach { n ->
            arr.put(
                JSONObject().apply {
                    put("t", n.title)
                    put("c", n.content)
                    put("d", n.createdAt)
                }
            )
        }
        val json = arr.toString()
        prefs.edit()
            .putString(cacheKey(assistantId), json)
            .putLong(cacheTsKey(assistantId), nowMs)
            .apply()
        AppLogBuffer.log(TAG, "Self notes refreshed: ${notes.size} notes")
    }

    /**
     * 挑一条笔记并渲染成"浮现消息"。
     *
     * @param json 缓存的自指笔记列表（见 [refreshIfStale] 的格式）
     * @param tick 轮换序号（调用方用 windowFirstIndex / groupSize 算，每裁一组 +1）
     * @return 该插进上下文的那条 assistant 消息；缓存空 / 解析失败 / 没笔记 → null（不插）
     */
    fun buildMessage(json: String?, tick: Long): UIMessage? {
        val notes = parse(json) ?: return null
        if (notes.isEmpty()) return null

        val size = notes.size
        val idx = (((tick % size) + size) % size).toInt()
        val note = notes[idx]

        val body = note.content.trim()
        if (body.isBlank()) return null

        val text = buildString {
            append("【浮现·")
            append(daysAgoLabel(daysSince(note.date)))
            append("我写过这样一段话】:")
            if (note.title.isNotBlank()) {
                append("\n「").append(note.title).append("」")
            }
            append("\n\n").append(body)
        }
        return UIMessage.assistant(prompt = text)
    }

    private fun parse(json: String?): List<Note>? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val c = o.optString("c", "")
                if (c.isBlank()) return@mapNotNull null
                Note(
                    title = o.optString("t", ""),
                    content = c,
                    date = o.optString("d", ""),
                )
            }
        }.getOrNull()
    }

    /** 从 created_at（ISO，如 2026-09-01T12:00:00+00:00）算到今天的天数 */
    private fun daysSince(createdAt: String): Long {
        if (createdAt.length < 10) return 0L
        return runCatching {
            val d = LocalDate.parse(createdAt.substring(0, 10))
            ChronoUnit.DAYS.between(d, LocalDate.now())
        }.getOrDefault(0L)
    }

    /** 天数 → 中文口语量词（给的是结论，不是让模型自己减） */
    private fun daysAgoLabel(days: Long): String = when {
        days <= 0L -> "今天"
        days == 1L -> "昨天"
        days <= 6L -> "${days}天前"
        days <= 14L -> "十几天前"
        days <= 25L -> "二十多天前"
        days <= 40L -> "一个多月前"
        days <= 70L -> "两三个月前"
        else -> "几个月前"
    }
}
