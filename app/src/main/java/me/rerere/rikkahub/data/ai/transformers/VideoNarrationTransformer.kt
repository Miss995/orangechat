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

        return withContext(Dispatchers.IO) {
            try {
                ctx.processingStatus.value = "正在转述视频..."
                messages.map { message ->
                    message.copy(
                        parts = message.parts.map { part ->
                            when {
                                // 最外层视频: 转述成文字
                                part is UIMessagePart.Video && part.url.startsWith("file:") -> {
                                    UIMessagePart.Text(performNarration(part))
                                }

                                // Tool.output 里的视频: 递归扫描, 把视频替换成转述文字
                                part is UIMessagePart.Tool -> {
                                    part.copy(
                                        output = part.output.map { outputPart ->
                                            when {
                                                outputPart is UIMessagePart.Video && outputPart.url.startsWith("file:") -> {
                                                    UIMessagePart.Text(performNarration(outputPart))
                                                }
                                                else -> outputPart
                                            }
                                        }
                                    )
                                }

                                else -> part
                            }
                        }
                    )
                }
            } finally {
                ctx.processingStatus.value = null
            }
        }
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
        val model = settings.findModelById(settings.ocrModelId) ?: return "[Video]"
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
