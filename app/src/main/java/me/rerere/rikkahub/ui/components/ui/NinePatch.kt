/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

/*
 * 九宫格绘制
 *
 * 2026-11-14 从 ChatMessage.kt 抽出来（设置页预览也要用）。
 * 2026-11-15 00:0x 改成四个切分点各自设定 —— 宝画的兔子耳朵在上面，
 *   原来的"四角一样大"会把耳朵切到会被横向拉长的那条边里，拉成两个椭圆。
 *   现在左右、上下各有一对切分线，要把哪块锁死就把它压到极窄。
 */

package me.rerere.rikkahub.ui.components.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.max
import kotlin.math.min

/**
 * 九宫格绘制：四个角按原像素贴（不缩放），四条边只往一个方向拉，中间两个方向都拉。
 *
 * 四个切分点都是 0..1 的比例（相对原图）：
 * - [splitLeft]  左切分线，这条线左边的那段原样贴
 * - [splitRight] 右切分线，这条线右边的那段原样贴
 * - [splitTop]   上切分线
 * - [splitBottom] 下切分线
 *
 * [splitLeft] 和 [splitRight] 之间的宽度 = 会被横向拉伸的部分；想锁死不拉，就把两条线贴到一起。
 * [expandPx] = 往外多画多少像素（"出血"，给伸到气泡外面的装饰用）。
 */
fun DrawScope.drawNinePatch(
    bmp: ImageBitmap,
    splitLeft: Float = 0.25f,
    splitRight: Float = 0.75f,
    splitTop: Float = 0.25f,
    splitBottom: Float = 0.75f,
    expandPx: Float = 0f,
) {
    val sw = bmp.width.toFloat()
    val sh = bmp.height.toFloat()
    val W = size.width + expandPx * 2
    val H = size.height + expandPx * 2
    // 整体往左上挪，让多出来的那圈露在气泡外面
    val ox = -expandPx
    val oy = -expandPx
    if (sw <= 0f || sh <= 0f || W <= 0f || H <= 0f) return

    // 原图上的四个切点（像素）
    val sx1 = sw * splitLeft.coerceIn(0f, 1f)
    val sx2 = sw * splitRight.coerceIn(0f, 1f)
    val sy1 = sh * splitTop.coerceIn(0f, 1f)
    val sy2 = sh * splitBottom.coerceIn(0f, 1f)

    // 目标上的四角尺寸：按原像素大小贴、不缩放；目标太小的时候最多各占一半
    val dxL = min(sx1, W / 2f)
    val dxR = min(sw - sx2, W / 2f)
    val dyT = min(sy1, H / 2f)
    val dyB = min(sh - sy2, H / 2f)

    // 中间那块（会被双向拉伸）的目标尺寸，别算成负数
    val midW = max(0f, W - dxL - dxR)
    val midH = max(0f, H - dyT - dyB)

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
    patch(0f, 0f, sx1, sy1, 0f, 0f, dxL, dyT)
    patch(sx2, 0f, sw - sx2, sy1, W - dxR, 0f, dxR, dyT)
    patch(0f, sy2, sx1, sh - sy2, 0f, H - dyB, dxL, dyB)
    patch(sx2, sy2, sw - sx2, sh - sy2, W - dxR, H - dyB, dxR, dyB)
    // 四边：只往一个方向拉
    patch(sx1, 0f, sx2 - sx1, sy1, dxL, 0f, midW, dyT)
    patch(sx1, sy2, sx2 - sx1, sh - sy2, dxL, H - dyB, midW, dyB)
    patch(0f, sy1, sx1, sy2 - sy1, 0f, dyT, dxL, midH)
    patch(sx2, sy1, sw - sx2, sy2 - sy1, W - dxR, dyT, dxR, midH)
    // 中间：两个方向都拉
    patch(sx1, sy1, sx2 - sx1, sy2 - sy1, dxL, dyT, midW, midH)
}
