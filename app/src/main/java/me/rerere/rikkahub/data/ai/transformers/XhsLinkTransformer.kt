/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "XhsLink"

/**
 * 小红书链接自动展开 · 2026-10-25
 *
 * 需求（宝当天说的）：发一条小红书链接过来，猫只看到一串字，得自己想
 * 起来去调工具；想不起来就什么都不知道。
 *
 * 这个 transformer 干的就是"替猫想起来"：
 *   扫最后一条用户消息里的链接 → 抓页面 → 解 window.__INITIAL_STATE__
 *   → 拿到标题/正文/配图 → 拼成一段文字塞在消息末尾、图落成 Image part。
 *
 * 页面里的 __INITIAL_STATE__ 不需要登录、不需要 cookie（跟着手机 UA 走就行），
 * 所以这条路不吃风控。
 *
 * 三条纪律：
 *   ① 只处理最后一条 USER 消息（历史不用管，省时间也省钱）；
 *   ② 抓不到、超时、结构变了 —— 一律原样放行，绝不让一次失败阻塞对话；
 *   ③ 同一个链接抓过一次就记着，不重复抓。
 */
object XhsLinkTransformer : InputMessageTransformer, KoinComponent {

    /** 匹配 xhslink.cn / xhslink.com 短链，以及 www.xiaohongshu.com 长链 */
    private val linkRegex = Regex(
        """https?://(?:xhslink\.(?:cn|com)/[A-Za-z0-9\-_/?=&%.]+|(?:www\.)?xiaohongshu\.com/[A-Za-z0-9\-_/?=&%.]+)"""
    )

    /** 抓过的链接缓存（url → 展开后的 parts），进程内有效 */
    private val cache = LinkedHashMap<String, List<UIMessagePart>>()

    private const val MAX_IMAGES = 9          // 最多带这么多张图
    private const val MAX_DESC_CHARS = 2000   // 正文截断
    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 12000

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        // 只认最后一条用户消息
        val lastIndex = messages.indexOfLast { it.role == MessageRole.USER }
        if (lastIndex < 0) return messages

        val target = messages[lastIndex]
        val textParts = target.parts.filterIsInstance<UIMessagePart.Text>()
        if (textParts.isEmpty()) return messages

        // 找链接
        val link = textParts
            .asSequence()
            .flatMap { part -> linkRegex.findAll(part.text).map { it.value } }
            .firstOrNull()
            ?: return messages

        val extra = cache[link] ?: run {
            val fetched = try {
                withContext(Dispatchers.IO) { fetchNote(link) }
            } catch (e: Exception) {
                Log.w(TAG, "fetch failed: ${e.javaClass.simpleName} ${e.message}")
                null
            }
            if (fetched == null || fetched.isEmpty()) {
                // 失败也记一笔，避免同一条链接反复重试
                cache[link] = emptyList()
                Log.w(TAG, "expand failed, pass through: $link")
                return messages
            }
            cache[link] = fetched
            Log.i(TAG, "expanded ${fetched.size} parts for $link")
            fetched
        }

        if (extra.isEmpty()) return messages

