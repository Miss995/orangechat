/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.miniapp

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.HapticFeedbackConstants
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat.startActivity
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.TransactionHistory
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.LocalDarkMode

private const val TAG = "MiniAppPage"

/** 小程序 WebView 的统一配置 —— 页面里那只、和猫自己造的那只，共用这一套。 */
@SuppressLint("SetJavaScriptEnabled")
internal fun applyMiniAppWebViewSettings(wv: WebView) {
    wv.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        allowContentAccess = true
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        cacheMode = WebSettings.LOAD_DEFAULT
        // 像个普通浏览器：认 viewport、按页面宽度适配、允许缩放
        useWideViewPort = true
        loadWithOverviewMode = true
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
        // 音视频不强制"先点一下"才播
        mediaPlaybackRequiresUserGesture = false
        // 抹掉 WebView 的身份标记（UA 里的 "; wv"）。
        // 小红书/抖音看到它就认定"这是内嵌浏览器"，会弹"请打开 App"的拦截层，帖子根本不给看。
        userAgentString = userAgentString?.replace("; wv", "") ?: userAgentString
    }
    wv.webChromeClient = WebChromeClient()
}

/** 桌面版 UA：让网页以为自己在电脑上。★ 2026-10-26 改成 internal —— 猫的工具也要用它。 */
internal const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"

private val json = Json { ignoreUnknownKeys = true }

