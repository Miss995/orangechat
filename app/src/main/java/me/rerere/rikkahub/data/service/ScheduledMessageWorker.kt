/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * 定时消息的 WorkManager 兜底（2026-09-17 宝点破）。
 *
 * 起因：宝用 OPPO 试定时消息，发现「退到后台就不发，得等 App 转前台才发」。
 * 原因是 ColorOS 会把后台应用冻住（cached app freezer），闹钟到点了但冻住的进程跑不动，
 * 等 App 被打开、解冻了，系统才把欠着的闹钟补投过来。
 *
 * 主动消息那边早有对策：闹钟之外再排一个 WorkManager（系统级调度器，进程被杀/设备重启
 * 后仍会执行）。这里照搬，作为第二道保险。两道都到也没关系 ——
 * ScheduledMessageStore.removeIfPresent 会原子地取走任务，只有一个能拿到。
 */
class ScheduledMessageWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ScheduledMessage"
        private const val KEY_ID = "id"

        private fun uniqueName(id: String) = "scheduled_message_work_$id"

        fun schedule(context: Context, message: ScheduledMessage) {
            val delay = (message.triggerAt - System.currentTimeMillis()).coerceAtLeast(0L)
            val request = OneTimeWorkRequestBuilder<ScheduledMessageWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_ID to message.id))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueName(message.id),
                ExistingWorkPolicy.REPLACE,
                request,
            )
            Log.d(TAG, "WorkManager fallback scheduled for ${message.id}, delay=${delay}ms")
        }

        fun cancel(context: Context, id: String) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(id))
        }
    }

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.success()
        val ctx = applicationContext

        // 幂等：清单里已经没有这条 = 闹钟那边已经发过了
        if (ScheduledMessageStore.getAll(ctx).none { it.id == id }) {
            Log.d(TAG, "WorkManager: $id already sent, skip")
            return Result.success()
        }

        return try {
            val intent = Intent(ctx, ScheduledMessageSendService::class.java).apply {
                putExtra(ScheduledMessageScheduler.EXTRA_ID, id)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
            Log.d(TAG, "WorkManager fired send service for $id")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "WorkManager fallback failed for $id", e)
            Result.retry()
        }
    }
}
