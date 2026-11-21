/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.AppLogBuffer
import me.rerere.rikkahub.data.ai.transformers.ImageDescriber
import me.rerere.rikkahub.data.sticker.StickerStore
import java.io.File
import kotlin.math.abs

/**
 * 常驻悬浮球（2026-11-21）
 *
 * 跟 [FloatingBubbleService] 的区别：那个是「主动消息来了弹一下、60 秒自己消失」的提醒球；
 * 这个是**一直飘着**的球，用来当拖拽接收口 —— 不用先打开橘瓣，把东西拖到球上就行。
 *
 * 现在这一版只做球本身：
 * - 常驻（START_STICKY，被杀会尝试重启）
 * - 拖动改位置、松手吸附左右边
 * - 点击先弹一个提示（功能后面接）
 * - **已经埋好拖拽接收**（setOnDragListener），收到什么东西现在只打日志 + 提示，
 *   真正「存表情包」那一步等确认 overlay 窗口能不能收到跨应用拖拽之后再接。
 *
 * ⚠️ 窗口 flags 里**不能加 FLAG_NOT_TOUCHABLE** —— 加了就收不到拖拽。
 */
class FloatingBallService : Service() {

    companion object {
        private const val TAG = "FloatingBallService"
        const val ACTION_SHOW = "me.rerere.rikkahub.ACTION_SHOW_FLOAT_BALL"
        const val ACTION_HIDE = "me.rerere.rikkahub.ACTION_HIDE_FLOAT_BALL"

        const val DEFAULT_BALL_SIZE_DP = 48
        /** 球径可调范围（dp）—— 设置页那根滑杆就是按这个走的，2026-11-21 从三档改成自由拖 */
        const val MIN_BALL_SIZE_DP = 24
        const val MAX_BALL_SIZE_DP = 80
        private const val FOREGROUND_NOTIF_ID = 20004
        private const val CLICK_THRESHOLD_PX = 16f
        private const val EDGE_MARGIN_DP = 8

        /** 启动（或唤醒）常驻球 */
        fun show(context: Context) {
            val intent = Intent(context, FloatingBallService::class.java).apply {
                action = ACTION_SHOW
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.e(TAG, "Failed to start ball service", it) }
        }

        /**
         * 关掉球。
         *
         * ⚠️ 2026-11-21 崩溃修：原来这里是 startService(ACTION_HIDE)，让服务自己 stopSelf。
         * 但那条路进来时**没调 startForeground**，一旦这次启动是 startForegroundService 发起的
         * （改大小时 hide+show 撞在一起、或系统 START_STICKY 重启），系统就报
         * ForegroundServiceDidNotStartInTimeException，直接把整个 App 崩掉。
         * 关就用 stopService —— 它压根不启动服务，最干净。
         */
        fun hide(context: Context) {
            runCatching { context.stopService(Intent(context, FloatingBallService::class.java)) }
                .onFailure { Log.w(TAG, "Failed to stop ball service", it) }
        }
    }

    private lateinit var windowManager: WindowManager
    private var ballView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null

    /** 存图/描述走这个，跟 Service 生命周期无关，存完就走 */
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ⚠️ 必须无条件先 startForeground —— 只要这次启动来自 startForegroundService，
        // 5 秒内不进前台系统就抛 ForegroundServiceDidNotStartInTimeException（整个 App 崩）。
        // 所以这一行放在所有分支之前，任何 return 都不许跑到它前面去。
        startForegroundCompat()

