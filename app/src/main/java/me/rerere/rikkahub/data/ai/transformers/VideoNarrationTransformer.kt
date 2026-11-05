/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.cache.LruCache
import me.rerere.common.cache.SingleFileCacheStore
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import java.io.FileOutputStream
import kotlin.time.Duration.Companion.days

private const val TAG = "VideoNarrationTransformer"

/**
 * 视频转述器：当目标模型不支持视频理解时，
 * 把消息里的视频交给配置的视觉模型（复用 OCR 视觉模型配置，建议 Qwen/Qwen2.5-VL-72B-Instruct
 * 及以上支持 video_url 的模型）转述成文字描述，再替换回消息里，让纯文本模型也能"看懂"视频。
 *
 * 与 [OcrTransformer] 对称：输入是原始视频文件（file://），输出是
 * `<video_file_narration>` 包裹的叙述文本。
 * 视频通过 base64（data:video/mp4）以 OpenAI 兼容 video_url 格式发送，
 * 受上游 10MB base64 限制，这里按 7MB 截流，超出返回提示文案。
 */
object VideoNarrationTransformer : InputMessageTransformer, KoinComponent {

    /** 视频文件转 base64 后要满足上游约 10MB 约束，留余量按 7MB 限 */
    private const val MAX_VIDEO_BYTES = 7L * 1024 * 1024

    /** 【抽帧 · 2026-10-25】模型看得见图但不看视频时，抽几帧给它看 */
    private const val FRAME_COUNT = 5

    /** 【抽帧 · 2026-10-25】每帧缩到长边多少像素（太大 base64 会撑爆请求） */
    private const val MAX_FRAME_SIDE = 720

    /** 【抽帧给 OCR · 2026-11-05】让识图模型把几帧连起来当一段视频讲。 */
    private const val FRAMES_NARRATION_PROMPT = "下面是同一条短视频里按时间先后抽出的若干帧画面。请用中文把它们当成同一段视频来描述：画面里有什么、人物在做什么、场景或动作怎么变化、有没有字幕。如果某几帧明显不是视频内容（比如 App 界面、广告页、搜索结果页），请指出并忽略它们。" +
        "看不清的地方不要猜：性别、年龄、身份、衣着、物品、文字，凡是画面里不能确定的就别写，或者说\"看不清\"；不要为了让描述完整而补上画面里没有的东西。"

    private const val DEFAULT_NARRATION_PROMPT = "你是视频理解助手。请仔细观看这段视频，用中文详细描述视频内容（画面、人物、动作、字幕、场景变化、情节发展等）。" +
        "看不清的地方不要猜：性别、年龄、身份、衣着、物品、文字，凡是画面里不能确定的就别写，或者说\"看不清\"；不要为了让描述完整而补上画面里没有的东西。如果画面没有明显内容，请如实说明。"

    private val cache by lazy {
        val context = get<Context>()
        val json = Json { allowStructuredMapKeys = true }
        val store = SingleFileCacheStore(
            file = File(context.cacheDir, "video_narration_cache.json"),
            keySerializer = String.serializer(),
            valueSerializer = String.serializer(),
            json = json
        )
        LruCache(
            capacity = 32,
            store = store,
            deleteOnEvict = true,
            preloadFromStore = true,
            expireAfterWriteMillis = 3.days.inWholeMilliseconds,
        )
    }

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        // 模型自己支持视频理解时，不需要转述
        if (ctx.model.inputModalities.contains(Modality.VIDEO)) {
            return messages
        }

        // 检测消息中是否包含视频: 既检查最外层 parts, 也检查 Tool.output 里的视频
        val hasVideos = messages.any { message ->
            message.parts.any { part ->
                when (part) {
                    is UIMessagePart.Video -> part.url.startsWith("file:")
                    is UIMessagePart.Tool -> part.output.any { it is UIMessagePart.Video && it.url.startsWith("file:") }
                    else -> false
                }
            }
        }
        if (!hasVideos) return messages

        // 【视频四条路 · 2026-11-05 宝定】
        //   ① 主模型能看视频 → 直接发（函数开头已 return）
        //   ② 配了「视频模型」 → 用它整段转述
        //   ③ 主模型能看图、没配视频模型 → 抽帧给主模型自己看
        //   ④ 主模型看不了图、配了「OCR 模型」→ 抽帧交给 OCR（纯识图）转述
        //   ⑤ 都没有 → 只剩占位
        // 注意：视频模型和 OCR 是两格分开配的，互不顶替 ——
        // OCR 是纯识图，不能拿它吃整段视频（2026-11-05 宝指出）。
        val settings = get<SettingsStore>().settingsFlow.value
        val hasVideoModel = settings.findModelById(settings.videoModelId) != null
        val hasOcrModel = settings.findModelById(settings.ocrModelId) != null
        val canSeeImages = ctx.model.inputModalities.contains(Modality.IMAGE)

