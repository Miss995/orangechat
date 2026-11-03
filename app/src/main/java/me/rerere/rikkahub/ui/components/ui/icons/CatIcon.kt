/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 小猫 - 自定义"思考中"图标
 * 由宝提供的 Hugeicons CatIcon (viewBox 0 0 24 24) 转换而来。
 * 原图线宽 1.5，这里提到 2.0：16dp 下 1.5 只有 6.25% 的视觉重量，偏细发虚。
 */
private var _catIcon: ImageVector? = null

public val CatIcon: ImageVector
    get() {
        if (_catIcon != null) {
            return _catIcon!!
        }
        _catIcon = ImageVector.Builder(
            name = "Cat",
            defaultWidth = 16.dp,
            defaultHeight = 16.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            // 左眼
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(8f, 11f)
                verticalLineTo(11.5f)
            }
            // 右眼
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(16f, 11f)
                verticalLineTo(11.5f)
            }
            // 鼻子
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(11f, 15f)
                horizontalLineTo(13f)
                lineTo(12f, 16f)
                lineTo(11f, 15f)
                close()
            }
            // 右胡须（上）
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(18f, 14.5f)
                lineTo(22f, 14f)
            }
            // 右胡须（下）
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(18f, 17f)
                lineTo(21.5f, 18f)
            }
            // 左胡须（上）
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(6f, 14.5f)
                lineTo(2f, 14f)
            }
            // 左胡须（下）
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(6f, 17f)
                lineTo(2.5f, 18f)
            }
            // 脸轮廓（两只耳朵 + 左右两侧）
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(3.28125f, 11f)
                curveTo(3.34566f, 10.7775f, 3.42057f, 10.5588f, 3.50547f, 10.3443f)
                curveTo(3.71942f, 9.8037f, 3.82639f, 9.53341f, 3.83453f, 9.36834f)
                curveTo(3.84266f, 9.20326f, 3.75537f, 8.89775f, 3.58077f, 8.28671f)
                curveTo(3.23955f, 7.09254f, 3.19717f, 5.63145f, 3.52602f, 4.5068f)
                curveTo(3.65296f, 4.07268f, 3.71643f, 3.85562f, 4.38389f, 3.58155f)
                curveTo(5.05136f, 3.30749f, 5.39987f, 3.5011f, 6.09689f, 3.88833f)
                lineTo(8.99774f, 5.4999f)
                curveTo(8.99774f, 5.4999f, 9.99774f, 4.9999f, 11.9977f, 4.9999f)
                curveTo(13.9977f, 4.9999f, 14.9977f, 5.4999f, 14.9977f, 5.4999f)
                lineTo(17.8986f, 3.88833f)
                curveTo(18.5956f, 3.5011f, 18.9441f, 3.30749f, 19.6116f, 3.58155f)
                curveTo(20.2791f, 3.85562f, 20.3425f, 4.07268f, 20.4695f, 4.5068f)
                curveTo(20.7983f, 5.63145f, 20.7559f, 7.09254f, 20.4147f, 8.28671f)
                curveTo(20.2401f, 8.89775f, 20.1528f, 9.20326f, 20.161f, 9.36834f)
                curveTo(20.1691f, 9.53341f, 20.2761f, 9.8037f, 20.49f, 10.3443f)
                curveTo(20.5749f, 10.5588f, 20.6498f, 10.7775f, 20.7142f, 11f)
            }
            // 下巴
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(17f, 19.6528f)
                curveTo(15.5699f, 20.5037f, 13.8501f, 21f, 12f, 21f)
                curveTo(10.1499f, 21f, 8.4301f, 20.5037f, 7f, 19.6528f)
            }
        }.build()
        return _catIcon!!
    }
