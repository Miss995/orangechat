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

    /**
     * 最后停在的那个网址 —— 「只存路径，不存页面」（2026-10-17 宝定的方案）。
     *
     * 离开页面时把网址记下来、然后让 WebView 跳去 about:blank：
     * 它待在窝里就是一片空白，不留旧页面（下次进来不会「旧内容 + 转圈」），
     * 也不占着旧页面的内存。再进来时从这个网址重新加载。
     */
    @Volatile
    var savedUrl: String? = null
        private set

    /** 记一个要下次打开的网址（入口处用）。 */
    fun remember(url: String?) {
        val u = url?.trim().orEmpty()
        if (u.isNotEmpty()) savedUrl = u
    }

    /**
     * 浮窗自己那一只（2026-10-26）。
     *
     * 为什么不共用主那只：浮窗和小程序页会同时开着，主那只只有一个爹 ——
     * 谁后挂谁就把对方顶掉，小程序页会直接变空白（宝 2026-10-26 报的）。
     * 所以浮窗单独一只，互不干扰。
     *
     * 跟主那只的区别：**关浮窗就销毁**，不留在窝里跑。
     * 浮窗是"临时看一眼"，留着它白白占内存、还烧电（视频一直在解码）。
     */
    @Volatile
    var floatWebView: WebView? = null
        private set

    /** 造浮窗那只。要 IO 线程调（内部 post 到主线程造 + 等结果）。 */
    fun obtainFloat(configure: (WebView) -> Unit): WebView? {
        floatWebView?.let { return it }
        val ctx = appContext ?: return null
        val latch = java.util.concurrent.CountDownLatch(1)
        var created: WebView? = null
        Handler(Looper.getMainLooper()).post {
            try {
                created = WebView(ctx).also { wv ->
                    configure(wv)
                    floatWebView = wv
                }
            } catch (_: Throwable) {
            } finally {
                latch.countDown()
            }
        }
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        return created
    }

    /** 关浮窗：把浮窗那只真销毁掉（不留在窝里）。 */
    fun destroyFloat() {
        floatWebView?.let { target ->
            (target.parent as? ViewGroup)?.removeView(target)
            try {
                target.destroy()
            } catch (_: Throwable) {
            }
        }
        floatWebView = null
    }

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

    /** 页面走了：放回小窝，不销毁。
     *
     * @param clearPage 走之前要不要把页面清成 about:blank。
     *   小程序页要清（窝里不留旧内容，下次进来不撞「旧页面 + 转圈」）；
     *   浮窗不要清（关掉只是想把它藏起来，下次点开还要接着看原来的页面）。 */
    fun back(target: WebView, clearPage: Boolean = true) {
        val activity = findActivity(target.context)
        val root = rootView ?: (activity?.window?.decorView as? ViewGroup) ?: return
        val box = park ?: FrameLayout(activity ?: target.context).also { layout ->
            // ★ 别用 1×1（2026-10-17 修）。
            // WebView 的视口尺寸是在它第一次挂到窗口上时初始化的，挂在 1×1 的窝里
            // 就定成 1×1，之后挪到多大都不重算。症状是"所有视口量全坏"：
            //   window.innerWidth / innerHeight = 0
            //   document.documentElement.clientHeight = 1
            //   window.visualViewport.height = 1
            //   CSS 的 100vh = 0
            // 同一个页面在系统浏览器里完全正常，就是因为那边从一开始就是全屏。
            // 改成整屏大小 + INVISIBLE：INVISIBLE 不绘制、也不接收触摸，所以照样"没人看得见"，
            // 但 WebView 从第一次挂上去开始，视口尺寸就是对的。
            val dm = (activity ?: target.context).resources.displayMetrics
            layout.layoutParams = ViewGroup.LayoutParams(dm.widthPixels, dm.heightPixels)
            layout.visibility = View.INVISIBLE
            layout.isClickable = false
            layout.isFocusable = false
            // 加在最底层（INVISIBLE 本来就不吃触摸，这只是再多一层保险）
            root.addView(layout, 0)
            park = layout
        }
        // ★ 只存路径，不存页面（2026-10-17）。
        // 走之前把当前网址记下来，再把页面清成空白 ——
        // 窝里那只是一片 about:blank，下次进来就不会撞见「旧内容 + 转圈」。
        if (clearPage) {
            try {
                val current = target.url
                if (!current.isNullOrBlank() && !current.startsWith("about:")) {
                    savedUrl = current
                }
                target.loadUrl("about:blank")
                // 顺手清掉历史 —— 不然 canGoBack() 还是 true，顶栏会把「关闭」画成
                // 「后退」，点它只是往回退一页，退不出去（2026-10-17 宝报的）。
                target.clearHistory()
            } catch (_: Throwable) {
            }
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