        // 塞进那条消息末尾
        val newParts = target.parts + extra
        val newMessage = target.copy(parts = newParts)
        return messages.toMutableList().also { it[lastIndex] = newMessage }
    }

    // ---------------------------------------------------------------- 抓页面

    private fun fetchNote(link: String): List<UIMessagePart>? {
        val html = httpGet(link) ?: return null
        val stateJson = extractInitialState(html) ?: run {
            Log.w(TAG, "no __INITIAL_STATE__, len=${html.length}")
            return null
        }

        val state = try {
            json.parseToJsonElement(stateJson).jsonObject
        } catch (e: Exception) {
            Log.w(TAG, "state parse failed: ${e.message}")
            return null
        }

        val noteData = state["noteData"]?.jsonObject ?: return null

        // 两条路都兼容（教程提过结构会变）
        val preload = noteData["normalNotePreloadData"]?.jsonObject
        val deep = noteData["data"]?.jsonObject?.get("noteData")?.jsonObject

        val title = (preload?.str("title") ?: deep?.str("title") ?: "").trim()
        val desc = (preload?.str("desc") ?: deep?.str("desc") ?: "").trim()
        val author = deep?.get("user")?.jsonObject?.str("nickName")?.trim().orEmpty()
        val noteId = deep?.str("noteId").orEmpty()

        // 图片 url
        val imageUrls = mutableListOf<String>()
        preload?.get("imagesList")?.let { arr ->
            runCatching {
                arr.jsonArray.forEach { el ->
                    val o = el.jsonObject
                    val u = o.str("url") ?: o.str("urlDefault") ?: o.str("urlPre")
                    if (!u.isNullOrBlank()) imageUrls.add(normalizeUrl(u))
                }
            }
        }
        if (imageUrls.isEmpty()) {
            deep?.get("imageList")?.let { arr ->
                runCatching {
                    arr.jsonArray.forEach { el ->
                        val o = el.jsonObject
                        val u = o.str("urlDefault") ?: o.str("url") ?: o.str("urlPre")
                        if (!u.isNullOrBlank()) imageUrls.add(normalizeUrl(u))
                    }
                }
            }
        }
        // 去重 + 限数
        val urls = imageUrls.distinct().take(MAX_IMAGES)
        if (title.isBlank() && desc.isBlank() && urls.isEmpty()) return null

        val parts = mutableListOf<UIMessagePart>()
        val sb = StringBuilder()
        sb.append("\n\n<xhs_note>\n")
        sb.append("【小红书笔记已展开】\n")
        if (title.isNotBlank()) sb.append("标题：").append(title).append('\n')
        if (author.isNotBlank()) sb.append("作者：").append(author).append('\n')
        if (desc.isNotBlank()) {
            sb.append("正文：")
            sb.append(if (desc.length > MAX_DESC_CHARS) desc.take(MAX_DESC_CHARS) + "…" else desc)
            sb.append('\n')
        }
        sb.append("配图：").append(urls.size).append(" 张")
        if (urls.isEmpty()) sb.append("（没取到）")
        sb.append('\n')
        if (noteId.isNotBlank()) {
            sb.append("链接：https://www.xiaohongshu.com/explore/").append(noteId).append('\n')
        }
        sb.append("</xhs_note>")
        parts.add(UIMessagePart.Text(sb.toString()))

        // 下载配图 → Image part（能走后面已有的转述链路）
        val saved = downloadImages(urls)
        saved.forEach { parts.add(UIMessagePart.Image(url = "file://${it.absolutePath}")) }
        if (saved.isNotEmpty()) Log.i(TAG, "images saved: ${saved.size}/${urls.size}")

        return parts
    }

    private fun extractInitialState(html: String): String? {
        val marker = "window.__INITIAL_STATE__"
        val i = html.indexOf(marker)
        if (i < 0) return null
        val eq = html.indexOf('=', i)
        if (eq < 0) return null
        val end = html.indexOf("</script>", eq)
        if (end < 0) return null
        var raw = html.substring(eq + 1, end).trim()
        if (raw.endsWith(";")) raw = raw.dropLast(1).trim()
        // JS 里的 undefined 不是合法 JSON
        return raw.replace("undefined", "null")
    }

    private fun httpGet(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", MOBILE_UA)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            }
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "http ${conn.responseCode} for $url")
                return null
            }
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            Log.w(TAG, "httpGet ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** 下载配图到 cacheDir/xhs_images，返回落地的文件 */
    private fun downloadImages(urls: List<String>): List<File> {
        if (urls.isEmpty()) return emptyList()
        val dir = File(get<Context>().cacheDir, "xhs_images").apply { mkdirs() }
        val out = mutableListOf<File>()
        urls.forEachIndexed { idx, u ->
            val f = try {
                downloadOne(u, dir, idx)
            } catch (e: Exception) {
                Log.w(TAG, "img[$idx] ${e.javaClass.simpleName}: ${e.message}")
                null
            }
            if (f != null) out.add(f)
        }
        return out
    }

    private fun downloadOne(url: String, dir: File, idx: Int): File? {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", MOBILE_UA)
                // CDN 有防盗链，带上 referer 才拿得到
                setRequestProperty("Referer", "https://www.xiaohongshu.com/")
            }
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "img[$idx] http ${conn.responseCode}")
                return null
            }
            val ext = guessExt(url, conn.contentType)
            val file = File(dir, "xhs_${System.currentTimeMillis()}_$idx.$ext")
            conn.inputStream.use { input: InputStream ->
                FileOutputStream(file).use { fos -> input.copyTo(fos) }
            }
            return if (file.length() > 0) file else null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun guessExt(url: String, contentType: String?): String {
        val u = url.lowercase()
        return when {
            u.contains(".png") -> "png"
            u.contains(".gif") -> "gif"
            u.contains(".webp") -> "webp"
            contentType?.contains("png", true) == true -> "png"
            contentType?.contains("webp", true) == true -> "webp"
            else -> "jpg"
        }
    }

    private fun normalizeUrl(u: String): String {
        var s = u.replace("\\u002F", "/").replace("\\/", "/")
        if (s.startsWith("//")) s = "https:$s"
        if (s.startsWith("http://")) s = "https://" + s.removePrefix("http://")
        return s
    }

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()

    private const val MOBILE_UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
}
