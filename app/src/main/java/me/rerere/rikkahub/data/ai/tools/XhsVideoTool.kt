package me.rerere.rikkahub.data.ai.tools

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.AppLogBuffer
import me.rerere.rikkahub.ui.pages.miniapp.MiniAppWebViewBridge
import me.rerere.rikkahub.ui.pages.miniapp.MiniAppWebViewHost
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/*
 * 【小红书视频直投 · 2026-10-27】
 *
 * 为什么有它：浮窗已经能播「直链」了（网页里抠不到抖音/小红书的视频，
 * 但只要有 mp4 地址，原生播放器就能放）。缺的是「拿到那个地址」这一步。
 *
 * 小红书给了现成的一条路（2026-10-27 实测通过）：
 *   search_feeds(keyword) → 挑一条 type=video → get_feed_detail(feed_id, xsec_token)
 *   → video.media.stream.h264[].masterUrl（带签名的 mp4，无防盗链，HEAD 200）
 *
 * 这里就是把这步串起来，拿完直接把浮窗指过去 —— 宝点一下 ▶ 就进原生播放器。
 *
 * 边界：
 *   - 直链带签名，约 1 小时过期（expiresAt 会一起报给模型，别攒着）
 *   - 走的是猫自己配的小红书 MCP（cloudflare 隧道），不直接碰小红书接口
 *   - 拿不到就老实说拿不到，不编
 */

private const val XHS_TAG = "XhsVideo"
private const val MCP_ENDPOINT = "https://xhs.rickycat.asia/mcp"
private const val MCP_TIMEOUT_SECONDS = 110L

private val xhsHttp: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .readTimeout(MCP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
}

private const val XHS_NO_PAGE =
    "浮窗这会儿没开着 —— 先让宝点开浮窗（聊天页顶栏那个 ▣），猫才有地方投。"

private fun xhsBrowser(): WebView? =
    MiniAppWebViewHost.floatWebView ?: MiniAppWebViewBridge.current

/** 用一次 MCP 会话连续调几次工具：initialize 拿 session，再逐个 tools/call。 */
private suspend fun withMcpSession(block: suspend (sessionId: String) -> Unit) {
    val initBody = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", 1)
        put("method", "initialize")
        putJsonObject("params") {
            put("protocolVersion", "2024-11-05")
            putJsonObject("capabilities") {}
            putJsonObject("clientInfo") {
                put("name", "orangecat")
                put("version", "1.0")
            }
        }
    }
    val initReq = Request.Builder()
        .url(MCP_ENDPOINT)
        .post(initBody.toString().toRequestBody("application/json".toMediaType()))
        .header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream")
        .build()

    xhsHttp.newCall(initReq).execute().use { resp ->
        val sid = resp.header("Mcp-Session-Id")
        if (sid.isNullOrBlank()) {
            AppLogBuffer.log(XHS_TAG, "initialize 没拿到 session（code=${resp.code}）")
            return
        }
        block(sid)
    }
}

/** 调一个 MCP 工具，把 result.content[0].text 里那层 JSON 解出来。 */
private suspend fun mcpCall(sessionId: String, tool: String, arguments: JsonObject): JsonObject? =
    withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 2)
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", tool)
                put("arguments", arguments)
            }
        }
        val req = Request.Builder()
            .url(MCP_ENDPOINT)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("Mcp-Session-Id", sessionId)
            .build()

        val raw = try {
            xhsHttp.newCall(req).execute().use { it.body.string() }
        } catch (e: Exception) {
            AppLogBuffer.log(XHS_TAG, "$tool 请求挂了: ${e.message}")
            return@withContext null
        }

        // 可能是纯 JSON，也可能是 SSE（data: {...}）—— 兼容两种
        val jsonText = raw.lineSequence()
            .filter { it.startsWith("{") }
            .joinToString("")
            .ifBlank { raw }

        try {
            val root = Json.parseToJsonElement(jsonText).jsonObject
            val text = root["result"]?.jsonObject
                ?.get("content")?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("text")?.jsonPrimitive?.content
            if (text == null) {
                AppLogBuffer.log(XHS_TAG, "$tool 返回里没有 content: ${raw.take(200)}")
                return@withContext null
            }
            Json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            AppLogBuffer.log(XHS_TAG, "$tool 解析失败: ${e.message}")
            null
        }
    }

/** 从详情 JSON 里挖出 masterUrl（挑清晰度最高那一档）。 */
private fun extractMasterUrl(detail: JsonObject): String? {
    fun walk(el: kotlinx.serialization.json.JsonElement?): List<String> {
        if (el == null) return emptyList()
        return when (el) {
            is JsonObject -> el.entries.flatMap { (k, v) ->
                if (k == "masterUrl" && v is kotlinx.serialization.json.JsonPrimitive) {
                    listOf(v.content)
                } else walk(v)
            }

            is kotlinx.serialization.json.JsonArray -> el.flatMap { walk(it) }
            else -> emptyList()
        }
    }
    // 详情可能包在 data / note 里，整棵树扫一遍最省事
    val all = walk(detail).filter { it.contains(".mp4", ignoreCase = true) }
    if (all.isEmpty()) return null
    // 名字里数字越大越清晰（…_520.mp4 > …_258.mp4），挑大的
    return all.maxByOrNull { url ->
        Regex("_(\\d+)\\.mp4").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }
}

