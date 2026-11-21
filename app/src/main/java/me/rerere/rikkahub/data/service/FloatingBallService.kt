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
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
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

        private const val BALL_SIZE_DP = 48
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

        /** 关掉球 */
        fun hide(context: Context) {
            val intent = Intent(context, FloatingBallService::class.java).apply {
                action = ACTION_HIDE
            }
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "Failed to stop ball service", it) }
        }
    }

    private lateinit var windowManager: WindowManager
    private var ballView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HIDE) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat()

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
        val sizePx = dp(BALL_SIZE_DP)

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
        Log.i(TAG, "DROPPED items=${clip?.itemCount ?: 0} ${describe(clip)}")
        Toast.makeText(this, "球接到了 ${clip?.itemCount ?: 0} 个东西", Toast.LENGTH_SHORT).show()
    }

    private fun onBallClick() {
        // 先占位，功能后面接（双击分享 / 单击开面板）
        Toast.makeText(this, "球还只是个球", Toast.LENGTH_SHORT).show()
    }

    private fun snapToEdge(view: View, params: WindowManager.LayoutParams) {
        val sizePx = dp(BALL_SIZE_DP)
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

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        Resources.getSystem().displayMetrics
    ).toInt()

    private fun screenWidth(): Int = Resources.getSystem().displayMetrics.widthPixels
    private fun screenHeight(): Int = Resources.getSystem().displayMetrics.heightPixels
}

private val BALL_COLOR = 0xFFFF8800.toInt()
