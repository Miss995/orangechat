package me.rerere.rikkahub.ui.pages.miniapp

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
        val activity = findActivity(target.context) ?: return
        val root = activity.window?.decorView as? ViewGroup ?: return
        val box = park ?: FrameLayout(activity).also { layout ->
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
