package me.rerere.rikkahub.ui.pages.chat

import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.pages.miniapp.MiniAppWebViewHost
import me.rerere.rikkahub.ui.pages.miniapp.applyMiniAppWebViewSettings
import kotlin.math.roundToInt

/**
 * 浮在聊天页上的网页小窗（骨架版 2026-10-25）。
 *
 * 为什么要有它：小程序页是独立页面，宝切回聊天页，那个 WebView 就被收进看不见的窝里 ——
 * 猫截不到、宝也看不见。有了浮窗，网页可以「露着」跟聊天并存。
 *
 * 这一版只做「窗子」本身：拖动、缩放、关闭。里面还没有网页，先放一句占位。
 * 接 WebView 的口子明天做（要处理和小程序页共用同一条 WebView 的生命周期）。
 *
 * 三个尺寸规矩（宝 2026-10-25 问过的）：
 *  - 不能超过屏幕，超出去截不到；
 *  - 太小了页面会自己重排（WebView 把「窗口小」当真），所以有下限；
 *  - 太小视频糊。
 */
@Composable
fun FloatingBrowserWindow(
    visible: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // ★ 2026-10-26：窗子藏起来（visible 变 false）那一下，要把借来的 WebView 还回窝。
    //   不能让它没了爹 —— 没爹的 WebView 会自己停掉 JS，猫那边就摸不到东西了。
    //   clearPage = false：藏起来不是「关掉页面」，下次点开还要接着看原来那页。
    DisposableEffect(Unit) {
        onDispose {
            // ★ 2026-10-26：浮窗那只用完就销毁，不留在窝里跑。
            //   留着它会一直占内存、烧电（视频还在解码，手机直接热起来）。
            MiniAppWebViewHost.destroyFloat()
        }
    }
    if (!visible) return

    val density = LocalDensity.current
    val screenWp = LocalConfiguration.current.screenWidthDp.toFloat()

    // 窗子大小（dp）
    var widthDp by remember { mutableFloatStateOf(230f) }
    var heightDp by remember { mutableFloatStateOf(340f) }

    /**
     * 拖动偏移 —— 从 0 起，相对「外面给的定位」（ChatPage 那边是 align(TopEnd) + 留边）。
     *
     * ★ 2026-10-25 修：之前拿 screenWidthDp 自己算初始 X，结果那个数跟实际可用宽度对不上，
     *   窗子被推到屏幕外，右上角的 ✕ 看不见也点不到（宝：「一点就收不回去了」）。
     *   现在不自己算 —— 起点交给外面摆，这里只负责「从起点挪了多少」。
     *   往左上只能挪到 0（也就是回到起点），往右下最多挪到父容器的边界。
     */
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }

    val minW = 140f
    val minH = 180f

    // 【浮窗播视频 · 2026-10-27】网页里播不动的视频（抖音/小红书那种），
    // 把网页里 <video> 的地址抠出来，交给原生播放器播 —— 网页播不动，但直链能播。
    val ctx = LocalContext.current
    var wv by remember { mutableStateOf<WebView?>(MiniAppWebViewHost.floatWebView) }
    var playUrl by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = modifier
            .width(widthDp.dp)
            .height(heightDp.dp)
            .offset { IntOffset(
                dragX.dp.roundToPx(),
                dragY.dp.roundToPx(),
            ) }
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
            modifier = Modifier
                .fillMaxSize()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
        ) {
            Column(Modifier.fillMaxSize()) {

                // ── 顶栏：拖动把手 + 关闭 ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .pointerInput(Unit) {
                            // ★ 2026-10-25：整条顶栏都能拖（之前只有「网页」两个字能拖，太难点中）
                            detectDragGestures { change, drag ->
                                change.consume()
                                val dx = drag.x / density.density
                                val dy = drag.y / density.density
                                // ★ 2026-10-25 修：之前 X 锁成"只能往左"，宝往右拖它一动不动，
                                //   手指在动窗子不动 = 看起来像反着跑。现在两边都能拖，
                                //   只拦住"别整个跑出屏幕"（往右最多让窗子右边缘贴屏幕右边）。
                                dragX = (dragX + dx).coerceIn(-(screenWp - widthDp).coerceAtLeast(0f), 0f)
                                dragY = (dragY + dy).coerceAtLeast(0f)
                            }
                        }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = if (playUrl == null) "网页" else "播放器",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 【浮窗播视频 · 2026-10-27】网页里播不动的视频 → 抠地址交给原生播放器。
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    if (playUrl != null) {
                                        // 只清状态，释放交给身体那段的 DisposableEffect —— 两边都 release 会崩
                                        playUrl = null
                                        return@clickable
                                    }
                                    val page = wv
                                    if (page == null) {
                                        Toast.makeText(ctx, "网页还没开起来", Toast.LENGTH_SHORT).show()
                                        return@clickable
                                    }
                                    page.evaluateJavascript(
                                        "(function(){var v=document.querySelector('video');" +
                                            "if(v&&v.currentSrc)return v.currentSrc;" +
                                            "if(v&&v.src)return v.src;" +
                                            "var s=document.querySelectorAll('source');" +
                                            "for(var i=0;i<s.length;i++){if(s[i].src)return s[i].src;}" +
                                            "return '';})()"
                                    ) { raw ->
                                        val u = raw?.trim('"', ' ')
                                        if (u.isNullOrBlank() || u == "null") {
                                            Toast.makeText(
                                                ctx,
                                                "这页里没找到能直接播的视频",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        } else {
                                            playUrl = u
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (playUrl == null) "▶" else "⏹",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                MiniAppWebViewHost.destroyFloat()
                                onClose()
                            },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "✕",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // ── 身体：借用小程序那只 WebView（2026-10-26 接上） ──
                //   ★ 2026-10-26 改：以前是「借」小程序那只，结果浮窗一开、小程序页就空了
                //   （一个 View 只能有一个爹，谁后挂谁把对方顶掉）。
                //   现在浮窗自己造一只，跟小程序完全分开，互不相干。
                //   没有的话就自己造一只（MiniAppWebViewHost.obtainFloat）——
                //   配置直接调小程序页那套 applyMiniAppWebViewSettings，两边一模一样。
                LaunchedEffect(Unit) {
                    if (wv == null) {
                        MiniAppWebViewHost.rememberHost(ctx)
                        // ★ 必须在 IO 线程调：obtainFloat 内部是
                        //   「post 到主线程造 WebView + latch.await(5s) 等结果」。
                        //   要是在主线程调，主线程一 await，那个 post 就排不上队，
                        //   必然死等满 5 秒再返回 null —— 界面还会僵在那儿。
                        val created = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            MiniAppWebViewHost.obtainFloat {
                                applyMiniAppWebViewSettings(it)
                            }
                        }
                        if (created != null) {
                            // 有意先开一个真页面再交给 Compose：
                            // about:blank 是"没有 contentSize"的，等挂上来再 loadUrl
                            // 会让它先按 0 高度量一次（小程序那轮 vh=0 的同类坑）。
                            created.loadUrl("https://rickycat.asia")
                        }
                        wv = created
                    }
                }
                val cur = wv
                val playing = playUrl
                if (playing != null) {
                    // 【浮窗播视频 · 2026-10-27】原生播放器接管整个身体。
                    // 网页那边不销毁 —— 退回网页模式还接着看原来那页。
                    val player = remember(playing) {
                        ExoPlayer.Builder(ctx).build().apply {
                            setMediaItem(MediaItem.fromUri(playing))
                            prepare()
                            playWhenReady = true
                        }
                    }
                    DisposableEffect(player) {
                        onDispose { player.release() }
                    }
                    AndroidView(
                        factory = { c ->
                            PlayerView(c).apply {
                                setPlayer(player)
                                useController = true
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (cur == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFFF3F1ED)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "网页没在开着\n先去小程序打开一个",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF8A8578),
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    AndroidView(
                        factory = {
                            (cur.parent as? ViewGroup)?.removeView(cur)
                            cur
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // ── 右下角缩放把手 ──
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(22.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        val dx = drag.x / density.density
                        val dy = drag.y / density.density
                        widthDp = (widthDp + dx).coerceIn(minW, 480f)
                        heightDp = (heightDp + dy).coerceIn(minH, 720f)
                    }
                }
        ) {
            // 三条斜线当把手纹
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
        }
    }
}
