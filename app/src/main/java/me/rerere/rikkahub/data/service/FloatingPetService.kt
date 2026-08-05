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
import android.graphics.PixelFormat
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
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

/**
 * 悬浮桌宠服务：把透明 GIF 显示成全局悬浮窗，漂浮在手机桌面上。
 *
 * - 素材路径（按顺序查找第一个存在的）：
 *   1. 公共目录 /sdcard/OrangePet/pet.gif（需要「所有文件访问」权限）
 *   2. App 私有外部目录 getExternalFilesDir(null)/OrangePet/pet.gif（无需权限）
 * - 服务启动时会自动创建两个 OrangePet 文件夹。
 * - 拖动桌宠移动位置，松手自动贴边。
 * - 常驻前台服务（通知常驻），关闭开关后通过 ACTION_STOP 停止。
 */
class FloatingPetService : Service() {

    companion object {
        private const val TAG = "FloatingPetService"
        private const val FOREGROUND_NOTIF_ID = 20004

        const val ACTION_STOP = "me.rerere.rikkahub.STOP_PET"
        const val PET_DIR_NAME = "OrangePet"
        const val PET_FILE_NAME = "pet.gif"

        private const val PET_SIZE_DP = 140
        private const val CLICK_THRESHOLD_PX = 16f

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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
        showPet()

        // 桌宠常驻：进程被系统回收后尝试重建
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
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

        val sizePx = dp(PET_SIZE_DP)

        val imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            contentDescription = "悬浮桌宠"
        }

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
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

    /** 用 Coil 加载本地透明 GIF（coil.gif 已内置，自动循环播放）。execute 是挂起函数，必须在协程里调 */
    private fun loadGif(imageView: ImageView, file: File) {
        scope.launch {
            runCatching {
                val loader = SingletonImageLoader.get(this@FloatingPetService)
                val request = ImageRequest.Builder(this@FloatingPetService)
                    .data(file)
                    .target(ImageViewTarget(imageView))
                    .build()
                loader.execute(request)
            }.onFailure {
                Log.w(TAG, "Failed to load pet gif", it)
            }
        }
    }

    /** 拖动 + 贴边（复用悬浮球的交互套路） */
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
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (abs(dx) > CLICK_THRESHOLD_PX || abs(dy) > CLICK_THRESHOLD_PX) {
                        isDragging = true
                    }
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    runCatching { windowManager.updateViewLayout(view, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        snapToEdge(view, params)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToEdge(view: View, params: WindowManager.LayoutParams) {
        val sizePx = dp(PET_SIZE_DP)
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

    private fun removePetInternal() {
        petView?.let { v ->
            runCatching { windowManager.removeView(v) }
        }
        petView = null
        layoutParams = null
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
