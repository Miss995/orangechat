/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant

class TransformerContext(
    val context: Context,
    val model: Model,
    val assistant: Assistant,
    val settings: Settings,
    val processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
    val workspaceCwd: String? = null,
    /**
     * 【消息引用修复 · 2026-10-10 宝发现】按 id 从「整个会话」里找一条消息。
     *
     * 为什么需要它：QuotedMessageTransformer 原来只在 messages 里找被引的那条，
     * 而会话有几百条、请求里只有最近 30 条 —— 被引那条一旦滚出请求范围就找不到了。
     * （2026-09-22 把它从 ChatService 挪进 transformer 时，查找范围从「整个会话」
     *   缩成了「本次请求」，因为 transformer 只拿得到 messages、够不着 conversation。）
     *
     * 聊天链路会传一个从 conversation.messageNodes 按 id 查的 lambda；
     * 不传（主动消息等）则回退到只在 messages 里找，行为跟以前一致。
     *
     * 参数用 id 的字符串形式：省掉跨模块 import Uuid，两个模块都能直接用。
     */
    val quotedLookup: (suspend (String) -> UIMessage?)? = null,

    /**
     * 【媒体贴回会话 · 2026-10-28 宝要的】transformer 抓到图片/封面后，把它交出去。
     *
     * 为什么需要：transformer 的产出只活在"这一次请求"里（跟时间注入、召回块一样），
     * 界面上看不见。要让那张封面卡在聊天里也显示，得把它写回会话的那条消息。
     *
     * 参数 = (消息 id, 要贴上去的 part)。聊天链路会传一个"贴到那条 node 上并存库"的
     * lambda；不传（主动消息等）就什么都不做，行为跟以前一致。
     */
    val onMediaAttached: (suspend (String, UIMessagePart) -> Unit)? = null,
)

interface MessageTransformer {
    /**
     * 消息转换器，用于对消息进行转换
     *
     * 对于输入消息，消息会转换被提供给API模块
     *
     * 对于输出消息，会对消息输出chunk进行转换
     */
    suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages
    }
}

interface InputMessageTransformer : MessageTransformer

interface OutputMessageTransformer : MessageTransformer {
    /**
     * 一个视觉的转换，例如转换think tag为reasoning parts
     * 但是不实际转换消息，因为流式输出需要处理消息delta chunk
     * 不能还没结束生成就transform，因此提供一个visualTransform
     */
    suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages
    }

    /**
     * 消息生成完成后调用
     */
    suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages
    }
}

suspend fun List<UIMessage>.transforms(
    transformers: List<MessageTransformer>,
    context: Context,
    model: Model,
    assistant: Assistant,
    settings: Settings,
    processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
    workspaceCwd: String? = null,
    // 【消息引用修复 · 2026-10-10】按 id 从整个会话里找消息的口子（见 TransformerContext.quotedLookup）
    quotedLookup: (suspend (String) -> UIMessage?)? = null,
    // 【媒体贴回会话 · 2026-10-28】抓到封面/图后交给外部写回会话（见 TransformerContext.onMediaAttached）
    onMediaAttached: (suspend (String, UIMessagePart) -> Unit)? = null,
): List<UIMessage> {
    val ctx = TransformerContext(
        context = context,
        model = model,
        assistant = assistant,
        settings = settings,
        processingStatus = processingStatus,
        workspaceCwd = workspaceCwd,
        quotedLookup = quotedLookup,
        onMediaAttached = onMediaAttached,
    )
    return transformers.fold(this) { acc, transformer ->
        transformer.transform(ctx, acc)
    }
}

suspend fun List<UIMessage>.visualTransforms(
    transformers: List<MessageTransformer>,
    context: Context,
    model: Model,
    assistant: Assistant,
    settings: Settings,
): List<UIMessage> {
    val ctx = TransformerContext(context, model, assistant, settings)
    return transformers.fold(this) { acc, transformer ->
        if (transformer is OutputMessageTransformer) {
            transformer.visualTransform(ctx, acc)
        } else {
            acc
        }
    }
}

suspend fun List<UIMessage>.onGenerationFinish(
    transformers: List<MessageTransformer>,
    context: Context,
    model: Model,
    assistant: Assistant,
    settings: Settings,
): List<UIMessage> {
    val ctx = TransformerContext(context, model, assistant, settings)
    return transformers.fold(this) { acc, transformer ->
        if (transformer is OutputMessageTransformer) {
            transformer.onGenerationFinish(ctx, acc)
        } else {
            acc
        }
    }
}