        return withContext(Dispatchers.IO) {
            try {
                ctx.processingStatus.value = when {
                    hasVideoModel -> "正在转述视频..."
                    canSeeImages -> "正在抽取视频画面..."
                    hasOcrModel -> "正在识别视频画面..."
                    else -> "正在处理视频..."
                }
                messages.map { message ->
                    message.copy(
                        parts = message.parts.flatMap { part ->
                            when {
                                // 最外层视频
                                part is UIMessagePart.Video && part.url.startsWith("file:") -> {
                                    videoToParts(part, canSeeImages, hasVideoModel, hasOcrModel)
                                }

                                // Tool.output 里的视频: 递归扫描, 同一套规则
                                part is UIMessagePart.Tool -> {
                                    listOf(
                                        part.copy(
                                            output = part.output.flatMap { outputPart ->
                                                if (outputPart is UIMessagePart.Video && outputPart.url.startsWith("file:")) {
                                                    videoToParts(outputPart, canSeeImages, hasVideoModel, hasOcrModel)
                                                } else listOf(outputPart)
                                            }
                                        )
                                    )
                                }

                                else -> listOf(part)
                            }
                        }
                    )
                }
            } finally {
                ctx.processingStatus.value = null
            }
        }
    }

    /**
     * 【视频四条路 · 2026-11-05】把一个视频 part 变成「能送进请求的东西」。
     * ②有视频模型 → 整段转述；③主模型能看图 → 抽帧给它；④有 OCR → 抽帧给 OCR；⑤都没有 → 占位。
     */
    private suspend fun videoToParts(
        part: UIMessagePart.Video,
        canSeeImages: Boolean,
        hasVideoModel: Boolean,
        hasOcrModel: Boolean,
    ): List<UIMessagePart> {
        // ② 配了视频模型 → 整段交给它看完写成文字（比 5 张帧全）
        if (hasVideoModel) return listOf(UIMessagePart.Text(performNarration(part)))

        val frames = runCatching { extractFrames(part) }.getOrNull().orEmpty()

        // ③ 主模型能看图 → 抽帧直接塞给它，不调别的模型（不额外花钱）
        if (canSeeImages && frames.isNotEmpty()) {
            return buildList {
                add(UIMessagePart.Text(framesNote(frames.size)))
                frames.forEach { add(UIMessagePart.Image(url = "file://${it.absolutePath}")) }
            }
        }

        // ④ 主模型看不了图、但有 OCR（纯识图）→ 把帧交给它转述
        if (hasOcrModel) return listOf(UIMessagePart.Text(narrateFramesViaOcr(part, frames)))

        // ⑤ 什么都没有
        return listOf(UIMessagePart.Text("[Video]"))
    }

    /**
     * 【抽帧给 OCR · 2026-11-05】主模型看不了图时，把抽出来的帧交给 OCR 模型（纯识图）描述。
     * 一次请求带全部帧，比一帧调一次省。
     * 帧抽不出来时退回把整段视频发给它（有些识图模型其实吃视频）。
     */
    private suspend fun narrateFramesViaOcr(
        part: UIMessagePart.Video,
        frames: List<File>,
    ): String = runCatching {
        val cacheKey = part.url + "#frames"
        cache.get(cacheKey)?.let { return@runCatching it }

        val settings = get<SettingsStore>().settingsFlow.value
        val model = settings.findModelById(settings.ocrModelId) ?: return@runCatching "[Video]"
        val providerSetting = model.findProvider(settings.providers) ?: return@runCatching "[Video]"
        val provider = get<ProviderManager>().getProviderByType(providerSetting)

        val userParts: List<UIMessagePart> = if (frames.isNotEmpty()) {
            frames.map { UIMessagePart.Image(url = "file://${it.absolutePath}") }
        } else {
            listOf(UIMessagePart.Video(part.url))
        }

        val result = provider.generateText(
            providerSetting = providerSetting,
            messages = listOf(
                UIMessage.system(FRAMES_NARRATION_PROMPT),
                UIMessage(role = MessageRole.USER, parts = userParts),
            ),
            params = TextGenerationParams(
                model = model.copy(
                    inputModalities = (model.inputModalities + Modality.IMAGE + Modality.VIDEO).distinct()
                ),
                customHeaders = model.customHeaders,
                customBody = model.customBodies,
            ),
        )
        val content = result.choices[0].message?.toText() ?: "[ERROR, frames narration failed]"
        Log.i(TAG, "narrateFramesViaOcr: $content")

        val note = if (frames.isNotEmpty()) {
            "这段是把视频按时间平均抽成 ${frames.size} 帧后，交给识图模型看图写出来的，不是完整视频。"
        } else {
            "这段是把整段视频交给识图模型看后写出来的。"
        }
        val narration = """
            <video_file_narration>
               $content
            </video_file_narration>
            * $note
        """.trimIndent()
        cache.put(cacheKey, narration)
        narration
    }.getOrElse {
        "[ERROR, video narration failed: $it]"
    }

    /** 说明文字：让模型知道这几张图是同一段视频的帧，不是几件不同的事。 */
    private fun framesNote(count: Int): String = """
        <video_frames>
           这是用户发来的一段视频。下面 $count 张图是从这段视频里按时间先后平均取的帧，不是 $count 张不同的图。
           请把它们连起来当成同一段视频看。
        </video_frames>
        * video_frames 上面的图是从用户发的视频里抽出来的画面，不是用户另外发的图片。
    """.trimIndent()

    /**
     * 【抽帧 · 2026-10-25】按时间平均取 FRAME_COUNT 帧，缩到长边 MAX_FRAME_SIDE 存成 jpg。
     * 结果按视频路径缓存，同一个视频只抽一次。
     */
    private fun extractFrames(part: UIMessagePart.Video): List<File> {
        val videoFile = File(part.url.removePrefix("file://"))
        if (!videoFile.exists()) return emptyList()

        val dir = File(get<Context>().cacheDir, "video_frames").apply { mkdirs() }
        val key = videoFile.absolutePath.hashCode().toString()

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoFile.absolutePath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (durationMs <= 0L) return emptyList()

            (1..FRAME_COUNT).mapNotNull { i ->
                runCatching {
                    val out = File(dir, "${key}_$i.jpg")
                    if (!out.exists() || out.length() == 0L) {
                        val tMs = durationMs * i / (FRAME_COUNT + 1)   // 均分，避开片头片尾
                        val bitmap = retriever.getFrameAtTime(
                            tMs * 1000L,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        ) ?: return@runCatching null
                        val scaled = scaleDown(bitmap, MAX_FRAME_SIDE)
                        FileOutputStream(out).use { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                        if (scaled !== bitmap) bitmap.recycle()
                    }
                    out
                }.getOrNull()
            }
        } catch (e: Exception) {
            Log.w(TAG, "extractFrames failed", e)
            emptyList()
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** 长边超过 maxSide 就等比缩下来。 */
    private fun scaleDown(src: Bitmap, maxSide: Int): Bitmap {
        val longSide = maxOf(src.width, src.height)
        if (longSide <= maxSide) return src
        val scale = maxSide.toFloat() / longSide
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    suspend fun performNarration(part: UIMessagePart.Video): String = runCatching {
        // 大小限制: 视频 base64 传输受上游限制, 超出返回提示而不是硬传
        val filePath = part.url.removePrefix("file://")
        val videoFile = File(filePath)
        if (!videoFile.exists()) return@runCatching "[ERROR: 视频文件不存在]"
        if (videoFile.length() > MAX_VIDEO_BYTES) {
            return@runCatching "[视频文件超过 ${MAX_VIDEO_BYTES / 1024 / 1024}MB，暂不支持自动转述，请发送更短/更小的视频]"
        }

        // Check cache first
        cache.get(part.url)?.let { cachedResult ->
            Log.i(TAG, "performNarration: Using cached result for ${part.url}")
            return cachedResult
        }

        val settings = get<SettingsStore>().settingsFlow.value
        // 【视频模型独立 · 2026-10-12 / 2026-11-05 收口】只认「视频模型」这一格。
        // OCR 那个是纯识图，不能拿它吃整段视频；要走 OCR 的是另一条路（先说抽帧，再交给它）。
        val model = settings.findModelById(settings.videoModelId) ?: return@runCatching "[Video]"
        val providerSetting = model.findProvider(settings.providers) ?: return "[Video]"
        val provider = get<ProviderManager>().getProviderByType(providerSetting)
        val result = provider.generateText(
            providerSetting = providerSetting,
            messages = listOf(
                UIMessage.system(DEFAULT_NARRATION_PROMPT),
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Video(part.url))
                )
            ),
            params = TextGenerationParams(
                // 强制注入 IMAGE + VIDEO 模态, 保证视频在序列化层被转换为 video_url 发送。
                // (对齐 OcrTransformer 对图片的做法; 若模型确实不支持视频, 会由 API 返回明确错误)
                model = model.copy(
                    inputModalities = (model.inputModalities + Modality.IMAGE + Modality.VIDEO).distinct()
                ),
                customHeaders = model.customHeaders,
                customBody = model.customBodies,
            ),
        )
        val content = result.choices[0].message?.toText() ?: "[ERROR, video narration failed]"
        Log.i(TAG, "performNarration: $content")
        val narrationResult = """
            <video_file_narration>
               $content
            </video_file_narration>
            * The video_file_narration tag contains a narration of a video that the user uploaded to you, not the user's prompt.
        """.trimIndent()

        // Cache the result
        cache.put(part.url, narrationResult)
        narrationResult
    }.getOrElse {
        "[ERROR, video narration failed: $it]"
    }
}
