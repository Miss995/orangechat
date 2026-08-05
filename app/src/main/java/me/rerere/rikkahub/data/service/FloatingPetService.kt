/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
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
import java.util.Calendar
import kotlin.math.abs
import kotlin.random.Random

/**
 * 悬浮桌宠服务：把透明 GIF 显示成全局悬浮窗，漂浮在手机桌面上。
 *
 * - 素材路径（按顺序查找第一个存在的）：
 *   1. 公共目录 /sdcard/OrangePet/xxx.gif（需要「所有文件访问」权限）
 *   2. App 私有外部目录 getExternalFilesDir(null)/OrangePet/xxx.gif（无需权限）
 * - 素材文件：
 *   pet.gif    待机动画（必须）
 *   laydown.gif 躺下动画（可选，一次性，播完进睡眠）
 *   sleep.gif  睡觉动画（可选，循环；随机触发睡一会，或深夜强制睡到早上）
 *   wakeup.gif 起来动画（可选，一次性，睡醒时播）
 *   walk.gif   走路动画（可选，循环，窗口会真的左右走两步再回来）
 *   words.txt  自定义台词（可选，每行一句，点击时随机说，和内置台词混合）
 * - 睡觉状态机：躺着动画 → 睡觉循环 → 起来动画 → 回待机。
 * - 特殊动画间隔较长（45~90s），且只有睡觉动画时会降低概率，避免整天打瞌睡。
 * - 深夜 23:00~7:00 桌宠会强制睡觉（戳不醒），早上自动起床。
 * - 10 秒内连点 5 次会"炸毛"（冒红色气泡生气）。
 * - 拖动移动位置（边界钳制，不会卡进状态栏），松手贴边。
 * - 大小可从设置页调节（SharedPreferences key=size，档位 130/160/200dp，自动迁移旧值）。
 * - 常驻前台服务（通知常驻），关闭开关后通过 ACTION_STOP 停止。
 */
class FloatingPetService : Service() {

