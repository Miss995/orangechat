/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/**
 * 系统级网页浮窗（2026-11-18 新建）
 *
 * 为什么要有它：原来的 FloatingBrowserWindow 是 Compose 画的，只能待在聊天页里；
 * 宝要的是「在什么应用上都能出现」，那就得挂到 WindowManager 上。
 *
 * 为什么不用 FloatingX：家里装的是 2.3.7，它只有「应用内」；系统级是 3.0 才拆出来的
 * 模块，而且 3.0 跟 2.x API 不兼容（升了 TTS 那个浮窗也要改）。所以走自己这条 ——
 * FloatingBubbleService 那套 WindowManager 已经跑通了，照抄它的骨架。
 *
 * 现在这一版：一个能拖、能关、里面装网页的窗。缩放先不做。
 * 焦点用 FLAG_NOT_FOCUSABLE —— 不抢宝正在用的 App 的焦点，代价是窗里打不了字
 * （要打字再说，那得换一套焦点策略）。
 */
class FloatingWebService : Service() {

    companion object {
        private const val TAG = "FloatingWebService"
        private const val CHANNEL_ID = "floating_web"
        private const val NOTIF_ID = 0x5E12
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val ACTION_STOP = "me.rerere.rikkahub.FLOATING_WEB_STOP"

        /** 有没有「显示在其他应用上层」的权限 */
        fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

        /** 开一个网页浮窗。已经开着的话换网址，不再叠第二个。 */
        fun start(context: Context, url: String, title: String? = null) {
            val intent = Intent(context, FloatingWebService::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** 关掉浮窗 */
        fun stop(context: Context) {
            context.startService(
                Intent(context, FloatingWebService::class.java).apply { action = ACTION_STOP }
            )
        }
    }

    private lateinit var windowManager: WindowManager
    private var root: FrameLayout? = null
    private var webView: WebView? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            teardown()
            stopSelf()
            return START_NOT_STICKY
        }

        val url = intent?.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            Log.w(TAG, "empty url, stop")
            stopSelf()
            return START_NOT_STICKY
        }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""

        startForegroundCompat()

        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "no overlay permission — 去设置里开「显示在其他应用上层」")
            stopSelf()
            return START_NOT_STICKY
        }

        val existing = root
        if (existing == null) {
            attachWindow(url, title)
        } else {
            // 已经开着：换网址，顺便把窗提到前面
            webView?.loadUrl(url)
            params?.let { runCatching { windowManager.updateViewLayout(existing, it) } }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 挂窗

    private fun attachWindow(url: String, title: String) {
        val ctx = this
        val widthPx = dp(330)
        val heightPx = dp(460)

        val container = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#F7F5F2"))
            }
            elevation = dp(10).toFloat()
            clipToOutline = true
        }

        // 顶部把手：拖它移动，右边 ✕ 关
        val handle = TextView(ctx).apply {
            text = if (title.isBlank()) "网页浮窗 · 拖我" else title
            setTextColor(Color.parseColor("#8A8078"))
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            setPadding(dp(14), 0, dp(8), 0)
        }
        val close = TextView(ctx).apply {
            text = "✕"
            setTextColor(Color.parseColor("#9A8F86"))
            textSize = 15f
            gravity = Gravity.CENTER
        }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EDE8E2"))
            }
            addView(handle, LinearLayout.LayoutParams(0, dp(36), 1f))
            addView(close, LinearLayout.LayoutParams(dp(42), dp(36)))
        }

        val wv = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            // 链接留在窗里，别跳到浏览器
            webViewClient = WebViewClient()
            setBackgroundColor(Color.WHITE)
            loadUrl(url)
        }

        container.addView(bar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(36), Gravity.TOP
        ))
        container.addView(wv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { topMargin = dp(36) })

        val lp = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(18)
            y = dp(140)
        }

        // 拖动
        var dragDx = 0f
        var dragDy = 0f
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragDx = e.rawX - lp.x
                    dragDy = e.rawY - lp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = (e.rawX - dragDx).roundToInt()
                    lp.y = (e.rawY - dragDy).roundToInt()
                    runCatching { windowManager.updateViewLayout(container, lp) }
                    true
                }
                else -> false
            }
        }

        close.setOnClickListener {
            teardown()
            stopSelf()
        }

        try {
            windowManager.addView(container, lp)
        } catch (e: Exception) {
            Log.e(TAG, "addView failed", e)
            runCatching { wv.destroy() }
            stopSelf()
            return
        }

        root = container
        webView = wv
        params = lp
    }

    private fun teardown() {
        webView?.let { wv ->
            runCatching {
                wv.stopLoading()
                wv.loadUrl("about:blank")
                wv.destroy()
            }
        }
        root?.let { v ->
            runCatching { windowManager.removeView(v) }
        }
        webView = null
        root = null
        params = null
    }

    // ---------------------------------------------------------------- 通知

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "网页浮窗", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("网页浮窗开着")
                .setContentText("点浮窗右上角 ✕ 关掉")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("网页浮窗开着")
                .setContentText("点浮窗右上角 ✕ 关掉")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setOngoing(true)
                .build()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).roundToInt()
}
