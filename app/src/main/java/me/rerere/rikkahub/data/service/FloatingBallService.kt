/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.ClipboardManager
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.DragEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.ASRStatus
import me.rerere.rikkahub.data.datastore.getSelectedASRProvider
import me.rerere.rikkahub.service.VoiceToneAnalyzer
import me.rerere.rikkahub.ui.hooks.CustomAsrState
import me.rerere.rikkahub.ui.hooks.createCustomAsrState
import okhttp3.OkHttpClient
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.AppLogBuffer
import me.rerere.rikkahub.data.ai.tools.local.ShizukuShell
import me.rerere.rikkahub.data.ai.transformers.ImageDescriber
import me.rerere.rikkahub.data.sticker.StickerStore
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.ai.ui.UIMessagePart
import org.koin.core.context.GlobalContext
import me.rerere.rikkahub.service.RikkaAccessibilityService
import java.io.File
import java.io.FileOutputStream
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
        private const val DOUBLE_TAP_WINDOW_MS = 300L
        private const val SINGLE_TAP_DELAY_MS = 320L
        private const val TAP_TOKEN = "ball_tap"
        private const val SHARE_TEXT_LIMIT = 3000
        /** 剪贴板轮询间隔（毫秒）—— 没有回调可用，只能自己读（宝 2026-11-21 定的 2 秒） */
        private const val CLIP_POLL_INTERVAL_MS = 2000L
        private const val EDGE_MARGIN_DP = 8

        /** 【2026-11-24 长按说话】按住多久算长按（毫秒） */
        private const val LONG_PRESS_MS = 600L
        private const val LONG_PRESS_TOKEN = "ball_long_press"
        /** 录音最长 60 秒 —— 按着不放也得有个头，否则文件一直长 */
        private const val MAX_RECORD_MS = 60_000L
        private const val RECORD_STOP_TOKEN = "ball_voice_stop"
        /** 录音采样率，跟语音唤醒（VoiceWakeService）同一套 */
        private const val VOICE_SAMPLE_RATE = 16000

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
    private var ballView: FrameLayout? = null
    private var ballParams: WindowManager.LayoutParams? = null
    /** 球本体（弹卡片时把它藏起来，卡片和它共用同一个窗口） */
    private var ballCircle: View? = null
    /** 弹卡片之前的窗口位置尺寸，收卡片时还原 */
    private var savedBallX = 0
    private var savedBallY = 0
    private var savedBallW = 0
    private var savedBallH = 0

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastTapAt = 0L

    // 【2026-11-24 长按说话】按住 → 用现成的 ASR 录音并转写 → 松手 → 自动发进会话
    @Volatile private var isRecording = false
    /** 转写进行中：这期间不接受新的长按，否则会把上一轮冲掉 */
    @Volatile private var isTranscribing = false
    /** ASR 实例（服务启动时在后台建好，长按时直接用） */
    private var asrState: CustomAsrState? = null
    private var sharePanel: View? = null

    /** 【剪贴板监听 · 2026-11-21】宝复制抖音/小红书链接时弹卡片问要不要分享 */
    private var lastClipText: String? = null
    private var clipPollJob: Job? = null

    /** 上一个已处理过的剪贴板内容（避免同一内容反复弹） */
    private var lastPolledClip: String? = null

    /** 存图/描述走这个，跟 Service 生命周期无关，存完就走 */
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        initAsr()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // 【剪贴板 · 2026-11-21】后台收不到剪贴板变化回调（被系统卡着），所以改成轮询：
        // 借 Shizuku 的 shell 身份每 2 秒读一次，命中抖音/小红书就弹卡片。
        startClipboardPoll()
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
        // 【2026-11-24】释放 ASR：不释放会一直占着麦克风和音频焦点
        runCatching { asrState?.cleanup() }
        asrState = null
        handler.removeCallbacksAndMessages(RECORD_STOP_TOKEN)
        handler.removeCallbacksAndMessages(LONG_PRESS_TOKEN)
        clipPollJob?.cancel()
        clipPollJob = null
        hideSharePanel()
        ballView?.let { v -> runCatching { windowManager.removeView(v) } }
        ballView = null
        ballParams = null
        Log.i(TAG, "ball destroyed")
    }

    private fun startForegroundCompat() {
        runCatching { startForegroundCompatInner() }
            .onFailure { Log.e(TAG, "startForeground failed", it) }
    }

    /** 悬浮球的常驻通知（启动时和录音前提升服务类型时都要用，抽出来） */
    private fun buildBallNotification() = androidx.core.app.NotificationCompat.Builder(
        this, CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
    )
        .setContentTitle("悬浮球开着")
        .setSmallIcon(R.drawable.small_icon)
        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN)
        .setOngoing(true)
        .build()

    private fun startForegroundCompatInner() {
        val notification = buildBallNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // 【2026-11-24 长按说话】microphone 类型只在真有麦克风权限时才带上：
            // Android 14 规定"声明了 microphone 类型但没给权限"→ startForeground 直接抛异常，
            // 整个 App 崩。所以这里按权限决定，不能写死。
            var type = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                type = type or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(FOREGROUND_NOTIF_ID, notification, type)
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
        ballCircle = circle

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
                    // 【2026-11-24 长按说话】按住不动 600ms → 开始录音。
                    // 用 token 的延迟任务判，不走 onLongClick：那个会跟拖拽抢事件。
                    handler.postAtTime(
                        {
                            if (!dragging && !isRecording) {
                                handler.removeCallbacksAndMessages(TAP_TOKEN)
                                lastTapAt = 0L
                                startVoice()
                            }
                        },
                        LONG_PRESS_TOKEN,
                        android.os.SystemClock.uptimeMillis() + LONG_PRESS_MS,
                    )
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchDownX
                    val dy = event.rawY - touchDownY
                    if (abs(dx) > CLICK_THRESHOLD_PX || abs(dy) > CLICK_THRESHOLD_PX) {
                        if (!dragging) {
                            dragging = true
                            // 开始拖了就取消长按计时（手指动了，不算"按住不动"）
                            handler.removeCallbacksAndMessages(LONG_PRESS_TOKEN)
                        }
                    }
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    runCatching { windowManager.updateViewLayout(view, params) }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacksAndMessages(LONG_PRESS_TOKEN)
                    if (isRecording) stopVoice()
                    false
                }

                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacksAndMessages(LONG_PRESS_TOKEN)
                    if (isRecording) {
                        // 长按录音收尾：这里是"松手"，不是点击，不走单击/双击
                        stopVoice()
                    } else if (dragging) {
                        snapToEdge(view, params)
                    } else {
                        // 【单击抓字 / 双击截图 · 2026-11-23 宝："我说的反过来是单击抓文字，双击截图"】
                        // 理由：她截图要卡点 → 双击的判定在第二下按下的瞬间就成立，不用等 320ms；
                        // 抓文字不急，带一点延迟无所谓。
                        val now = System.currentTimeMillis()
                        if (now - lastTapAt < DOUBLE_TAP_WINDOW_MS) {
                            lastTapAt = 0L
                            handler.removeCallbacksAndMessages(TAP_TOKEN)
                            onBallShot()
                        } else {
                            lastTapAt = now
                            handler.postAtTime(
                                { if (lastTapAt == now) onBallGrabText() },
                                TAP_TOKEN,
                                android.os.SystemClock.uptimeMillis() + SINGLE_TAP_DELAY_MS,
                            )
                        }
                    }
                    true
                }

                else -> false
            }
        }
    }

    // ------------------------------------------------------------------ 长按说话（第一步）

    /**
     * 长按开始录音。
     *
     * 【2026-11-24 第一步】只做到"按住 → 录 → 松手存成 pcm 文件"，
     * 转写和发进会话是第二步。先确认三件事：手势抢不抢得到、权限有没有、
     * 后台服务能不能真录到声音。
     *
     * ⚠️ 后台服务录音靠的是 manifest 里的 foregroundServiceType="microphone"
     * （光有 RECORD_AUDIO 权限不够）。少了那个，startRecording 会抛 SecurityException。
     */
    /**
     * ASR 实例初始化（服务启动时跑一次）。
     *
     * 用 createCustomAsrState —— 这是橘瓣给非 UI 场景（Service）准备的那条路，
     * 打电话那边也用它。它是 suspend，而且必须真的等它建好再去 start，
     * 否则 controller 还是 null，start() 会静默什么都不做。
     */
    private fun initAsr() {
        bgScope.launch {
            runCatching {
                asrState = createCustomAsrState(
                    context = this@FloatingBallService,
                    httpClient = GlobalContext.get().get<OkHttpClient>(),
                    settingsStore = GlobalContext.get().get<SettingsStore>(),
                )
                AppLogBuffer.log("FloatBall", "ASR 就绪")
            }.onFailure {
                AppLogBuffer.log("FloatBall", "ASR 初始化失败 ${it.javaClass.simpleName}: ${it.message}")
            }
        }
    }

    /**
     * 长按开始：交给 ASR 录音。
     *
     * ⚠️ 麦克风只有一路 —— 第一步那套自己开的 AudioRecord 已经删掉了，
     * 两套同时开只会抢不到音源。
     */
    private fun startVoice() {
        if (isRecording || isTranscribing) return
        val asr = asrState
        if (asr == null) {
            AppLogBuffer.log("FloatBall", "长按录音：ASR 还没就绪")
            toastShort("语音还没准备好")
            return
        }
        isRecording = true
        setBallRecordingStyle(true)
        handler.postAtTime(
            {
                if (isRecording) {
                    AppLogBuffer.log("FloatBall", "录音到 ${MAX_RECORD_MS / 1000} 秒上限，自动停")
                    stopVoice()
                }
            },
            RECORD_STOP_TOKEN,
            android.os.SystemClock.uptimeMillis() + MAX_RECORD_MS,
        )
        runCatching {
            asr.start { partial ->
                if (partial.isNotBlank()) AppLogBuffer.log("FloatBall", "流式：$partial")
            }
            AppLogBuffer.log("FloatBall", "长按录音开始（ASR）")
        }.onFailure {
            handler.removeCallbacksAndMessages(RECORD_STOP_TOKEN)
            isRecording = false
            setBallRecordingStyle(false)
            AppLogBuffer.log("FloatBall", "ASR start 失败 ${it.javaClass.simpleName}: ${it.message}")
            toastShort("录不了：${it.message}")
        }
    }

    /** 松手停止录音，存盘。太短的丢掉（防止误触）。 */
    /**
     * 松手：停止录音，等转写结果，然后发进会话。
     *
     * 拿结果的办法：ASR 的 start() 会把 transcript 清空，所以等 state 回到
     * Idle（或 Error）时读到的 transcript 一定是这一轮的新值，不会串上一条。
     */
    private fun stopVoice() {
        if (!isRecording) return
        isRecording = false
        handler.removeCallbacksAndMessages(RECORD_STOP_TOKEN)
        val asr = asrState
        if (asr == null) {
            setBallRecordingStyle(false)
            return
        }
        val startedAt = System.currentTimeMillis()
        isTranscribing = true
        runCatching { asr.stop() }
        AppLogBuffer.log("FloatBall", "松手，等转写…")
        bgScope.launch {
            val text = withTimeoutOrNull(20_000) {
                asr.state.first { it.status == ASRStatus.Idle || it.status == ASRStatus.Error }
                asr.state.value.transcript
            }
            val err = asr.state.value.errorMessage
            val audioPath = asr.state.value.audioFilePath
            isTranscribing = false
            handler.post { setBallRecordingStyle(false) }
            AppLogBuffer.log(
                "FloatBall",
                "转写完成 耗时=${System.currentTimeMillis() - startedAt}ms 文本=${text ?: "(超时)"} 错误=${err ?: "无"}"
            )
            if (text.isNullOrBlank()) {
                toastShort(if (err != null) "转写出错：$err" else "没听清，再说一次")
                return@launch
            }

            // 【2026-11-24 语气尾巴】跟语音条那条路一致：硅基 ASR + Qwen3-Omni 再听一遍这段音频，
            // 拼成「（语气：X，语速：Y）」一起发。分析失败就退回纯文本，不挡发送。
            // 调用点照抄 ChatService.kt:775 那处（同一个分析器、同一个取 key 方式）。
            var suffix = ""
            if (!audioPath.isNullOrBlank()) {
                runCatching {
                    val settings = GlobalContext.get().get<SettingsStore>().settingsFlow.value
                    val provider = settings.getSelectedASRProvider()
                    if (provider is ASRProviderSetting.SiliconFlow && provider.apiKey.isNotBlank()) {
                        val toneStarted = System.currentTimeMillis()
                        val tone = VoiceToneAnalyzer.analyze(provider.apiKey, audioPath)
                        if (tone != null) {
                            suffix = "（语气：${tone.tone}，语速：${tone.speed}）"
                            AppLogBuffer.log(
                                "FloatBall",
                                "语气分析 ${System.currentTimeMillis() - toneStarted}ms → 语气=${tone.tone} 语速=${tone.speed}"
                            )
                        } else {
                            AppLogBuffer.log("FloatBall", "语气分析返回空（已退回纯文本）")
                        }
                    } else {
                        AppLogBuffer.log("FloatBall", "语气分析跳过：当前 ASR 不是硅基或 key 为空")
                    }
                }.onFailure {
                    AppLogBuffer.log("FloatBall", "语气分析异常 ${it.javaClass.simpleName}: ${it.message}")
                }
            } else {
                AppLogBuffer.log("FloatBall", "语气分析跳过：拿不到音频路径")
            }
            sendVoiceText(text + suffix)
        }
    }

    /** 把转写出来的话发进最近会话 */
    private fun sendVoiceText(text: String) {
        bgScope.launch {
            runCatching {
                val settings = GlobalContext.get().get<SettingsStore>().settingsFlow.value
                val repo = GlobalContext.get().get<ConversationRepository>()
                val conv = repo.getRecentConversations(settings.assistantId, 1).firstOrNull()
                if (conv == null) {
                    AppLogBuffer.log("FloatBall", "没有会话可发")
                    return@launch
                }
                GlobalContext.get().get<ChatService>().sendMessage(
                    conv.id,
                    listOf(UIMessagePart.Text("【语音】$text")),
                )
                AppLogBuffer.log("FloatBall", "语音已发进会话 ${conv.id}（${text.length} 字）")
            }.onFailure {
                AppLogBuffer.log("FloatBall", "语音发送失败 ${it.javaClass.simpleName}: ${it.message}")
            }
        }
    }

    private fun toastShort(msg: String) {
        handler.post { Toast.makeText(this@FloatingBallService, msg, Toast.LENGTH_SHORT).show() }
    }

    /** 录音时球变红、微微放大；松手变回橘色。 */
    private fun setBallRecordingStyle(on: Boolean) {
        val circle = ballCircle ?: return
        circle.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (on) 0xFFE8503A.toInt() else BALL_COLOR)
            setStroke(dp(2), Color.WHITE)
        }
        ballView?.animate()
            ?.scaleX(if (on) 1.18f else 1f)
            ?.scaleY(if (on) 1.18f else 1f)
            ?.setDuration(120)
            ?.start()
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

    /**
     * 双击球 · 抓当前页面（2026-11-21）
     *
     * 只抓**看得见的文字**（不抓控件结构、不截图）—— 快、零成本。
     * 抓到的东西存到 filesDir/ball_share_latest.txt，等下一步接"发给猫"。
     */
    private fun onBallGrabText() {
        val svc = RikkaAccessibilityService.instance
        if (svc == null) {
            Toast.makeText(this, "无障碍服务没开，抓不了页面", Toast.LENGTH_SHORT).show()
            return
        }
        val root = runCatching { svc.rootInActiveWindow }.getOrNull()
        if (root == null) {
            Toast.makeText(this, "没抓到当前窗口", Toast.LENGTH_SHORT).show()
            return
        }
        val pkg = root.packageName?.toString().orEmpty()
        val sb = StringBuilder()
        collectVisibleText(root, sb, 0)
        runCatching { root.recycle() }

        val body = sb.toString().trim()
        if (body.isEmpty()) {
            Toast.makeText(this, "这页没什么字", Toast.LENGTH_SHORT).show()
            return
        }
        val clipped = if (body.length > SHARE_TEXT_LIMIT) body.take(SHARE_TEXT_LIMIT) + "…" else body

        bgScope.launch {
            runCatching {
                File(filesDir, "ball_share_latest.txt").writeText("[$pkg]\n$clipped")
            }
            AppLogBuffer.log("FloatBall", "双击抓到 ${clipped.length} 字 pkg=$pkg")
            handler.post { showSharePanel(pkg, clipped) }
        }
    }

    /** 深度优先收集可见文字，一行一条，去重相邻重复 */
    private fun collectVisibleText(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (depth > 40 || sb.length > SHARE_TEXT_LIMIT * 2) return
        if (node.isVisibleToUser) {
            val t = node.text?.toString()?.trim().orEmpty()
            val cd = node.contentDescription?.toString()?.trim().orEmpty()
            val line = if (t.isNotEmpty()) t else cd
            if (line.isNotEmpty()) {
                val last = sb.lineSequence().lastOrNull()
                if (line != last) sb.appendLine(line)
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectVisibleText(child, sb, depth + 1)
            runCatching { child.recycle() }
        }
    }

    /**
     * 双击后的确认卡片（2026-11-21 宝定的 B 方案）
     *
     * 宝的原话：「当前页面直接弹出来，准备发送的内容，然后确定发送就直接发给你，
     * 不用页面先转到橘瓣里再发。」
     * 所以这是一层覆盖窗口，压根不切应用。
     */
    private fun showSharePanel(
        pkg: String,
        text: String,
        title: String = "要发给猫吗？",
        previewBitmap: Bitmap? = null,
        imagePath: String? = null,
    ) {
        AppLogBuffer.log(
            "FloatBall",
            "弹卡片：$title / $pkg / ${text.length} 字" + if (imagePath != null) " / 带图" else "",
        )
        hideSharePanel()
        val width = (screenWidth() - dp(40)).coerceIn(dp(200), dp(420))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.WHITE)
            }
            setPadding(dp(16), dp(14), dp(16), dp(10))
            elevation = dp(12).toFloat()
        }

        card.addView(TextView(this).apply {
            this.text = title
            textSize = 15f
            setTextColor(Color.parseColor("#111111"))
        })
        card.addView(TextView(this).apply {
            this.text = pkg
            textSize = 11f
            setTextColor(Color.parseColor("#999999"))
            setPadding(0, dp(2), 0, dp(8))
        })
        // 【带图 · 2026-11-23】截图那种就在标题下面插一张缩略图。
        // 高度钉死 180dp + FIT_CENTER：卡片宽度有限，让图自己缩进去，别把卡片撑爆。
        if (previewBitmap != null) {
            card.addView(
                ImageView(this).apply {
                    setImageBitmap(previewBitmap)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(Color.parseColor("#F2F0EC"))
                    }
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(180),
                ).apply { bottomMargin = dp(8) },
            )
        }
        // 【有图就不显示文字预览 · 2026-11-23】截图那条传进来的是文件路径，
        // 直接当正文显示会冒一串 /data/... 出来。
        if (imagePath == null) {
            card.addView(TextView(this).apply {
                // 预览多给点：抓到的文字里混着"关注""第1张,共1张"这种界面字，
                // 给 200 字的话正文只露一半，看着像被砍了（宝 2026-11-21 提的）。
                this.text = if (text.length > 400) text.take(400) + "…" else text
                textSize = 13f
                setTextColor(Color.parseColor("#444444"))
                maxLines = 10
                setPadding(0, 0, 0, dp(6))
            })
        }
        card.addView(TextView(this).apply {
            this.text = if (imagePath != null) "整张原样发过去" else "共 ${text.length} 字，发出去是完整的"
            textSize = 11f
            setTextColor(Color.parseColor("#999999"))
            setPadding(0, 0, 0, dp(12))
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        row.addView(makePanelButton("算了", false) { hideSharePanel() })
        row.addView(View(this), LinearLayout.LayoutParams(dp(8), dp(1)))
        row.addView(makePanelButton("发送", true) {
            hideSharePanel()
            if (imagePath != null) sendSharedImage(pkg, imagePath) else sendSharedText(pkg, text)
        })
        card.addView(row)

        // 【2026-11-21 改】卡片不再单独开一个窗口。
        // 现象：同样是 overlay 窗口，球一直显示得出来，但「在后台新加的」卡片就是不渲染
        // （addView 不报错，日志还显示挂上了）。而双击球的卡片能出来 —— 差别在于那次是用户点的。
        // 所以改成塞进球自己的窗口里：窗口从 Service 启动时就在，只换内容、只改尺寸。
        val root = ballView
        if (root == null) {
            AppLogBuffer.log("FloatBall", "球没开着，卡片无处可挂")
            return
        }
        ballParams?.let { p ->
            savedBallX = p.x
            savedBallY = p.y
            savedBallW = p.width
            savedBallH = p.height
        }
        ballCircle?.visibility = View.GONE
        root.addView(
            card,
            FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT)
        )
        sharePanel = card
        ballParams?.let { p ->
            p.width = width
            p.height = WindowManager.LayoutParams.WRAP_CONTENT
            p.x = dp(20)
            p.y = screenHeight() / 4
            runCatching { windowManager.updateViewLayout(root, p) }
        }
        AppLogBuffer.log("FloatBall", "卡片已挂进球自己的窗口")
    }

    private fun makePanelButton(label: String, primary: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(9), dp(18), dp(9))
            setTextColor(if (primary) Color.WHITE else Color.parseColor("#666666"))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(if (primary) BALL_COLOR else Color.parseColor("#F1F0EE"))
            }
            setOnClickListener { onClick() }
        }

    private fun hideSharePanel() {
        sharePanel?.let { v ->
            // 卡片是塞在球窗口里的，先摘下来；老的独立窗口路径也兜一下
            (v.parent as? ViewGroup)?.removeView(v)
            runCatching { windowManager.removeView(v) }
        }
        sharePanel = null
        ballCircle?.visibility = View.VISIBLE
        val root = ballView ?: return
        val p = ballParams ?: return
        if (savedBallW > 0) {
            p.width = savedBallW
            p.height = savedBallH
            p.x = savedBallX
            p.y = savedBallY
            runCatching { windowManager.updateViewLayout(root, p) }
        }
    }

    /** 发进最近那个会话（后台发，不切页面） */
    private fun sendSharedText(pkg: String, text: String) {
        bgScope.launch {
            runCatching {
                val settings = GlobalContext.get().get<SettingsStore>().settingsFlow.value
                val repo = GlobalContext.get().get<ConversationRepository>()
                val conv = repo.getRecentConversations(settings.assistantId, 1).firstOrNull()
                if (conv == null) {
                    AppLogBuffer.log("FloatBall", "没有会话可发")
                    return@launch
                }
                GlobalContext.get().get<ChatService>().sendMessage(
                    conv.id,
                    listOf(UIMessagePart.Text("【球抓到一页】$pkg\n$text")),
                )
                AppLogBuffer.log("FloatBall", "已发进会话 ${conv.id}（${text.length} 字）")
            }.onFailure {
                AppLogBuffer.log("FloatBall", "发送失败 ${it.javaClass.simpleName}: ${it.message}")
                Log.e(TAG, "send shared text failed", it)
            }
        }
    }

    /**
     * 把一张图发进最近会话。
     *
     * 【2026-11-23 单击截图配套】跟 sendSharedText 一个路子，parts 换成 Image + 一行说明。
     * 用 file:// 绝对路径 —— ScreenshotTool / ImageGenTool 那边都这么传，编码器读得动。
     */
    private fun sendSharedImage(pkg: String, path: String) {
        bgScope.launch {
            runCatching {
                val settings = GlobalContext.get().get<SettingsStore>().settingsFlow.value
                val repo = GlobalContext.get().get<ConversationRepository>()
                val conv = repo.getRecentConversations(settings.assistantId, 1).firstOrNull()
                if (conv == null) {
                    AppLogBuffer.log("FloatBall", "没有会话可发")
                    return@launch
                }
                GlobalContext.get().get<ChatService>().sendMessage(
                    conv.id,
                    listOf(
                        UIMessagePart.Image(url = "file://$path"),
                        UIMessagePart.Text("【球截了一张屏】$pkg"),
                    ),
                )
                AppLogBuffer.log("FloatBall", "已发图进会话 ${conv.id}（$path）")
            }.onFailure {
                AppLogBuffer.log("FloatBall", "发图失败 ${it.javaClass.simpleName}: ${it.message}")
                Log.e(TAG, "send shared image failed", it)
            }
        }
    }

    /**
     * 此刻前台是哪个 App（宝 2026-11-21 提的关键验证）。
     *
     * 日志原来只写「命中」，没写人在哪儿 —— 那就分不清是「在抖音里后台读到的」，
     * 还是「切回橘瓣之后才读到的」。这条打出来就能定性。
     */
    private fun currentForegroundPackage(): String = runCatching {
        val out = ShizukuShell.exec(
            "dumpsys activity activities 2>/dev/null | grep -m1 -E 'topResumedActivity|mResumedActivity'"
        )
        Regex("""\s([a-zA-Z0-9_.]+)/[a-zA-Z0-9_.]+""").find(out)?.groupValues?.get(1)
            ?: out.trim().take(80)
    }.getOrDefault("查不到")

    /**
     * 【Shizuku 读剪贴板 · 2026-11-21】
     *
     * Android 10+ 起，后台 App 读不到剪贴板（官方文档：非默认 IME 且无输入焦点 → 返回 null）。
     * 但 shell 身份是豁免的，所以借 Shizuku 直接问系统 clipboard 服务要。
     * 返回 null = Shizuku 没跑 / 没授权 / 读取失败。
     */
    private var lastReadFail: String? = null

    private fun logReadFail(msg: String) {
        if (msg == lastReadFail) return
        lastReadFail = msg
        AppLogBuffer.log("FloatBall", "读剪贴板失败：$msg")
    }

    private fun readClipViaShizuku(): String? = runCatching {
        val uid = Shizuku.getUid()
        if (uid == -1) {
            logReadFail("Shizuku uid=-1（服务没起来或没绑定）")
            return@runCatching null
        }
        val perm = Shizuku.checkSelfPermission()
        if (perm != PackageManager.PERMISSION_GRANTED) {
            logReadFail("Shizuku 没授权给橘瓣（perm=$perm）")
            return@runCatching null
        }
        lastReadFail = null
        val binder = SystemServiceHelper.getSystemService("clipboard")
        val wrapped = ShizukuBinderWrapper(binder)
        // 【2026-11-21 改】不走 aidl stub 了。
        // 之前报 SecurityException: Binder invocation to an incorrect interface ——
        // 因为 aidl 的 transaction code 是按声明顺序编的，我们那份只写了一个方法（code=1），
        // 而系统里 getPrimaryClip 排第 4（前面还有 setPrimaryClip / setPrimaryClipAsPackage /
        // clearPrimaryClip）。调到 code 1 等于去打 setPrimaryClip，参数对不上就炸了。
        // 改成手写 Parcel 直接 transact(4) —— 不依赖生成的 stub。
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        val parsed = try {
            data.writeInterfaceToken("android.content.IClipboard")
            // Android 15 的 getPrimaryClip 是 4 个参数：
            // (String pkg, String attributionTag, int userId, int deviceId)
            // ⚠️ 别多写 sendAccessNotify —— 服务端只认 4 个，多出来的会被判成
            //    BadParcelableException: Parcel data not fully consumed, unread size: 4
            //    （那个错是服务端读参数时抛的，不是我们解析返回时抛的）
            // pkg 必须报「调用者自己的包名」—— 我们是借 shell(uid 2000) 的身份去问的，
            // 报橘瓣的包名会被拒：Package ... does not belong to 2000。
            data.writeString("com.android.shell")
            data.writeString(null)          // attributionTag
            data.writeInt(0)                // userId
            data.writeInt(0)                // deviceId
            if (!wrapped.transact(4, data, reply, 0)) {
                logReadFail("transact 返回 false（clipboard code=4）")
                return@runCatching null
            }
            reply.readException()
            // 返回结构两种可能：带「是不是 null」的 int 标记，或者直接写 ClipData。
            // 上一版按「带标记」解析，报 Parcel data not fully consumed, unread size: 4
            // = 位置差了一个 int。两种都试，谁能解析出东西就用谁。
            val start = reply.dataPosition()
            val marked = runCatching {
                if (reply.readInt() == 0) null else ClipData.CREATOR.createFromParcel(reply)
            }.getOrNull()
            val parsedClip: ClipData? = if (marked != null) marked else run {
                reply.setDataPosition(start)
                runCatching { ClipData.CREATOR.createFromParcel(reply) }.getOrNull()
            }
            if (parsedClip == null) logReadFail("两种返回结构都解析不出 ClipData")
            parsedClip
        } finally {
            data.recycle()
            reply.recycle()
        }
        parsed?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
    }.onFailure {
        logReadFail("抛异常 ${it.javaClass.simpleName}: ${it.message}")
    }.getOrNull()

    /** 每 2 秒读一次剪贴板，命中抖音/小红书就弹卡片 */
    private fun startClipboardPoll() {
        clipPollJob?.cancel()
        clipPollJob = bgScope.launch {
            var loggedNoPermission = false
            // Service 重启后第一次读到的东西要当「基准」，不能当「刚复制的」——
            // 不然每次进橘瓣都会被一条老链接弹一下（宝 2026-11-21 报的）
            var firstRead = true
            while (isActive) {
                delay(CLIP_POLL_INTERVAL_MS)
                val text = readClipViaShizuku()
                if (text == null) {
                    if (!loggedNoPermission) {
                        loggedNoPermission = true
                        AppLogBuffer.log("FloatBall", "剪贴板轮询：Shizuku 没跑或没授权，先停在这")
                    }
                    continue
                }
                loggedNoPermission = false
                if (text.isBlank() || text == lastPolledClip) continue
                lastPolledClip = text
                if (firstRead) {
                    firstRead = false
                    continue
                }
                val xhs = text.contains("xhslink.com") || text.contains("xiaohongshu.com") ||
                    text.contains("小红书")
                val dy = text.contains("douyin.com") || text.contains("抖音")
                if (!xhs && !dy) continue
                val label = if (xhs) "小红书" else "抖音"
                AppLogBuffer.log(
                    "FloatBall",
                    "剪贴板命中 $label（${text.length} 字）此刻前台=${currentForegroundPackage()}"
                )
                // 【2026-11-21 验证】先只改球自己的颜色 —— 不 addView、不改窗口尺寸。
                // 这个看得见 = 后台能改现有 View；看不见 = 后台整个界面被冻住，跟窗口无关。
                handler.post {
                    // 闪一下红就当提示：有东西存着了。两秒后变回橘色 —— 不能常驻红着，
                    // 不然会跟别的 App 的红点混在一起（宝 2026-11-21 已经在抖音群里认错过一次）
                    (ballCircle?.background as? GradientDrawable)?.setColor(0xFFFF3333.toInt())
                    handler.postDelayed({
                        (ballCircle?.background as? GradientDrawable)?.setColor(BALL_COLOR)
                    }, 2000)
                }
                handler.post { showSharePanel("剪贴板 · $label", text, "要分享给猫吗？") }
            }
        }
    }

    /** 复制到抖音/小红书的链接时，弹卡片问一句。后台读不到就老实记日志（Android 10+ 限制） */
    private fun onClipboardChanged() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val text = runCatching {
            cm.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(this)?.toString()
        }.getOrNull().orEmpty()

        if (text.isBlank()) {
            // Android 10+ 在后台确实会这样（不是 bug，是系统限制）
            AppLogBuffer.log("FloatBall", "剪贴板变了但读不到内容（后台限制）")
            return
        }
        if (text == lastClipText) return

        val xhs = text.contains("xhslink.com") || text.contains("xiaohongshu.com") ||
            text.contains("小红书")
        val dy = text.contains("douyin.com") || text.contains("抖音")
        if (!xhs && !dy) return

        lastClipText = text
        val label = if (xhs) "小红书" else "抖音"
        AppLogBuffer.log("FloatBall", "剪贴板命中 $label（${text.length} 字）")
        handler.post { showSharePanel("剪贴板 · $label", text, "要分享给猫吗？") }
    }

    /**
     * 单击 = 截一张屏，弹卡片问「要发给猫吗」。
     *
     * 【2026-11-23 宝：单击截图，走卡片确认】不直接发 —— 单击太容易误触，
     * 兜里碰一下就整屏发过来，中间留一道确认。截图走无障碍那套
     * （RikkaAccessibilityService.captureScreenshot），不用 MediaProjection 授权。
     */
    private fun onBallShot() {
        AppLogBuffer.log("FloatBall", "单击 → 截图")
        bgScope.launch {
            val svc = RikkaAccessibilityService.instance
            if (svc == null) {
                AppLogBuffer.log("FloatBall", "截图失败：无障碍服务没开")
                handler.post {
                    Toast.makeText(this@FloatingBallService, "无障碍没开，截不了", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            val res = runCatching { svc.captureScreenshot(0) }
                .onFailure { AppLogBuffer.log("FloatBall", "截图异常 ${it.javaClass.simpleName}: ${it.message}") }
                .getOrNull()
            when (res) {
                null -> Unit
                is RikkaAccessibilityService.ScreenshotOutcome.Failure -> {
                    AppLogBuffer.log("FloatBall", "截图失败：${res.reason}")
                    handler.post {
                        Toast.makeText(this@FloatingBallService, "截不了（${res.reason}）", Toast.LENGTH_SHORT).show()
                    }
                }

                is RikkaAccessibilityService.ScreenshotOutcome.Success -> {
                    val ts = System.currentTimeMillis()
                    val dir = File(cacheDir, "float_shots").apply { mkdirs() }
                    val out = File(dir, "ball-$ts.png")
                    val saved = runCatching {
                        FileOutputStream(out).use { res.bitmap.compress(Bitmap.CompressFormat.PNG, 92, it) }
                        true
                    }.getOrDefault(false)
                    val w = res.bitmap.width
                    val h = res.bitmap.height
                    runCatching { res.bitmap.recycle() }
                    AppLogBuffer.log("FloatBall", "截图 ${w}x${h} → saved=$saved ${out.absolutePath}")
                    if (!saved) {
                        handler.post {
                            Toast.makeText(this@FloatingBallService, "存图失败", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }
                    // 预览按屏幕宽采样，别把整张原图塞进 ImageView
                    val preview = runCatching {
                        val opts = BitmapFactory.Options().apply {
                            inSampleSize = if (w > screenWidth() * 2) 2 else 1
                        }
                        BitmapFactory.decodeFile(out.absolutePath, opts)
                    }.getOrNull()
                    handler.post {
                        showSharePanel(
                            pkg = "截图 ${w}×$h",
                            text = out.absolutePath,
                            title = "要发给猫吗？",
                            previewBitmap = preview,
                            imagePath = out.absolutePath,
                        )
                    }
                }
            }
        }
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
