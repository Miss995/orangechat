/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.hooks

import android.net.Uri
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import me.rerere.ai.ui.UIMessagePart
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid

class ChatInputState {
    val textContent = TextFieldState()
    var messageContent by mutableStateOf(listOf<UIMessagePart>())
    var editingMessage by mutableStateOf<Uuid?>(null)
    // 【消息引用 2026-09-22】正在引用哪一条（输入框上方那条引用条的来源）。
    // 发送时写进新消息的 quotedMessageId；和"编辑"互不干扰，但发完/清空时一并清掉。
    var quotedMessageId by mutableStateOf<Uuid?>(null)
    // 【消息引用 2026-09-22】被引消息的摘要文本，只给输入框上方那条条显示用，不参与发送。
    var quotedPreview by mutableStateOf("")
    private var editingParts: List<UIMessagePart>? = null
    private var editingAttachmentUrls: Set<String> = emptySet()

    fun clearInput() {
        textContent.setTextAndPlaceCursorAtEnd("")
        messageContent = emptyList()
        editingMessage = null
        editingParts = null
        editingAttachmentUrls = emptySet()
        quotedMessageId = null
        quotedPreview = ""
    }

    fun isEditing() = editingMessage != null

    fun setMessageText(text: String) {
        textContent.setTextAndPlaceCursorAtEnd(text)
    }

    fun appendText(content: String) {
        textContent.setTextAndPlaceCursorAtEnd(textContent.text.toString() + content)
    }

    fun setContents(contents: List<UIMessagePart>) {
        val lastTextIndex = contents.indexOfLast { it is UIMessagePart.Text }
        val text = if (lastTextIndex >= 0) {
            (contents[lastTextIndex] as UIMessagePart.Text).text
        } else {
            ""
        }
        textContent.setTextAndPlaceCursorAtEnd(text)
        messageContent = contents.filter { it !is UIMessagePart.Text }
        editingParts = contents
        editingAttachmentUrls = contents.mapNotNull { it.attachmentUrlOrNull() }.toSet()
    }

    fun getContents(): List<UIMessagePart> {
        val text = textContent.text.toString()
        if (isEditing()) {
            val originalParts = editingParts
            if (originalParts != null) {
                val editedTextIndex = originalParts.indexOfLast { it is UIMessagePart.Text }
                val remainingAttachments = messageContent.toMutableList()
                val merged = mutableListOf<UIMessagePart>()

                originalParts.forEachIndexed { index, part ->
                    when {
                        index == editedTextIndex -> {
                            merged.add(UIMessagePart.Text(text))
                        }

                        part is UIMessagePart.Text -> {
                            merged.add(part)
                        }

                        else -> {
                            val currentIndex = remainingAttachments.indexOf(part)
                            if (currentIndex >= 0) {
                                merged.add(remainingAttachments.removeAt(currentIndex))
                            }
                        }
                    }
                }
                // Newly added attachments are appended in insertion order.
                merged.addAll(remainingAttachments)
                return merged
            }
            return if (text.isBlank()) messageContent else listOf(UIMessagePart.Text(text)) + messageContent
        }
        return listOf(UIMessagePart.Text(text)) + messageContent
    }

    fun isEmpty(): Boolean {
        return textContent.text.isEmpty()
    }

    fun addImages(uris: List<Uri>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach { uri ->
            // 【表情包 · 2026-10-24】从表情包面板挑出来的图，本来就有描述（存的时候跑过一次）。
            // 挂到 metadata 上，OcrTransformer 的 noteFor() 会先读它，不再为同一张图反复
            // 跑视觉模型（宝算过账：一次「发出去 → 回一张」花了四趟钱）。
            val note = stickerNoteOrNull(uri)
            val meta = note?.let { n -> buildJsonObject { put("imageNote", n) } }
            newMessage.add(UIMessagePart.Image(uri.toString(), metadata = meta))
        }
        messageContent = newMessage
    }

    /**
     * 【表情包 · 2026-10-24】只有表情包目录下的文件才有 sidecar 描述。
     * 别的图一律返回 null，交给正常的图片转述流程，行为不变。
     */
    private fun stickerNoteOrNull(uri: Uri): String? {
        if (uri.scheme != "file") return null
        val path = uri.path ?: return null
        if (!path.contains("/stickers/")) return null
        val f = java.io.File(path)
        if (!f.isFile) return null
        val note = me.rerere.rikkahub.data.sticker.StickerStore.readNote(f)
        return note?.takeIf { it.isNotBlank() && !it.startsWith("【失败】") }
    }

    fun addVideos(uris: List<Uri>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach { uri ->
            newMessage.add(UIMessagePart.Video(uri.toString()))
        }
        messageContent = newMessage
    }

    fun addAudios(uris: List<Uri>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach { uri ->
            newMessage.add(UIMessagePart.Audio(uri.toString()))
        }
        messageContent = newMessage
    }

    fun addFiles(uris: List<UIMessagePart.Document>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach {
            newMessage.add(it)
        }
        messageContent = newMessage
    }

    /**
     * 仅删除当前输入组件临时新增的本地文件。
     * 编辑历史消息时，原有附件不在这里删除，由会话层统一做差异清理。
     */
    fun shouldDeleteFileOnRemove(part: UIMessagePart): Boolean {
        val url = part.attachmentUrlOrNull() ?: return false
        if (!url.startsWith("file:")) return false
        return !isEditing() || url !in editingAttachmentUrls
    }

    private fun UIMessagePart.attachmentUrlOrNull(): String? {
        return when (this) {
            is UIMessagePart.Image -> this.url
            is UIMessagePart.Video -> this.url
            is UIMessagePart.Audio -> this.url
            is UIMessagePart.Document -> this.url
            else -> null
        }
    }
}
