/* 橘瓣 OrangeChat
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
import me.rerere.rikkahub.data.model.ExternalMemory
import org.json.JSONArray
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

        // 中文虚词/无意义高频词（拆词召回时剔除，避免 "的""我们" 之类搜出一堆不相干）
        private val STOP_WORDS = setOf(
            "我们", "你们", "他们", "她们", "它们", "这个", "那个", "什么", "怎么", "为什么",
            "一个", "一下", "还有", "然后", "因为", "所以", "但是", "如果", "就是", "真的",
            "这么", "那么", "今天", "明天", "昨天", "现在", "时候", "知道", "觉得", "有点",
            "哈哈", "嗯嗯", "还是", "可以", "没有", "不是", "而且", "其实", "应该", "可能",
            "大概", "之前", "之后", "最近", "上次", "记得", "感觉", "好像", "看到", "听到",
            "说到", "想到", "想问", "宝贝", "小宝", "橘仔", "宝和", "想问"
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
            val safeLimit = limit.coerceIn(1, 50)
            var results: List<ExternalMemoryMessage> = emptyList()
            if (trimmed.isNotBlank()) {
                // 1. 整句 ILIKE 优先
                results = searchMessagesOnce(assistantId, trimmed, safeLimit)
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
            }
            results
        }
    }

    /** 单次 ILIKE 搜索（searchMessages 内部用） */
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
        parseMessages(responseText)
    }

    /** 拆词：标点分段 + 2-3 字 ngram，去虚词/纯标点，最多取 8 个 */
    private fun buildSearchKeywords(query: String): List<String> {
        val parts = query.split(Regex("[\\s，。！？、；：,.!?;:（）()\"'']+"))
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
     * 按日期查询消息（用于日记总结）
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
                Log.e(TAG, "queryLatestSummaries HTTP $responseCode body=$errorBody")
                throw Exception("Supabase API error ($responseCode): $errorBody")
            }

            val responseText = connection.inputStream.bufferedReader().readText()
            parseSummaries(responseText)
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