fun xhsVideoTool(): Tool = Tool(
    name = "xhs_video",
    description = "把小红书里的一条视频投到浮窗播放。给 keyword（比如「看海」）就让猫搜一条视频；给 url（小红书笔记链接）就解析那一条。" +
        "拿到的是带签名的直链，约一小时过期，投完让宝点浮窗右上角 ▶ 就进原生播放器。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("keyword") {
                    put("type", "string")
                    put("description", "搜索关键词（和 url 二选一）")
                }
                putJsonObject("url") {
                    put("type", "string")
                    put("description", "小红书笔记链接（和 keyword 二选一），形如 https://www.xiaohongshu.com/explore/xxxx?xsec_token=yyy")
                }
            },
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val keyword = obj["keyword"]?.jsonPrimitive?.content?.trim().orEmpty()
        val url = obj["url"]?.jsonPrimitive?.content?.trim().orEmpty()

        if (keyword.isBlank() && url.isBlank()) {
            return@Tool listOf(UIMessagePart.Text("给个关键词或者小红书链接，猫才知道投哪条。"))
        }

        var resultText = ""
        var directLink: String? = null
        var title = ""

        try {
            withMcpSession { sid ->
                // ── 1. 定一条视频：要么从链接抠，要么搜 ──
                var feedId = ""
                var xsecToken = ""

                if (url.isNotBlank()) {
                    val m = Regex("/explore/([0-9a-fA-F]+)|/discovery/item/([0-9a-fA-F]+)")
                        .find(url)
                    feedId = m?.groupValues?.let { it[1].ifBlank { it[2] } }.orEmpty()
                    xsecToken = Regex("[?&]xsec_token=([^&]+)").find(url)
                        ?.groupValues?.get(1).orEmpty()
                    if (feedId.isBlank()) {
                        resultText = "这个链接里没认出笔记 id —— 换关键词搜吧。"
                        return@withMcpSession
                    }
                    if (xsecToken.isBlank()) {
                        resultText = "这条链接里没有 xsec_token（可能复制时被截了）。换关键词搜吧。"
                        return@withMcpSession
                    }
                } else {
                    val searchArgs = buildJsonObject { put("keyword", keyword) }
                    val searchResult = mcpCall(sid, "search_feeds", searchArgs)
                    if (searchResult == null) {
                        resultText = "搜索没回来（小红书那边慢了或者卡了）。"
                        return@withMcpSession
                    }
                    val feeds = searchResult["feeds"]?.jsonArray ?: return@withMcpSession
                    var picked: JsonObject? = null
                    for (f in feeds) {
                        val o = f.jsonObject
                        val card = o["noteCard"]?.jsonObject
                        if (card?.get("type")?.jsonPrimitive?.content == "video") {
                            picked = o
                            break
                        }
                    }
                    if (picked == null) {
                        resultText = "「$keyword」搜出来的都不是视频（小红书搜到的多是图文）。"
                        return@withMcpSession
                    }
                    feedId = picked["id"]?.jsonPrimitive?.content.orEmpty()
                    xsecToken = picked["xsecToken"]?.jsonPrimitive?.content.orEmpty()
                    title = picked["noteCard"]?.jsonObject
                        ?.get("displayTitle")?.jsonPrimitive?.content.orEmpty()
                }

                // ── 2. 拿详情，挖 masterUrl ──
                val detailArgs = buildJsonObject {
                    put("feed_id", feedId)
                    put("xsec_token", xsecToken)
                }
                val detail = mcpCall(sid, "get_feed_detail", detailArgs)
                if (detail == null) {
                    resultText = "拿到笔记了，但详情没回来（这一步最慢，可能要 80 秒）。"
                    return@withMcpSession
                }
                directLink = extractMasterUrl(detail)
                if (title.isBlank()) {
                    title = detail["data"]?.jsonObject
                        ?.get("title")?.jsonPrimitive?.content.orEmpty()
                }
            }
        } catch (e: Exception) {
            AppLogBuffer.log(XHS_TAG, "整体挂了: ${e.message}")
            return@Tool listOf(UIMessagePart.Text("小红书那条链没走通（${e.message}）。"))
        }

        val link = directLink
        if (link == null) {
            val msg = resultText.ifBlank { "链走通了，但详情里没找到 mp4 地址。" }
            return@Tool listOf(UIMessagePart.Text(msg))
        }

        // ── 3. 把浮窗指过去 ──
        val wv = xhsBrowser()
        if (wv == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    "拿到直链了，但浮窗没开，没地方投。\n$link"
                )
            )
        }
        Handler(Looper.getMainLooper()).post { wv.loadUrl(link) }
        AppLogBuffer.log(XHS_TAG, "投出去了: ${title.ifBlank { "(无标题)" }} -> ${link.take(90)}")

        listOf(
            UIMessagePart.Text(
                buildString {
                    append("投出去了")
                    if (title.isNotBlank()) append("：《$title》")
                    append("。\n")
                    append("浮窗这会儿在放这条（WebView 自己播），点右上角那个 ▶ 换成原生播放器，能拖进度、能暂停。\n")
                    append("直链带签名，大约一小时过期 —— 过了要重新投一次。")
                }
            )
        )
    },
)
