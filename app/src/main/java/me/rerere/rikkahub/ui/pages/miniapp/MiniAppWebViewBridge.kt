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
    /**
     * 猫要用的那只 WebView。
     *
     * 有就直接给；宝没开着页面时，自己悄悄造一只放进小窝跑起来 ——
     * 这样"必须宝先打开一个网页，猫才能用浏览器"这个限制就没了。
     * (需要 App 至少进过一次小程序页，好知道把窝安在哪儿；没记过就返回 null。)
     */
    fun ensure(): WebView? {
        MiniAppWebViewHost.webView?.let { return it }
        return MiniAppWebViewHost.obtainHeadless { wv ->
            applyMiniAppWebViewSettings(wv)
        }
    }

    val current: WebView?
        get() = ensure()
}
