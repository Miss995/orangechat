/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.utils

import android.content.Context
import android.util.Log
import androidx.core.content.edit

private const val TAG = "CrashHandler"
private const val PREFS_NAME = "crash_handler"
private const val KEY_CRASHED = "crashed"
private const val KEY_STACKTRACE = "stacktrace"
private const val MAX_STACKTRACE_LENGTH = 8000

object CrashHandler {
    fun install(context: Context) {
        val appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception on thread ${thread.name}", throwable)
            markCrashed(appContext, thread, throwable)
            // 【2026-08-22 升级】已知可恢复的 UI 崩溃（如长按复制跨布局层级）记录后不再闪退：
            // 吞掉 + Toast 提示，App 继续使用；其余崩溃照旧交给默认 handler。
            if (isRecoverableUiCrash(throwable)) {
                Log.w(TAG, "Recoverable UI crash swallowed, app continues", throwable)
                runCatching {
                    android.widget.Toast.makeText(
                        appContext,
                        "遇到一个小问题，已自动恢复（不影响使用）",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                return@setDefaultUncaughtExceptionHandler
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun hasCrashed(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CRASHED, false)
    }

    fun getStackTrace(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_STACKTRACE, null)
    }

    fun clearCrashed(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { remove(KEY_CRASHED).remove(KEY_STACKTRACE) }
    }

    // 已知可恢复的 UI 崩溃：Compose 文本选择在跨布局层级时抛的 IllegalArgumentException
    // （SelectionManager.convertToContainerCoordinates -> NodeCoordinator.findCommonAncestor），
    // 长按复制偶发触发（memory 87，请求日志界面/部分弹窗），吞掉后 App 状态不受影响。
    private fun isRecoverableUiCrash(throwable: Throwable): Boolean {
        val message = throwable.message ?: return false
        return throwable is IllegalArgumentException &&
            message.contains("layouts are not part of the same hierarchy")
    }

    private fun markCrashed(context: Context, thread: Thread, throwable: Throwable) {
        val stackTrace = buildString {
            appendLine("Thread: ${thread.name}")
            appendLine(throwable.stackTraceToString())
        }.take(MAX_STACKTRACE_LENGTH)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit(commit = true) {
                putBoolean(KEY_CRASHED, true)
                putString(KEY_STACKTRACE, stackTrace)
            } // commit() 同步写入，确保崩溃前写完
    }
}
