/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import me.rerere.rikkahub.data.ai.AppLogBuffer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

    /** 【实况 · 2026-11-04】一张配图：地址 + 实况那段 mp4（不是实况就是 null）。 */
    private data class NoteImage(
        val url: String,
        val liveVideo: String? = null,
    ) {
        val isLive: Boolean get() = !liveVideo.isNullOrBlank()
    }

    // 【链接卡 · 2026-10-29】跟 DyLinkTransformer 用同一套键，渲染层才认得出
    private const val META_LINK_URL = "videoLink"
    private const val META_LINK_SOURCE = "videoSource"
    private const val META_LINK_TITLE = "linkTitle"

    /** 【多图缩略图 · 2026-10-29】这条笔记全部配图的本地路径，用 ## 分隔（url 里不会有 ##）。 */
    private const val META_LINK_IMAGES = "linkImages"

    /**
     * 【实况 · 2026-11-04】跟 linkImages 严格同位序的实况视频地址，不是实况的位置放空串（## 分隔）。
     *
     * 小红书一条笔记里可以混着普通图和多张实况（那条 6 张里 5 张是）。实况 = 一张图 + 一段
     * 约 3.9 秒、1080x1080、没有声音的 mp4，就藏在 imageList[i].stream.h264[0].masterUrl 里。
     *
     * 存的是远程签名地址，不是本地文件 —— 会过期（跟视频卡一样，过了重新发一次链接）。
     * 这么选是为了不拖慢出卡：5 段视频下载下来是 1.8MB，等它下完卡才出来，不划算。
     *
     * 空串占位不能省：去掉位序就塌了，第 3 张的地址会被当成第 1 张的。
     */
    private const val META_LINK_LIVE = "linkLiveVideos"

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        /*
         * 【只认"真的带链接的那条"，不认"最后一条用户消息" · 2026-10-25 踩过的坑】
         *
         * 第一版写的是「indexOfLast { role == USER }」——看着天经地义，实际一次都没跑成。
         * 原因：请求的末尾还挂着一条系统注入（「以下是系统消息注入：当前时间…」），
         * 它也是 USER 角色、排在最后。于是找到的是它 → 里面没链接 → 直接返回，
         * 整个 transformer 等于不存在（连一行日志都没留）。
         *
         * 现在改成从后往前扫，找第一条文字里真含小红书链接的。
         * 这样不管注入在链的哪一头，都指向正确的那条消息。
         */
        var targetIndex = -1
        for (i in messages.indices.reversed()) {
            val m = messages[i]
            if (m.role != MessageRole.USER) continue
            val has = m.parts.any { p ->
                p is UIMessagePart.Text && linkRegex.containsMatchIn(p.text)
            }
            if (has) {
                targetIndex = i
                break
            }
        }
        if (targetIndex < 0) return messages

        val target = messages[targetIndex]
        val link = target.parts
            .asSequence()
            .filterIsInstance<UIMessagePart.Text>()
            .flatMap { part -> linkRegex.findAll(part.text).map { it.value } }
            .firstOrNull()
            ?: return messages

        AppLogBuffer.log(TAG, "found link=$link idx=$targetIndex/${messages.size}")

        val extra = cache[link] ?: run {
            val fetched = try {
                withContext(Dispatchers.IO) { fetchNote(link) }
            } catch (e: Exception) {
                AppLogBuffer.log(TAG, "fetch FAILED: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
            if (fetched == null || fetched.isEmpty()) {
                // 失败也记一笔，避免同一条链接反复重试
                cache[link] = emptyList()
                AppLogBuffer.log(TAG, "expand FAILED, pass through: $link")
                return messages
            }
            cache[link] = fetched
            AppLogBuffer.log(TAG, "expanded ${fetched.size} parts for $link")
            fetched
        }

        if (extra.isEmpty()) return messages

        /*
         * 【链接卡 · 2026-10-29 宝实测"没成功"】把带标记的封面贴回会话。
         *
         * transformer 的产物只喂模型、不落库 —— 不贴的话界面上永远还是那串原始分享文本。
         * 抖音那边 10-28 就走通了这条路（DyLinkTransformer 的 onMediaAttached），
         * 小红书当时漏了这一步，所以卡一直出不来。
         */
        extra.filterIsInstance<UIMessagePart.Image>()
            .firstOrNull { it.metadata?.containsKey(META_LINK_URL) == true }
            ?.let { cover ->
                runCatching { ctx.onMediaAttached?.invoke(target.id.toString(), cover) }
                    .onFailure {
                        AppLogBuffer.log(TAG, "attach to conversation FAILED: ${it.javaClass.simpleName}: ${it.message}")
                    }
            }

        // 【防重复展开 · 2026-10-29】transformer 每轮请求都会跑一遍。
        // 如果这条消息里已经有一张带 link 的 Image（上一轮展开过），就不再塞第二张，
        // 否则每发一轮消息就多叠一张卡（22:06 的 "expanded 2 parts" 就是这么来的）。
        if (target.parts.any { p -> p is UIMessagePart.Image && p.metadata?.containsKey(META_LINK_URL) == true }) {
            AppLogBuffer.log(TAG, "already expanded, skip: $link")
            return messages
        }

        // 塞进那条消息末尾
        val newParts = target.parts + extra
        val newMessage = target.copy(parts = newParts)
        return messages.toMutableList().also { it[targetIndex] = newMessage }
    }

    // ---------------------------------------------------------------- 抓页面

    private fun fetchNote(link: String): List<UIMessagePart>? {
        val html = httpGet(link) ?: return null
        val stateJson = extractInitialState(html) ?: run {
            AppLogBuffer.log(TAG, "no __INITIAL_STATE__, len=${html.length}")
            return null
        }

        val state = try {
            json.parseToJsonElement(stateJson).jsonObject
        } catch (e: Exception) {
            AppLogBuffer.log(TAG, "state parse failed: ${e.message}")
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

        /*
         * 【配图 + 实况 · 2026-11-04 重构】
         *
         * 老版只收集 url 字符串，实况那半（livePhoto / stream）一个字没碰 —— 所以一条
         * "6 张里 5 张会动"的笔记进来，界面上一张都不会动。
         *
         * 现在优先解析 deep.imageList（只有它带 livePhoto 和 stream），
         * 每张一并取出实况那段 mp4；老结构 preload.imagesList 只当兜底（它没有实况）。
         */
        val parsed = mutableListOf<NoteImage>()
        deep?.get("imageList")?.let { arr ->
            runCatching {
                arr.jsonArray.forEach { el ->
                    val o = el.jsonObject
                    val u = o.str("urlDefault") ?: o.str("url") ?: o.str("urlPre")
                    if (!u.isNullOrBlank()) {
                        // 实况视频：stream.h264[0].masterUrl（h265 / av1 实测都是空数组）
                        val live = runCatching {
                            o.get("stream")?.jsonObject
                                ?.get("h264")?.jsonArray
                                ?.firstOrNull()?.jsonObject
                                ?.str("masterUrl")
                        }.getOrNull()
                        parsed.add(
                            NoteImage(
                                url = normalizeUrl(u),
                                liveVideo = live?.takeIf { it.isNotBlank() }?.let { normalizeUrl(it) },
                            )
                        )
                    }
                }
            }.onFailure { AppLogBuffer.log(TAG, "imageList parse: ${it.javaClass.simpleName}") }
        }
        // 兜底：老结构（normalNotePreloadData.imagesList），只有第一张、没有实况
        if (parsed.isEmpty()) {
            preload?.get("imagesList")?.let { arr ->
                runCatching {
                    arr.jsonArray.forEach { el ->
                        val o = el.jsonObject
                        val u = o.str("url") ?: o.str("urlDefault") ?: o.str("urlPre")
                        if (!u.isNullOrBlank()) parsed.add(NoteImage(normalizeUrl(u)))
                    }
                }
            }
        }
        // 去重（按 url）+ 限数
        val items = parsed.distinctBy { it.url }.take(MAX_IMAGES)
        val urls = items.map { it.url }
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
        // 【实况 · 2026-11-04】得让模型（也就是猫自己）知道这些图里有会动的
        val liveCount = items.count { it.isLive }
        if (liveCount > 0) sb.append("（其中 ").append(liveCount).append(" 张是实况，会动）")
        sb.append('\n')
        if (noteId.isNotBlank()) {
            sb.append("链接：https://www.xiaohongshu.com/explore/").append(noteId).append('\n')
        }
        sb.append("</xhs_note>")
        parts.add(UIMessagePart.Text(sb.toString()))

        // 下载配图 → Image part（能走后面已有的转述链路）
        val saved = downloadImages(items)
        // 【多图缩略图 · 2026-10-29 宝要的】把全部图的本地路径塞进第一张卡的 metadata。
        // 为什么不全塞进 parts 让界面自己排：那样卡下面会跟着冒出两三张大图，
        // 一条笔记占掉大半屏。现在 part 只留第一张当封面，其余路径挂在卡里，
        // 展开的时候横着排一行小图（宝原话：「缩略图」）。
        val allImageUrls = saved.map { "file://${it.first.absolutePath}" }
        // 【实况 · 2026-11-04】跟 allImageUrls 严格同位序，不是实况的位置放空串。
        // 为什么同位序而不是只列实况的：渲染层拿 linkImages[i] 和这里[i] 对一对就知道哪张会动，
        // 不用再存一个"第几张"的索引表（多一张表就多一处能对不上的地方）。
        val allLive = saved.map { it.second.liveVideo ?: "" }
        // 【链接卡 · 2026-10-29 宝要的「左图右两行」】第一张带上卡片标记：
        // 界面把它画成一条横卡（缩略图＋标题＋域名），不再画大图。
        // 只把封面那张放进消息流；其余只在卡的 metadata 里（展开时横排）。
        // 全塞进 parts 的话，卡下面会跟着冒出两三张大图，一条笔记占掉大半屏。
        saved.firstOrNull()?.let { pair ->
            val f = pair.first
            parts.add(
                UIMessagePart.Image(
                    url = "file://${f.absolutePath}",
                    metadata = buildJsonObject {
                        put(META_LINK_URL, link)
                        put(META_LINK_SOURCE, "xhs")
                        if (title.isNotBlank()) put(META_LINK_TITLE, title)
                        // 全部配图（含封面自己）：展开时横排用；模型那边也靠它知道这条笔记有几张图
                        if (allImageUrls.size > 1) {
                            put(META_LINK_IMAGES, allImageUrls.joinToString("##"))
                        }
                        // 【实况 · 2026-11-04】一张实况都没有就不写这个键（别给老消息平白多层字段）
                        if (allLive.any { it.isNotBlank() }) {
                            put(META_LINK_LIVE, allLive.joinToString("##"))
                        }
                        // 【原地展开 · 2026-10-29】作者 + 正文也存进卡里：
                        // 渲染层会跳过那段 <xhs_note> 文本，这里补一份"给人看的"，点开原地展开。
                        if (author.isNotBlank() || desc.isNotBlank()) {
                            val body = buildString {
                                if (author.isNotBlank()) append(author).append('\n')
                                if (desc.isNotBlank()) {
                                    append(if (desc.length > 400) desc.take(400) + "…" else desc)
                                }
                            }.trim()
                            if (body.isNotBlank()) put("linkBody", body)
                        }
                    },
                )
            )
        }
        if (saved.isNotEmpty()) {
            AppLogBuffer.log(
                TAG,
                "images saved: ${saved.size}/${urls.size} live=${saved.count { it.second.isLive }}"
            )
        }

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
                AppLogBuffer.log(TAG, "http ${conn.responseCode} for $url")
                return null
            }
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            AppLogBuffer.log(TAG, "httpGet ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * 下载配图到 cacheDir/xhs_images，返回「落地文件 + 它对应的那张图」。
     *
     * 【2026-11-04 为什么要把 NoteImage 带回来】原来是 List<File>，下载失败的会被跳过 ——
     * 于是"第几个文件"跟"第几张图"就对不上了。平时没人在乎（反正都是一排缩略图），
     * 但实况要看位序：第 3 张的地址一旦错位到第 1 张，界面就会拿一张图去演另一张的动。
     * 现在整条一起带回来，位序天然对齐，失败的那张自己消失，不会顶掉别人的位置。
     */
    private fun downloadImages(items: List<NoteImage>): List<Pair<File, NoteImage>> {
        if (items.isEmpty()) return emptyList()
        val dir = File(get<Context>().cacheDir, "xhs_images").apply { mkdirs() }
        val out = mutableListOf<Pair<File, NoteImage>>()
        items.forEachIndexed { idx, item ->
            val f = try {
                downloadOne(item.url, dir, idx)
            } catch (e: Exception) {
                AppLogBuffer.log(TAG, "img[$idx] ${e.javaClass.simpleName}: ${e.message}")
                null
            }
            if (f != null) out.add(f to item)
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
                AppLogBuffer.log(TAG, "img[$idx] http ${conn.responseCode}")
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
