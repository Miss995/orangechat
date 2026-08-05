/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.target.ImageViewTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

/**
 * 悬浮桌宠服务：把透明 GIF 显示成全局悬浮窗，漂浮在手机桌面上。
 *
 * - 素材路径（按顺序查找第一个存在的）：
 *   1. 公共目录 /sdcard/OrangePet/pet.gif（需要「所有文件访问」权限）
 *   2. App 私有外部目录 getExternalFilesDir(null)/OrangePet/pet.gif（无需权限）
 * - 服务启动时会自动创建两个 OrangePet 文件夹。
 * - 拖动移动位置（边界钳制，不会卡进状态栏），松手贴边。
 * - 点击桌宠 → 头顶冒气泡说话。
 * - 没事会随机做小动作（跳一跳 / 晃一晃 / 呼吸缩放）。
 * - 大小可从设置页调节（SharedPreferences key=size，默认 140dp）。
 * - 常驻前台服务（通知常驻），关闭开关后通过 ACTION_STOP 停止。
 */
class FloatingPetService : Service() {

    companion object {
        private const val TAG = "FloatingPetService"
        private const val FOREGROUND_NOTIF_ID = 20004

        const val ACTION_STOP = "me.rerere.rikkahub.STOP_PET"
        const val PET_DIR_NAME = "OrangePet"
        const val PET_FILE_NAME = "pet.gif"
        const val PREFS_NAME = "floating_pet"
        const val PREF_SIZE = "size"
        const val PREF_ENABLED = "enabled"

        private const val DEFAULT_SIZE_DP = 140
        private const val BUBBLE_ALIVE_MS = 2600L
        private const val ACTION_MIN_DELAY_MS = 4000L
        private const val ACTION_MAX_DELAY_MS = 9000L
        private const val CLICK_THRESHOLD_PX = 16f

        private val catchphrases = listOf(
            "喵~", "宝！", "想你了", "嘿嘿", "嗨！", "干嘛鸭",
            "咕噜咕噜", "在看宝", "喵呜~", "好困……", "今天也要开心哦",
            "窗外天气好好", "宝理理我嘛", "呼噜呼噜", "咦？", "喵喵！"
        )

        fun start(context: Context) {
            val intent = Intent(context, FloatingPetService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingPetService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(intent) }
        }
    }

    private lateinit var windowManager: WindowManager
    private var petView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val removeBubbleRunnable = Runnable {
        removeBubbleInternal()
    }

    private val randomActionRunnable = object : Runnable {
        override fun run() {
            if (petView != null) {
                playRandomAction()
            }
            handler.postDelayed(this, Random.nextLong(ACTION_MIN_DELAY_MS, ACTION_MAX_DELAY_MS + 1))
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat()

        // 无悬浮窗权限就不显示（设置页开关会先引导授权）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "No overlay permission, skip showing pet")
            stopSelf()
            return START_NOT_STICKY
        }

        ensurePetDirs()
        removePetInternal()
        removeBubbleInternal()
        showPet()
        handler.postDelayed(randomActionRunnable, ACTION_MIN_DELAY_MS)

        // 桌宠常驻：进程被系统回收后尝试重建
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(randomActionRunnable)
        handler.removeCallbacks(removeBubbleRunnable)
        scope.cancel()
        removeBubbleInternal()
        removePetInternal()
    }