/** 地址栏里输什么就按什么处理：像网址就往那儿走，不像就丢去搜索。 */
private fun normalizeAddress(input: String): String {
    val t = input.trim()
    if (t.isEmpty()) return t
    val looksLikeUrl = t.startsWith("http://") || t.startsWith("https://") ||
        t.startsWith("file://") || t.startsWith("about:") || t.startsWith("data:")
    if (looksLikeUrl) return t
    if (t.contains(" ") || !t.contains(".") || t.startsWith("localhost")) {
        return "https://www.bing.com/search?q=" + java.net.URLEncoder.encode(t, "UTF-8")
    }
    return "https://$t"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiniAppPage(
    url: String,
    title: String?,
) {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val isDarkMode = LocalDarkMode.current
    val colorScheme = MaterialTheme.colorScheme

    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    val webView by webViewRef
    val toolScope = rememberCoroutineScope()
    var pageTitle by remember { mutableStateOf(title ?: "") }
    var isLoading by remember { mutableStateOf(true) }
    var currentUrl by remember { mutableStateOf(url) }
    var addressEditing by remember { mutableStateOf(false) }
    var addressText by remember { mutableStateOf(url) }
    var canBack by remember { mutableStateOf(false) }
    var canForward by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var desktopMode by remember { mutableStateOf(false) }
    var mainButtonVisible by remember { mutableStateOf(false) }
    var mainButtonText by remember { mutableStateOf("CONTINUE") }
    var mainButtonEnabled by remember { mutableStateOf(true) }
    var mainButtonProgress by remember { mutableStateOf(false) }
    var backButtonVisible by remember { mutableStateOf(false) }
    var popupData by remember { mutableStateOf<PopupData?>(null) }
    var confirmData by remember { mutableStateOf<ConfirmData?>(null) }
    var alertMessage by remember { mutableStateOf<String?>(null) }

    val bridgeScript = remember(colorScheme, isDarkMode) {
        buildTelegramBridgeScript(colorScheme, isDarkMode)
    }

    val miniAppWebViewClient = remember {
        MiniAppWebViewClient(
            bridgeScript = bridgeScript,
            onPageTitle = { pageTitle = it ?: title ?: "" },
            onLoadingChange = { isLoading = it },
            onUrlChange = { newUrl -> currentUrl = newUrl; if (!addressEditing) addressText = newUrl },
            onNavChange = { back, forward -> canBack = back; canForward = forward },
            onMainButton = { visible, text, enabled, progress ->
                mainButtonVisible = visible
                text?.let { mainButtonText = it }
                mainButtonEnabled = enabled
                mainButtonProgress = progress
            },
            onBackButton = { visible ->
                backButtonVisible = visible
            },
            onPopup = { popupData = it },
            onConfirm = { confirmData = it },
            onAlert = { alertMessage = it },
            onOpenLink = { link, tryInstantView ->
                openExternalLink(context, link, tryInstantView)
            },
            onOpenTelegramLink = { link ->
                openTelegramLink(context, link)
            },
            onClose = { navController.popBackStack() },
            onHaptic = { style ->
                webView?.performHapticFeedback(parseHapticStyle(style))
            },
            onToolRequest = { method, params ->
                val callbackId = params["callbackId"].orEmpty()
                toolScope.launch {
                    val payload = try {
                        MiniAppToolBridge.handle(method, params)
                    } catch (e: Exception) {
                        "{\"ok\":false,\"error\":\"${e.javaClass.simpleName}\"}"
                    }
                    webView?.evaluateJavascript(
                        "window.__bridgeResult('$callbackId', ${org.json.JSONObject.quote(payload)});",
                        null,
                    )
                }
            },
        )
    }

    BackHandler(enabled = true) {
        if (backButtonVisible) {
            webView?.evaluateJavascript("window.__bridgeFireEvent('backButtonClicked');", null)
        } else if (webView?.canGoBack() == true) {
            webView?.goBack()
        } else {
            navController.popBackStack()
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        if (addressEditing) {
                            OutlinedTextField(
                                value = addressText,
                                onValueChange = { addressText = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            Text(
                                text = currentUrl.ifBlank { pageTitle },
                                maxLines = 1,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.clickable {
                                    addressText = currentUrl
                                    addressEditing = true
                                },
                            )
                        }
                    },
                    navigationIcon = {
                        if (addressEditing) {
                            IconButton(onClick = { addressEditing = false }) {
                                Icon(HugeIcons.Cancel01, contentDescription = "取消")
                            }
                        } else if (canBack) {
                            // 能退就一定要能退 —— 这个排在最前，
                            // 免得页面（小红书这类）说自己接管了返回，结果又不管，宝就卡在里面出不来
                            IconButton(onClick = { webView?.goBack() }) {
                                Icon(HugeIcons.ArrowLeft01, contentDescription = "后退")
                            }
                        } else if (backButtonVisible) {
                            IconButton(onClick = {
                                webView?.evaluateJavascript(
                                    "window.__bridgeFireEvent('backButtonClicked');",
                                    null
                                )
                            }) {
                                Icon(HugeIcons.ArrowLeft01, contentDescription = "返回")
                            }
                        } else {
                            // 退无可退也不留空位：给一个关闭兜底
                            IconButton(onClick = {
                                // 明确的关闭：连 WebView 一起放掉，别让它在后台一直占着。
                                // （只是切走的话走不到这儿 —— 那种情况留着它，宝回来猫还能读。）
                                MiniAppWebViewHost.destroy()
                                navController.popBackStack()
                            }) {
                                Icon(HugeIcons.Cancel01, contentDescription = "关闭")
                            }
                        }
                    },
                    actions = {
                        if (addressEditing) {
                            IconButton(onClick = {
                                val target = addressText.trim()
                                addressEditing = false
                                if (target.isNotEmpty()) {
                                    webView?.loadUrl(normalizeAddress(target))
                                }
                            }) {
                                Icon(HugeIcons.ArrowRight01, contentDescription = "前往")
                            }
                        } else {
                            IconButton(onClick = { webView?.reload() }) {
                                Icon(HugeIcons.Refresh01, contentDescription = "刷新")
                            }
                            IconButton(
                                onClick = { webView?.goForward() },
                                enabled = canForward,
                            ) {
                                Icon(HugeIcons.ArrowRight01, contentDescription = "前进")
                            }
                            IconButton(onClick = { showMoreMenu = true }) {
                                Icon(HugeIcons.MoreVertical, contentDescription = "更多")
                                DropdownMenu(
                                    expanded = showMoreMenu,
                                    onDismissRequest = { showMoreMenu = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("在系统浏览器打开") },
                                        leadingIcon = { Icon(HugeIcons.Earth, contentDescription = null) },
                                        onClick = {
                                            showMoreMenu = false
                                            val target = currentUrl
                                            if (target.isNotBlank()) {
                                                try {
                                                    context.startActivity(
                                                        Intent(Intent.ACTION_VIEW, Uri.parse(target))
                                                    )
                                                } catch (_: ActivityNotFoundException) {
                                                }
                                            }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("复制链接") },
                                        onClick = {
                                            showMoreMenu = false
                                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                                as android.content.ClipboardManager
                                            cm.setPrimaryClip(
                                                android.content.ClipData.newPlainText("url", currentUrl)
                                            )
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(if (desktopMode) "桌面版 ✓" else "桌面版") },
                                        onClick = {
                                            showMoreMenu = false
                                            val next = !desktopMode
                                            desktopMode = next
                                            webView?.let { wv ->
                                                wv.settings.userAgentString =
                                                    if (next) DESKTOP_UA else WebSettings.getDefaultUserAgent(context)
                                                wv.settings.useWideViewPort = !next
                                                wv.settings.loadWithOverviewMode = !next
                                                wv.reload()
                                            }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(if (canBack) "后退" else "关闭页面") },
                                        onClick = {
                                            showMoreMenu = false
                                            if (canBack) {
                                                webView?.goBack()
                                            } else {
                                                // 退无可退：真把它关掉，别让它在后台一直占着
                                                MiniAppWebViewHost.destroy()
                                                navController.popBackStack()
                                            }
                                        },
                                    )
                                    // 「回到上次位置」—— 不强制跳，宝点了才回（2026-10-17）
                                    val lastUrl = MiniAppWebViewHost.savedUrl
                                    if (!lastUrl.isNullOrBlank() && lastUrl != currentUrl) {
                                        DropdownMenuItem(
                                            text = { Text("回到上次位置") },
                                            leadingIcon = {
                                                Icon(
                                                    HugeIcons.TransactionHistory,
                                                    contentDescription = null
                                                )
                                            },
                                            onClick = {
                                                showMoreMenu = false
                                                webView?.loadUrl(lastUrl)
                                            },
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = {
                                // 明确的关闭：连 WebView 一起放掉，别让它在后台一直占着。
                                // （只是切走的话走不到这儿 —— 那种情况留着它，宝回来猫还能读。）
                                MiniAppWebViewHost.destroy()
                                navController.popBackStack()
                            }) {
                                Icon(HugeIcons.Cancel01, contentDescription = "关闭")
                            }
                        }
                    },
                    colors = CustomColors.topBarColors,
                )
                if (isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        MiniAppWebViewHost.rememberHost(ctx)
                        MiniAppWebViewHost.obtain(ctx) {
                            WebView(ctx).apply {
                                applyMiniAppWebViewSettings(this)
                                webViewClient = miniAppWebViewClient
                                // 这里不 loadUrl —— 下面 also 里统一加载一次。
                                // （两处都写会加载两遍，页面就叠着画了。）
                            }
                        }.also { wv ->
                            // ★ 每次进页面都把 client 重挂一次（2026-10-17 修「一直转圈」）。
                            // client 的回调闭包绑着这一轮 Compose 的状态（isLoading 等）。
                            // 常驻 WebView 意味着 factory 里那个 create lambda 只会跑一次，
                            // 之后 obtain() 直接返回老实例 —— 于是 client 一直是第一轮那个，
                            // onPageFinished 全打到已经销毁的旧状态上，新状态的 isLoading
                            // 永远等不到 false，页面早加载完了圈还在转。
                            wv.webViewClient = miniAppWebViewClient
                            // 从常驻小窝里摘出来交给 Compose
                            MiniAppWebViewHost.take(wv)
                            webViewRef.value = wv
                            // ★ 从链接进来 = 开一个干净的页（2026-10-17 改）。
                            // 以前这里写的是"老实例带回来时不动它当前那一页"，于是宝每次从链接
                            // 进来都撞见上一次那个页面还挂在那儿（转着圈），同时又加载了一遍新地址。
                            // 常驻 WebView 是为了"切走不销毁、猫还能读"，不是"留着上一页"。
                            // 现在：每次从链接进来都回到干净的起点 —— 清掉历史、再打开目标地址。
                            // （不带条件：不管上次停在哪一页，从链接进来就是新的一页。）
                            try {
                                wv.clearHistory()
                                wv.loadUrl(url)
                            } catch (_: Throwable) {
                            }
                            // 这个 WebView 的 window.innerHeight 量出来是 0，页面自己算不出视口高度，
                            // 所以由这边量好了喂给它（每次布局变化都喂一遍）。
                            wv.addOnLayoutChangeListener { v, _, t, _, b, _, _, _, _ ->
                                // WebView 刚从 1×1 的小窝里摘出来时，Compose 还没量它，
                                // 这一回调量到的会是 1px —— 别把那个 1 喂给页面（会把
                                // ViewportFixClient 喂的屏幕高度盖掉，页面 vh 就塌成 0.01px）。
                                // 这个 WebView 自己多高（View 的坐标是物理像素）
                                val own = b - t
                                val dm = v.resources.displayMetrics
                                // 统一换算成 CSS 认的逻辑像素再喂 —— 不除 density 会喂成三倍高。
                                val h = if (own >= 100) (own / dm.density).toInt()
                                        else (dm.heightPixels / dm.density).toInt()
                                if (h > 0) {
                                    wv.evaluateJavascript(
                                        "window.__vhFixSetHeight && window.__vhFixSetHeight($h);",
                                        null
                                    )
                                }
                            }
                        }
                    },
                    update = {
                        webViewRef.value = it
                    }
                )

                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }

            if (mainButtonVisible) {
                Button(
                    onClick = {
                        webView?.evaluateJavascript(
                            "window.__bridgeFireEvent('mainButtonClicked');",
                            null
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    enabled = mainButtonEnabled && !mainButtonProgress,
                ) {
                    if (mainButtonProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                    Text(mainButtonText)
                }
            }
        }
    }

    popupData?.let { data ->
        AlertDialog(
            onDismissRequest = { popupData = null },
            title = { data.title?.let { Text(it) } },
            text = { Text(data.message) },
            confirmButton = {
                TextButton(
                    onClick = {
                        popupData = null
                        val buttonId = data.buttons.firstOrNull()?.id ?: ""
                        webView?.evaluateJavascript(
                            "window.__bridgeResult('${data.callbackId}', {buttonId:'$buttonId'});",
                            null
                        )
                    }
                ) {
                    Text(data.buttons.firstOrNull()?.text ?: "确定")
                }
            },
            dismissButton = if (data.buttons.size > 1) {
                {
                    TextButton(
                        onClick = {
                            popupData = null
                            val buttonId = data.buttons.getOrNull(1)?.id ?: ""
                            webView?.evaluateJavascript(
                                "window.__bridgeResult('${data.callbackId}', {buttonId:'$buttonId'});",
                                null
                            )
                        }
                    ) {
                        Text(data.buttons.getOrNull(1)?.text ?: "取消")
                    }
                }
            } else null
        )
    }

    confirmData?.let { data ->
        RikkaConfirmDialog(
            show = true,
            title = "",
            confirmText = "确定",
            dismissText = "取消",
            onConfirm = {
                webView?.evaluateJavascript(
                    "window.__bridgeResult('${data.callbackId}', true);",
                    null
                )
                confirmData = null
            },
            onDismiss = {
                webView?.evaluateJavascript(
                    "window.__bridgeResult('${data.callbackId}', false);",
                    null
                )
                confirmData = null
            }
        ) {
            Text(data.message)
        }
    }

    alertMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { alertMessage = null },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { alertMessage = null }) {
                    Text("确定")
                }
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            // 不销毁 —— 放回常驻小窝。这样宝切去聊天，浏览器工具照样摸得到它
            webView?.let { MiniAppWebViewHost.back(it) }
        }
    }
}

private class MiniAppWebViewClient(
    private val bridgeScript: String,
    private val onPageTitle: (String?) -> Unit,
    private val onLoadingChange: (Boolean) -> Unit,
    private val onUrlChange: (String) -> Unit,
    private val onNavChange: (Boolean, Boolean) -> Unit,
    private val onMainButton: (visible: Boolean, text: String?, enabled: Boolean, progress: Boolean) -> Unit,
    private val onBackButton: (visible: Boolean) -> Unit,
    private val onPopup: (PopupData) -> Unit,
    private val onConfirm: (ConfirmData) -> Unit,
    private val onAlert: (String) -> Unit,
    private val onOpenLink: (String, Boolean) -> Unit,
    private val onOpenTelegramLink: (String) -> Unit,
    private val onClose: () -> Unit,
    private val onHaptic: (String) -> Unit,
    private val onToolRequest: (method: String, params: Map<String, String>) -> Unit,
) : WebViewClient() {

    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
        super.onPageStarted(view, url, favicon)
        onLoadingChange(true)
        url?.let(onUrlChange)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        onLoadingChange(false)
        onPageTitle(view?.title)
        url?.let(onUrlChange)
        reportNav(view)
        view?.evaluateJavascript(bridgeScript, null)
        // 补上某些系统 WebView 算不出来的视口单位（见 MiniAppViewportFix.kt）
        view?.evaluateJavascript(viewportFixScript, null)
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        url?.let(onUrlChange)
        reportNav(view)
    }

    private fun reportNav(view: WebView?) {
        if (view == null) return
        try {
            onNavChange(view.canGoBack(), view.canGoForward())
        } catch (_: Exception) {
        }
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: android.webkit.WebResourceError?,
    ) {
        super.onReceivedError(view, request, error)
        // 只报主文档的失败；图片/JS 这些子资源挂掉不打扰
        if (request?.isForMainFrame == true) {
            onLoadingChange(false)
            onAlert("页面加载失败：${error?.description ?: "未知错误"}")
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return false
        if (url.startsWith("bridge://")) {
            handleBridgeCall(view!!, url)
            return true
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            // 保持内部 WebView 加载
            return false
        }
        // 剩下的都是 WebView 加载不了的协议（alipays:// / xhsdiscover:// / weixin:// ...），
        // 硬加载就是那句 net::ERR_UNKNOWN_URL_SCHEME。
        // 能交给系统唤起对应 App 的就交出去；系统也接不住就当没点这一下。
        val target = request?.url ?: return true
        val scheme = target.scheme?.lowercase().orEmpty()
        if (scheme == "javascript" || scheme == "data" || scheme == "about" || scheme == "file") {
            return true
        }
        return try {
            view?.context?.startActivity(Intent(Intent.ACTION_VIEW, target))
            true
        } catch (_: Exception) {
            true
        }
    }

    private fun handleBridgeCall(webView: WebView, url: String) {
        val uri = Uri.parse(url)
        val method = uri.host ?: return
        val params = uri.queryParameterNames.associateWith { uri.getQueryParameter(it) ?: "" }
        val callbackId = params["callbackId"] ?: ""

        when (method) {
            "ready" -> {
                Log.i(TAG, "MiniApp ready")
            }
            "expand" -> {
                // 全屏展开：当前实现由页面自身占满屏幕即可
            }
            "close" -> {
                onClose()
            }
            "openLink" -> {
                val link = params["url"] ?: ""
                val tryInstantView = params["try_instant_view"] == "true"
                if (link.isNotBlank()) onOpenLink(link, tryInstantView)
            }
            "openTelegramLink" -> {
                val link = params["url"] ?: ""
                if (link.isNotBlank()) onOpenTelegramLink(link)
            }
            "sendData" -> {
                // 发送给 bot 的数据，当前仅记录
                Log.i(TAG, "sendData: ${params["data"]}")
            }
            "showPopup" -> {
                val title = params["title"]
                val message = params["message"] ?: ""
                val buttons = try {
                    json.decodeFromString<List<PopupButton>>(params["buttons"] ?: "[]")
                } catch (e: Exception) {
                    emptyList()
                }
                onPopup(PopupData(callbackId, title, message, buttons))
            }
            "showConfirm" -> {
                onConfirm(ConfirmData(callbackId, params["message"] ?: ""))
            }
            "showAlert" -> {
                onAlert(params["message"] ?: "")
            }
            "haptic.impact" -> {
                onHaptic(params["style"] ?: "medium")
            }
            "haptic.notification" -> {
                onHaptic("notification_${params["type"] ?: "success"}")
            }
            "haptic.selection" -> {
                onHaptic("selection")
            }
            "mainButton.setText" -> {
                onMainButton(true, params["text"], true, false)
            }
            "mainButton.show" -> {
                onMainButton(true, null, true, false)
            }
            "mainButton.hide" -> {
                onMainButton(false, null, true, false)
            }
            "mainButton.enable" -> {
                onMainButton(true, null, true, false)
            }
            "mainButton.disable" -> {
                onMainButton(true, null, false, false)
            }
            "mainButton.showProgress" -> {
                onMainButton(true, null, true, true)
            }
            "mainButton.hideProgress" -> {
                onMainButton(true, null, true, false)
            }
            "mainButton.setParams" -> {
                val text = params["text"]
                val enabled = params["is_active"] != "false"
                onMainButton(true, text, enabled, false)
            }
            "backButton.show" -> {
                onBackButton(true)
            }
            "backButton.hide" -> {
                onBackButton(false)
            }
            else -> {
                if (MiniAppToolBridge.handles(method)) {
                    onToolRequest(method, params)
                } else {
                    Log.w(TAG, "Unknown bridge method: $method")
                }
            }
        }
    }
}

private data class PopupData(
    val callbackId: String,
    val title: String?,
    val message: String,
    val buttons: List<PopupButton>,
)

@kotlinx.serialization.Serializable
private data class PopupButton(
    val id: String = "",
    val text: String = "",
    val type: String = "",
)

private data class ConfirmData(
    val callbackId: String,
    val message: String,
)

private fun openExternalLink(context: android.content.Context, url: String, tryInstantView: Boolean) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(context, intent, null)
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "No browser found for $url")
    }
}