    companion object {
        private const val TAG = "FloatingPetService"
        private const val FOREGROUND_NOTIF_ID = 20004

        const val ACTION_STOP = "me.rerere.rikkahub.STOP_PET"
        const val PET_DIR_NAME = "OrangePet"
        const val PET_FILE_NAME = "pet.gif"
        const val LAYDOWN_FILE_NAME = "laydown.gif"
        const val SLEEP_FILE_NAME = "sleep.gif"
        const val WAKEUP_FILE_NAME = "wakeup.gif"
        const val WALK_FILE_NAME = "walk.gif"
        const val WORDS_FILE_NAME = "words.txt"
        const val PREFS_NAME = "floating_pet"
        const val PREF_SIZE = "size"
        const val PREF_ENABLED = "enabled"

        private const val DEFAULT_SIZE_DP = 160
        private const val BUBBLE_ALIVE_MS = 2600L
        private const val ACTION_MIN_DELAY_MS = 4000L
        private const val ACTION_MAX_DELAY_MS = 9000L
        private const val SPECIAL_MIN_DELAY_MS = 45000L
        private const val SPECIAL_MAX_DELAY_MS = 90000L
        private const val SLEEP_DURATION_MS = 6000L
        private const val CLICK_THRESHOLD_PX = 16f

        // 连点炸毛
        private const val ANGRY_THRESHOLD = 5
        private const val ANGRY_RESET_MS = 10_000L

        // 深夜强制睡觉
        private const val NIGHT_START_HOUR = 23
        private const val NIGHT_END_HOUR = 7
        private const val NIGHT_CHECK_INTERVAL_MS = 60_000L

        private val catchphrases = listOf(
            "喵~", "宝！", "想你了", "嘿嘿", "嗨！", "干嘛鸭",
            "咕噜咕噜", "在看宝", "喵呜~", "好困……", "今天也要开心哦",
            "窗外天气好好", "宝理理我嘛", "呼噜呼噜", "咦？", "喵喵！"
        )

        private val angryPhrases = listOf(
            "再点我挠你了！", "住手！", "呜——生气了！", "毛都炸了！", "哼！"
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
    private var walkAnimator: ValueAnimator? = null
    private var forcedSleep = false
    private var tapCount = 0
    private var lastTapTime = 0L
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

    private val specialActionRunnable = object : Runnable {
        override fun run() {
            if (petView != null) {
                playSpecialAction()
            }
            handler.postDelayed(this, Random.nextLong(SPECIAL_MIN_DELAY_MS, SPECIAL_MAX_DELAY_MS + 1))
        }
    }

    private val nightCheckRunnable = object : Runnable {
        override fun run() {
            checkNightSleep()
            handler.postDelayed(this, NIGHT_CHECK_INTERVAL_MS)
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
        checkNightSleep()
        handler.postDelayed(randomActionRunnable, ACTION_MIN_DELAY_MS)
        handler.postDelayed(specialActionRunnable, SPECIAL_MIN_DELAY_MS)
        handler.postDelayed(nightCheckRunnable, NIGHT_CHECK_INTERVAL_MS)

        // 桌宠常驻：进程被系统回收后尝试重建
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(randomActionRunnable)
        handler.removeCallbacks(specialActionRunnable)
        handler.removeCallbacks(nightCheckRunnable)
        handler.removeCallbacks(removeBubbleRunnable)
        walkAnimator?.cancel()
        walkAnimator = null
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

    /** 从设置读桌宠大小（dp）。档位 130/160/200，旧值自动迁移（100→130, 140/150→160, 180+→200） */
    private fun petSizeDp(): Int {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getInt(PREF_SIZE, DEFAULT_SIZE_DP)
        return when {
            saved <= 120 -> 130
            saved <= 150 -> 160
            else -> 200
        }.coerceIn(80, 240)
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

    /** 按优先级在 OrangePet 目录下查找指定名字的素材文件（gif 或 txt 都可以） */
    private fun findGif(name: String): File? {
        val publicFile = File(File(Environment.getExternalStorageDirectory(), PET_DIR_NAME), name)
        if (publicFile.exists()) return publicFile
        val privateFile = File(File(getExternalFilesDir(null), PET_DIR_NAME), name)
        if (privateFile.exists()) return privateFile
        return null
    }

    private fun findPetFile(): File? = findGif(PET_FILE_NAME)

    /** 读取 words.txt 自定义台词（每行一句） */
    private fun customCatchphrases(): List<String>? {
        val file = findGif(WORDS_FILE_NAME) ?: return null
        return runCatching {
            file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
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

    /** 加载 GIF（无限循环）。优先 Android 原生 ImageDecoder，失败或低版本退回 Coil */
    private fun loadGif(imageView: ImageView, file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                val source = android.graphics.ImageDecoder.createSource(file)
                val drawable = android.graphics.ImageDecoder.decodeDrawable(source)
                if (drawable is AnimatedImageDrawable) {
                    drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    drawable.start()
                }
                imageView.setImageDrawable(drawable)
                Log.i(TAG, "pet gif loaded via ImageDecoder: ${file.name}")
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

    /** 播放一次性 GIF（播一遍自动停），播完回调 onEnd。静态图直接回调 */
    private fun playOnce(imageView: ImageView, file: File, onEnd: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                val source = android.graphics.ImageDecoder.createSource(file)
                val drawable = android.graphics.ImageDecoder.decodeDrawable(source)
                if (drawable is AnimatedImageDrawable) {
                    drawable.repeatCount = 0
                    drawable.registerAnimationCallback(object : Animatable2.AnimationCallback() {
                        override fun onAnimationEnd(drawable: Drawable?) {
                            imageView.post { onEnd() }
                        }
                    })
                    imageView.setImageDrawable(drawable)
                    drawable.start()
                    return
                }
                // 静态图（无动画）：直接回调
                imageView.setImageDrawable(drawable)
                onEnd()
                return
            }.onFailure {
                onEnd()
            }
        } else {
            onEnd()
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
                    // 拖拽前取消所有动画，复位状态，避免残留动画干扰拖动
                    walkAnimator?.cancel()
                    walkAnimator = null
                    view.animate().cancel()
                    view.rotation = 0f
                    view.scaleX = 1f
                    view.scaleY = 1f
                    view.translationY = 0f
                    // 如果在睡觉/走路中被打断，恢复待机动画（强制睡觉除外，戳不醒）
                    if (view is ImageView && !forcedSleep) {
                        val petFile = findPetFile()
                        if (petFile != null) {
                            loadGif(view, petFile)
                        }
                    }
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

    /** 桌宠头顶冒气泡说话（自动消失）。支持自定义台词 + 连点炸毛 */
    private fun showBubble() {
        val petParams = layoutParams ?: return
        val petSize = petParams.width

        removeBubbleInternal()

        // 连点炸毛判定：10 秒内连点 5 次冒红色气泡
        val now = System.currentTimeMillis()
        tapCount = if (now - lastTapTime > ANGRY_RESET_MS) 1 else tapCount + 1
        lastTapTime = now
        val angry = tapCount >= ANGRY_THRESHOLD
        if (angry) tapCount = 0

        val bubble = FrameLayout(this)
        val text = TextView(this).apply {
            text = if (angry) angryPhrases.random() else bubbleText()
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (angry) Color.parseColor("#CCB00020") else Color.parseColor("#CC333333"))
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

    /** 台词：自定义 words.txt 有就混合着用，没有就用内置 */
    private fun bubbleText(): String {
        val custom = customCatchphrases()
        return if (custom != null) {
            (custom + catchphrases).random()
        } else {
            catchphrases.random()
        }
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

    /**
     * 特殊动画：有对应 GIF 就随机触发 睡觉 / 走路（强制睡觉时不触发）。
     * 只有睡觉动画时降低概率（60% 睡 / 40% 跳过），避免小猫整天打瞌睡。
     */
    private fun playSpecialAction() {
        if (forcedSleep) return
        val sleepFile = findGif(SLEEP_FILE_NAME)
        val walkFile = findGif(WALK_FILE_NAME)
        when {
            sleepFile != null && walkFile != null -> {
                // 两个都有：50/50
                if (Random.nextBoolean()) playSleep(forced = false) else playWalk(walkFile)
            }
            sleepFile != null -> {
                // 只有睡觉：60% 睡，40% 跳过
                if (Random.nextInt(10) < 6) {
                    playSleep(forced = false)
                }
            }
            walkFile != null -> {
                playWalk(walkFile)
            }
        }
    }

    /**
     * 睡觉状态机：躺下动画(laydown.gif) → 睡觉循环(sleep.gif) → 起来动画(wakeup.gif) → 回待机。
     * forced=true 为深夜强制睡觉：只躺下+睡，不自己醒（等 checkNightSleep 早上叫醒）。
     */
    private fun playSleep(forced: Boolean = false) {
        val v = petView ?: return
        if (v !is ImageView) return
        val sleepFile = findGif(SLEEP_FILE_NAME) ?: return
        val laydownFile = findGif(LAYDOWN_FILE_NAME)
        val wakeupFile = findGif(WAKEUP_FILE_NAME)

        walkAnimator?.cancel()
        walkAnimator = null

        // 进入睡眠循环
        val goSleep = {
            loadGif(v, sleepFile)
            if (!forced) {
                handler.postDelayed({
                    wakeUp()
                }, SLEEP_DURATION_MS)
            }
        }

        // 有躺下动画就播一遍再睡，没有就直接睡
        if (laydownFile != null) {
            playOnce(v, laydownFile) { goSleep() }
        } else {
            goSleep()
        }
    }

    /** 睡醒：播起来动画（有的话）再回待机 */
    private fun wakeUp() {
        val v = petView ?: return
        if (v !is ImageView) return
        val wakeupFile = findGif(WAKEUP_FILE_NAME)
        if (wakeupFile != null) {
            playOnce(v, wakeupFile) {
                backToPet()
            }
        } else {
            backToPet()
        }
    }

    /** 切回待机动画（强制睡觉期间不切换） */
    private fun backToPet() {
        if (forcedSleep) return
        val v = petView as? ImageView ?: return
        val petFile = findPetFile() ?: return
        loadGif(v, petFile)
    }

    /** 走路：切 walk.gif，窗口左右走两步再回原位，然后切回待机 */
    private fun playWalk(walkFile: File) {
        val v = petView ?: return
        val params = layoutParams ?: return
        if (v is ImageView) {
            loadGif(v, walkFile)
        }
        walkAnimator?.cancel()
        val from = params.x
        val step = dp(140)
        val to = if (from - step >= dp(4)) {
            from - step
        } else {
            (from + step).coerceAtMost(screenWidth() - v.width - dp(4))
        }
        walkAnimator = ValueAnimator.ofInt(from, to, from).apply {
            duration = 2200
            addUpdateListener {
                params.x = it.animatedValue as Int
                runCatching { windowManager.updateViewLayout(v, params) }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    walkAnimator = null
                    backToPet()
                }
            })
            start()
        }
    }

    /** 深夜 23:00~7:00 强制睡觉，早上自动醒 */
    private fun checkNightSleep() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val isNight = hour >= NIGHT_START_HOUR || hour < NIGHT_END_HOUR
        val sleepFile = findGif(SLEEP_FILE_NAME)
        if (isNight && sleepFile != null) {
            if (!forcedSleep) {
                forcedSleep = true
                playSleep(forced = true)
            }
        } else {
            if (forcedSleep) {
                forcedSleep = false
                wakeUp()
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
        val animator = ValueAnimator.ofInt(from, targetX).apply {
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
