/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.AppLogBuffer
import me.rerere.rikkahub.plugin.webview.MusicPlayerService
import java.net.URL
import java.net.URLEncoder

/*
 * 【汽水音乐直投 · 2026-10-31 宝要的】
 *
 * 汽水的分享链是短链（qishui.douyin.com/s/xxx），展开后落在
 * music.douyin.com/qishui/share/track?track_id=...，那一页的 HTML 里
 * 内嵌着 _ROUTER_DATA，里面有 audioWithLyricsOption.url —— 就是这首歌的
 * 音频直链（mime_type=audio_mp4，其实是 m4a 封在 mp4 里，ExoPlayer 认）。
 *
 * 两个坑（都是实测出来的）：
 *
 * 1. 直链带签名和过期时间（url 里的 dy_q / l 两个参数），存下来隔天就废。
 *    所以每次播放都必须现解析一次，不能缓存复用。
 *
 * 2. _ROUTER_DATA 那一大坨 JSON 后面还接着别的脚本，直接用 Json 解析会报
 *    "Extra data"。这里不解析整块 —— 只定位 "audioWithLyricsOption" 之后
 *    第一个 "url":"..."，把它取出来反转义（里面的 / 是写成了 \u002F 的）。
 *    少解析、少出错。
 */

private const val QISHUI_TAG = "QiShui"
private const val QISHUI_UA =
    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"

private val QISHUI_URL_RE = Regex("""https?://[^\s，,。""']+""")
private val QISHUI_OG_TITLE_RE = Regex("""<meta[^>]+property="og:title"[^>]+content="([^"]*)"""")

/** 分享文本里有没有汽水的链接（给上层判断要不要走这条路）。 */
internal fun looksLikeQiShui(text: String): Boolean =
    text.contains("qishui.douyin.com") || text.contains("music.douyin.com/qishui")

/**
 * 从分享文本 / 链接里解析出可播放的音频直链。
 *
 * 返回 Triple(直链, 曲名, 歌手)。拿不到就返回 null，由调用方退回「开网页」。
 */
internal suspend fun resolveQiShuiAudio(text: String): Triple<String, String, String>? {
    val shareUrl = QISHUI_URL_RE.find(text)?.value ?: return null
    return try {
        val conn = URL(shareUrl).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 25000
        conn.instanceFollowRedirects = true
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", QISHUI_UA)

        val code = conn.responseCode
        val html = if (code == 200) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            AppLogBuffer.log(QISHUI_TAG, "分享页 HTTP $code")
            return null
        }

        // 只认 audioWithLyricsOption 之后那一个 url —— 页面上还有别的 url 字段（推荐歌、背景乐）
        val anchor = html.indexOf("audioWithLyricsOption")
        if (anchor < 0) {
            AppLogBuffer.log(QISHUI_TAG, "页面里没有 audioWithLyricsOption，长度=${html.length}")
            return null
        }
        val urlKey = html.indexOf("\"url\":\"", anchor)
        if (urlKey < 0) {
            AppLogBuffer.log(QISHUI_TAG, "audioWithLyricsOption 后面没有 url")
            return null
        }
        val start = urlKey + "\"url\":\"".length
        val end = html.indexOf('"', start)
        if (end <= start) return null
        val audioUrl = html.substring(start, end)
            .replace("\\u002F", "/")
            .replace("\\/", "/")

        if (!audioUrl.startsWith("http")) {
            AppLogBuffer.log(QISHUI_TAG, "解析出来的不是 http 地址: ${audioUrl.take(40)}")
            return null
        }

        // 曲名：og:title 是 "《歌名》@汽水音乐" 这种，去掉尾巴留干净的名字
        val ogTitle = QISHUI_OG_TITLE_RE.find(html)?.groupValues?.get(1).orEmpty()
        val title = ogTitle
            .substringAfter("《").substringBefore("》")
            .ifBlank { ogTitle.substringBefore("@").trim() }
            .ifBlank { "汽水音乐" }

        AppLogBuffer.log(QISHUI_TAG, "解析成功 title=$title code=$code len=${audioUrl.length}")
        Triple(audioUrl, title, "")
    } catch (e: Exception) {
        AppLogBuffer.log(QISHUI_TAG, "解析挂了: ${e.javaClass.simpleName} ${e.message}")
        null
    }
}

/**
 * 汽水音乐工具：宝发来汽水分享链接 → 解析出直链 → 交给 App 自己的播放器。
 *
 * 需要 context（要起 MusicPlayerService），所以跟 music 工具一样从外面传进来。
 */
fun createQiShuiMusicTool(context: Context): Tool = Tool(
    name = "qishui_music",
    description = "汽水音乐（QQ/字节系）直投：宝发来汽水音乐（qishui.douyin.com）的分享链接时调用。" +
        "解析分享页拿到音频直链，直接在 App 自己的播放器里播。" +
        "参数 url=宝发来的分享文本或链接（整段贴进来也行）。",
    parameters = {
        InputSchema.Obj(
            properties = kotlinx.serialization.json.buildJsonObject {
                put("url", JsonPrimitive("string"))
                put("description", JsonPrimitive("宝发来的汽水分享文本/链接，整段贴进来即可"))
            },
            required = listOf("url")
        )
    },
    execute = { input ->
        val params = input.jsonObject
        val raw = params["url"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        if (raw.isBlank()) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("ok", false)
                    put("error", "url 是空的，把宝发的分享链接整段贴进来就行")
                }.toString()
            ))
        }

        val resolved = resolveQiShuiAudio(raw)
        if (resolved == null) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("ok", false)
                    put("error", "没解析出音频直链（链接过期 / 那首歌在汽水上没版权 / 字节那边改了页面结构）")
                    put("tip", "退回方案：让宝点开链接，用汽水 App 自己听")
                }.toString()
            ))
        }

        val (audioUrl, title, artist) = resolved
        try {
            MusicPlayerService.play(context, audioUrl, title, artist)
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("ok", true)
                    put("pushed", title)
                    put("note", "这条直链带签名，会过期；下次听重新解析就行")
                }.toString()
            ))
        } catch (e: Exception) {
            AppLogBuffer.log(QISHUI_TAG, "交给播放器挂了: ${e.message}")
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("ok", false)
                    put("error", "解析到了但播放器没起来: " + (e.message ?: "unknown"))
                }.toString()
            ))
        }
    }
)