private fun openTelegramLink(context: android.content.Context, url: String) {
    try {
        val telegramIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .setPackage("org.telegram.messenger")
        startActivity(context, telegramIntent, null)
    } catch (e: ActivityNotFoundException) {
        openExternalLink(context, url, false)
    }
}

private fun parseHapticStyle(style: String): Int {
    return when (style.lowercase()) {
        "light" -> HapticFeedbackConstants.LONG_PRESS
        "medium" -> HapticFeedbackConstants.LONG_PRESS
        "heavy" -> HapticFeedbackConstants.CONFIRM
        "rigid" -> HapticFeedbackConstants.CONFIRM
        "soft" -> HapticFeedbackConstants.LONG_PRESS
        "selection" -> HapticFeedbackConstants.CLOCK_TICK
        "notification_success" -> HapticFeedbackConstants.CONFIRM
        "notification_warning" -> HapticFeedbackConstants.LONG_PRESS
        "notification_error" -> HapticFeedbackConstants.REJECT
        else -> HapticFeedbackConstants.LONG_PRESS
    }
}

private fun buildTelegramBridgeScript(
    colorScheme: androidx.compose.material3.ColorScheme,
    isDarkMode: Boolean,
): String {
    val colorSchemeName = if (isDarkMode) "dark" else "light"
    val themeParams = buildString {
        append("{")
        append("bg_color: '${colorScheme.background.toRgbHex()}', ")
        append("text_color: '${colorScheme.onBackground.toRgbHex()}', ")
        append("hint_color: '${colorScheme.onSurfaceVariant.toRgbHex()}', ")
        append("link_color: '${colorScheme.primary.toRgbHex()}', ")
        append("button_color: '${colorScheme.primary.toRgbHex()}', ")
        append("button_text_color: '${colorScheme.onPrimary.toRgbHex()}', ")
        append("secondary_bg_color: '${colorScheme.surfaceVariant.toRgbHex()}'")
        append("}")
    }

    return TELEGRAM_BRIDGE_JAVASCRIPT
        .replace("%COLOR_SCHEME%", colorSchemeName)
        .replace("%THEME_PARAMS%", themeParams)
}