        if (intent?.action == ACTION_HIDE) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "No overlay permission, stop")
            stopSelf()
            return START_NOT_STICKY
        }

        if (ballView == null) {
            createBall()
            Log.i(TAG, "ball created")
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        ballView?.let { v -> runCatching { windowManager.removeView(v) } }
        ballView = null
        ballParams = null
        Log.i(TAG, "ball destroyed")
    }

    private fun startForegroundCompat() {
        runCatching { startForegroundCompatInner() }
            .onFailure { Log.e(TAG, "startForeground failed", it) }
    }

    private fun startForegroundCompatInner() {
        val notification = androidx.core.app.NotificationCompat.Builder(
            this, CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
        )
            .setContentTitle("悬浮球开着")
            .setSmallIcon(R.drawable.small_icon)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                FOREGROUND_NOTIF_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(FOREGROUND_NOTIF_ID, notification)
        }
    }

    private fun createBall() {
        val sizePx = dp(ballSizeDp())

        val ball = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
            elevation = dp(8).toFloat()
        }

        // 球本体：一个橘色的圆 + 细白边
        val circle = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(BALL_COLOR)
                setStroke(dp(2), Color.WHITE)
            }
        }
        ball.addView(circle, FrameLayout.LayoutParams(sizePx, sizePx))

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            // ⚠️ 不能加 FLAG_NOT_TOUCHABLE，否则接收不到拖拽
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth() - sizePx - dp(EDGE_MARGIN_DP)
            y = screenHeight() / 3
        }

        setupDragAndDrop(ball)
        setupTouch(ball, params)

        try {
            windowManager.addView(ball, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add ball view", e)
            stopSelf()
            return
        }

        ballView = ball
        ballParams = params
    }

    /** 拖动 + 点击 */
    private fun setupTouch(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var touchDownX = 0f
        var touchDownY = 0f
        var dragging = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchDownX = event.rawX
                    touchDownY = event.rawY
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchDownX
                    val dy = event.rawY - touchDownY
                    if (abs(dx) > CLICK_THRESHOLD_PX || abs(dy) > CLICK_THRESHOLD_PX) {
                        dragging = true
                    }
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    runCatching { windowManager.updateViewLayout(view, params) }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (dragging) snapToEdge(view, params) else onBallClick()
                    true
                }

                else -> false
            }
        }
    }

    /**
     * 拖拽接收。
     *
     * ⚠️ ACTION_DRAG_STARTED 必须返回 true，后面的 ENTERED / DROP 才收得到。
     * 这一版只把「收到了什么」记下来，真正存表情包那步还没接。
     */
    private fun setupDragAndDrop(view: View) {
        view.setOnDragListener { v, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> {
                    Log.i(TAG, "drag started: label=${event.clipDescription?.label}")
                    true
                }

                DragEvent.ACTION_DRAG_ENTERED -> {
                    v.scaleX = 1.18f
                    v.scaleY = 1.18f
                    true
                }

                DragEvent.ACTION_DRAG_EXITED -> {
                    v.scaleX = 1f
                    v.scaleY = 1f
                    true
                }

                DragEvent.ACTION_DROP -> {
                    v.scaleX = 1f
                    v.scaleY = 1f
                    onDrop(event.clipData)
                    true
                }

                DragEvent.ACTION_DRAG_ENDED -> {
                    v.scaleX = 1f
                    v.scaleY = 1f
                    true
                }

                else -> false
            }
        }
    }

    private fun describe(clip: ClipData?): String {
        if (clip == null) return "clip=null"
        val parts = StringBuilder("clip=$clip.itemCount")
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i)
            parts.append(" [").append(i).append(": uri=").append(item.uri)
                .append(" text=").append(item.text?.take(30)).append("]")
        }
        return parts.toString()
    }

    private fun onDrop(clip: ClipData?) {
        if (clip == null) return
        AppLogBuffer.log("FloatBall", "onDrop ${describe(clip)}")

        val uris = buildList {
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.let { add(it) }
            }
        }
        if (uris.isEmpty()) {
            AppLogBuffer.log("FloatBall", "onDrop: 没有 uri，可能只有 text")
            Toast.makeText(this, "球只认图片，这个拖不进来", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "球接到了 ${uris.size} 张，正在存", Toast.LENGTH_SHORT).show()

        bgScope.launch {
            val saved = mutableListOf<File>()
            for (uri in uris) {
                val path = runCatching { StickerStore.saveFromUri(this@FloatingBallService, uri) }
                    .onFailure { AppLogBuffer.log("FloatBall", "存失败 $uri -> ${it.javaClass.simpleName}: ${it.message}") }
                    .getOrNull()
                if (path != null) saved += File(path)
            }
            AppLogBuffer.log("FloatBall", "存了 ${saved.size} / ${uris.size} 张")
            val tip = if (saved.isEmpty()) {
                AppLogBuffer.log("FloatBall", "一张都没存成 —— 多半是读不了 content:// 的权限")
                "没存进去，看日志"
            } else {
                "存好了，${saved.size} 张"
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(this@FloatingBallService, tip, Toast.LENGTH_SHORT).show()
            }

            // 顺手让视觉模型看一眼，描述写进 sidecar。
            // 聊天页那条路本来就有这一步，球这条漏了 —— 宝 2026-11-21 发现的：
            // 「你一直不用表情包工具的话，表情包会一直没有介绍」。
            // 一张图一辈子只描述一次，已有的跳过。
            for (f in saved) {
                if (StickerStore.readNote(f) != null) continue
                val result = runCatching { ImageDescriber.describe("file://" + f.absolutePath) }
                if (result.isFailure) {
                    val e = result.exceptionOrNull()
                    StickerStore.saveNote(
                        f,
                        "【失败】describe 抛异常：" + (e?.message ?: e?.javaClass?.simpleName ?: "未知")
                    )
                    continue
                }
                val note = result.getOrNull()
                if (note != null) {
                    StickerStore.saveNote(f, note)
                    AppLogBuffer.log("FloatBall", "描述写好了 ${f.name}")
                } else {
                    StickerStore.saveNote(f, "【失败】describe 返回 null（多半是没配 OCR 模型）")
                    AppLogBuffer.log("FloatBall", "describe 返回 null（没配识别模型？）")
                }
            }
        }
    }

    private fun onBallClick() {
        // 先占位，功能后面接（双击分享 / 单击开面板）
        Toast.makeText(this, "球还只是个球", Toast.LENGTH_SHORT).show()
    }

    private fun snapToEdge(view: View, params: WindowManager.LayoutParams) {
        val sizePx = dp(ballSizeDp())
        val targetX = if (params.x + sizePx / 2 < screenWidth() / 2) {
            dp(EDGE_MARGIN_DP)
        } else {
            screenWidth() - sizePx - dp(EDGE_MARGIN_DP)
        }
        val from = params.x
        android.animation.ValueAnimator.ofInt(from, targetX).apply {
            duration = 180
            addUpdateListener {
                params.x = it.animatedValue as Int
                runCatching { windowManager.updateViewLayout(view, params) }
            }
            start()
        }
    }

    /** 当前球径（dp）—— 设置页那根滑杆拖出来的值，改完由设置页重启服务生效 */
    private fun ballSizeDp(): Int = runCatching {
        getSharedPreferences("floating_ball", Context.MODE_PRIVATE)
            .getInt("size", DEFAULT_BALL_SIZE_DP)
    }.getOrDefault(DEFAULT_BALL_SIZE_DP)
        .coerceIn(MIN_BALL_SIZE_DP, MAX_BALL_SIZE_DP)

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        Resources.getSystem().displayMetrics
    ).toInt()

    private fun screenWidth(): Int = Resources.getSystem().displayMetrics.widthPixels
    private fun screenHeight(): Int = Resources.getSystem().displayMetrics.heightPixels
}

private val BALL_COLOR = 0xFFFF8800.toInt()
