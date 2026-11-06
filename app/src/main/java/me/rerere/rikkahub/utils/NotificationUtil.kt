/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.utils

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import me.rerere.rikkahub.R

/**
 * 通知构建器的配置 DSL
 */
class NotificationConfig {
    var title: String = ""
    var content: String = ""
    var subText: String? = null
    var smallIcon: Int = R.drawable.small_icon
    var autoCancel: Boolean = false
    var ongoing: Boolean = false
    var onlyAlertOnce: Boolean = false
    var category: String? = null
    var visibility: Int = NotificationCompat.VISIBILITY_PRIVATE
    var contentIntent: PendingIntent? = null
    var useBigTextStyle: Boolean = false

    // Live Update 相关
    var requestPromotedOngoing: Boolean = false
    var shortCriticalText: String? = null

    // 默认通知效果
    var useDefaults: Boolean = false

    // 【通知栏快捷回复 · 2026-11-06 宝要的】不为 null 时，通知上多一个能直接打字的回复框
    var remoteReply: RemoteReplyConfig? = null
}

/**
 * 通知栏快捷回复的配置（2026-11-06）
 *
 * [actionIntent] 必须是一个 Broadcast 的 **MUTABLE** PendingIntent，指向
 * NotificationReplyReceiver —— RemoteInput 要求 mutable：系统要往这个 intent 里塞结果，
 * 用 IMMUTABLE 会静默失效（Android 12+ 的硬要求，不报错，只是回复框点了没反应）。
 */
class RemoteReplyConfig(
    val actionLabel: String = "回复",
    val resultKey: String = "reply_text",
    val actionIcon: Int = android.R.drawable.ic_menu_send,
    val actionIntent: PendingIntent,
)

object NotificationUtil {

    /**
     * 检查是否有通知权限
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 使用 DSL 风格创建并发送通知
     *
     * @param context 上下文
     * @param channelId 通知渠道 ID
     * @param notificationId 通知 ID
     * @param config 通知配置 lambda
     * @return 是否成功发送
     */
    @SuppressLint("MissingPermission")
    fun notify(
        context: Context,
        channelId: String,
        notificationId: Int,
        config: NotificationConfig.() -> Unit
    ): Boolean {
        if (!hasNotificationPermission(context)) {
            return false
        }

        val notificationConfig = NotificationConfig().apply(config)
        val notification = buildNotification(context, channelId, notificationConfig)

        NotificationManagerCompat.from(context).notify(notificationId, notification.build())
        return true
    }

    /**
     * 构建通知
     */
    fun buildNotification(
        context: Context,
        channelId: String,
        config: NotificationConfig
    ): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, channelId).apply {
            setContentTitle(config.title)
            setContentText(config.content)
            setSmallIcon(config.smallIcon)
            setAutoCancel(config.autoCancel)
            setOngoing(config.ongoing)
            setOnlyAlertOnce(config.onlyAlertOnce)
            setVisibility(config.visibility)

            config.subText?.let { setSubText(it) }
            config.category?.let { setCategory(it) }
            config.contentIntent?.let { setContentIntent(it) }

            // 【通知栏快捷回复 · 2026-11-06】挂了远程输入，通知上就能直接打字回
            config.remoteReply?.let { reply ->
                val remoteInput = androidx.core.app.RemoteInput.Builder(reply.resultKey)
                    .setLabel(reply.actionLabel)
                    .build()
                addAction(
                    NotificationCompat.Action.Builder(
                        reply.actionIcon,
                        reply.actionLabel,
                        reply.actionIntent
                    )
                        .addRemoteInput(remoteInput)
                        .setAllowGeneratedReplies(true)
                        .build()
                )
            }

            if (config.useBigTextStyle) {
                setStyle(NotificationCompat.BigTextStyle().bigText(config.content))
            }

            if (config.useDefaults) {
                setDefaults(NotificationCompat.DEFAULT_ALL)
            }

            // Android 15+ Live Update 支持
            if (config.requestPromotedOngoing && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                setRequestPromotedOngoing(true)
            }

            // Android 16+ 状态栏 chip 文本
            if (config.shortCriticalText != null && Build.VERSION.SDK_INT >= 36) {
                setShortCriticalText(config.shortCriticalText!!)
            }
        }
    }

    /**
     * 取消通知
     */
    fun cancel(context: Context, notificationId: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId)
    }

    /**
     * 取消所有通知
     */
    fun cancelAll(context: Context) {
        NotificationManagerCompat.from(context).cancelAll()
    }
}

/**
 * Context 扩展函数，简化通知发送
 */
fun Context.sendNotification(
    channelId: String,
    notificationId: Int,
    config: NotificationConfig.() -> Unit
): Boolean = NotificationUtil.notify(this, channelId, notificationId, config)

/**
 * Context 扩展函数，取消通知
 */
fun Context.cancelNotification(notificationId: Int) {
    NotificationUtil.cancel(this, notificationId)
}
