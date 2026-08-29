/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.rikkahub.data.ai.AppLogBuffer
import me.rerere.rikkahub.data.model.ExternalMemory
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 外置记忆库服务
 * 基于 ExternalMemory 配置操作 Supabase 数据库
 */
class ExternalMemoryService(
    private val config: ExternalMemory
) {
    companion object {
        private const val TAG = "ExternalMemoryService"

        // 中文虚词/无意义高频词（拆词召回时剔除，避免 "速速""我们" 之类口语虚词搜出一堆不相干）
        // internal：QueryKeywordExtractor 过滤 AI 拆词结果时也复用这份表
        internal val STOP_WORDS = setOf(
            // 人称/指代
            "我们", "你们", "他们", "她们", "它们", "咱们", "人家", "自己", "大家",
            "这个", "那个", "这些", "那些", "这里", "那里", "这边", "那边", "这样", "那样",
            // 疑问/连接
            "什么", "怎么", "为什么", "如何", "为何", "哪里", "哪儿", "哪个", "哪些", "多少", "何时", "几时",
            "因为", "所以", "但是", "可是", "然而", "如果", "虽然", "尽管", "即使", "就算", "不管", "无论", "只要", "除非", "以及", "或者", "还是", "要么", "而且", "其实", "不过", "反正", "然后", "还有", "之类", "等等", "就是", "真的",
            // 时间/状态
            "今天", "明天", "昨天", "现在", "刚才", "最近", "上次", "之前", "之后", "时候", "这会儿", "一下子",
            // 口语高频（宝实测："速速给自己点一个大鸡腿吃"拆出"速速"当关键词，纯噪声）
            "速速", "快快", "赶紧", "马上", "立刻", "快点", "顺便", "直接", "突然", "忽然", "居然", "竟然", "到底", "究竟", "难道", "莫非", "非常", "特别", "十分", "相当", "比较", "稍微", "略微", "简直", "实在", "的确", "确实", "明显", "显然", "当然", "自然", "毕竟", "终究",
            // 认知/感受（作为搜索词太泛）
            "知道", "觉得", "感觉", "好像", "有点", "记得", "想问", "想到", "看到", "听到", "说到",
            // 语气/拟声
            "哈哈", "嗯嗯", "嘿嘿", "嘻嘻", "呜呜", "啊啊", "哦哦", "好吧", "好的", "好了", "可以", "没有", "不是", "应该", "可能", "大概", "一个", "一下", "这么", "那么",
            // 专属称呼（作为搜索词无意义）
            "宝贝", "小宝", "橘仔", "宝和", "年糕", "猫猫", "喵喵"
        )
    }

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /**
     * 保存聊天消息到外置记忆库
     * @param embedding 可选的消息向量（bge-m3 1024 维），提供时写入 embedding 列用于语义召回
     */
    suspend fun saveMessage(
        assistantId: String,
        conversationId: String,
        role: String,
        content: String,
        embedding: List<Float>? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = config.supabaseUrl.trimEnd('/')
            val endpoint = URL("$url/rest/v1/${config.tableName}")

            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            val jsonString = buildJsonObject {
                put("assistant_id", JsonPrimitive(assistantId))
                put("conversation_id", JsonPrimitive(conversationId))
                put("role", JsonPrimitive(role))
                put("content", JsonPrimitive(content))
                put("created_at", JsonPrimitive(sdf.format(java.util.Date())))
                if (embedding != null) {
                    put("embedding", JsonPrimitive(embedding.joinToString(",", "[", "]")))
                }
            }.toString()

            val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("apikey", config.supabaseKey)
                setRequestProperty("Authorization", "Bearer ${config.supabaseKey}")
                setRequestProperty("Prefer", "return=minimal")
                doOutput = true
                connectTimeout = 15000
                readTimeout = 15000
            }

            connection.outputStream.bufferedWriter().use { writer ->
                writer.write(jsonString)
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                Log.e(TAG, "saveMessage HTTP $responseCode body=$errorBody")
                throw Exception("Supabase API error ($responseCode): $errorBody")
            }

            Log.d(TAG, "Saved message to ${config.tableName} for assistant $assistantId")
        }.map { }
    }

    /**
     * 查询最新 N 条消息
     */
    suspend fun queryLatestMessages(
        assistantId: String,
        limit: Int = 10,
    ): Result<List<ExternalMemoryMessage>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = config.supabaseUrl.trimEnd('/')
            val query = "assistant_id=eq.${URLEncoder.encode(assistantId, "UTF-8")}&order=created_at.desc&limit=$limit"
            val endpoint = URL("$url/rest/v1/${config.tableName}?$query")

            val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("apikey", config.supabaseKey)
                setRequestProperty("Authorization", "Bearer ${config.supabaseKey}")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 15000
                readTimeout = 15000
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                Log.e(TAG, "queryLatestMessages HTTP $responseCode body=$errorBody")
                throw Exception("Supabase API error ($responseCode): $errorBody")
            }

            val responseText = connection.inputStream.bufferedReader().readText()
            parseMessages(responseText)
        }
    }

    /**
     * 统计某助手的外置库消息总数（用于监工台状态灯显示）
     * 通过 Prefer: count=exact + Range 0-0 从 Content-Range 头解析总数。
     */
    suspend fun countMessages(
        assistantId: String,
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val url = config.supabaseUrl.trimEnd('/')
            val query = "assistant_id=eq.${URLEncoder.encode(assistantId, "UTF-8")}&select=id"
            val endpoint = URL("$url/rest/v1/${config.tableName}?$query")

            val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("apikey", config.supabaseKey)
                setRequestProperty("Authorization", "Bearer ${config.supabaseKey}")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Prefer", "count=exact")
                setRequestProperty("Range", "0-0")
                connectTimeout = 15000
                readTimeout = 15000
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                Log.e(TAG, "countMessages HTTP $responseCode body=$errorBody")
                throw Exception("Supabase API error ($responseCode): $errorBody")
            }

            // Content-Range 形如 0-0/12345，斜杠后是总数
            val contentRange = connection.getHeaderField("Content-Range") ?: ""
            val total = contentRange.substringAfter("/").trim().toIntOrNull() ?: 0
            Log.d(TAG, "countMessages for assistant $assistantId = $total")
            total
        }
    }

    /**
     * 删除一条消息（监工台「剔除」用；默认走归档语义——先物理删除，后续如需软删再改）
     */
    suspend fun deleteMessage(
        id: Int,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = config.supabaseUrl.trimEnd('/')
            val query = "id=eq.$id"
            val endpoint = URL("$url/rest/v1/${config.tableName}?$query")

            val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"
                setRequestProperty("apikey", config.supabaseKey)
                setRequestProperty("Authorization", "Bearer ${config.supabaseKey}")
                setRequestProperty("Prefer", "return=minimal")
                connectTimeout = 15000
                readTimeout = 15000
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                Log.e(TAG, "deleteMessage HTTP $responseCode body=$errorBody")
                throw Exception("Supabase API error ($responseCode): $errorBody")
            }
            Log.d(TAG, "Deleted message id=$id from ${config.tableName}")
        }.map { }
    }

    /**
     * 关键词搜索消息（查询加工版）
     *
     * 不再直接 ILIKE 整句（长句几乎必然搜空），而是自适应：
     * 1. 先整句 ILIKE 搜（精确命中直接用）
     * 2. 结果不足时自动拆词（标点分段 + 2-3 字 ngram，去虚词）多关键词合并去重
     * 3. 搜够条数就停（避免慢）
     *
     * 监工台召回体检与生成时兜底都走这里，一起变聪明。
     */
    suspend fun searchMessages(
        assistantId: String,
        keyword: String,
        limit: Int = 10,
    ): Result<List<ExternalMemoryMessage>> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = keyword.trim()
            if (trimmed.isBlank()) return@runCatching emptyList()

            val safeLimit = limit.coerceIn(1, 50)

            // 1. 整句 ILIKE 优先
            var results = searchMessagesOnce(assistantId, trimmed, safeLimit)

            // 2. 不足则拆词合并（去重，搜够即停）
            if (results.size < safeLimit) {
                val keywords = buildSearchKeywords(trimmed)
                val seen = results.map { it.id }.toMutableSet()
                val merged = results.toMutableList()
                for (kw in keywords) {
                    if (merged.size >= safeLimit) break
                    searchMessagesOnce(assistantId, kw, safeLimit)
                        .filter { seen.add(it.id) }
                        .forEach { merged.add(it) }
                }
                results = merged.take(safeLimit)
            }
            results
        }
    }

    /**
     * 按给定关键词列表逐个 ILIKE 搜索并合并去重（AI 拆词后使用；单个失败不影响整体）
     */
    suspend fun searchByKeywords(
        assistantId: String,
        keywords: List<String>,
        limit: Int = 10,
    ): Result<List<ExternalMemoryMessage>> = withContext(Dispatchers.IO) {
        runCatching {
            val safeLimit = limit.coerceIn(1, 50)
            val seen = mutableSetOf<Int>()
            val merged = mutableListOf<ExternalMemoryMessage>()
            for (kw in keywords) {
                if (merged.size >= safeLimit) break
                runCatching { searchMessagesOnce(assistantId, kw, safeLimit) }
                    .getOrDefault(emptyList())
                    .filter { seen.add(it.id) }
                    .forEach { merged.add(it) }
            }
            merged.take(safeLimit)
        }
    }

    /** 单次 ILIKE 搜索（searchMessages / searchByKeywords 内部用） */
    private fun searchMessagesOnce(
        assistantId: String,
        keyword: String,
        limit: Int,
    ): List<ExternalMemoryMessage> {
        val url = config.supabaseUrl.trimEnd('/')
        val encodedKeyword = URLEncoder.encode("%$keyword%", "UTF-8")
        val query = "assistant_id=eq.${URLEncoder.encode(assistantId, "UTF-8")}&content=ilike.$encodedKeyword&order=created_at.desc&limit=$limit"
        val endpoint = URL("$url/rest/v1/${config.tableName}?$query")

        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("apikey", config.supabaseKey)
            setRequestProperty("Authorization", "Bearer ${config.supabaseKey}")
            setRequestProperty("Accept", "application/json")
            connectTimeout = 15000
            readTimeout = 15000
        }

        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            Log.e(TAG, "searchMessagesOnce HTTP $responseCode body=$errorBody")
            throw Exception("Supabase API error ($responseCode): $errorBody")
        }

        val responseText = connection.inputStream.bufferedReader().readText()
        return parseMessages(responseText)
    }

    /** 拆词：标点分段 + 2-3 字 ngram，去虚词/纯标点，最多取 8 个 */
    private fun buildSearchKeywords(query: String): List<String> {
        val parts = query.split(Regex("[\\s，。！？、；：,.!?;:（）()\"']+"))
            .filter { it.isNotBlank() }
        val keywords = mutableListOf<String>()
        parts.forEach { part ->
            if (part.length <= 4) {
                keywords.add(part)
            } else {
                var i = 0
                while (i < part.length - 1) {
                    keywords.add(part.substring(i, minOf(i + 2, part.length)))
                    if (i + 3 <= part.length) keywords.add(part.substring(i, i + 3))
                    i += 2
                }
            }
        }
        return keywords
            .distinct()
            .filter { it.length >= 2 && it !in STOP_WORDS && it.any { c -> c.isLetterOrDigit() } }
            .take(8)
    }

    /**
     * 向量语义召回聊天记录（调 Supabase RPC match_chat_messages，hnsw 索引加速）
     * 返回按相似度降序的最近 count 条消息。
     */
    suspend fun vectorRecallMessages(
        queryEmbedding: List<Float>,
        assistantId: String,
        count: Int = 5,
    ): Result<List<ExternalMemoryMessage>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = config.supabaseUrl.trimEnd('/')
            val endpoint = URL("$url/rest/v1/rpc/match_chat_messages")

            val body = buildJsonObject {
                put("query_embedding", JsonPrimitive(queryEmbedding.joinToString(",", "[", "]")))
                put("match_count", JsonPrimitive(count))
                put("assistant_filter", JsonPrimitive(assistantId))
            }.toString()

            val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("apikey", config.supabaseKey)
                setRequestProperty("Authorization", "Bearer ${config.supabaseKey}")
                setRequestProperty("Accept", "application/json")
                doOutput = true
                connectTimeout = 15000
                readTimeout = 15000
            }

            connection.outputStream.bufferedWriter().use { writer ->
                writer.write(body)
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                Log.e(TAG, "vectorRecallMessages HTTP $responseCode body=$errorBody")
                throw Exception("Supabase API error ($responseCode): $errorBody")
            }

            val responseText = connection.inputStream.bufferedReader().readText()
            parseMessages(responseText)
        }
    }

    /**
     * 保存日记摘要（可选带 embedding 向量）
     *
     * 若表缺少 embedding 列导致失败，自动降级为不带 embedding 重试，
     * 保证日记内容一定能写入表（向量检索能力需用户在 Supabase 补列后才有）。
     */
    suspend fun saveDiarySummary(
        assistantId: String,
        content: String,
        embedding: List<Float>? = null,
        /**
         * 这篇日记对应的日期（"yyyy-MM-dd"）。
         * 会作为 created_at 写入（设为该日 00:00:00），这样去重查询 querySummariesByDate
         * 才能按"日记对应日"命中，而不是按写入时刻（可能落在次日）导致每次都重复生成。
         * 为空时退回旧逻辑（用当前写入时刻）。
         */
        targetDate: String? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            val createdAt = if (!targetDate.isNullOrBlank()) {
                "$targetDate 00:00:00"
            } else {
                sdf.format(java.util.Date())
            }

            // 首次尝试：若提供了 embedding，则带上 embedding 字段
            val firstJson = buildSummaryJson(assistantId, content, createdAt, embedding)
            try {
                postSummaries(firstJson)
                Log.i(
                    TAG,
                    "Saved diary summary to ${config.summariesTableName} for assistant $assistantId " +
                        "(with embedding=${embedding != null})"
                )
            } catch (e: Exception) {
                val msg = e.message.orEmpty()
                // 表缺少 embedding 列（PostgREST PGRST204 / 列名错误）时，降级为不带 embedding 重试
                val missingEmbeddingColumn = embedding != null &&
                    (
                        msg.contains("PGRST204", ignoreCase = true) ||
                            msg.contains("Could not find the", ignoreCase = true) ||
                            msg.contains("'embedding'", ignoreCase = true) ||
                            msg.contains("embedding", ignoreCase = true)
                        )
                if (missingEmbeddingColumn) {
                    Log.w(
                        TAG,
                        "Column 'embedding' not found in ${config.summariesTableName}, retrying without embedding",
                        e
                    )
                    val fallbackJson = buildSummaryJson(assistantId, content, createdAt, null)
                    postSummaries(fallbackJson)
                    Log.i(
                        TAG,
                        "Saved diary summary to ${config.summariesTableName} for assistant $assistantId " +
                            "(without embedding, fallback)"
                    )
                } else {
                    throw e
                }
            }
        }.map { }
    }

    /**
     * 构建日记摘要的 JSON body
     */
    private fun buildSummaryJson(
        assistantId: String,
        content: String,
        createdAt: String,
        embedding: List<Float>?
    ): String = buildJsonObject {
        put("assistant_id", JsonPrimitive(assistantId))
        put("content", JsonPrimitive(content))
        put("created_at", JsonPrimitive(createdAt))
        if (embedding != null) {
            // pgvector 接受 "[1.0,2.0]" 字符串形式
            put("embedding", JsonPrimitive(embedding.joinToString(",", "[", "]")))
        }
    }.toString()

    /**
     * 向 memory_summaries 表发送 POST 请求，失败抛出带详细错误体的异常
     */
    private fun postSummaries(jsonString: String) {
        val url = config.supabaseUrl.trimEnd('/')
        val endpoint = URL("$url/rest/v1/${config.summariesTableName}")

        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("apikey", config.supabaseKey)
            setRequestProperty("Authorization", "Bearer ${config.supabaseKey}")
            setRequestProperty("Prefer", "return=minimal")
            doOutput = true
            connectTimeout = 15000
            readTimeout = 15000
        }

        connection.outputStream.bufferedWriter().use { writer ->
            writer.write(jsonString)
            writer.flush()
        }

        val responseCode = connection.responseCode
        Log.d(TAG, "saveDiarySummary POST ${config.summariesTableName} responseCode=$responseCode")
        if (responseCode !in 200..299) {
            val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            Log.e(TAG, "saveDiarySummary HTTP $responseCode body=$errorBody")
            throw Exception("Supabase API error ($responseCode): $errorBody")
        }
    }

    /**
     * 按日期查询消息（用于日记总结 / 事件原文展开）
     * chat_messages.created_at 是 timestamptz，实际存的是北京时间的数值（无时区字符串被按 UTC 解析），
     * 所以按当天 00:00:00~23:59:59 过滤即命中"北京当天全天"，与归档脚本 fetch_msgs 同口径。
     */
    suspend fun queryMessagesByDate(
        dateStr: String,
    ): Result<List<ExternalMemoryMessage>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = config.supabaseUrl.trimEnd('/')
            val startOfDay = "${dateStr} 00:00:00"
            val endOfDay = "${dateStr} 23:59:59"
            val query = "created_at=gte.${URLEncoder.encode(startOfDay, "UTF-8")}&created_at=lte.${URLEncoder.encode(endOfDay, "UTF-8")}&order=created_at.asc"
            val endpoint = URL("$url/rest/v1/${config.tableName}?$query")

            val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("apikey", config.supabaseKey)
                setRequestProperty("Authorization", "Bearer ${config.supabaseKey}")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 15000
                readTimeout = 15000
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                Log.e(TAG, "queryMessagesByDate HTTP $responseCode body=$errorBody")
                throw Exception("Supabase API error ($responseCode): $errorBody")
            }

            val responseText = connection.inputStream.bufferedReader().readText()
            parseMessages(responseText)
        }
    }

    /**
     * 查询指定日期是否有日记摘要（用于去重）
     */
    suspend fun querySummariesByDate(
        assistantId: String,
        dateStr: String,
    ): Result<List<ExternalMemorySummary>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = config.supabaseUrl.trimEnd('/')
            val startOfDay = "${dateStr} 00:00:00"
            val endOfDay = "${dateStr} 23:59:59"
            val query = "assistant_id=eq.${URLEncoder.encode(assistantId, "UTF-8")}&created_at=gte.${URLEncoder.encode(startOfDay, "UTF-8")}&created_at=lte.${URLEncoder.encode(endOfDay, "UTF-8")}&order=created_at.desc"
            val endpoint = URL("$url/rest/v1/${config.summariesTableName}?$query")

            val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("apikey", config.supabaseKey)
                setRequestProperty("Authorization", "Bearer ${config.supabaseKey}")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 15000
                readTimeout = 15000
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                Log.e(TAG, "querySummariesByDate HTTP $responseCode body=$errorBody")
                throw Exception("Supabase API error ($responseCode): $errorBody")
            }

            val responseText = connection.inputStream.bufferedReader().readText()
            parseSummaries(responseText)
        }
    }

    /**
     * 查询最新日记摘要
     */
    suspend fun queryLatestSummaries(
        assistantId: String,
        limit: Int = 5,
    ): Result<List<ExternalMemorySummary>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = config.supabaseUrl.trimEnd('/')
            val query = "assistant_id=eq.${URLEncoder.encode(assistantId, "UTF-8")}&order=created_at.desc&limit=$limit"
            val endpoint = URL("$url/rest/v1/${config.summariesTableName}?$query")
            AppLogBuffer.log(TAG, "queryLatestSummaries: GET ${config.summariesTableName}?$query")

            val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("apikey", config.supabaseKey)
                setRequestProperty("Authorization", "Bearer ${config.supabaseKey}")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 15000
                readTimeout = 15000
            }

            val responseCode = connection.responseCode
            AppLogBuffer.log(TAG, "queryLatestSummaries: HTTP $responseCode")
            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                Log.e(TAG, "queryLatestSummaries HTTP $responseCode body=$errorBody")
                AppLogBuffer.log(TAG, "queryLatestSummaries HTTP $responseCode body=$errorBody")
                throw Exception("Supabase API error ($responseCode): $errorBody")
            }

            val responseText = connection.inputStream.bufferedReader().readText()
            val parsed = parseSummaries(responseText)
            AppLogBuffer.log(TAG, "queryLatestSummaries: parsed ${parsed.size} summaries")
            parsed
        }.onFailure { e ->
            AppLogBuffer.log(TAG, "queryLatestSummaries FAILED: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * 查询某助手的所有日记摘要（用于向量召回）
     */
    suspend fun queryAllSummaries(
        assistantId: String,
    ): Result<List<ExternalMemorySummary>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = config.supabaseUrl.trimEnd('/')
            val query = "assistant_id=eq.${URLEncoder.encode(assistantId, "UTF-8")}&order=created_at.desc"
            val endpoint = URL("$url/rest/v1/${config.summariesTableName}?$query")

            val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("apikey", config.supabaseKey)
                setRequestProperty("Authorization", "Bearer ${config.supabaseKey}")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 15000
                readTimeout = 15000
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                Log.e(TAG, "queryAllSummaries HTTP $responseCode body=$errorBody")
                throw Exception("Supabase API error ($responseCode): $errorBody")
            }

            val responseText = connection.inputStream.bufferedReader().readText()
            parseSummaries(responseText)
        }
    }

    /**
     * 向量召回日记摘要（本地计算余弦相似度）
     */
    suspend fun vectorRecallSummaries(
        queryEmbedding: List<Float>,
        assistantId: String,
        count: Int = 5,
    ): Result<List<ExternalMemorySummary>> = withContext(Dispatchers.IO) {
        runCatching {
            val allSummaries = queryAllSummaries(assistantId).getOrDefault(emptyList())
                .filter { it.embedding.isNotEmpty() }

            val scored = allSummaries.mapNotNull { summary ->
                val similarity = cosineSimilarity(queryEmbedding, summary.embedding)
                summary to similarity
            }

            scored.sortedByDescending { it.second }
                .take(count)
                .map { it.first }
        }
    }

    /**
     * 查询某助手的所有事件（用于事件级向量召回）
     */
    suspend fun queryAllEvents(
        assistantId: String,
    ): Result<List<ExternalMemoryEvent>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = config.supabaseUrl.trimEnd('/')
            val query = "assistant_id=eq.${URLEncoder.encode(assistantId, "UTF-8")}&order=created_at.desc"
            val endpoint = URL("$url/rest/v1/memory_events?$query")

            val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("apikey", config.supabaseKey)
                setRequestProperty("Authorization", "Bearer ${config.supabaseKey}")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 15000
                readTimeout = 15000
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                Log.e(TAG, "queryAllEvents HTTP $responseCode body=$errorBody")
                throw Exception("Supabase API error ($responseCode): $errorBody")
            }

            val responseText = connection.inputStream.bufferedReader().readText()
            parseEvents(responseText)
        }
    }

    /**
     * 查询最近 N 天的事件（实时层注入用，2026-08-21 宝的记忆实时化方案定稿）：
     * source_date >= 今天-(days-1)，按 source_date ASC + id ASC 稳定排序（前缀稳定=保 DS 缓存命中），
     * 过滤 superseded_by 非空的失效事件（A.U.D.N. 已标记；顺手完成 App 侧过滤待办）。
     */
    suspend fun fetchRecentEvents(
        assistantId: String,
        days: Int = 3,
    ): Result<List<ExternalMemoryEvent>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = config.supabaseUrl.trimEnd('/')
            val dateFrom = java.time.LocalDate.now().minusDays((days - 1).toLong()).toString()
            val query = "assistant_id=eq.${URLEncoder.encode(assistantId, "UTF-8")}" +
                "&source_date=gte.$dateFrom" +
                "&order=source_date.asc,id.asc" +
                "&limit=500"
            val endpoint = URL("$url/rest/v1/memory_events?$query")
            AppLogBuffer.log(TAG, "fetchRecentEvents: GET memory_events?$query")

            val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("apikey", config.supabaseKey)
                setRequestProperty("Authorization", "Bearer ${config.supabaseKey}")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 15000
                readTimeout = 15000
            }

            val responseCode = connection.responseCode
            AppLogBuffer.log(TAG, "fetchRecentEvents: HTTP $responseCode")
            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                Log.e(TAG, "fetchRecentEvents HTTP $responseCode body=$errorBody")
                AppLogBuffer.log(TAG, "fetchRecentEvents HTTP $responseCode body=$errorBody")
                throw Exception("Supabase API error ($responseCode): $errorBody")
            }

            val responseText = connection.inputStream.bufferedReader().readText()
            val parsed = parseEvents(responseText)
            val result = parsed.filter { it.supersededBy.isBlank() } // 过滤已失效事件（A.U.D.N. 写入层标记）
            AppLogBuffer.log(TAG, "fetchRecentEvents: parsed ${parsed.size}, after filter ${result.size}, first=${result.firstOrNull()?.title ?: "-"}")
            result
        }.onFailure { e ->
            AppLogBuffer.log(TAG, "fetchRecentEvents FAILED: ${e.javaClass.simpleName}: ${e.message}\n${e.stackTraceToString().take(800)}")
        }
    }

    /**
     * 查询最近一条归档状态（数据监控⑤，2026-08-22）：
     * archive_daily 每天 upsert 一行 archive_status（date 唯一），这里拉最近一条给注入段用。
     */
    suspend fun queryLatestArchiveStatus(): Result<ArchiveStatus?> = withContext(Dispatchers.IO) {
        runCatching {
            val url = config.supabaseUrl.trimEnd('/')
            val query = "order=date.desc&limit=1"
            val endpoint = URL("$url/rest/v1/archive_status?$query")
            AppLogBuffer.log(TAG, "queryArchiveStatus: GET archive_status?$query")

            val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("apikey", config.supabaseKey)
                setRequestProperty("Authorization", "Bearer ${config.supabaseKey}")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 15000
                readTimeout = 15000
            }

            val responseCode = connection.responseCode
            AppLogBuffer.log(TAG, "queryArchiveStatus: HTTP $responseCode")
            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                AppLogBuffer.log(TAG, "queryArchiveStatus HTTP $responseCode body=$errorBody")
                throw Exception("Supabase API error ($responseCode): $errorBody")
            }

            val responseText = connection.inputStream.bufferedReader().readText()
            val parsed = JSONArray(responseText)
            if (parsed.length() == 0) {
                AppLogBuffer.log(TAG, "queryArchiveStatus: empty (表还没有记录)")
                null
            } else {
                val obj = parsed.getJSONObject(0)
                val status = ArchiveStatus(
                    date = obj.safeString("date"),
                    success = obj.optBoolean("success", false),
                    eventsCount = obj.optInt("events_count", 0),
                    msgsCount = obj.optInt("msgs_count", 0),
                    error = obj.safeString("error"),
                )
                AppLogBuffer.log(TAG, "queryArchiveStatus: date=${status.date} success=${status.success} events=${status.eventsCount} msgs=${status.msgsCount}")
                status
            }
        }.onFailure { e ->
            AppLogBuffer.log(TAG, "queryArchiveStatus FAILED: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * 事件级向量召回：问题向量 -> 全量事件本地余弦 -> 冲突消解（同主题取新 + 过滤 superseded） -> 取最相关 count 条
     * 支持时间定位：dateFrom/dateTo（yyyy-MM-dd）按事件 source_date 过滤（字典序比较=日期比较，与橘瓣同口径）。
     * 没来源日期的事件放行（保守不拦，避免误杀重要记忆）。
     *
     * 冲突消解（宝定的行动清单③，App 层）：
     * - 时间加权：相似度相近时 source_date 更新的事件优先（新事实优先于旧事实）
     * - 同标题去重：归一化标题相同的事件只保留最新一条（防重复总结）
     * - **过滤 superseded（第二层，2026-08-18 已闭环）**：写入层 A.U.D.N.（archive_daily_v3.py，服务器已启用）
     *   会把被新事实覆盖的旧事件标记 superseded_by（失效不删），App 召回时直接跳过这些已失效事件，
     *   只把「当前有效」的记忆喂给模型——新旧事实不会再打架。
     *
     * 多事件关联（related_events，宝要的联想式回忆，2026-08-18 接上读取链路）：
     * 命中事件若带 related_event_ids（写入端 A.U.D.N. linked_event_ids 已存），把关联事件也带出来
     * （克制最多 3 条，去重、过滤失效）——模型看到「这件事」时会连带到「相关的事」，培养联想式思考方向。
     */
    suspend fun vectorRecallEvents(
        queryEmbedding: List<Float>,
        assistantId: String,
        count: Int = 5,
        dateFrom: String? = null,
        dateTo: String? = null,
        queryText: String? = null, // 原始查询文本（2026-08-29 三变量评分：关键词分用）
    ): Result<List<ExternalMemoryEvent>> = withContext(Dispatchers.IO) {
        runCatching {
            val allEvents = queryAllEvents(assistantId).getOrDefault(emptyList())
                .filter { it.embedding.isNotEmpty() }
                .filter { it.supersededBy.isBlank() } // 过滤已失效事件（A.U.D.N. 写入层标记 superseded，失效不删只标记）
                .filter { event ->
                    if (dateFrom.isNullOrBlank() && dateTo.isNullOrBlank()) true
                    else {
                        val d = event.sourceDate
                        d.isBlank() ||
                            (dateFrom.isNullOrBlank() || d >= dateFrom) &&
                            (dateTo.isNullOrBlank() || d <= dateTo)
                    }
                }

            // 2026-08-29 三变量召回评分（方案A：0.5×向量 + 0.3×关键词 + 0.2×时间，宝定）
            // 08-29 修正：①无时间词查询时间分=0（"上次/之前"是回溯查询，近因衰减抬最近事件帮倒忙）
            // ②关键词分只匹配 title+keywords（"提到"不算命中，"主题就是"才算；content 由向量分覆盖语义）
            val queryKeywords = if (queryText.isNullOrBlank()) emptyList() else buildSearchKeywords(queryText)
            val hasTimeRange = !dateFrom.isNullOrBlank() || !dateTo.isNullOrBlank()
            val scored = allEvents.mapNotNull { event ->
                val vecScore = cosineSimilarity(queryEmbedding, event.embedding)
                // 关键词分：查询拆词命中事件 title / keywords（二筛索引）的比例（0~1），不匹配 content
                val kwScore = if (queryKeywords.isEmpty()) 0f else {
                    val matched = queryKeywords.count { kw ->
                        event.keywords.any { e -> e.contains(kw, ignoreCase = true) || kw.contains(e, ignoreCase = true) } ||
                            event.title.contains(kw, ignoreCase = true)
                    }
                    matched.toFloat() / queryKeywords.size
                }
                // 时间分：带时间范围 → 范围内=1（范围外已被上方 filter 排除）；不带时间 → 0（不偏爱最近）
                val timeScore = if (hasTimeRange) 1f else 0f
                event to (0.5f * vecScore + 0.3f * kwScore + 0.2f * timeScore)
            }

            // 主命中（2026-08-29 宝定：加置信度阈值——总分低于 0.3 不召回，宁缺毋滥治低分噪声；
            // 然后扩大候选池 -> 同标题取新 -> 取 count 条）
            val base = scored.sortedByDescending { it.second }
                .filter { it.second >= 0.3f }
                .take(count * 2) // 扩大候选池，给冲突消解留空间
                .map { it.first }
                .let { dedupeByTitle(it) } // 同标题取新（冲突消解基础版）
                .take(count)

            // 多事件关联（联想式回忆）：把命中事件的 related_event_ids 对应事件带出来（克制最多 3 条）
            val byId = allEvents.associateBy { it.id.toString() }
            val related = base.flatMap { e -> e.relatedEventIds.mapNotNull { id -> byId[id] } }
                .filter { r -> base.none { it.id == r.id } } // 去重：主命中已有则不带
                .let { dedupeByTitle(it) }
                .take(3)

            if (related.isNotEmpty()) {
                Log.d(TAG, "Event recall: ${base.size} hits + ${related.size} related events")
            }
            base + related
        }
    }

    /**
     * 同标题去重（冲突消解基础版）：归一化标题相同的事件只保留 source_date 最新一条。
     * 事件按 source_date 倒序处理，先到的（最新）先占位，重复的跳过——数据不删，仅召回时不返回旧重复。
     */
    private fun dedupeByTitle(events: List<ExternalMemoryEvent>): List<ExternalMemoryEvent> {
        val best = LinkedHashMap<String, ExternalMemoryEvent>()
        events.sortedByDescending { it.sourceDate }.forEach { e ->
            val key = normalizeTitle(e.title)
            if (key.isNotBlank()) {
                best.putIfAbsent(key, e)
            } else {
                best.putIfAbsent("__id_${e.id}", e)
            }
        }
        return best.values.toList()
    }

    /** 标题归一化：去空白/标点/引号，统一小写（用于同主题检测） */
    private fun normalizeTitle(t: String): String = t
        .replace(Regex("[\\s，。！？、；：,.!?;:（）()\\[\\]【】\"']+"), "")
        .lowercase()

    /**
     * 展开事件原文证据：按事件的 source_date 拉当天消息，用 source_ids（当天 1-based 序号）取对应消息。
     * 返回 "[角色] 内容" 列表（按消息顺序）。拉不到就返回空列表（不阻塞事件本身）。
     */
    suspend fun fetchEventSources(
        event: ExternalMemoryEvent,
    ): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (event.sourceDate.isBlank() || event.sourceIds.isEmpty()) {
                return@withContext emptyList()
            }
            val messages = queryMessagesByDate(event.sourceDate).getOrDefault(emptyList())
                .sortedBy { it.createdAt }
            if (messages.isEmpty()) {
                return@withContext emptyList()
            }
            val ids = event.sourceIds.mapNotNull { it.toIntOrNull() }
            val lines = mutableListOf<String>()
            for (id in ids) {
                val idx = id - 1 // 编号从 1 开始
                if (idx in messages.indices) {
                    val msg = messages[idx]
                    val prefix = when (msg.role) {
                        "assistant" -> "AI"
                        "user" -> "用户"
                        else -> msg.role
                    }
                    lines.add("[$prefix] ${msg.content}")
                }
            }
            lines
        }.getOrDefault(emptyList())
    }

    private fun cosineSimilarity(a: List<Float>, b: List<Float>): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        return if (normA == 0f || normB == 0f) 0f else dot / (kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB))
    }

    private fun parseMessages(jsonText: String): List<ExternalMemoryMessage> {
        val result = mutableListOf<ExternalMemoryMessage>()
        try {
            val array = JSONArray(jsonText)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                result.add(
                    ExternalMemoryMessage(
                        id = obj.optInt("id", 0),
                        assistantId = obj.optString("assistant_id", ""),
                        conversationId = obj.optString("conversation_id", ""),
                        role = obj.optString("role", ""),
                        content = obj.optString("content", ""),
                        createdAt = obj.optString("created_at", ""),
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse messages", e)
        }
        return result
    }

    private fun parseSummaries(jsonText: String): List<ExternalMemorySummary> {
        val result = mutableListOf<ExternalMemorySummary>()
        try {
            val array = JSONArray(jsonText)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val embeddingStr = obj.optString("embedding", "")
                val embedding = if (embeddingStr.isNotBlank() && embeddingStr.startsWith("[")) {
                    embeddingStr.trim('[', ']').split(",").mapNotNull { it.trim().toFloatOrNull() }
                } else emptyList()
                result.add(
                    ExternalMemorySummary(
                        id = obj.optInt("id", 0),
                        assistantId = obj.optString("assistant_id", ""),
                        content = obj.optString("content", ""),
                        createdAt = obj.optString("created_at", ""),
                        embedding = embedding,
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse summaries", e)
        }
        return result
    }

    private fun parseEvents(jsonText: String): List<ExternalMemoryEvent> {
        val result = mutableListOf<ExternalMemoryEvent>()
        try {
            val array = JSONArray(jsonText)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val embeddingStr = obj.optString("embedding", "")
                val embedding = if (embeddingStr.isNotBlank() && embeddingStr.startsWith("[")) {
                    embeddingStr.trim('[', ']').split(",").mapNotNull { it.trim().toFloatOrNull() }
                } else emptyList()
                val sourceIds = mutableListOf<String>()
                val sourceIdsJson = obj.optJSONArray("source_ids")
                if (sourceIdsJson != null) {
                    for (j in 0 until sourceIdsJson.length()) {
                        sourceIds.add(sourceIdsJson.optString(j))
                    }
                }
                val relatedIds = mutableListOf<String>()
                val relatedJson = obj.optJSONArray("related_event_ids")
                if (relatedJson != null) {
                    for (j in 0 until relatedJson.length()) {
                        relatedIds.add(relatedJson.optString(j))
                    }
                }
                val keywords = mutableListOf<String>()
                val kwJson = obj.optJSONArray("keywords")
                if (kwJson != null) {
                    for (j in 0 until kwJson.length()) {
                        keywords.add(kwJson.optString(j))
                    }
                }
                result.add(
                    ExternalMemoryEvent(
                        id = obj.optInt("id", 0),
                        title = obj.safeString("title"),
                        content = obj.safeString("content"),
                        eventType = obj.safeString("event_type"),
                        sourceDate = obj.safeString("source_date"),
                        sourceIds = sourceIds,
                        sourceRange = obj.safeString("source_range"),
                        embedding = embedding,
                        supersededBy = obj.safeString("superseded_by"), // A.U.D.N. 标记的失效事件（写入层已启用）
                        relatedEventIds = relatedIds, // A.U.D.N. linked_event_ids 写入的关联事件
                        keywords = keywords, // 二筛关键词索引
                        timeLabel = obj.safeString("time_label"), // 一筛时间标
                        category = obj.safeString("category"), // 二筛事件分类
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse events", e)
        }
        return result
    }

    /**
     * org.json 的 optString(key, fallback) 对 JSON null 值返回字符串 "null"（不是 fallback）——
     * 导致 superseded_by 为 null 的事件被解析成 "null" 字符串，"null".isBlank()=false
     * → 全部被当成已失效事件过滤掉（fetchRecentEvents parsed 112 after filter 0，2026-08-22 凌晨破案）。
     * 统一：null 值转空串，只有真正的非空字符串才保留。
     */
    private fun JSONObject.safeString(key: String): String {
        return if (isNull(key)) "" else optString(key, "")
    }
}

data class ExternalMemoryMessage(
    val id: Int = 0,
    val assistantId: String = "",
    val conversationId: String = "",
    val role: String = "",
    val content: String = "",
    val createdAt: String = "",
)

data class ExternalMemorySummary(
    val id: Int = 0,
    val assistantId: String = "",
    val content: String = "",
    val createdAt: String = "",
    val embedding: List<Float> = emptyList(),
)

data class ExternalMemoryEvent(
    val id: Int = 0,
    val title: String = "",
    val content: String = "",
    val eventType: String = "",
    val sourceDate: String = "",
    val sourceIds: List<String> = emptyList(),
    val sourceRange: String = "",
    val embedding: List<Float> = emptyList(),
    val supersededBy: String = "", // 被哪个新事件取代（A.U.D.N. 写入层标记；非空=已失效，召回时跳过）
    val relatedEventIds: List<String> = emptyList(), // 关联事件 id（A.U.D.N. linked_event_ids 写入；召回时带出=联想式回忆）
    val keywords: List<String> = emptyList(), // 二筛关键词索引（2026-08-29 记忆系统分层）
    val timeLabel: String = "", // 一筛时间标（上午/下午/晚上/深夜）
    val category: String = "", // 二筛事件分类（fact/decision/plan/procedure/daily）
)

/**
 * 归档状态（数据监控⑤，2026-08-22）：
 * archive_daily 每天 upsert 一行，橘瓣查最近一条注入上下文，归档断了橘仔每轮都看得到。
 */
data class ArchiveStatus(
    val date: String = "",
    val success: Boolean = false,
    val eventsCount: Int = 0,
    val msgsCount: Int = 0,
    val error: String = "",
)
