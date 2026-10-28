package me.rerere.rikkahub.data.ai.tools

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/*
 * 【抖音视频直投 · 2026-10-27】
 *
 * 跟 xhs_video 是一对：那个走小红书 MCP 拿签名直链，这个走抖音移动端 Feed 接口。
 *
 * 抖音这条路比小红书还干净（2026-10-27 沙盒实测全通）：
 *   aweme_id → api5-normal-c-hl.amemv.com/aweme/v1/feed/?aweme_id=xxx
 *   → video.bit_rate[n].play_addr.url_list[0]（挑最高码率，筛 video_mp4）
 *   → 把浮窗指过去，宝点一下 ▶ 就进原生播放器
 *
 * 边界（踩过的坑都写在这儿）：
 *   - 不用签名、不用 cookie、不跑浏览器 —— 别再去爬分享页，
 *     那是 _$jsvmprt 反爬虚拟机，body 是空的（2026-10-27 白折腾半小时就是走了那条路）
 *   - 顶层 video.play_addr 不是文件，是 api-play.amemv.com 的跳转接口
 *   - 直链带签名参数，会过期（约一小时量级），别攒着
 */

private const val DY_TAG = "DyVideo"
private const val DY_UA =
    "com.ss.android.ugc.aweme/300904 (Linux; U; Android 13; zh_CN; Pixel 7; Build/TQ3A.230901.001; Cronet/58.0.2991.0)"
private const val DY_NO_PAGE =
    "直链拿到了，但浮窗没开着 —— 先把浮窗点开（聊天页顶栏那个 ▣），猫才有地方投。"

private val dyHttp: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()
}

private val dyHttpFollow: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
}

private fun dyBrowser(): WebView? =
    MiniAppWebViewHost.floatWebView ?: MiniAppWebViewBridge.current

private val DY_ID_RE = Regex("(?:/video/|/note/|modal_id=)(\\d{15,20})")

/** 抽 aweme_id：文字里直接有就用；是短链就读 Location；再不行跟随重定向看最终 URL。 */
internal suspend fun resolveAwemeId(text: String): String? = withContext(Dispatchers.IO) {
    DY_ID_RE.find(text)?.groupValues?.get(1)?.let { return@withContext it }
    val trimmed = text.trim()
    if (trimmed.length in 15..20 && trimmed.all { it.isDigit() }) return@withContext trimmed

    val url = Regex("https?://[^\\s，,。\\\"']+").find(text)?.value ?: return@withContext null
    try {
        val req = Request.Builder().url(url).header("User-Agent", DY_UA).get().build()
        dyHttp.newCall(req).execute().use { resp ->
            val loc = resp.header("Location").orEmpty()
            DY_ID_RE.find(loc)?.groupValues?.get(1)?.let { return@withContext it }
        }
        val req2 = Request.Builder().url(url).header("User-Agent", DY_UA).get().build()
        dyHttpFollow.newCall(req2).execute().use { resp ->
            Regex("(\\d{15,20})").find(resp.request.url.toString())
                ?.groupValues?.get(1)?.let { return@withContext it }
        }
    } catch (e: Exception) {
        AppLogBuffer.log(DY_TAG, "短链解析挂了: ${e.message}")
    }
    null
}

/** 调移动端 Feed 接口，拿整条作品。 */
internal suspend fun fetchAweme(id: String): JsonObject? = withContext(Dispatchers.IO) {
    val url = "https://api5-normal-c-hl.amemv.com/aweme/v1/feed/?" +
        "aweme_id=$id&aid=1128&device_platform=android&version_code=300904&version_name=30.9.4" +
        "&os_version=13&device_type=Pixel7&device_model=Pixel7&os_api=33" +
        "&channel=googleplay&language=zh&count=1"
    try {
        val req = Request.Builder().url(url).header("User-Agent", DY_UA).get().build()
        dyHttpFollow.newCall(req).execute().use { resp ->
            val body = resp.body.string()
            AppLogBuffer.log(DY_TAG, "feed code=${resp.code} len=${body.length}")
            if (body.length < 200) return@withContext null
            val root = Json.parseToJsonElement(body).jsonObject
            val list = root["aweme_list"]?.jsonArray ?: return@withContext null
            // 【2026-10-28 修】原来是"找不到匹配的就用第一条"（插件同款兜底）。
            // 实测接口经常无视 aweme_id、直接吐推荐流第一条 → 兜底等于"随便给一条"，
            // 封面/视频就跟用户分享的完全无关。改成：对不上就不返回，宁可不出卡片也不出错。
            val matched = list.firstOrNull { it.jsonObject["aweme_id"]?.jsonPrimitive?.content == id }?.jsonObject
            if (matched == null) {
                val got = list.firstOrNull()?.jsonObject?.get("aweme_id")?.jsonPrimitive?.content
                AppLogBuffer.log(DY_TAG, "feed 不认 aweme_id：要 $id，回 $got（共 ${list.size} 条），放弃")
            }
            matched
        }
    } catch (e: Exception) {
        AppLogBuffer.log(DY_TAG, "feed 挂了: ${e.message}")
        null
    }
}

