/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.model

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.InstantSerializer
import me.rerere.rikkahub.data.ai.AppLogBuffer
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import java.time.Instant
import kotlin.uuid.Uuid

@Serializable
data class Conversation(
    val id: Uuid = Uuid.random(),
    val assistantId: Uuid,
    val title: String = "",
    val messageNodes: List<MessageNode>,
    val chatSuggestions: List<String> = emptyList(),
    val isPinned: Boolean = false,
    @Serializable(with = InstantSerializer::class)
    val createAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    val updateAt: Instant = Instant.now(),
    val customSystemPrompt: String? = null,
    // Absolute path inside the workspace rootfs
    val workspaceCwd: String? = null,
    // 所属文件夹（助手内分组），null 表示未归入任何文件夹
    val folderId: Uuid? = null,
    @Transient
    val newConversation: Boolean = false
) {
    val files: List<Uri>
        get() = messageNodes
            .flatMap { node -> node.messages.flatMap { it.parts } }
            .collectAllParts()
            .mapNotNull { it.fileUri() }

    /**
     *  当前选中的 message
     */
    val currentMessages
        get(): List<UIMessage> {
            return messageNodes.map { node -> node.messages[node.selectIndex] }
        }

    fun getMessageNodeByMessage(message: UIMessage): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.contains(message) }
    }

    fun getMessageNodeByMessageId(messageId: Uuid): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.any { it.id == messageId } }
    }

    fun updateCurrentMessages(messages: List<UIMessage>): Conversation {
        // 【按 id 对齐 · 2026-10-08】原版按"位置"对齐（messages[index] ↔ newNodes[index]），
        // 前提是"列表和节点树条数完全一致"。而插话那套（请求时拆开 / 流式时合回）会改条数，
        // 只改列表、不动节点树 → 一旦差上几条，从差的位置起每条都被串到后一格，
        // 表现就是"同一句出现两三次 + 时间顺序乱"（宝 2026-10-08 实测，796 格中招）。
        //
        // 改成按 id 找家：命中的回它自己那格（两边条数不等也不会互相带歪）；
        // 找不到的（真·新消息）紧跟在上一条之后插一格。
        val idToNode = HashMap<String, Int>()
        messageNodes.forEachIndexed { i, node ->
            node.messages.forEach { m -> idToNode[m.id.toString()] = i }
        }

        val newNodes = messageNodes.toMutableList()
        val strayAppends = StringBuilder()
        var lastIndex = -1

        messages.forEach { message ->
            val known = idToNode[message.id.toString()]
            if (known != null && known <= newNodes.lastIndex) {
                val node = newNodes[known]
                val nm = node.messages.toMutableList()
                val j = nm.indexOfFirst { it.id == message.id }
                if (j >= 0) {
                    nm[j] = message
                    newNodes[known] = node.copy(messages = nm)
                }
                lastIndex = known
            } else {
                // 新消息：插在"上一条"之后（按位置对齐最容易在这里串位）
                val insertAt = (if (lastIndex < 0) 0 else lastIndex + 1)
                    .coerceIn(0, newNodes.size)
                newNodes.add(insertAt, message.toMessageNode())
                // 插入点之后的旧下标全部 +1
                for (k in insertAt until newNodes.size) {
                    newNodes[k].messages.forEach { m -> idToNode[m.id.toString()] = k }
                }
                lastIndex = insertAt
                if (strayAppends.isNotEmpty()) strayAppends.append(' ')
                strayAppends.append(
                    "+${message.id.toString().take(8)}@$insertAt(${message.role}:" +
                        message.parts.joinToString("") { it::class.simpleName?.take(4) ?: "?" } +
                        ")"
                )
            }
        }

        if (strayAppends.isNotEmpty()) {
            AppLogBuffer.log(
                "ConvUpd",
                "NEWBYID list=${messages.size} nodes=${messageNodes.size} at=$strayAppends"
            )
        }

        return this.copy(
            messageNodes = newNodes
        )
    }

    companion object {
        fun ofId(
            id: Uuid,
            assistantId: Uuid = DEFAULT_ASSISTANT_ID,
            messages: List<MessageNode> = emptyList(),
            newConversation: Boolean = false
        ) = Conversation(
            id = id,
            assistantId = assistantId,
            messageNodes = messages,
            newConversation = newConversation,
        )
    }
}

@Serializable
data class MessageNode(
    val id: Uuid = Uuid.random(),
    val messages: List<UIMessage>,
    val selectIndex: Int = 0,
    @Transient
    val isFavorite: Boolean = false,
) {
    val currentMessage get() = if (messages.isEmpty() || selectIndex !in messages.indices) {
        throw IllegalStateException("MessageNode has no valid current message: messages.size=${messages.size}, selectIndex=$selectIndex")
    } else {
        messages[selectIndex]
    }

    val role get() = messages.firstOrNull()?.role ?: MessageRole.USER

    companion object {
        fun of(message: UIMessage) = MessageNode(
            messages = listOf(message),
            selectIndex = 0
        )
    }
}

fun UIMessage.toMessageNode(): MessageNode {
    return MessageNode(
        messages = listOf(this),
        selectIndex = 0
    )
}

/**
 * 递归展开所有 parts，包括工具调用结果中的嵌套 parts。
 */
private fun List<UIMessagePart>.collectAllParts(): List<UIMessagePart> =
    this + filterIsInstance<UIMessagePart.Tool>().flatMap { it.output.collectAllParts() }

/**
 * 提取 part 中引用的本地文件 URI，新增文件类型时只需在此处添加。
 */
private fun UIMessagePart.fileUri(): Uri? = when (this) {
    is UIMessagePart.Image -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Document -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Video -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Audio -> url.takeIf { it.startsWith("file://") }?.toUri()
    else -> null
}
