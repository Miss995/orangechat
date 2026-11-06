/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.cancelNotification
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

/**
 * 通知栏快捷回复接收器 · 2026-11-06
 *
 * 宝要"不用点开 App、直接在通知上回一句"。
 * 通知那边由 [NotificationUtil] 挂 RemoteInput，回复的文字由系统塞进 clipData 送过来，
 * 这里取出来直接喂给 ChatService —— 跟她在聊天框里打字是同一个入口。
 *
 * 注意：onReceive 只有几秒，不能做长活，所以真正的发送扔给 AppScope。
 */
class NotificationReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val results = RemoteInput.getResultsFromIntent(intent) ?: return
        val text = results.getCharSequence(EXTRA_RESULT_KEY)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return

        val conversationIdStr = intent.getStringExtra(EXTRA_CONVERSATION_ID) ?: return
        val conversationId = runCatching { Uuid.parse(conversationIdStr) }.getOrNull() ?: run {
            Log.e(TAG, "reply: bad conversationId=$conversationIdStr")
            return
        }

        // 回完就把那条通知收掉，别让回复框还挂在那儿
        intent.getIntExtra(EXTRA_NOTIFICATION_ID, Int.MIN_VALUE)
            .takeIf { it != Int.MIN_VALUE }
            ?.let { context.cancelNotification(it) }

        val koin = GlobalContext.get()
        val appScope = koin.getOrNull<AppScope>()
        val chatService = koin.getOrNull<ChatService>()
        if (appScope == null || chatService == null) {
            Log.e(TAG, "reply: koin not ready (scope=$appScope service=$chatService)")
            return
        }

        Log.d(TAG, "reply from notification: conversation=$conversationId len=${text.length}")
        appScope.launch {
            runCatching {
                chatService.sendMessage(
                    conversationId = conversationId,
                    content = listOf(UIMessagePart.Text(text)),
                )
            }.onFailure { Log.e(TAG, "reply send failed", it) }
        }
    }

    companion object {
        private const val TAG = "NotificationReply"

        /** 跟 RemoteReplyConfig.resultKey 保持一致 */
        const val EXTRA_RESULT_KEY = "reply_text"

        /** 回复要落进哪个对话 */
        const val EXTRA_CONVERSATION_ID = "reply_conversation_id"

        /** 回复完收掉的通知 id */
        const val EXTRA_NOTIFICATION_ID = "reply_notification_id"
    }
}
