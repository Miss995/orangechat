/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.rememberAsyncImagePainter
import com.dokar.sonner.ToastType
import com.jvziyaoyao.scale.image.pager.ImagePager
import com.jvziyaoyao.scale.zoomable.pager.rememberZoomablePagerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Download01
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject

/**
 * 图片大图预览。
 *
 * 【实况 · 2026-11-04】新加 liveVideos：跟 images 严格同位序，空的表示那张不是实况。
 * 按住图 → 原地开始动；松手 → 停。跟手机相册里的实况一个手感。
 *
 * 为什么加在这儿、不放在缩略图那儿：
 *   ① 缩略图只有 76dp，按住也看不清是什么；
 *   ② 全 App 的图（消息里的、小红书/抖音链接卡里的）都走这个弹窗，加一次到处都有。
 *
 * ⚠️ 手势：ImagePager 自己管着拖拽和双击缩放，所以这里只用 requireUnconsumed = false
 * "旁听"手指，一个事件都不消费 —— 否则拖不动、也缩不了。
 */
@Composable
fun ImagePreviewDialog(
    images: List<String>,
    onDismissRequest: () -> Unit,
    /** 【实况 · 2026-11-04】跟 images 严格同位序的实况视频地址；不是实况的位置放 null 或空串。 */
    liveVideos: List<String?> = emptyList(),
) {
    val context = LocalContext.current
    val filesManager: FilesManager = koinInject()
    val state = rememberZoomablePagerState { images.size }
    val toaster = LocalToaster.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    /* 【实况 · 2026-11-04】当前这一页是不是实况 */
    val currentLive = liveVideos.getOrNull(state.currentPage)?.takeIf { it.isNotBlank() }

    /* 手指现在是不是按着 */
    var liveOn by remember(currentLive) { mutableStateOf(false) }

    /*
     * 播放器提前备好 —— prepare() 要几百毫秒，不预备着，按住的前半秒是静止的，
     * 那就不是实况了，是"按住一下，然后才动"。
     * 一段实况只有 3.9 秒，按住期间循环放（手指按多久看多久）。
     */
    val livePlayer = remember(currentLive) {
        currentLive?.let { url ->
            runCatching {
                ExoPlayer.Builder(context).build().apply {
                    setMediaItem(MediaItem.fromUri(url))
                    repeatMode = Player.REPEAT_MODE_ONE
                    volume = 0f      // 实况本来就无声，写死更保险
                    prepare()
                }
            }.getOrNull()
        }
    }
    DisposableEffect(livePlayer) {
        onDispose { runCatching { livePlayer?.release() } }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(currentLive) {
                    if (currentLive == null || livePlayer == null) return@pointerInput
                    awaitEachGesture {
                        // requireUnconsumed = false：就算 ImagePager 抢先消费了也要听得到
                        awaitFirstDown(requireUnconsumed = false)
                        var fired = false
                        /*
                         * 计时必须放在独立协程里。
                         * 手指按着不动的时候是没有新指针事件的，靠 awaitPointerEvent
                         * 循环去数时间，会永远等不到"该长按了"那一刻。
                         */
                        val timer = scope.launch {
                            delay(viewConfiguration.longPressTimeoutMillis)
                            fired = true
                            liveOn = true
                            livePlayer.playWhenReady = true
                        }
                        // 这个会一直挂起，直到松手、或者被别的手势打断
                        waitForUpOrCancellation()
                        timer.cancel()
                        if (fired) {
                            liveOn = false
                            livePlayer.playWhenReady = false
                            livePlayer.seekTo(0)
                        }
                    }
                }
        ) {
            ImagePager(
                modifier = Modifier.fillMaxSize(),
                pagerState = state,
                imageLoader = { index ->
                    val painter = rememberAsyncImagePainter(images[index])
                    return@ImagePager Pair(painter, painter.intrinsicSize)
                },
            )

            /* 【实况 · 2026-11-04】按住时盖上去的播放层：铺满、按原比例居中（跟图一样不裁切） */
            if (liveOn && livePlayer != null) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            setPlayer(livePlayer)
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(0.5f),
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(1f)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = {
                        lifecycleOwner.lifecycleScope.launch {
                            runCatching {
                                toaster.show("正在保存")
                                val imgUrl = images[state.currentPage]
                                filesManager.saveMessageImage(context, imgUrl)
                                toaster.show(message = "已保存图片", type = ToastType.Success)
                            }.onFailure {
                                it.printStackTrace()
                                toaster.show(
                                    message = it.toString(),
                                    type = ToastType.Error
                                )
                            }
                        }
                    }
                ) {
                    Icon(HugeIcons.Download01, null, tint = Color.White)
                }
            }
        }
    }
}
