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
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.DragEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
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
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.AppLogBuffer
import me.rerere.rikkahub.data.ai.transformers.ImageDescriber
import me.rerere.rikkahub.data.sticker.StickerStore
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.ai.ui.UIMessagePart
import org.koin.core.context.GlobalContext
import me.rerere.rikkahub.service.RikkaAccessibilityService
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

        /** 【剪贴板检查 · 2026-11-21】橘瓣回到前台时读到了抖音/小红书链接，交给球弹卡片 */
        const val ACTION_CHECK_CLIP = "me.rerere.rikkahub.ACTION_CHECK_CLIP"
        const val EXTRA_CLIP_TEXT = "clip_text"
        const val EXTRA_CLIP_LABEL = "clip_label"

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
        /**
         * 【剪贴板检查 · 2026-11-21】
         * 由 RouteActivity.onResume 调用 —— 后台读不到剪贴板（Android 10+ 限制），
         * 只有切回橘瓣那一刻有焦点才读得到，读到就交给这里弹卡片。
         */
        fun checkClipboard(context: Context, text: String, label: String) {
            val intent = Intent(context, FloatingBallService::class.java).apply {
                action = ACTION_CHECK_CLIP
                putExtra(EXTRA_CLIP_TEXT, text)
                putExtra(EXTRA_CLIP_LABEL, label)
            }
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "checkClipboard failed", it) }
        }

        fun hide(context: Context) {
            runCatching { context.stopService(Intent(context, FloatingBallService::class.java)) }
                .onFailure { Log.w(TAG, "Failed to stop ball service", it) }
        }
    }

    private lateinit var windowManager: WindowManager
    private var ballView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastTapAt = 0L
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

        if (intent?.action == ACTION_CHECK_CLIP) {
            val clipText = intent.getStringExtra(EXTRA_CLIP_TEXT).orEmpty()
            val clipLabel = intent.getStringExtra(EXTRA_CLIP_LABEL).orEmpty()
            if (clipText.isNotBlank()) {
                AppLogBuffer.log("FloatBall", "剪贴板命中 $clipLabel（${clipText.length} 字）")
                handler.post { showSharePanel("剪贴板 · $clipLabel", clipText, "要分享给猫吗？") }
            }
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
                    if (dragging) {
                        snapToEdge(view, params)
                    } else {
                        // 双击 = 把当前页面抓给猫；单击延后 320ms，等不到第二下才算单击
                        val now = System.currentTimeMillis()
                        if (now - lastTapAt < DOUBLE_TAP_WINDOW_MS) {
                            lastTapAt = 0L
                            handler.removeCallbacksAndMessages(TAP_TOKEN)
                            onBallDoubleTap()
                        } else {
                            lastTapAt = now
                            handler.postAtTime(
                                { if (lastTapAt == now) onBallClick() },
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
    private fun onBallDoubleTap() {
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
    private fun showSharePanel(pkg: String, text: String, title: String = "要发给猫吗？") {
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
        card.addView(TextView(this).apply {
            // 预览多给点：抓到的文字里混着"关注""第1张,共1张"这种界面字，
            // 给 200 字的话正文只露一半，看着像被砍了（宝 2026-11-21 提的）。
            this.text = if (text.length > 400) text.take(400) + "…" else text
            textSize = 13f
            setTextColor(Color.parseColor("#444444"))
            maxLines = 10
            setPadding(0, 0, 0, dp(6))
        })
        card.addView(TextView(this).apply {
            this.text = "共 ${text.length} 字，发出去是完整的"
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
            sendSharedText(pkg, text)
        })
        card.addView(row)

        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        runCatching { windowManager.addView(card, params) }
            .onSuccess { sharePanel = card }
            .onFailure { Log.e(TAG, "add share panel failed", it) }
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
        sharePanel?.let { v -> runCatching { windowManager.removeView(v) } }
        sharePanel = null
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
     * 【Shizuku 读剪贴板 · 2026-11-21】
     *
     * Android 10+ 起，后台 App 读不到剪贴板（官方文档：非默认 IME 且无输入焦点 → 返回 null）。
     * 但 shell 身份是豁免的，所以借 Shizuku 直接问系统 clipboard 服务要。
     * 返回 null = Shizuku 没跑 / 没授权 / 读取失败。
     */
    private fun readClipViaShizuku(): String? = runCatching {
        if (Shizuku.getUid() == -1) return@runCatching null
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            return@runCatching null
        }
        val binder = SystemServiceHelper.getSystemService("clipboard")
        val svc = IClipboard.Stub.asInterface(ShizukuBinderWrapper(binder))
        val clip = svc.getPrimaryClip(packageName, null, 0, 0, false)
        clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
    }.getOrNull()

    /** 每 2 秒读一次剪贴板，命中抖音/小红书就弹卡片 */
    private fun startClipboardPoll() {
        clipPollJob?.cancel()
        clipPollJob = bgScope.launch {
            var loggedNoPermission = false
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
                val xhs = text.contains("xhslink.com") || text.contains("xiaohongshu.com") ||
                    text.contains("小红书")
                val dy = text.contains("douyin.com") || text.contains("抖音")
                if (!xhs && !dy) continue
                val label = if (xhs) "小红书" else "抖音"
                AppLogBuffer.log("FloatBall", "剪贴板命中 $label（${text.length} 字）")
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
