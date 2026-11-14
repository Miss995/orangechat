/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

/*
 * 气泡背景图绘制（平铺 / 拉伸）
 *
 * 2026-11-15 凌晨重写。参数和对齐方式参考 Operit（AAswordman/Operit，
 * BubbleImageBackgroundSurface.kt）—— 宝画的兔子耳朵被拉成椭圆，根因是
 * 猫一直在用"拉伸"，而 Operit 用的是"平铺"：
 *   拉伸 = 一个像素拉成十个像素（会变形）
 *   平铺 = 把那一小块原样重复贴十遍（形状不变）
 * 所以这里把两种模式都做出来，默认平铺。
 */

package me.rerere.rikkahub.ui.components.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 画气泡背景图。
 *
 * 流程：① 按四条 crop 比例裁掉原图的边 → ② 裁完的图按 repeat 区间切成九块
 * → ③ 四角原样贴，边和中间按 [tiled] 决定平铺还是拉伸。
 *
 * @param cropLeft/cropTop/cropRight/cropBottom 四边各裁掉多少（0~0.45）
 * @param repeatXStart/repeatXEnd 横向"可重复区"的起止（0~1，相对裁完的图）
 * @param repeatYStart/repeatYEnd 纵向同理
 * @param tiled true = 平铺（不变形），false = 拉伸
 * @param expandPx 往外多画多少像素（出血，给伸到气泡外的装饰用）
 */
