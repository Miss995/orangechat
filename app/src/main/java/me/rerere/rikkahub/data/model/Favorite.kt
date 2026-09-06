/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

@Serializable
enum class FavoriteType(val value: String) {
    @SerialName("node")
    NODE("node"),

    // Keep old value for compatibility with existing data.
    @SerialName("message")
    MESSAGE("message");

    companion object {
        fun fromValue(value: String): FavoriteType? = entries.firstOrNull { it.value == value }
    }
}

@Serializable
data class FavoriteMeta(
    val title: String? = null,
    val subtitle: String? = null,
    val previewText: String? = null,
    // 五感记忆库 V1（2026-09-06）：橘仔收藏专用——收藏理由（灵魂）+ 对应五感标签
    val reason: String? = null,
    val senses: List<String>? = null,
)

@Serializable
data class NodeFavoriteRef(
    val conversationId: Uuid,
    val nodeId: Uuid,
)

data class NodeFavoriteTarget(
    val conversationId: Uuid,
    val conversationTitle: String,
    val nodeId: Uuid,
    val node: MessageNode,
)

fun UIMessage.buildFavoritePreview(maxLength: Int = 160): String {
    // 五感记忆库 V1（2026-09-06）：快照提取对图片/语音/音视频友好——
    // 纯语音条、纯图片消息不再 fallback 成 "[User Message]"。
    val lines = parts.mapNotNull { part ->
        when (part) {
            is UIMessagePart.Text -> part.text.trim().takeIf { it.isNotBlank() }
            is UIMessagePart.Image -> "[图片] ${part.url}"
            is UIMessagePart.VoiceMessage ->
                if (part.transcript.isNotBlank()) "[语音] ${part.transcript.trim()}" else "[语音消息]"
            is UIMessagePart.Audio -> "[音频]"
            is UIMessagePart.Video -> "[视频]"
            is UIMessagePart.Document -> "[文件] ${part.url}"
            else -> null
        }
    }
    val joined = lines.joinToString("\n").trim()
    if (joined.isNotBlank()) {
        return joined.take(maxLength)
    }
    return when (role) {
        MessageRole.USER -> "[User Message]"
        MessageRole.ASSISTANT -> "[Assistant Message]"
        MessageRole.SYSTEM -> "[System Message]"
        MessageRole.TOOL -> "[Tool Message]"
    }
}

fun MessageNode.buildFavoritePreview(maxLength: Int = 160): String {
    return currentMessage.buildFavoritePreview(maxLength)
}
