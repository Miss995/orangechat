package me.rerere.rikkahub.ui.components.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.LocalContext
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.transformers.ImageDescriber
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
/**
 * 【2026-10-24 修】描述那步不能挂在 rememberCoroutineScope 上 —— 它跟 Compose 页面
 * 绑定，宝存完图一离开页面，协程就被取消，describe 断在半路，`.txt` 永远写不进去。
 * 改用跟页面无关的后台 scope：存完就走，描述慢慢跑完。
 */
private val stickerBgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberStickerDropModifier(onSaved: (Int) -> Unit = {}): Modifier {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivityOrNull() }
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

                stickerBgScope.launch {
                    val savedFiles = mutableListOf<File>()
                    try {
                        for (uri in uris) {
                            StickerStore.saveFromUri(context, uri)?.let { savedFiles += File(it) }
                        }
                    } finally {
                        // ② 复制完再放
                        if (permissions != null) {
                            runCatching { permissions.release() }
                        }
                    }
                    // 先报「存好了」，别让描述卡住反馈
                    currentOnSaved.value(savedFiles.size)

                    // ③ 顺手让视觉模型看一眼，把描述写进 sidecar。
                    //    以后发出去直接复用这份，不再多看一次（一张表情包一辈子只看一次）。
                    //    失败也写一句进去 —— 不然界面只显示“还没描述”，分不清是没跑还是跑了没成。
                    for (f in savedFiles) {
                        if (StickerStore.readNote(f) != null) continue
                        val result = runCatching { ImageDescriber.describe("file://" + f.absolutePath) }
                        if (result.isFailure) {
                            val e = result.exceptionOrNull()
                            StickerStore.saveNote(
                                f,
                                "【失败】describe 抛异常：" + (e?.message ?: e?.javaClass?.simpleName ?: "未知")
                            )
                            continue
                        }
                        val note = result.getOrNull()
                        if (note != null) {
                            StickerStore.saveNote(f, note)
                        } else {
                            StickerStore.saveNote(f, "【失败】describe 返回 null（多半是没配 OCR 模型）")
                        }
                    }
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
