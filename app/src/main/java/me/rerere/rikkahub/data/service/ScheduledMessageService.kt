/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.ChatService
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

/**
 * 定时发送（2026-09-17 宝提出：「比较好玩」）
 *
 * 宝写一条消息 → 选个时间 → 到点自动发出去，发出来跟宝亲手发的一模一样
 * （落库、界面上出现、橘仔照常回）。
 *
 * 三块：
 *  ① 存：`ScheduledMessageStore`（SharedPreferences + JSON，一张待发清单）
 *  ② 闹：`ScheduledMessageScheduler`（AlarmManager，照抄 ProactiveMessageService 那套，
 *      含 Android 12+ 的 canScheduleExactAlarms 检查与退让）
 *  ③ 发：`ScheduledMessageReceiver` 收到闹钟 → 拉 `ScheduledMessageSendService`（前台服务，
 *      因为要在后台活着把消息发出去）→ 调 `ChatService.sendMessage` 走宝平时那条发送链路
 *
 * 开机后由 Receiver 的 BOOT_COMPLETED 分支重新排闹钟（AlarmManager 的闹钟重启会丢）。
 */
@Serializable
data class ScheduledMessage(
    val id: String,
    val conversationId: String,
    val content: String,
    /** 触发时间（epoch millis） */
    val triggerAt: Long,
    val createdAt: Long = System.currentTimeMillis(),
)

/** 待发清单的本地存储（SharedPreferences + JSON） */
object ScheduledMessageStore {
    private const val PREFS_NAME = "scheduled_message_prefs"
    private const val KEY_LIST = "scheduled_message_list"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun getAll(context: Context): List<ScheduledMessage> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LIST, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<ScheduledMessage>>(raw) }
            .getOrElse {
                Log.e("ScheduledMessage", "decode scheduled list failed", it)
                emptyList()
            }
    }

    fun saveAll(context: Context, list: List<ScheduledMessage>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LIST, json.encodeToString(list))
            .apply()
    }

    fun add(context: Context, message: ScheduledMessage) {
        saveAll(context, getAll(context).filterNot { it.id == message.id } + message)
    }

    fun remove(context: Context, id: String) {
        saveAll(context, getAll(context).filterNot { it.id == id })
    }
}

/** 排闹钟 / 取消闹钟 */
object ScheduledMessageScheduler {
    const val ACTION_SEND_SCHEDULED = "me.rerere.orangechat.SEND_SCHEDULED_MESSAGE"
    const val EXTRA_ID = "scheduled_message_id"
    private const val TAG = "ScheduledMessage"

    /** 存进清单 + 排闹钟 */
    fun schedule(context: Context, message: ScheduledMessage) {
        ScheduledMessageStore.add(context, message)
        setAlarm(context, message)
        Log.d(TAG, "scheduled ${message.id} at ${message.triggerAt}")
    }

    /** 从清单移除 + 取消闹钟 */
    fun cancel(context: Context, id: String) {
        ScheduledMessageStore.remove(context, id)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(buildPendingIntent(context, id))
        Log.d(TAG, "cancelled $id")
    }

    /** 开机后重新排（AlarmManager 的闹钟重启就没了） */
    fun rescheduleAll(context: Context) {
        ScheduledMessageStore.getAll(context).forEach { setAlarm(context, it) }
    }

    private fun setAlarm(context: Context, message: ScheduledMessage) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = buildPendingIntent(context, message.id)
        val triggerAt = message.triggerAt

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            // 没拿到「精确闹钟」权限就退让成不精确的（跟主动消息那边一个处理）
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            Log.w(TAG, "exact alarm not permitted, fallback to inexact")
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    private fun buildPendingIntent(context: Context, id: String): PendingIntent {
        val intent = Intent(context, ScheduledMessageReceiver::class.java).apply {
            action = ACTION_SEND_SCHEDULED
            putExtra(EXTRA_ID, id)
        }
        return PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

class ScheduledMessageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ScheduledMessageScheduler.ACTION_SEND_SCHEDULED -> {
                val id = intent.getStringExtra(ScheduledMessageScheduler.EXTRA_ID) ?: return
                val serviceIntent = Intent(context, ScheduledMessageSendService::class.java).apply {
                    putExtra(ScheduledMessageScheduler.EXTRA_ID, id)
                }
                context.startForegroundService(serviceIntent)
            }

            Intent.ACTION_BOOT_COMPLETED -> {
                ScheduledMessageScheduler.rescheduleAll(context)
            }
        }
    }
}

/**
 * 到点了，把这条消息发出去。
 * 前台服务（要在后台活到消息真正落库），发完就停。
 */
class ScheduledMessageSendService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        val id = intent?.getStringExtra(ScheduledMessageScheduler.EXTRA_ID)
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                if (id != null) sendScheduled(id)
            } catch (e: Exception) {
                Log.e(TAG, "scheduled message send failed, id=$id", e)
            } finally {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun sendScheduled(id: String) {
        val message = ScheduledMessageStore.getAll(this).firstOrNull { it.id == id }
        if (message == null) {
            Log.w(TAG, "scheduled message not found: $id")
            return
        }
        val chatService = GlobalContext.get().get<ChatService>()
        // 后台回合：请求编辑跳过（界面上没人在，弹出来只会卡住等确认）
        me.rerere.rikkahub.data.ai.RequestEditController.bypassNextRequestEdit = true
        // 走宝平时发消息那条链路：落库 + 界面显示 + 橘仔照常回
        chatService.sendMessage(
            conversationId = Uuid.parse(message.conversationId),
            content = listOf(UIMessagePart.Text(message.content)),
        )
        ScheduledMessageStore.remove(this, id)
        Log.d(TAG, "scheduled message sent: $id")
    }

    private fun buildNotification(): android.app.Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "定时消息",
                NotificationManager.IMPORTANCE_MIN
            )
            manager.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("定时消息")
            .setContentText("正在把预约的消息发出去…")
            .setSmallIcon(R.drawable.small_icon)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val TAG = "ScheduledMessage"
        private const val CHANNEL_ID = "scheduled_message_channel"
        private const val NOTIFICATION_ID = 20001
    }
}
