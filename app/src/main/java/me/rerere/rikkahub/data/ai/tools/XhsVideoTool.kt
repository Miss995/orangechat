package me.rerere.rikkahub.data.ai.tools

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
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
 * 【小红书视频直投 · 2026-10-27 改版：不再走 MCP】
 *
 * 旧版靠小红书 MCP（search_feeds → get_feed_detail）拿 masterUrl。好处是能搜，
 * 坏处是 MCP 一挂就是 18 个工具的说明每次请求都要带上（实打实的钱），还得维护 cookie。
 *
 * 新路（2026-10-27 沙盒实测全通，全程未登录、无 cookie）：
 *   笔记链接 → 302 跳到 /discovery/item/{id}?xsec_token=...
 *   → 手机 UA 直接 GET 那个页面（未登录也能过）
 *   → 页面里 __INITIAL_STATE__ 的 "masterUrl":"http:\u002F\u002F..."
 *   → 反转义 \u002F、http 换 https → 带签名的 mp4 直链
 *   → 把浮窗指过去，宝点 ▶ 进原生播放器
 *
 * 边界：
 *   - 只能给链接，不能按关键词搜了（搜索本身就要 MCP）。丢了 MCP 换来的就是这个。
 *   - masterUrl 是 http + 转义斜杠，两条都得处理，否则一条都取不到
 *   - 直链带签名（sign + t 参数），会过期
 */

private const val XHS_TAG = "XhsVideo"
private const val XHS_UA =
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
private const val XHS_NO_PAGE =
    "浮窗这会儿没开着 —— 先把浮窗点开（聊天页顶栏那个 ▣），猫才有地方投。"

private val xhsHttp: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
}

private fun xhsBrowser(): WebView? =
    MiniAppWebViewHost.floatWebView ?: MiniAppWebViewBridge.current

/** 抓笔记页，拿 HTML。 */
/**
 * 【抽出来给界面用 · 2026-10-30 宝要的】链接卡的"点开"要能播小红书视频。
 * 抖音那边是卡片内部自己解 mp4（resolveAwemeId → fetchAweme → bestMp4），
 * 小红书这套原来只活在工具里（只有 AI 能调），界面够不着。抽出来之后两边共用一份。
 *
 * 返回 null 的两种可能：抓不到页面（限流/要登录），或者这条是图文没有 masterUrl。
 * 调用方分不清也不用分清 —— 拿不到就当作图文去展开，正合逻辑。
 */
internal suspend fun resolveXhsMp4(url: String): String? =
    fetchNotePage(url)?.let { extractMp4(it) }

internal suspend fun fetchNotePage(url: String): String? = withContext(Dispatchers.IO) {
    try {
        val req = Request.Builder().url(url).header("User-Agent", XHS_UA).get().build()
        xhsHttp.newCall(req).execute().use { resp ->
            val body = resp.body.string()
            AppLogBuffer.log(
                XHS_TAG,
                "page code=${resp.code} len=${body.length} final=${resp.request.url.toString().take(80)}"
            )
            if (body.length < 5000) null else body
        }
    } catch (e: Exception) {
        AppLogBuffer.log(XHS_TAG, "page 挂了: ${e.message}")
        null
    }
}

/** 从页面里挖 mp4 直链：反转义 \u002F，http 换 https，去重。 */
private fun extractMp4(html: String): String? {
    val re = Regex("\"masterUrl\":\"([^\"]+)\"")
    val seen = LinkedHashSet<String>()
    re.findAll(html).forEach { m ->
        val decoded = m.groupValues[1]
            .replace("\\u002F", "/")
            .replace("\\/", "/")
            .replace("http://", "https://")
        if (decoded.startsWith("https://") && decoded.contains(".mp4")) seen.add(decoded)
    }
    if (seen.isEmpty()) return null
    AppLogBuffer.log(XHS_TAG, "masterUrl 命中 ${seen.size} 条")
    return seen.first()
}

fun xhsVideoTool(): Tool = Tool(
    name = "xhs_video",
    description = "把小红书里的一条视频投到浮窗播放。给 url（小红书笔记链接，带不带 xsec_token 都行）即可。" +
        "不用登录、不用 cookie、不走 MCP —— 直接抓页面拿带签名的 mp4 直链。" +
        "直链会过期；投完让宝点浮窗右上角 ▶ 就进原生播放器。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("url") {
                    put("type", "string")
                    put("description", "小红书笔记链接，形如 https://www.xiaohongshu.com/explore/xxxx?xsec_token=yyy 或 xhslink.com 短链")
                }
            },
            required = listOf("url")
        )
    },
    execute = { args ->
        val url = args.jsonObject["url"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (url.isBlank() || !url.startsWith("http")) {
            return@Tool listOf(UIMessagePart.Text("给个小红书笔记链接，猫才知道投哪条。"))
        }

        val html = fetchNotePage(url)
        if (html == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    "这个页面没抓下来（可能被限流，或者这条笔记要登录才看）。\n" +
                        "换个时间再试，或者把链接再发一遍。"
                )
            )
        }

        val link = extractMp4(html)
        if (link == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    "页面拿到了，但里面没有视频地址 —— 这条大概是图文笔记（小红书搜到的多是图文）。"
                )
            )
        }

        val title = Regex("<title>(.*?)</title>").find(html)
            ?.groupValues?.get(1)
            ?.replace(Regex("\\s*-\\s*小红书\\s*$"), "")
            ?.take(60)
            .orEmpty()

        val wv = xhsBrowser()
        if (wv == null) {
            return@Tool listOf(UIMessagePart.Text("$XHS_NO_PAGE\n$link"))
        }
        Handler(Looper.getMainLooper()).post { wv.loadUrl(link) }
        AppLogBuffer.log(XHS_TAG, "投出去了: ${title.ifBlank { "(无标题)" }} -> ${link.take(90)}")

        listOf(
            UIMessagePart.Text(
                buildString {
                    append("投出去了")
                    if (title.isNotBlank()) append("：《$title》")
                    append("\n")
                    append("浮窗这会儿在放这条，点右上角 ▶ 换成原生播放器，能拖进度、能暂停。\n")
                    append("直链带签名，会过期 —— 过了要重新投一次。")
                }
            )
        )
    },
)
