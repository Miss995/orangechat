/* 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL

/**
 * 查询关键词提取器（省钱版 AI 拆词）
 *
 * 只在向量召回不足、需要关键词兜底时被调用。
 * 调 SiliconFlow 的便宜 chat 模型把用户句子拆成 2-5 个关键词；
 * 失败/没配 key 时返回空列表，上层自动退回 ngram 拆词。
 *
 * 解决了 ngram 拆词的问题："速速给自己点一个大鸡腿吃"
 * ngram 会拆出 "速速/速给/己点" 之类碎片，AI 直接输出 "鸡腿"。
 */
class QueryKeywordExtractor(
    private val apiKey: String,
    private val apiBase: String = "https://api.siliconflow.cn/v1",
    private val model: String = "Qwen/Qwen2.5-7B-Instruct",
) {
    companion object {
        private const val TAG = "QueryKeywordExtractor"
        private val json = Json { ignoreUnknownKeys = true }
    }

    /** 把句子拆成 2-5 个关键词；任何失败都返回空列表（上层降级 ngram） */
    suspend fun extract(query: String): List<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || query.isBlank()) return@withContext emptyList()
        runCatching {
            val url = URL("${apiBase.trimEnd('/')}/chat/completions")
            val body = buildJsonObject {
                put("model", JsonPrimitive(model))
                put("temperature", JsonPrimitive(0.0))
                put("max_tokens", JsonPrimitive(64))
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", JsonPrimitive("system"))
                        put("content", JsonPrimitive("你是中文关键词提取器。把用户句子提取成2-5个最重要的中文关键词（名词/实体/动作优先，去掉虚词、语气词、人称代词）。只输出关键词，用逗号分隔，不要任何多余文字。"))
                    })
                    add(buildJsonObject {
                        put("role", JsonPrimitive("user"))
                        put("content", JsonPrimitive(query))
                    })
                })
            }.toString()

            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                doOutput = true
                connectTimeout = 5000
                readTimeout = 8000
            }
            connection.outputStream.bufferedWriter().use { writer ->
                writer.write(body)
                writer.flush()
            }
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: ""
                Log.w(TAG, "extract HTTP $responseCode body=$errorBody")
                throw Exception("HTTP $responseCode")
            }
            val respText = connection.inputStream.bufferedReader().readText()
            val content = parseContent(respText)
            content.split(Regex("[,，、;；\\s]+"))
                .map { it.trim() }
                .filter { it.length in 2..12 && it !in ExternalMemoryService.STOP_WORDS }
                .distinct()
                .take(5)
        }.getOrElse {
            Log.w(TAG, "extract failed: ${it.message}")
            emptyList()
        }
    }

    private fun parseContent(jsonText: String): String {
        return runCatching {
            json.parseToJsonElement(jsonText).jsonObject
                .get("choices")?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject
                ?.get("content")?.jsonPrimitive?.contentOrNull
                .orEmpty()
        }.getOrElse { "" }
    }
}
