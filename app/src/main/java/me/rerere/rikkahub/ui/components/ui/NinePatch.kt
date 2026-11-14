/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

/*
 * 九宫格绘制
 *
 * 2026-11-14 从 ChatMessage.kt 里抽出来 —— 因为设置页的「预览」也要用它，
 * 私有函数跨不过去。现在气泡背景图和设置页预览共用这一份。
 */

package me.rerere.rikkahub.ui.components.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.min

/**
 * 九宫格绘制：四个角按原像素贴，四条边只往一个方向拉，中间两个方向都拉。
 *
 * [insetFrac] = 四个角各占图的边长比例（0.25 = 四分之一）。
 * 图最好是正方形 —— 这样气泡再宽再窄，四角都不会被拉变形。
 */
fun DrawScope.drawNinePatch(
    bmp: ImageBitmap,
    insetFrac: Float = 0.25f,
    // 【出血 · 2026-11-14】往外多画多少像素。给"耳朵伸出气泡外面"这类图用 ——
    // 图本身会画到气泡边界之外（Compose 的 DrawScope 不裁剪，除非上层套了 clip）。
    expandPx: Float = 0f,
) {
    val sw = bmp.width.toFloat()
    val sh = bmp.height.toFloat()
    val W = size.width + expandPx * 2
    val H = size.height + expandPx * 2
    // 画的时候整体往左上挪，让多出来的那圈露在气泡外面
    val ox = -expandPx
    val oy = -expandPx
    if (sw <= 0f || sh <= 0f || W <= 0f || H <= 0f) return

    val ix = sw * insetFrac
    val iy = sh * insetFrac
    // 目标区太小的时候，角最多各占一半，别互相压过去
    val dx = min(ix, W / 2f)
    val dy = min(iy, H / 2f)

    fun patch(
        sx: Float, sy: Float, sw2: Float, sh2: Float,
        dx0: Float, dy0: Float, dw: Float, dh: Float,
    ) {
        if (sw2 <= 0f || sh2 <= 0f || dw <= 0f || dh <= 0f) return
        drawImage(
            image = bmp,
            srcOffset = IntOffset(sx.toInt(), sy.toInt()),
            srcSize = IntSize(sw2.toInt(), sh2.toInt()),
            dstOffset = IntOffset((ox + dx0).toInt(), (oy + dy0).toInt()),
            dstSize = IntSize(dw.toInt(), dh.toInt()),
        )
    }

    // 四角：原样贴，不拉伸
    patch(0f, 0f, ix, iy, 0f, 0f, dx, dy)
    patch(sw - ix, 0f, ix, iy, W - dx, 0f, dx, dy)
    patch(0f, sh - iy, ix, iy, 0f, H - dy, dx, dy)
    patch(sw - ix, sh - iy, ix, iy, W - dx, H - dy, dx, dy)
    // 四边：只往一个方向拉
    patch(ix, 0f, sw - 2 * ix, iy, dx, 0f, W - 2 * dx, dy)
    patch(ix, sh - iy, sw - 2 * ix, iy, dx, H - dy, W - 2 * dx, dy)
    patch(0f, iy, ix, sh - 2 * iy, 0f, dy, dx, H - 2 * dy)
    patch(sw - ix, iy, ix, sh - 2 * iy, W - dx, dy, dx, H - 2 * dy)
    // 中间：两个方向都拉
    patch(ix, iy, sw - 2 * ix, sh - 2 * iy, dx, dy, W - 2 * dx, H - 2 * dy)
}