/** 从 bit_rate 里挑最高码率的 mp4 直链（顶层 play_addr 是跳转接口，不能用）。 */
internal fun bestMp4(aweme: JsonObject): String? {    val gears = aweme["video"]?.jsonObject?.get("bit_rate")?.jsonArray ?: return null
    var best: String? = null
    var bestRate = -1L
    gears.forEach { g ->
        val o = g.jsonObject
        val url = o["play_addr"]?.jsonObject?.get("url_list")?.jsonArray
            ?.firstOrNull()?.jsonPrimitive?.content
        if (url != null && url.contains("video_mp4")) {
            val rate = o["bit_rate"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            if (rate > bestRate) {
                bestRate = rate
                best = url
            }
        }
    }
    return best
}

/** 取封面图地址：origin_cover > cover > dynamic_cover，各自 url_list 取最后一个。 */
internal fun bestCover(aweme: JsonObject): String? {
    val video = aweme["video"]?.jsonObject ?: return null
    for (key in listOf("origin_cover", "cover", "dynamic_cover")) {
        val list = video[key]?.jsonObject?.get("url_list")?.jsonArray ?: continue
        val u = list.mapNotNull { it.jsonPrimitive.contentOrNull }.lastOrNull { it.isNotBlank() }
        if (!u.isNullOrBlank()) return u
    }
    return null
}

internal fun str(o: JsonObject?, key: String): String =
    o?.get(key)?.jsonPrimitive?.content.orEmpty()

fun dyVideoTool(): Tool = Tool(
    name = "dy_video",
    description = "把抖音视频投到浮窗播放（无水印直链）。给 url（分享链接或整段口令）或 aweme_id 都行。" +
        "走移动端 Feed 接口，不用签名不用 cookie，比爬网页快得多。" +
        "拿到的是带签名的 mp4 直链，会过期；投完让宝点浮窗右上角 ▶ 就进原生播放器。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("url") {
                    put("type", "string")
                    put("description", "抖音分享链接/口令原文，或直接的 aweme_id（15-20 位数字）")
                }
            },
            required = listOf("url")
        )
    },
    execute = { args ->
        val raw = args.jsonObject["url"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (raw.isBlank()) {
            return@Tool listOf(UIMessagePart.Text("给个抖音链接或 id，猫才知道投哪条。"))
        }

        val id = resolveAwemeId(raw)
        if (id == null) {
            return@Tool listOf(UIMessagePart.Text("这段里没认出抖音作品 id —— 换个完整的分享链接试试。"))
        }

        val aweme = fetchAweme(id)
        if (aweme == null) {
            return@Tool listOf(UIMessagePart.Text("接口没返回这条作品（可能已删除、私密，或者被限流了）。"))
        }

        val link = bestMp4(aweme)
        if (link == null) {
            val imgs = aweme["images"]?.jsonArray?.size ?: 0
            val msg = if (imgs > 0) "这是图文笔记（${imgs} 张图），没有视频。" else "作品里没找到可播的 mp4。"
            return@Tool listOf(UIMessagePart.Text(msg))
        }

        val title = str(aweme, "desc").take(60)
        val author = str(aweme["author"]?.jsonObject, "nickname")
        val durationMs = str(aweme["video"]?.jsonObject, "duration").toLongOrNull() ?: 0L
        val sec = durationMs / 1000
        val stats = aweme["statistics"]?.jsonObject

        val wv = dyBrowser()
        if (wv == null) {
            return@Tool listOf(UIMessagePart.Text("$DY_NO_PAGE\n$link"))
        }
        Handler(Looper.getMainLooper()).post { wv.loadUrl(link) }
        AppLogBuffer.log(DY_TAG, "投出去了: ${title.ifBlank { "(无标题)" }} -> ${link.take(90)}")

        listOf(
            UIMessagePart.Text(
                buildString {
                    append("投出去了")
                    if (title.isNotBlank()) append("：《$title》")
                    if (author.isNotBlank()) append(" · @$author")
                    if (sec > 0) append(" · ${sec}秒")
                    append("\n")
                    val s = stats
                    if (s != null) {
                        append("赞 ${str(s, "digg_count")} / 评 ${str(s, "comment_count")} / 藏 ${str(s, "collect_count")}\n")
                    }
                    append("浮窗这会儿在放这条，点右上角 ▶ 换成原生播放器，能拖进度、能暂停。\n")
                    append("直链带签名，会过期 —— 过了要重新投一次。")
                }
            )
        )
    },
)
