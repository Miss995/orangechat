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
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 爪印（空心线条版）- 没有专属图标的工具用它
 *
 * 由宝提供的 Hugeicons Paw 图标（viewBox 0 0 24 24，原线宽 1.5）转换而来。
 * 原图两个趾垫用 matrix 翻转画，这里换算成实际坐标，去掉 transform。
 * 线宽提到 2.0：跟思考链那对猫狗、以及 Hugeicons 那批工具图标保持同一视觉重量。
 *
 * 注意：命令行（workspace_shell）仍然用 Material 的实心 Icons.Filled.Pets，不走这个。
 */
private var _pawOutlineIcon: ImageVector? = null

public val PawOutlineIcon: ImageVector
    get() {
        if (_pawOutlineIcon != null) {
            return _pawOutlineIcon!!
        }
        _pawOutlineIcon = ImageVector.Builder(
            name = "PawOutline",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            addPath(
                pathData = addPathNodes(
                    // 四个趾垫（两个原本带 transform，已换算）
                    "M6.49219 6a2 2 0 1 0 4 0a2 2 0 1 0-4 0 " +
                        "M13.4922 6a2 2 0 1 0 4 0a2 2 0 1 0-4 0 " +
                        "M1.99219 11a2 2 0 1 0 4 0a2 2 0 1 0-4 0 " +
                        "M17.9922 11a2 2 0 1 0 4 0a2 2 0 1 0-4 0 " +
                        // 掌垫
                        "M7.31094 20C6.65469 20 6.10417 19.7604 5.65938 19.2813C5.21458 18.8021 4.99219 18.2361 4.99219 17.5833C4.99219 16.8611 5.25104 16.2292 5.76875 15.6875C6.28646 15.1458 6.80052 14.6111 7.31094 14.0833C7.73385 13.6528 8.09844 13.184 8.40469 12.6771C8.71094 12.1701 9.07552 11.6944 9.49844 11.25C9.81927 10.8889 10.1911 10.5903 10.6141 10.3542C11.037 10.1181 11.4964 10 11.9922 10C12.488 10 12.9474 10.1111 13.3703 10.3333C13.7932 10.5556 14.1651 10.8472 14.4859 11.2083C14.8943 11.6528 15.2552 12.1319 15.5688 12.6458C15.8823 13.1597 16.2505 13.6389 16.6734 14.0833C17.1839 14.6111 17.6979 15.1458 18.2156 15.6875C18.7333 16.2292 18.9922 16.8611 18.9922 17.5833C18.9922 18.2361 18.7698 18.8021 18.325 19.2813C17.8802 19.7604 17.3297 20 16.6734 20C15.8859 20 15.1057 19.9375 14.3328 19.8125C13.5599 19.6875 12.7797 19.625 11.9922 19.625C11.2047 19.625 10.4245 19.6875 9.65156 19.8125C8.87865 19.9375 8.09844 20 7.31094 20Z"
                ),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            )
        }.build()
        return _pawOutlineIcon!!
    }
