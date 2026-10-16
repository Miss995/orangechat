package me.rerere.rikkahub.ui.pages.miniapp

import android.webkit.WebView

/**
 * 浏览器工具摸 WebView 的唯一入口。
 *
 * v1 的时候这里还兼着"页面进出时挂上 / 摘掉"，因为 WebView 跟着页面生灭。
 * 现在 WebView 常驻在 [MiniAppWebViewHost] 的小窝里，这里就退成一句转发 ——
 * 页面在不在都不影响：只要 WebView 还活着，猫就够得着。
 */
internal object MiniAppWebViewBridge {
    val current: WebView?
        get() = MiniAppWebViewHost.webView
}
