/* 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.ProviderSetting
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 记忆召回门控（2026-08-19 升级版）
 *
 * 旧版 hasSearchIntent 纯词表匹配的病（宝 2026-08-19 定位）：
 * - 词表不全 → 该触发不触发（聊到"海"想不起海相关的记忆，词表里没有"海"）
 * - 时间词（之前/最近/刚刚）单独出现就触发 → 不该触发乱触发（聊配置也触发 embedding）
 *
 * 新版三级判断：
 * 1. 强回忆词（记得/聊过/啥来着…）→ 直接 YES（不调 LLM，省延迟）
 * 2. 其余 → 硅基免费模型（Qwen/Qwen2-7B-Instruct，零成本）LLM 判断"需不需要召回记忆"
 * 3. LLM 不可用/失败 → 回退旧词表（保底不哑火）
 *
 * 判断模型选硅基免费模型：不花钱、任务极简（YES/NO）、宝已有硅基 key 直接用。
 * （调研参考：Mem0 每轮召回 / Letta agent 自主 / Adaptive RAG LLM 判断；
 *  咱家前缀敏感 + 省钱，只在需要时召回 = 门控必须保留，只是从词表升级成 LLM 判断。）
 */
object MemoryIntentJudge {
    private const val TAG = "MemoryIntentJudge"

    // 硅基免费对话模型（零成本；判断 YES/NO 足够）
    private const val JUDGE_MODEL = "Qwen/Qwen2-7B-Instruct"

    // 强回忆词：命中直接触发（这些词误触发率极低，不用麻烦 LLM）
    private val STRONG_WORDS = listOf(
        "记得", "记不记得", "还记得", "忘了", "忘记", "没印象", "有印象", "想起来了",
        "聊过", "说过", "讲过", "提到", "提过",
        "搜", "搜索", "查查", "查一下", "找找",
        "回忆", "回想", "回顾",
        "聊了什么", "说了什么", "讲了什么", "发生了什么", "发生什么", "怎么回事",
        "怎么说的", "怎么聊的", "说过啥", "聊过啥", "啥来着", "啥事",
        "叫什么", "来着",
    )

    // 回退词表：LLM 不可用时用旧 hasSearchIntent 逻辑（8-18 大修版，含时间词+组合判断）
    private val FALLBACK_KEYWORDS = listOf(
        "记得", "记不记得", "还记得", "忘了", "忘记", "没印象", "有印象", "想起来了",
        "上次", "之前", "以前", "说过", "提到", "提过", "聊过", "讲过",
        "什么时候", "哪一天", "哪天", "搜", "搜索", "找找", "查一下", "查查",
        "回忆", "回想", "回顾", "叫什么", "来着",
        "聊了什么", "说了什么", "讲了什么", "发生了什么", "发生什么", "怎么回事",
        "怎么说的", "怎么聊的", "说过啥", "聊过啥", "啥来着", "啥事",
        "昨天", "前天", "昨晚", "今早", "今天早上", "上周", "上上周", "上个月", "今年", "去年",
        "前几天", "前段时间", "最近", "这几天", "那天", "当时", "那时候", "几点", "几号", "几月",
        "周一", "周二", "周三", "周四", "周五", "周六", "周日", "周天", "星期天",
        "星期一", "星期二", "星期三", "星期四", "星期五", "星期六",
    )

    /**
     * 判断用户消息是否需要召回记忆。
     * @param judgeProvider 硅基 provider（取外置库 embedding provider；null 时跳 LLM 直接回退词表）
     */
    suspend fun needsRecall(text: String, judgeProvider: ProviderSetting?): Boolean {
        if (text.isBlank()) return false
        // 1. 强回忆词快速过（省 LLM 调用）
        if (STRONG_WORDS.any { text.contains(it) }) {
            Log.d(TAG, "gate: strong word hit")
            return true
        }
        // 2. LLM 判断（硅基免费模型）
        val llm = judgeByLLM(text, judgeProvider)
        if (llm != null) {
            Log.d(TAG, "gate: llm judge = $llm")
            return llm
        }
        // 3. 回退旧词表（LLM 不可用/失败时保底）
        val fallback = fallbackHasSearchIntent(text)
        Log.w(TAG, "gate: llm unavailable, fallback = $fallback")
        return fallback
    }

    /** 调硅基免费模型判断；返回 null = 不可用/失败（走回退） */
    private suspend fun judgeByLLM(text: String, provider: ProviderSetting?): Boolean? {
        val openai = provider as? ProviderSetting.OpenAI ?: return null
        if (openai.apiKey.isBlank()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val url = URL(openai.baseUrl.trimEnd('/') + openai.chatCompletionsPath)
                val body = JSONObject()
                    .put("model", JUDGE_MODEL)
                    .put("messages", JSONArray()
                        .put(JSONObject()
                            .put("role", "system")
                            .put("content", "你是记忆检索判断器。判断用户这句话是否需要检索历史聊天记忆。\n" +
                                "需要检索(YES)：回忆/询问过去的事、提到以前聊过的内容、暗示'你还记得吗'、" +
                                "聊到可能关联旧记忆的具体话题（人名/事件/物品/地点）。\n" +
                                "不需要检索(NO)：日常闲聊、当前话题继续、单纯讨论当下、不需要旧记忆的提问。\n" +
                                "只回答 YES 或 NO。"))
                        .put(JSONObject()
                            .put("role", "user")
                            .put("content", text)))
                    .put("max_tokens", 5)
                    .put("temperature", 0)
                    .toString()

                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer ${openai.apiKey}")
                    setRequestProperty("Accept", "application/json")
                    doOutput = true
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                conn.outputStream.bufferedWriter().use { it.write(body) }
                val responseCode = conn.responseCode
                if (responseCode !in 200..299) {
                    val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
                    Log.w(TAG, "judge LLM HTTP $responseCode: ${err.take(200)}")
                    null
                } else {
                    val respText = conn.inputStream.bufferedReader().readText()
                    val content = parseContent(respText)
                    content?.trim()?.uppercase()?.let { it.contains("YES") }
                }
            }.getOrNull()
        }
    }

    /** 解析 chat/completions 响应，取 choices[0].message.content */
    private fun parseContent(resp: String): String? = runCatching {
        val root = JSONObject(resp)
        val choices = root.optJSONArray("choices") ?: return null
        val first = choices.optJSONObject(0) ?: return null
        val message = first.optJSONObject("message") ?: return null
        message.optString("content", "").ifBlank { null }
    }.getOrNull()

    /** 旧词表逻辑（8-18 大修版，LLM 不可用时保底） */
    private fun fallbackHasSearchIntent(text: String): Boolean {
        if (FALLBACK_KEYWORDS.any { text.contains(it) }) return true
        val timeWords = listOf(
            "昨天", "前天", "昨晚", "上周", "上个月", "之前", "以前", "上次",
            "那天", "当时", "最近", "前几天", "刚刚", "刚才"
        )
        val questionWords = listOf(
            "什么", "怎么", "哪里", "哪儿", "哪个", "哪些", "谁", "吗", "呢", "啥", "回事", "为什么"
        )
        return timeWords.any { text.contains(it) } && questionWords.any { text.contains(it) }
    }
}