private fun androidx.compose.ui.graphics.Color.toRgbHex(): String {
    val r = (red * 255).toInt().coerceIn(0, 255)
    val g = (green * 255).toInt().coerceIn(0, 255)
    val b = (blue * 255).toInt().coerceIn(0, 255)
    return String.format("#%02X%02X%02X", r, g, b)
}

private val TELEGRAM_BRIDGE_JAVASCRIPT = """
(function() {
    if (window.Telegram && window.Telegram.WebApp) return;
    window.__tgCallbackId = 0;
    window.__tgCallbacks = {};
    window.__tgEventListeners = {};

    function bridgeCall(method, params) {
        return new Promise(function(resolve, reject) {
            var cbId = 'tg_' + (++window.__tgCallbackId);
            window.__tgCallbacks[cbId] = { resolve: resolve, reject: reject };
            var url = 'bridge://' + method + '?callbackId=' + encodeURIComponent(cbId);
            if (params) {
                for (var key in params) {
                    if (params[key] !== undefined && params[key] !== null) {
                        url += '&' + encodeURIComponent(key) + '=' + encodeURIComponent(String(params[key]));
                    }
                }
            }
            var iframe = document.createElement('iframe');
            iframe.style.display = 'none';
            iframe.src = url;
            document.body.appendChild(iframe);
            setTimeout(function() { document.body.removeChild(iframe); }, 100);
        });
    }

    window.__bridgeCall = bridgeCall;

    window.__bridgeResult = function(callbackId, result) {
        var cb = window.__tgCallbacks[callbackId];
        if (cb) {
            cb.resolve(result);
            delete window.__tgCallbacks[callbackId];
        }
    };

    window.__bridgeFireEvent = function(eventType) {
        var listeners = window.__tgEventListeners[eventType] || [];
        listeners.forEach(function(listener) {
            try { listener(); } catch (e) { console.error('[TMA] event error', e); }
        });
    };

    var MainButton = {
        text: 'CONTINUE',
        color: null,
        textColor: '#ffffff',
        isVisible: false,
        isActive: true,
        isProgressVisible: false,
        setText: function(text) { this.text = text; bridgeCall('mainButton.setText', { text: text }); return this; },
        show: function() { this.isVisible = true; bridgeCall('mainButton.show', {}); return this; },
        hide: function() { this.isVisible = false; bridgeCall('mainButton.hide', {}); return this; },
        enable: function() { this.isActive = true; bridgeCall('mainButton.enable', {}); return this; },
        disable: function() { this.isActive = false; bridgeCall('mainButton.disable', {}); return this; },
        showProgress: function(leaveActive) { this.isProgressVisible = true; bridgeCall('mainButton.showProgress', {}); return this; },
        hideProgress: function() { this.isProgressVisible = false; bridgeCall('mainButton.hideProgress', {}); return this; },
        setParams: function(params) { 
            if (params.text) this.text = params.text; 
            if (params.color) this.color = params.color;
            if (params.text_color) this.textColor = params.text_color;
            if (params.is_active !== undefined) this.isActive = params.is_active;
            bridgeCall('mainButton.setParams', params); 
            return this; 
        },
        onClick: function(callback) { 
            window.__tgEventListeners['mainButtonClicked'] = [callback]; 
            return this; 
        }
    };

    var BackButton = {
        isVisible: false,
        show: function() { this.isVisible = true; bridgeCall('backButton.show', {}); return this; },
        hide: function() { this.isVisible = false; bridgeCall('backButton.hide', {}); return this; },
        onClick: function(callback) { 
            window.__tgEventListeners['backButtonClicked'] = [callback]; 
            return this; 
        }
    };

    var HapticFeedback = {
        impactOccurred: function(style) { bridgeCall('haptic.impact', { style: style }); },
        notificationOccurred: function(type) { bridgeCall('haptic.notification', { type: type }); },
        selectionChanged: function() { bridgeCall('haptic.selection', {}); }
    };

    window.Telegram = {
        WebApp: {
            initData: '',
            initDataUnsafe: {},
            version: '8.0',
            platform: 'android',
            colorScheme: '%COLOR_SCHEME%',
            themeParams: %THEME_PARAMS%,
            isExpanded: true,
            viewportHeight: window.innerHeight,
            viewportStableHeight: window.innerHeight,
            ready: function() { bridgeCall('ready', {}); },
            expand: function() { bridgeCall('expand', {}); },
            close: function() { bridgeCall('close', {}); },
            openLink: function(url, options) { 
                bridgeCall('openLink', { url: url, try_instant_view: options && options.try_instant_view ? 'true' : 'false' }); 
            },
            openTelegramLink: function(url) { bridgeCall('openTelegramLink', { url: url }); },
            sendData: function(data) { bridgeCall('sendData', { data: data }); },
            showPopup: function(params) { 
                return bridgeCall('showPopup', { 
                    title: params.title, 
                    message: params.message, 
                    buttons: JSON.stringify(params.buttons || []) 
                }); 
            },
            showConfirm: function(params) { 
                return bridgeCall('showConfirm', { message: params.message }); 
            },
            showAlert: function(message) { return bridgeCall('showAlert', { message: message }); },
            showScanQrPopup: function(params) { return bridgeCall('showScanQrPopup', { text: params.text }); },
            closeScanQrPopup: function() { bridgeCall('closeScanQrPopup', {}); },
            readTextFromClipboard: function() { return bridgeCall('readTextFromClipboard', {}); },
            HapticFeedback: HapticFeedback,
            MainButton: MainButton,
            BackButton: BackButton,
            onEvent: function(eventType, callback) {
                if (!window.__tgEventListeners[eventType]) window.__tgEventListeners[eventType] = [];
                window.__tgEventListeners[eventType].push(callback);
            },
            offEvent: function(eventType, callback) {
                var listeners = window.__tgEventListeners[eventType] || [];
                window.__tgEventListeners[eventType] = listeners.filter(function(l) { return l !== callback; });
            }
        }
    };
    window.TelegramGameProxy = window.Telegram.WebApp;
})();
""".trimIndent()
