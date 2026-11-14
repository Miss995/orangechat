/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

/*
 * 水波背景（WebView 版）
 *
 * 2026-11-14 宝：「给他搬家吧」——把网页上那池水搬进聊天页当背景。
 *
 * 为什么不重写成 AGSL：水那套 shader 有四十多个 uniform，还有 uRipples[14]
 * 这种数组（AGSL 不支持数组 uniform，得拆成几十个单独参数）。先挂 WebView，
 * 网页跟橘瓣分开跑，效果跟网页一模一样，出问题也好退。
 *
 * ⚠️ 页面要从 ?bare=1 进来 —— 那个模式会把滑杆面板和 ≡ 藏掉，只留水面。
 * ⚠️ 触摸在这里全部吃掉：它是背景，绝不能跟聊天的滚动抢事件。
 *    （想吃掉又想让手指按出涟漪，得把触摸透传上来再接，那是以后的事。）
 */

package me.rerere.rikkahub.ui.pages.chat

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** 水面页面。?bare=1 = 裸奔模式（无面板、无按钮）。 */
private const val WATER_URL = "https://miss995.github.io/1/water.html?bare=1"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WaterBackground(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                // 页面本身不透明（深蓝池水），这里给透明只是防止加载瞬间闪白
                setBackgroundColor(android.graphics.Color.TRANSPARENT)

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.setSupportZoom(false)
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                settings.loadWithOverviewMode = false
                settings.useWideViewPort = false
                // 水要自己跑动画，别让系统把 WebGL 当"需要用户手势"拦下来
                settings.mediaPlaybackRequiresUserGesture = false

                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                isClickable = false
                isFocusable = false

                // 背景层把触摸全部吃掉：不拦的话，手指按在空白处会落到 WebView 里，
                // 聊天列表就滑不动了。
                setOnTouchListener { _, _ -> true }

                loadUrl(WATER_URL)
            }
        },
    )
}
