package me.rerere.rikkahub.data.ai.transformers

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.AppLogBuffer
import me.rerere.rikkahub.data.ai.tools.bestCover
import me.rerere.rikkahub.data.ai.tools.fetchAweme
import me.rerere.rikkahub.data.ai.tools.resolveAwemeId

/*
 * 【抖音链接 → 封面卡 · 2026-10-28 宝要的】
 *
 * 宝的原话：「能不能发享视频链接像普通视频一样，放个封面，然后点击播放啊」
 *          「普通视频是原地起播放器」（那套 10-27 已经有 VideoBubble 了）
 *
 * 所以这里只干最轻的一件事：把链接换成一张带标记的封面图。
 * 不下载视频、不解析直链（那是点开那一瞬间的事），所以发完是秒出卡片。
 *
 * ★ 为什么走 Image + metadata 而不是 Video part：
 *   Video part 的封面是 VideoBubble 自己从视频里抽第一帧 —— 那得先有视频文件。
 *   我们手上只有链接，所以先用封面图顶上；点开时再去解析真地址。
 *
 * ★ 三条纪律（跟 XhsLinkTransformer 一致）：
 *   ① 只处理最后一条真带链接的 USER 消息 —— 注意末尾还挂着系统时间注入，
 *      它也是 USER 角色，所以必须按"内容里有没有链接"找，不能按"最后一条 USER"找。
 *      （2026-10-25 就这么栽过一次，一次都没跑成，连日志都没有）
 *   ② 抓不到、超时、结构变了 —— 一律原样放行，绝不让一次失败阻塞对话。
 *   ③ 同一个链接抓过一次就记着（成功记结果，失败也记一笔），不重复抓。
 */
object DyLinkTransformer : InputMessageTransformer {

    private const val TAG = "DyLink"

    /** metadata 键：这张图对应哪个视频链接 / 从哪来 */
    const val META_VIDEO_LINK = "videoLink"
    const val META_VIDEO_SOURCE = "videoSource"

    /** 【链接卡 · 2026-10-29 宝要的「左图右两行」】卡片第二行那个标题 */
    const val META_LINK_TITLE = "linkTitle"

    /** 匹配抖音短链 v.douyin.com/xxx，以及 www.douyin.com / iesdouyin.com 长链 */
    private val linkRegex = Regex(
        """https?://(?:v\.douyin\.com/[A-Za-z0-9\-_]+|(?:www\.)?douyin\.com/[A-Za-z0-9\-_/?=&%.]+|(?:www\.)?iesdouyin\.com/[A-Za-z0-9\-_/?=&%.]+)"""
    )

    /** 抓过的链接缓存（link → 封面 part；value 为 null 表示抓过但失败） */
    private val cache = LinkedHashMap<String, UIMessagePart.Image?>()

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        // ① 从后往前找「第一条文字里真含抖音链接的 USER 消息」
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

        // 已经有封面卡了就别重复加（同一条消息可能被 transform 多次）
        val alreadyHasCard = target.parts.any { p ->
            p is UIMessagePart.Image && p.metadata?.containsKey(META_VIDEO_LINK) == true
        }
        if (alreadyHasCard) return messages

        val link = target.parts
            .asSequence()
            .filterIsInstance<UIMessagePart.Text>()
            .flatMap { part -> linkRegex.findAll(part.text).map { it.value } }
            .firstOrNull()
            ?: return messages

        // ② 抓封面（缓存里试过的直接拿结论）
        val cover: UIMessagePart.Image? = if (cache.containsKey(link)) {
            cache[link]
        } else {
            val fetched = try {
                fetchCover(link, extractTitle(target, link))
            } catch (e: Exception) {
                AppLogBuffer.log(TAG, "fetch FAILED: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
            cache[link] = fetched
            if (fetched == null) {
                AppLogBuffer.log(TAG, "no cover, pass through: $link")
            } else {
                AppLogBuffer.log(TAG, "cover ok: $link")
            }
            fetched
        }
        if (cover == null) return messages

        // ③ 塞进那条消息末尾（这一份只喂模型，不落库）
        val newParts = target.parts + cover
        val result = messages.toMutableList().also { it[targetIndex] = target.copy(parts = newParts) }

        // ④ 【2026-10-28 宝：界面上也得看得见】把封面贴回会话那条消息，让卡片在聊天里真的出现。
        //    失败只记一笔，不影响这一轮对话。
        runCatching { ctx.onMediaAttached?.invoke(target.id.toString(), cover) }
            .onFailure { AppLogBuffer.log(TAG, "attach to conversation FAILED: ${it.javaClass.simpleName}: ${it.message}") }

        return result
    }

    /**
     * 【链接卡 · 2026-10-29】从分享文本里切出标题。
     *
     * 抖音分享文本的形状：`6.15 复制打开抖音，看看【某某的作品】正文… https://v.douyin.com/xxx/ 乱码口令`
     *   → 链接前面那段 = 标题（还要洗掉开头那句固定前缀、结尾的省略号）
     *   → 链接后面那段 = 口令，一律不要
     */
    private fun extractTitle(msg: UIMessage, link: String): String {
        val text = msg.parts.filterIsInstance<UIMessagePart.Text>()
            .joinToString(" ") { it.text }
        val idx = text.indexOf(link)
        if (idx <= 0) return ""
        var head = text.substring(0, idx).trim()
        head = head.replace(Regex("""^\d+(\.\d+)?\s*复制打开抖音[，,]\s*看看"""), "")
        head = head.replace(Regex("""^复制打开抖音[，,]\s*看看"""), "")
        head = head.trim().trimEnd('…', '.', ' ', '\u2026')
        return head.take(80)
    }

    /**
     * 拿封面：抽 aweme_id → 调 Feed 接口 → 取封面地址。
     * 全程只走接口，不开浏览器（分享页是 _$jsvmprt 反爬虚拟机，body 是空的）。
     */
    private suspend fun fetchCover(link: String, title: String): UIMessagePart.Image? {
        val id = resolveAwemeId(link) ?: run {
            AppLogBuffer.log(TAG, "aweme_id 抽不出来: $link")
            return null
        }
        val aweme = fetchAweme(id) ?: run {
            AppLogBuffer.log(TAG, "feed 拿不到: id=$id")
            return null
        }
        val coverUrl = bestCover(aweme) ?: run {
            AppLogBuffer.log(TAG, "这条没有封面字段: id=$id")
            return null
        }
        return UIMessagePart.Image(
            url = coverUrl,
            metadata = buildJsonObject {
                put(META_VIDEO_LINK, link)
                put(META_VIDEO_SOURCE, "douyin")
                if (title.isNotBlank()) put(META_LINK_TITLE, title)
            },
        )
    }
}
