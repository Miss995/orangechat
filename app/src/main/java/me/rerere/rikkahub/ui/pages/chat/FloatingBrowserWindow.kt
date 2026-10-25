package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
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
    if (!visible) return

    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val screenW = config.screenWidthDp.dp
    val screenH = config.screenHeightDp.dp

    // 窗子的位置（左上角）+ 大小，全用 dp
    var widthDp by remember { mutableFloatStateOf(230f) }
    var heightDp by remember { mutableFloatStateOf(340f) }

    // 初始位置：靠右，但不贴边；上下留在屏幕上半部（不吃输入框那块）
    var offsetX by remember { mutableFloatStateOf((screenW.value - 230f - 12f).coerceAtLeast(8f)) }
    var offsetY by remember { mutableFloatStateOf((screenH.value * 0.18f).coerceAtLeast(8f)) }

    val maxW = screenW.value
    val maxH = screenH.value
    val minW = 140f
    val minH = 180f

    Box(
        modifier = modifier
            .width(widthDp.dp)
            .height(heightDp.dp)
            .offset { IntOffset(
                with(density) { offsetX.dp.roundToPx() },
                with(density) { offsetY.dp.roundToPx() },
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
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "网页",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.pointerInput(Unit) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                val dx = drag.x / density.density
                                val dy = drag.y / density.density
                                offsetX = (offsetX + dx).coerceIn(0f, (maxW - widthDp).coerceAtLeast(0f))
                                offsetY = (offsetY + dy).coerceIn(0f, (maxH - heightDp).coerceAtLeast(0f))
                            }
                        }
                    )
                    Text(
                        text = "✕",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .pointerInput(Unit) {
                                detectTapGesturesCompat { onClose() }
                            }
                            .padding(horizontal = 4.dp)
                    )
                }

                // ── 身体：将来放 WebView，现在占位 ──
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFFF3F1ED)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "网页会放在这儿\n（明天接）",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF8A8578),
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
                        widthDp = (widthDp + dx).coerceIn(minW, maxW)
                        heightDp = (heightDp + dy).coerceIn(minH, maxH)
                        // 拉大之后别让它跑出屏幕
                        offsetX = offsetX.coerceIn(0f, (maxW - widthDp).coerceAtLeast(0f))
                        offsetY = offsetY.coerceIn(0f, (maxH - heightDp).coerceAtLeast(0f))
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

/**
 * 轻量点击 —— 只认「按一下」，不认拖动。
 * Compose 的 detectTapGestures 是 foundation 里的扩展，单独包一层免得 import 打架。
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTapGesturesCompat(
    onTap: () -> Unit,
) {
    androidx.compose.foundation.gestures.detectTapGestures(onTap = { onTap() })
}