    private fun startForegroundCompat() {
        val notification = NotificationCompat.Builder(this, CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("🐱 橘仔桌宠正在桌面陪你")
            .setContentText("去设置里可以关闭它")
            .setSmallIcon(R.drawable.small_icon)
            .setPriority(NotificationCompat.PRIORITY_MIN)
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

    /** 从设置读桌宠大小（dp），默认 140，钳制在 80~240 */
    private fun petSizeDp(): Int {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(PREF_SIZE, DEFAULT_SIZE_DP).coerceIn(80, 240)
    }

    /** 自动创建素材文件夹（公共目录 + 私有目录都建） */
    private fun ensurePetDirs() {
        runCatching {
            File(Environment.getExternalStorageDirectory(), PET_DIR_NAME).mkdirs()
        }
        runCatching {
            File(getExternalFilesDir(null), PET_DIR_NAME).mkdirs()
        }
    }

    /** 按优先级查找 pet.gif */
    private fun findPetFile(): File? {
        val publicFile = File(File(Environment.getExternalStorageDirectory(), PET_DIR_NAME), PET_FILE_NAME)
        if (publicFile.exists()) return publicFile
        val privateFile = File(File(getExternalFilesDir(null), PET_DIR_NAME), PET_FILE_NAME)
        if (privateFile.exists()) return privateFile
        return null
    }

    private fun showPet() {
        val petFile = findPetFile()
        if (petFile == null) {
            Log.w(TAG, "pet.gif not found, showing placeholder text")
        }

        val sizePx = dp(petSizeDp())

        val imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "悬浮桌宠"
        }

        // 不启用 FLAG_LAYOUT_NO_LIMITS：避免窗口越界钻到状态栏后面卡住
        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth() - sizePx - dp(12)
            y = screenHeight() / 3
        }

        setupTouchListener(imageView, params)

        try {
            windowManager.addView(imageView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add pet view", e)
            stopSelf()
            return
        }

        petView = imageView
        layoutParams = params

        if (petFile != null) {
            loadGif(imageView, petFile)
        }
    }

    /**
     * 加载透明 GIF。
     * 优先用 Android 原生 ImageDecoder（API 28+）：解码 GIF 为 AnimatedImageDrawable，自动无限循环播放。
     * 失败或低版本再退回 Coil。
     */
    private fun loadGif(imageView: ImageView, file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                val source = android.graphics.ImageDecoder.createSource(file)
                val drawable = android.graphics.ImageDecoder.decodeDrawable(source)
                if (drawable is android.graphics.drawable.AnimatedImageDrawable) {
                    drawable.repeatCount = android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE
                    drawable.start()
                }
                imageView.setImageDrawable(drawable)
                Log.i(TAG, "pet gif loaded via ImageDecoder")
                return
            }.onFailure {
                Log.w(TAG, "ImageDecoder failed, fallback to Coil", it)
            }
        }
        // Coil 兜底（挂起函数放协程）
        scope.launch {
            runCatching {
                val loader = SingletonImageLoader.get(this@FloatingPetService)
                val request = ImageRequest.Builder(this@FloatingPetService)
                    .data(file)
                    .target(ImageViewTarget(imageView))
                    .build()
                loader.execute(request)
            }.onFailure {
                Log.w(TAG, "Failed to load pet gif via Coil", it)
            }
        }
    }

    /** 拖动 + 贴边 + 点击冒气泡。坐标有边界钳制：够不到状态栏、不出屏幕 */
    private fun setupTouchListener(
        view: View,
        params: WindowManager.LayoutParams,
    ) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    // 拖拽前取消随机动作动画，复位状态，避免残留动画干扰拖动
                    view.animate().cancel()
                    view.rotation = 0f
                    view.scaleX = 1f
                    view.scaleY = 1f
                    view.translationY = 0f
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (abs(dx) > CLICK_THRESHOLD_PX || abs(dy) > CLICK_THRESHOLD_PX) {
                        isDragging = true
                    }
                    params.x = (initialX + dx.toInt()).coerceIn(0, screenWidth() - view.width)
                    params.y = (initialY + dy.toInt()).coerceIn(
                        statusBarHeight() + dp(2),
                        screenHeight() - view.height - dp(4)
                    )
                    runCatching { windowManager.updateViewLayout(view, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        snapToEdge(view, params)
                    } else {
                        // 点击：头顶冒气泡
                        showBubble()
                    }
                    true
                }
                else -> false
            }
        }
    }

    /** 桌宠头顶冒气泡说话（自动消失） */
    private fun showBubble() {
        val petParams = layoutParams ?: return
        val petSize = petParams.width

        removeBubbleInternal()

        val bubble = FrameLayout(this)
        val text = TextView(this).apply {
            text = catchphrases.random()
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#CC333333"))
            }
        }
        bubble.addView(text, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        // 先测量，好把气泡定位在桌宠正上方
        bubble.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val bw = bubble.measuredWidth
        val bh = bubble.measuredHeight

        val bubbleLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (petParams.x + petSize / 2 - bw / 2).coerceIn(dp(4), screenWidth() - bw - dp(4))
            y = (petParams.y - bh - dp(10)).coerceAtLeast(statusBarHeight() + dp(2))
        }

        try {
            windowManager.addView(bubble, bubbleLp)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add bubble view", e)
            return
        }

        bubbleView = bubble
        bubbleParams = bubbleLp

        handler.removeCallbacks(removeBubbleRunnable)
        handler.postDelayed(removeBubbleRunnable, BUBBLE_ALIVE_MS)
    }

    /** 随机小动作：跳一跳 / 左右晃 / 呼吸缩放 */
    private fun playRandomAction() {
        val v = petView ?: return
        when (Random.nextInt(3)) {
            0 -> { // 小跳
                v.animate().translationY((-dp(18)).toFloat()).setDuration(180).withEndAction {
                    v.animate().translationY(0f).setDuration(180).start()
                }.start()
            }
            1 -> { // 左右晃
                v.animate().rotation(-6f).setDuration(140).withEndAction {
                    v.animate().rotation(6f).setDuration(140).withEndAction {
                        v.animate().rotation(0f).setDuration(140).start()
                    }.start()
                }.start()
            }
            2 -> { // 呼吸缩放
                v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(280).withEndAction {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(280).start()
                }.start()
            }
        }
    }

    private fun snapToEdge(view: View, params: WindowManager.LayoutParams) {
        val sizePx = view.width
        val targetX = if (params.x + sizePx / 2 < screenWidth() / 2) {
            dp(8)
        } else {
            screenWidth() - sizePx - dp(8)
        }
        val from = params.x
        val animator = android.animation.ValueAnimator.ofInt(from, targetX).apply {
            duration = 200
            addUpdateListener {
                params.x = it.animatedValue as Int
                runCatching { windowManager.updateViewLayout(view, params) }
            }
        }
        animator.start()
    }

    /** 状态栏高度（防止桌宠被拖进状态栏卡住） */
    private fun statusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            resources.getDimensionPixelSize(resourceId)
        } else {
            dp(24)
        }
    }

    private fun removePetInternal() {
        petView?.let { v ->
            runCatching { windowManager.removeView(v) }
        }
        petView = null
        layoutParams = null
    }

    private fun removeBubbleInternal() {
        bubbleView?.let { v ->
            runCatching { windowManager.removeView(v) }
        }
        bubbleView = null
        bubbleParams = null
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            Resources.getSystem().displayMetrics
        ).toInt()

    private fun screenWidth(): Int = Resources.getSystem().displayMetrics.widthPixels
    private fun screenHeight(): Int = Resources.getSystem().displayMetrics.heightPixels
}
