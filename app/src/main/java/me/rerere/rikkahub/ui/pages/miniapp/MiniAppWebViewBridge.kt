package me.rerere.rikkahub.ui.pages.miniapp

import android.webkit.WebView

/**
 * 小程序 WebView 的"挂钩"。
 *
 * 页面上打开着的小程序只可能有一个。工具侧的 browser_* 系列要摸到它，
 * 就得有个地方能拿到"当前那个 WebView"。页面进出时挂上 / 摘掉。
 *
 * 只在主线程读写。
 */
internal object MiniAppWebViewBridge {
    @Volatile
    var current: WebView? = null
        private set

    fun attach(webView: WebView) {
        current = webView
    }

    /** 只有还是同一个才算摘掉，避免后开的页面被先走的页面清空。 */
    fun detach(webView: WebView) {
        if (current === webView) current = null
    }
}
