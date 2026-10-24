package me.rerere.rikkahub.ui.components.ui

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
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.sticker.StickerStore

/**
 * 【拖图片存表情包 · 2026-10-24】
 *
 * 宝实测：抖音没有「分享到其它应用」的入口，但**可以把图拖出来**。
 * 安卓的跨 App 拖拽要求两个 App 同时在屏幕上，所以用法是分屏/小窗拖进橘仔。
 *
 * ⚠️ 两个关键点：
 *   ① 必须**立刻** requestDragAndDropPermissions，否则 content:// 读不出字节（SecurityException）
 *   ② 复制完再 release，别提前放
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberStickerDropModifier(onSaved: (Int) -> Unit = {}): Modifier {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val currentOnSaved by rememberUpdatedState(onSaved)

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

                // ① 立刻要权限
                val permissions = runCatching {
                    view.requestDragAndDropPermissions(androidEvent)
                }.getOrNull()

                scope.launch {
                    var saved = 0
                    try {
                        for (uri in uris) {
                            if (StickerStore.saveFromUri(context, uri) != null) saved++
                        }
                    } finally {
                        runCatching { permissions?.release() }
                    }
                    currentOnSaved(saved)
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
