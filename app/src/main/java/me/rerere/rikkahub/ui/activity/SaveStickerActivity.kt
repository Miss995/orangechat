package me.rerere.rikkahub.ui.activity

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.sticker.StickerStore

/**
 * 【存表情包 · 2026-10-24】
 *
 * 系统「分享」进来的图片，直接复制进 App 私有目录。窗口是透明的，所以她只会看到
 * 屏幕闪一下 + 一句 toast，不会觉得跳走了。
 *
 * 入口：任何 App 里点分享 → 图片 → 选「橘仔」。
 */
class SaveStickerActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setDimAmount(0f)

        val uris = collectImageUris(intent)
        if (uris.isEmpty()) {
            toast("没收到图片")
            finish()
            return
        }

        scope.launch {
            var ok = 0
            for (u in uris) {
                if (StickerStore.saveFromUri(this@SaveStickerActivity, u) != null) ok++
            }
            val saved = ok
            runOnUiThread {
                toast(
                    if (saved > 0) "存进表情包了 · ${saved} 张"
                    else "没存下来（这张图可能读不到）"
                )
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private fun toast(text: String) {
        runCatching { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
    }

    @Suppress("DEPRECATION")
    private fun collectImageUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        return when (intent.action) {
            // ⚠️ 用非泛型版本：泛型 getParcelableExtra<T> 是 API 33 才加的，
            // minSdk 低时调它要编译期版本判断，直接用非泛型更省事
            Intent.ACTION_SEND -> {
                val single = intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                listOfNotNull(single)
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                val many = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                many?.filterIsInstance<Uri>() ?: emptyList()
            }

            else -> emptyList()
        }
    }
}
