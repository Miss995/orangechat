package me.rerere.rikkahub.ui.components.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.sticker.StickerStore

/**
 * 【拖图片存表情包 · 2026-10-24】
 *
 * 宝实测：抖音没有「分享到其它应用」的入口，但**可以把图拖出来**。
 * 安卓的跨 App 拖拽要求两个 App 同时在屏幕上，所以用法是分屏 / 小窗拖进橘仔。
 *
 * ⚠️ 两个关键点：
 *   ① 必须**立刻**拿权限，否则 content:// 读不出字节（SecurityException）
 *      —— 而且这个方法是 **Activity.requestDragAndDropPermissions(DragEvent)**，
 *         不是 View 的（2026-10-24 查文档才确认，前面两版都栽在这个"以为"上）
 *   ② 复制完再 release，别提前放
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberStickerDropModifier(onSaved: (Int) -> Unit = {}): Modifier {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivityOrNull() }
    val scope = rememberCoroutineScope()
    val currentOnSaved = rememberUpdatedState(onSaved)

    val target = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val androidEvent = event.toAndroidDragEvent()
                val clip = androidEvent.clipData ?: return false

                val uris = buildList {
                    for (i in 0 until clip.itemCount) {
                        clip.getItemAt(i).uri?.let { add(it) }
                    }
                }
                if (uris.isEmpty()) return false

                // ① 立刻拿权限（minSdk 26，API 24+ 的东西可以直接调）
                val permissions = runCatching {
                    activity?.requestDragAndDropPermissions(androidEvent)
                }.getOrNull()

                scope.launch {
                    var saved = 0
                    try {
                        for (uri in uris) {
                            if (StickerStore.saveFromUri(context, uri) != null) saved++
                        }
                    } finally {
                        // ② 复制完再放
                        if (permissions != null) {
                            runCatching { permissions.release() }
                        }
                    }
                    currentOnSaved.value(saved)
                }
                return true
            }
        }
    }

    return Modifier.dragAndDropTarget(
        shouldStartDragAndDrop = { e ->
            val clip = e.toAndroidDragEvent().clipData
            clip != null && clip.itemCount > 0
        },
        target = target
    )
}

private tailrec fun Context.findActivityOrNull(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivityOrNull()
    else -> null
}
