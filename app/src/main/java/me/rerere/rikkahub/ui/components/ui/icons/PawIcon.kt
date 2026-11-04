/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 爪印图标（橘仔自己画的）
 *
 * 一个掌垫 + 四个趾垫，24x24 画布。
 * 用椭圆拼，不用现成的图标库（huge-icons 1.3 里没有爪子）。
 */
private fun PathBuilder.ellipse(cx: Float, cy: Float, rx: Float, ry: Float) {
    moveTo(cx - rx, cy)
    arcTo(rx, ry, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx + rx, y1 = cy)
    arcTo(rx, ry, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx - rx, y1 = cy)
    close()
}

val PawIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Paw",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            // 掌垫
            ellipse(cx = 12f, cy = 16.6f, rx = 4.7f, ry = 3.9f)
            // 四个趾垫
            ellipse(cx = 5.4f, cy = 11.0f, rx = 2.05f, ry = 2.35f)
            ellipse(cx = 9.6f, cy = 7.5f, rx = 2.15f, ry = 2.45f)
            ellipse(cx = 14.4f, cy = 7.5f, rx = 2.15f, ry = 2.45f)
            ellipse(cx = 18.6f, cy = 11.0f, rx = 2.05f, ry = 2.35f)
        }
    }.build()
}
