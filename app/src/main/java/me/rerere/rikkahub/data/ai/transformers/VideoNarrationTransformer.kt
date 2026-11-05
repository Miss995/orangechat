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

    private const val DEFAULT_NARRATION_PROMPT = "你是视频理解助手。请仔细观看这段视频，用中文详细描述视频内容（画面、人物、动作、字幕、场景变化、情节发展等）。如果画面没有明显内容，请如实说明。"

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

    /**
     * 【一律转述 · 2026-11-05】有没有配转述用的视觉模型：
     * 优先「视频模型」，没配就退回 OCR 那个（跟 performNarration 的取法保持一致）。
     */
    private fun hasNarrationModel(): Boolean {
        val settings = get<SettingsStore>().settingsFlow.value
        return settings.findModelById(settings.videoModelId) != null ||
            settings.findModelById(settings.ocrModelId) != null
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

        // 【一律转述 · 2026-11-05 宝定】只要配了转述用的视觉模型（设置里的「视频模型」，
        // 没配就退回 OCR 那个），就**不再抽帧**，一律让那个模型把整段视频看完写成文字。
        // 原因：抽帧平均只取 5 张，动作过程全丢，片尾还容易抽到 App 界面（宝 2026-11-05 实测）。
        // 抽帧只留给"没配转述模型、但主模型自己能看图"这一种情况。
        val narrationAvailable = hasNarrationModel()
        val canSeeImages = !narrationAvailable &&
            ctx.model.inputModalities.contains(Modality.IMAGE)

        return withContext(Dispatchers.IO) {
            try {
                ctx.processingStatus.value = if (canSeeImages) "正在抽取视频画面..." else "正在转述视频..."
                messages.map { message ->
                    message.copy(
                        parts = message.parts.flatMap { part ->
                            when {
                                // 最外层视频
                                part is UIMessagePart.Video && part.url.startsWith("file:") -> {
                                    videoToParts(part, canSeeImages)
                                }

                                // Tool.output 里的视频: 递归扫描, 同一套规则
                                part is UIMessagePart.Tool -> {
                                    listOf(
                                        part.copy(
                                            output = part.output.flatMap { outputPart ->
                                                if (outputPart is UIMessagePart.Video && outputPart.url.startsWith("file:")) {
                                                    videoToParts(outputPart, canSeeImages)
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
     * 【抽帧 · 2026-10-25】把一个视频 part 变成「一句说明 + N 张图」。
     * 模型看得见图 → 抽帧直发；连图都看不了 → 退回转述。
     */
    private suspend fun videoToParts(
        part: UIMessagePart.Video,
        canSeeImages: Boolean,
    ): List<UIMessagePart> {
        if (!canSeeImages) return listOf(UIMessagePart.Text(performNarration(part)))

        val frames = runCatching { extractFrames(part) }.getOrNull().orEmpty()
        if (frames.isEmpty()) {
            // 抽不出帧（文件坏 / 时长读不到）→ 退回转述，别让这条视频整个消失
            return listOf(UIMessagePart.Text(performNarration(part)))
        }

        return buildList {
            add(UIMessagePart.Text(framesNote(frames.size)))
            frames.forEach { add(UIMessagePart.Image(url = "file://${it.absolutePath}")) }
        }
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
        // 【视频模型独立 · 2026-10-12】优先用「视频模型」；没配过就退回 OCR 那个（跟升级前的行为一致）。
        val model = settings.findModelById(settings.videoModelId)
            ?: settings.findModelById(settings.ocrModelId)
            ?: return "[Video]"
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
