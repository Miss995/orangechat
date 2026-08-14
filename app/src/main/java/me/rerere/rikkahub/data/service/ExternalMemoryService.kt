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
    }

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /**
     * 保存聊天消息到外置记忆库
     */
    suspend fun saveMessage(
        assistantId: String,
        conversationId: String,
        role: String,
        content: String,
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
     * 关键词搜索消息
     */
    suspend fun searchMessages(
        assistantId: String,
        keyword: String,
        limit: Int = 10,
    ): Result<List<ExternalMemoryMessage>> = withContext(Dispatchers.IO) {
        runCatching {
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
                Log.e(TAG, "searchMessages HTTP $responseCode body=$errorBody")
                throw Exception("Supabase API error ($responseCode): $errorBody")
            }

            val responseText = connection.inputStream.bufferedReader().readText()
            parseMessages(responseText)
        }
    }

    /**
     * 保存日记摘要（可选带 embedding 向量）
     */
    suspend fun saveDiarySummary(
        assistantId: String,
        content: String,
        embedding: List<Float>? = null,
        targetDate: String? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            val createdAt = if (!targetDate.isNullOrBlank()) {
                "$targetDate 00:00:00"
            } else {
                sdf.format(java.util.Date())
            }

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
            put("embedding", JsonPrimitive(embedding.joinToString(",", "[", "]")))
        }
    }.toString()

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
     * 事件级向量召回：问题向量 -> 全量事件本地余弦 -> 取最相关 count 条
     */
    suspend fun vectorRecallEvents(
        queryEmbedding: List<Float>,
        assistantId: String,
        count: Int = 5,
    ): Result<List<ExternalMemoryEvent>> = withContext(Dispatchers.IO) {
        runCatching {
            val allEvents = queryAllEvents(assistantId).getOrDefault(emptyList())
                .filter { it.embedding.isNotEmpty() }

            val scored = allEvents.mapNotNull { event ->
                val similarity = cosineSimilarity(queryEmbedding, event.embedding)
                event to similarity
            }

            scored.sortedByDescending { it.second }
                .take(count)
                .map { it.first }
        }
    }

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
                result.add(
                    ExternalMemoryEvent(
                        id = obj.optInt("id", 0),
                        title = obj.optString("title", ""),
                        content = obj.optString("content", ""),
                        eventType = obj.optString("event_type", ""),
                        sourceDate = obj.optString("source_date", ""),
                        sourceIds = sourceIds,
                        sourceRange = obj.optString("source_range", ""),
                        embedding = embedding,
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse events", e)
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

data class ExternalMemoryEvent(
    val id: Int = 0,
    val title: String = "",
    val content: String = "",
    val eventType: String = "",
    val sourceDate: String = "",
    val sourceIds: List<String> = emptyList(),
    val sourceRange: String = "",
    val embedding: List<Float> = emptyList(),
)