fun DrawScope.drawBubbleImage(
    bmp: ImageBitmap,
    cropLeft: Float = 0f,
    cropTop: Float = 0f,
    cropRight: Float = 0f,
    cropBottom: Float = 0f,
    repeatXStart: Float = 0.35f,
    repeatXEnd: Float = 0.65f,
    repeatYStart: Float = 0.35f,
    repeatYEnd: Float = 0.65f,
    tiled: Boolean = true,
    expandPx: Float = 0f,
) {
    val bmW = bmp.width
    val bmH = bmp.height
    if (bmW <= 0 || bmH <= 0) return

    // ① 裁剪
    val srcX = (bmW * cropLeft.coerceIn(0f, 0.45f)).roundToInt().coerceIn(0, bmW - 1)
    val srcY = (bmH * cropTop.coerceIn(0f, 0.45f)).roundToInt().coerceIn(0, bmH - 1)
    val srcR = (bmW * (1f - cropRight.coerceIn(0f, 0.45f))).roundToInt().coerceIn(srcX + 1, bmW)
    val srcB = (bmH * (1f - cropBottom.coerceIn(0f, 0.45f))).roundToInt().coerceIn(srcY + 1, bmH)
    val srcW = srcR - srcX
    val srcH = srcB - srcY

    // ② 切分
    val x1 = repeatXStart.coerceIn(0f, 1f)
    val x2 = repeatXEnd.coerceIn(0f, 1f)
    val y1 = repeatYStart.coerceIn(0f, 1f)
    val y2 = repeatYEnd.coerceIn(0f, 1f)
    val capL = (srcW * x1).roundToInt()
    val capR = (srcW * (1f - x2)).roundToInt()
    val capT = (srcH * y1).roundToInt()
    val capB = (srcH * (1f - y2)).roundToInt()
    val midW = srcW - capL - capR
    val midH = srcH - capT - capB

    val W = size.width + expandPx * 2
    val H = size.height + expandPx * 2
    val ox = -expandPx
    val oy = -expandPx

    // 切没了（两条线贴一起了）→ 整张兜底画上去
    if (midW <= 0 || midH <= 0 || W <= 0f || H <= 0f) {
        if (W <= 0f || H <= 0f) return
        drawImage(
            image = bmp,
            srcOffset = IntOffset(srcX, srcY),
            srcSize = IntSize(srcW.coerceAtLeast(1), srcH.coerceAtLeast(1)),
            dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()),
            dstSize = IntSize(W.roundToInt(), H.roundToInt()),
        )
        return
    }

    // ③ 四角按原像素贴，目标太小就各占一半
    val dCapL = min(capL.toFloat(), W / 2f)
    val dCapR = min(capR.toFloat(), W / 2f)
    val dCapT = min(capT.toFloat(), H / 2f)
    val dCapB = min(capB.toFloat(), H / 2f)
    val dMidW = max(0f, W - dCapL - dCapR)
    val dMidH = max(0f, H - dCapT - dCapB)

    fun one(sx: Int, sy: Int, sw: Int, sh: Int, dx: Float, dy: Float, dw: Float, dh: Float) {
        if (sw <= 0 || sh <= 0 || dw <= 0f || dh <= 0f) return
        drawImage(
            image = bmp,
            srcOffset = IntOffset(sx, sy),
            srcSize = IntSize(sw, sh),
            dstOffset = IntOffset((ox + dx).roundToInt(), (oy + dy).roundToInt()),
            dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
        )
    }

    /** 横向平铺：按原像素宽一块块贴满 [dw]（保持源高 [sh]）。 */
    fun tileH(sx: Int, sy: Int, sw: Int, sh: Int, dx: Float, dy: Float, dw: Float) {
        if (sw <= 0 || sh <= 0 || dw <= 0f) return
        var x = 0f
        while (x < dw - 0.01f) {
            val w = min(sw.toFloat(), dw - x)
            val srcPart = if (w >= sw.toFloat()) sw else max(1, (sw * (w / sw.toFloat())).roundToInt())
            drawImage(
                image = bmp,
                srcOffset = IntOffset(sx, sy),
                srcSize = IntSize(srcPart, sh),
                dstOffset = IntOffset((ox + dx + x).roundToInt(), (oy + dy).roundToInt()),
                dstSize = IntSize(w.roundToInt(), sh),
            )
            x += sw
        }
    }

    /** 纵向平铺：按原像素高一块块贴满 [dh]（保持源宽 [sw]）。 */
    fun tileV(sx: Int, sy: Int, sw: Int, sh: Int, dx: Float, dy: Float, dh: Float) {
        if (sw <= 0 || sh <= 0 || dh <= 0f) return
        var y = 0f
        while (y < dh - 0.01f) {
            val h = min(sh.toFloat(), dh - y)
            val srcPart = if (h >= sh.toFloat()) sh else max(1, (sh * (h / sh.toFloat())).roundToInt())
            drawImage(
                image = bmp,
                srcOffset = IntOffset(sx, sy),
                srcSize = IntSize(sw, srcPart),
                dstOffset = IntOffset((ox + dx).roundToInt(), (oy + dy + y).roundToInt()),
                dstSize = IntSize(sw, h.roundToInt()),
            )
            y += sh
        }
    }

    // 源坐标
    val sxL = srcX
    val sxM = srcX + capL
    val sxR = srcX + srcW - capR
    val syT = srcY
    val syM = srcY + capT
    val syB = srcY + srcH - capB

    // 四角：原样贴，永不拉伸
    one(sxL, syT, capL, capT, 0f, 0f, dCapL, dCapT)
    one(sxR, syT, capR, capT, W - dCapR, 0f, dCapR, dCapT)
    one(sxL, syB, capL, capB, 0f, H - dCapB, dCapL, dCapB)
    one(sxR, syB, capR, capB, W - dCapR, H - dCapB, dCapR, dCapB)

    if (tiled) {
        // 四边：平铺
        tileH(sxM, syT, midW, capT, dCapL, 0f, dMidW)
        tileH(sxM, syB, midW, capB, dCapL, H - dCapB, dMidW)
        tileV(sxL, syM, capL, midH, 0f, dCapT, dMidH)
        tileV(sxR, syM, capR, midH, W - dCapR, dCapT, dMidH)
        // 中间：双向平铺（逐行横向铺）
        var y = 0f
        while (y < dMidH - 0.01f) {
            val h = min(midH.toFloat(), dMidH - y)
            val srcPart = if (h >= midH.toFloat()) midH else max(1, (midH * (h / midH.toFloat())).roundToInt())
            tileH(
                sx = sxM, sy = syM, sw = midW, sh = srcPart,
                dx = dCapL, dy = dCapT + y, dw = dMidW,
            )
            y += midH
        }
    } else {
        // 四边 + 中间：拉伸
        one(sxM, syT, midW, capT, dCapL, 0f, dMidW, dCapT)
        one(sxM, syB, midW, capB, dCapL, H - dCapB, dMidW, dCapB)
        one(sxL, syM, capL, midH, 0f, dCapT, dCapL, dMidH)
        one(sxR, syM, capR, midH, W - dCapR, dCapT, dCapR, dMidH)
        one(sxM, syM, midW, midH, dCapL, dCapT, dMidW, dMidH)
    }
}
