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
    // 【引用一句 2026-10-10】宝长按选中一段文字后引用的那句原文（原样存下来，那条消息后来被编辑也不怕）。
    // null = 引用整条（走老路，靠 quotedMessageId 反查）；非 null = 只引用这一句。
    var quotedText by mutableStateOf<String?>(null)
    // 【引用一句 2026-10-10】刚选中、还没点"引用这句"的一段文字（输入框上方那条提示用）。
    // 有它 = 提示条出现；点确认才搬进 quotedText/quotedMessageId，取消就清掉。
    var pickedText by mutableStateOf<String?>(null)
    // 上面那段文字属于哪条消息（点了才真引用）
    var pickedMessageId by mutableStateOf<Uuid?>(null)
    // 【插话补充 · 2026-10-13 宝定的】这条要发的话，是不是"补上文里她自己那条"（不是新话题）。
    // 默认 false（= 新话题）；宝按一下输入框上方那条开关才置 true。发送时写进消息的 interjectKind。
    var interjectSupplement by mutableStateOf(false)
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
        quotedText = null
        pickedText = null
        pickedMessageId = null
        interjectSupplement = false
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

    /**
     * 输入框里有没有「可以发出去的东西」。
     * 【图片单独发 2026-10-13 宝的需求】以前只看文字，所以「一张图没配字」会被当成空的 ——
     * 发送键点不动、回车也不发。现在把附件也算进来：有图（或视频/语音/文件）就算有内容。
     */
    fun isEmpty(): Boolean {
        return textContent.text.isEmpty() && messageContent.isEmpty()
    }

    fun addImages(uris: List<Uri>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach { uri ->
            // 【表情包 · 2026-10-24】从表情包面板挑出来的图，本来就有描述（存的时候跑过一次）。
            // 挂到 metadata 上，OcrTransformer 的 noteFor() 会先读它，不再为同一张图反复
            // 跑视觉模型（宝算过账：一次「发出去 -> 她回一张」花了四趟钱）。
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
        // 【表情包 · 2026-10-24】filesDir/stickers 里的是"原件"（表情包本体），
        // 不是 App 复制出来的临时副本 —— 取消附件不能连它一起删掉。
        // bug 现场：面板点一张 → 变成附件 → 取消 → 那张从面板里永远消失，就是这一行干的。
        if (url.contains("/stickers/")) return false
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
