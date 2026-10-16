package me.rerere.rikkahub.ui.pages.miniapp

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout

/**
 * 小程序 WebView 的"常驻小窝"。
 *
 * 问题：橘瓣是单 Activity 换页面，小程序页一离开，里面的 WebView 就被销毁 ——
 * 宝一切到聊天，"猫的浏览器"就摸不到东西了。
 *
 * 解法：WebView 只建一次、一直活着。页面在的时候它待在页面里（Compose 管），
 * 页面走了就把它塞回一个小窝（1×1、INVISIBLE，挂在主 Activity 的根上）——
 * 仍然在窗口里，所以照常执行 JS、照常保有登录态，只是没人看得见。
 */
internal object MiniAppWebViewHost {

    /** 当前活着的那个 WebView。 */
    @Volatile
    var webView: WebView? = null
        private set

    private var park: FrameLayout? = null

    /** 主 Activity 的根 View。页面第一次打开时记一下 —— 之后猫自己造 WebView 也有地方安放。 */
    private var rootView: ViewGroup? = null

    /** 应用上下文，用来在"宝没开着页面"时自己造一只。 */
    private var appContext: Context? = null

    fun rememberHost(context: Context) {
        if (rootView == null) rootView = findActivity(context)?.window?.decorView as? ViewGroup
        if (appContext == null) appContext = context.applicationContext
    }

    /**
     * 猫要用页面、但宝没开着的时候调：自己造一只，直接放进小窝跑着。
     * 配置由调用方给（跟页面里那只共用同一套）。没记过上下文时返回 null。
     *
     * 注意：WebView 只能在有 Looper 的线程上 new 出来，而工具是在协程里跑的 ——
     * 所以这里把"造"这件事投回主线程，等它造完再交回来。
     */
    fun obtainHeadless(configure: (WebView) -> Unit): WebView? {
        webView?.let { return it }
        val ctx = appContext ?: return null
        val latch = java.util.concurrent.CountDownLatch(1)
        var created: WebView? = null
        Handler(Looper.getMainLooper()).post {
            try {
                created = WebView(ctx).also { wv ->
                    configure(wv)
                    webView = wv
                    back(wv)
                }
            } catch (_: Throwable) {
                // 造不出来就当没有，调用方会按 null 处理
            } finally {
                latch.countDown()
            }
        }
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        return created
    }

    private fun findActivity(context: Context): Activity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    /** 拿那个 WebView；还没有的话用 [create] 造一个并记住。 */
    fun obtain(context: Context, create: () -> WebView): WebView {
        webView?.let { return it }
        val created = create()
        webView = created
        return created
    }

    /** 页面要显示它：从别处摘下来，交给 Compose 挂。 */
    fun take(target: WebView) {
        (target.parent as? ViewGroup)?.removeView(target)
    }

    /** 页面走了：放回小窝，不销毁。 */
    fun back(target: WebView) {
        val activity = findActivity(target.context)
        val root = rootView ?: (activity?.window?.decorView as? ViewGroup) ?: return
        val box = park ?: FrameLayout(activity ?: target.context).also { layout ->
            layout.layoutParams = ViewGroup.LayoutParams(1, 1)
            // INVISIBLE 而不是 GONE：占着位、不显示，但仍然走正常的绘制/回调流程
            layout.visibility = View.INVISIBLE
            layout.isClickable = false
            layout.isFocusable = false
            root.addView(layout)
            park = layout
        }
        (target.parent as? ViewGroup)?.removeView(target)
        box.addView(target)
    }

    /** 真该销毁的时候（比如宝自己关掉小程序、或者页面重建）。 */
    fun destroy() {
        webView?.let { target ->
            (target.parent as? ViewGroup)?.removeView(target)
            target.destroy()
        }
        webView = null
        park?.let { box ->
            (box.parent as? ViewGroup)?.removeView(box)
        }
        park = null
    }
}
