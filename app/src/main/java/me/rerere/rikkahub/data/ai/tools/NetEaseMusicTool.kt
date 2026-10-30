package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.ai.AppLogBuffer
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/*
 * 【网易云音乐直投 · 2026-10-30 宝要的「现在」】
 *
 * 系统分享出来的是短链（163cn.tv/xxx），那一页里没有 mp3，只有一个歌曲 id。
 * 所以路是两段：短链读 Location 拿 id → 外链接口 302 到 CDN 上的真 mp3。
 *
 * 外链接口：对能听的歌都给；会员歌、下架的、版权没了的那一批会 404。
 * 404 的时候卡片会退回「开网页」，不影响别的功能。
 *
 * 为什么不在 App 里把 302 跟完再交给播放器：
 * 跟完就要在这一侧把整个文件重新收一遍，白白多跑一趟流量；
 * ExoPlayer 自己就会跟随重定向，直接喂接口地址更快。
 */

private const val NETEASE_TAG = "NetEase"
private const val NETEASE_UA =
    "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"

private val neteaseHttp: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        // 要看 Location 头，所以不自动跟随
        .followRedirects(false)
        .build()
}

private val NETEASE_ID_RE = Regex("""[?&]id=(\d+)""")
private val NETEASE_URL_RE = Regex("""https?://[^\s，,。\"']+""")

/**
 * 从分享文本 / 链接里拿到歌曲 id。
 * 长链（music.163.com/song?id=xxx）当场就能抠出来；短链（163cn.tv/xxx）要读一次 Location。
 */
internal suspend fun resolveNeteaseSongId(text: String): String? = withContext(Dispatchers.IO) {
    NETEASE_ID_RE.find(text)?.groupValues?.get(1)?.let { return@withContext it }
    val url = NETEASE_URL_RE.find(text)?.value ?: return@withContext null
    try {
        val req = Request.Builder().url(url).header("User-Agent", NETEASE_UA).get().build()
        neteaseHttp.newCall(req).execute().use { resp ->
            NETEASE_ID_RE.find(resp.header("Location").orEmpty())
                ?.groupValues?.get(1)?.let { return@withContext it }
            NETEASE_ID_RE.find(resp.request.url.toString())
                ?.groupValues?.get(1)?.let { return@withContext it }
        }
    } catch (e: Exception) {
        AppLogBuffer.log(NETEASE_TAG, "短链展开挂了: ${e.message}")
    }
    null
}

/**
 * 拿真正的播放地址。
 *
 * 为什么要自己跟这一次 302：
 * 外链接口 302 到的 CDN 地址是 **http://**，而安卓默认不让发明文 HTTP（cleartext），
 * ExoPlayer 跟到那一步就直接断了——宝看到的是「00:00 / 00:00」的黑播放器。
 * 实测把 scheme 换成 https 照样下得下来（206 + audio/mpeg），所以这里换掉再交出去。
 */
internal suspend fun resolveNeteaseMp3(text: String): String? = withContext(Dispatchers.IO) {
    val id = resolveNeteaseSongId(text) ?: run {
        AppLogBuffer.log(NETEASE_TAG, "歌曲 id 没拿到: $text")
        return@withContext null
    }
    try {
        val req = Request.Builder()
            .url("https://music.163.com/song/media/outer/url?id=$id.mp3")
            .header("User-Agent", NETEASE_UA)
            .get().build()
        neteaseHttp.newCall(req).execute().use { resp ->
            val loc = resp.header("Location").orEmpty()
            AppLogBuffer.log(NETEASE_TAG, "outer id=$id code=${resp.code} loc=${loc.take(50)}")
            if (loc.isBlank()) return@withContext null
            loc.replaceFirst("http://", "https://")
        }
    } catch (e: Exception) {
        AppLogBuffer.log(NETEASE_TAG, "拿播放地址挂了: ${e.message}")
        null
    }
}
