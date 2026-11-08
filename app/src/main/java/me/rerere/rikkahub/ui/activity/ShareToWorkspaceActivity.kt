package me.rerere.rikkahub.ui.activity

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.core.context.GlobalContext

/**
 * 【存进工作区 · 2026-11-08】
 *
 * 任何 App 点「分享 → 文件」都能选到「存进工作区」，收到就落到工作区的 upload/ 下。
 * 窗口是透明的、不进最近任务，她只会看到屏幕闪一下 + 一句 toast，不会觉得跳走了。
 *
 * 入口：任何 App 里点分享 → 文件 → 选「存进工作区」。
 */
class ShareToWorkspaceActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setDimAmount(0f)

        val uris = collectUris(intent)
        if (uris.isEmpty()) {
            toast("没收到文件")
            finish()
            return
        }

        scope.launch {
            val repo = runCatching { GlobalContext.get().get<WorkspaceRepository>() }.getOrNull()
            if (repo == null) {
                done("取不到工作区")
                return@launch
            }

            val workspace = runCatching { repo.listFlow().first().firstOrNull() }.getOrNull()
            if (workspace == null) {
                done("还没有工作区，先去开一个")
                return@launch
            }

            var ok = 0
            var lastName = ""
            for (uri in uris) {
                val name = displayName(uri) ?: "share_${System.currentTimeMillis()}"
                val saved = saveOne(repo, workspace.id, uri, name)
                if (saved) {
                    ok++
                    lastName = name
                }
            }

            val n = ok
            val last = lastName
            done(
                if (n > 0) {
                    if (n == 1) "存进工作区了 · $last" else "存进工作区了 · $n 个文件"
                } else {
                    "没存下来（这个文件可能读不到）"
                }
            )
        }
    }

    /** 先试 upload/，目录不存在就退回工作区根目录。 */
    private fun saveOne(
        repo: WorkspaceRepository,
        workspaceId: String,
        uri: Uri,
        name: String,
    ): Boolean {
        val toUpload = runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                repo.importFile(
                    id = workspaceId,
                    area = WorkspaceStorageArea.FILES,
                    destinationPath = "upload",
                    fileName = name,
                    inputStream = input,
                )
            } != null
        }.getOrDefault(false)
        if (toUpload) return true

        return runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                repo.importFile(
                    id = workspaceId,
                    area = WorkspaceStorageArea.FILES,
                    destinationPath = "",
                    fileName = name,
                    inputStream = input,
                )
            } != null
        }.getOrDefault(false)
    }

    private fun done(text: String) {
        runOnUiThread {
            toast(text)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private fun toast(text: String) {
        runCatching { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
    }

    private fun collectUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        // ⚠️ 不用 getParcelableExtra / getParcelableArrayListExtra：API 33 起它们被泛型化了，
        //    Kotlin 解析到泛型重载时 T 推断不出来（SaveStickerActivity 2026-10-24 就是栽在这）。
        //    直接从 extras 里按 Any 取，不碰泛型。
        val raw = intent.extras?.get(Intent.EXTRA_STREAM)
        return when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(raw as? Uri)

            Intent.ACTION_SEND_MULTIPLE ->
                (raw as? List<*>)?.filterIsInstance<Uri>() ?: emptyList()

            else -> emptyList()
        }
    }

    /** 从 Uri 问出文件名；问不到返回 null。 */
    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        }
    }.getOrNull()
}
