package me.rerere.rikkahub.ui.pages.chat

import android.view.ViewGroup
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.pages.miniapp.MiniAppWebViewHost
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
            MiniAppWebViewHost.webView?.let { MiniAppWebViewHost.back(it, clearPage = false) }
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
                        text = "网页",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                            MiniAppWebViewHost.webView?.let {
                                MiniAppWebViewHost.back(it, clearPage = false)
                            }
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

                // ── 身体：借用小程序那只 WebView（2026-10-26 接上） ──
                //   全局只有一只（MiniAppWebViewHost 单例），所以是「借」不是「造」：
                //   从它现在待的地方（小程序页 或 那个看不见的窝）摘下来，挂到这儿。
                //   摘的时候先 removeView —— 一个 View 只能有一个爹。
                val wv = MiniAppWebViewHost.webView
                if (wv == null) {
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
                            (wv.parent as? ViewGroup)?.removeView(wv)
                            wv
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
