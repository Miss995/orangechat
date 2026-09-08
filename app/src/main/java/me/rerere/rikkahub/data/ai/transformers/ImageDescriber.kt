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
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.ai.AppLogBuffer
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File

private const val TAG = "ImageDescriber"

/**
 * 图片转述器（给"云端落库"用，2026-09-08 宝拍板：图片消息带文字脸）。
 *
 * 背景：宝发真图时，外置记忆库落库只提取 Text/Voice 部件，Image 部件被丢掉——
 * 云端纯文字库里的图片记录是"哑巴"（归档总结看不到图、记忆召回搜不到图内容）。
 * 表情包/网络图走 Text 里的 markdown 链接本来就落库原文，只有真图（file://）需要文字脸。
 *
 * 与 [OcrTransformer]/[VideoNarrationTransformer] 对称：复用宝配置的 OCR 视觉模型
 * （settings.ocrModelId，现配 Qwen3-VL-32B），把图片内容转述成中文描述文字，
 * 由 ChatService 拼进落库消息文本。转述失败/无视觉模型时返回 null（调用方给 [图片] 兜底，
 * 至少留个存在标记，比现在完全丢强）。
 */
object ImageDescriber : KoinComponent {

    /** 图片 base64 上游约 10MB 约束，留余量按 7MB 限（对齐视频转述器） */
    private const val MAX_IMAGE_BYTES = 7L * 1024 * 1024

    private const val DESCRIBE_PROMPT =
        "你是图片理解助手。请仔细看这张图片，用中文详细描述图片内容" +
            "（画面主体、场景、人物动作、文字内容、氛围等，一两句话讲清楚这张图是什么）。" +
            "如果图片没有明显内容，请如实说明。"

    /**
     * 转述一张真图（file://）成文字描述。
     * @return 描述文本；非 file://（网络图/表情包）、文件缺失/超大、无视觉模型或转述失败 → null
     */
    suspend fun describe(imageUrl: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            if (!imageUrl.startsWith("file:")) return@runCatching null
            val file = File(imageUrl.removePrefix("file://"))
            if (!file.exists() || file.length() > MAX_IMAGE_BYTES) return@runCatching null

            val settings = get<SettingsStore>().settingsFlow.value
            val model = settings.findModelById(settings.ocrModelId) ?: return@runCatching null
            val providerSetting = model.findProvider(settings.providers) ?: return@runCatching null
            val provider = get<ProviderManager>().getProviderByType(providerSetting)

            val result = provider.generateText(
                providerSetting = providerSetting,
                messages = listOf(
                    UIMessage.system(DESCRIBE_PROMPT),
                    UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Image(imageUrl))
                    )
                ),
                params = TextGenerationParams(
                    // 强制注入 IMAGE 模态，保证图片在序列化层被转换为 data URI 发送
                    // （对齐 OcrTransformer 对图片的做法；若模型确实不支持图片，会由 API 返回明确错误）
                    model = model.copy(
                        inputModalities = (model.inputModalities + Modality.IMAGE).distinct()
                    ),
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                ),
            )
            val content = result.choices[0].message?.toText()?.trim()
            if (!content.isNullOrBlank()) {
                Log.i(TAG, "describe: $content")
                AppLogBuffer.log(TAG, "ImageDescriber ok: ${content.take(80)}")
            }
            content
        }.onFailure { e ->
            Log.w(TAG, "describe failed: $imageUrl", e)
            AppLogBuffer.log(TAG, "ImageDescriber FAILED: ${e.javaClass.simpleName}: ${e.message}")
        }.getOrNull()
    }
}
