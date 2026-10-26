package me.rerere.rikkahub.data.ai.tools

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.AppLogBuffer
import me.rerere.rikkahub.ui.pages.miniapp.DESKTOP_UA
import me.rerere.rikkahub.ui.pages.miniapp.MiniAppWebViewBridge
import me.rerere.rikkahub.ui.pages.miniapp.MiniAppWebViewHost
import kotlin.coroutines.resume

/*
 * 「猫的浏览器」第一版：让橘仔能摸到宝开着的那个小程序页面 —— 读文字、跳网址、点东西。
 *
 * 眼下的边界（有意为之）：页面得宝在小程序里开着，工具才摸得到。
 * "猫自己能起一个后台浏览器"是第二步，那要动 Activity 生命周期，不在这版里。
 */

private const val NO_PAGE =
    "浏览器这会儿起不来 —— 猫还不知道把窝安在哪儿。宝随便进一次小程序页就好了（一次就够，之后猫就能自己开）。"

/**
 * 猫这会儿该摸哪一只。
 *
 * ★ 2026-10-26：浮窗有了自己独立的一只（跟小程序那只分开），所以要分先后 ——
 *   浮窗开着的时候优先摸浮窗那只（宝正在看的就是它）；
 *   浮窗关着才去摸小程序那只（没开页面的话 bridge 会自己造一只）。
 *   不这么排的话永远摸不到浮窗 —— bridge.current 自己会造，从来不为 null。
 */
private fun currentBrowser(): WebView? =
    MiniAppWebViewHost.floatWebView ?: MiniAppWebViewBridge.current

/**
 * 抓「网页里正在播的那一帧」。
 *
 * 为什么要它：PixelCopy 是从 Window 的合成层抓，但 WebView 里的 <video> 走的是
 * 独立的硬件图层，抓下来只有一片糊的背景（2026-10-26 在抖音上实测：底图糊、UI 清楚）。
 * 换条路 —— 让页面自己把 video 画进 canvas 再吐 base64，视频帧就拿到了（实测 1920×1080 成功）。
 *
 * ★ 2026-10-26 晚：修「贴上去位置/内容对不上」。
 *   原因：老版拿的是整帧（videoWidth×videoHeight），Kotlin 那边把整帧拉伸填进元素框；
 *   但页面普遍是 object-fit:cover —— 屏幕上只露出中间裁过的一块。整帧压进框里，内容就和
 *   看到的错位了。
 *   现在：canvas 直接按「元素框 × dpr」出图，并按 object-fit 语义先把该裁的裁掉，
 *   于是 Kotlin 那边是 1:1 贴上去，不用再缩放。
 *   cover 精确裁；contain / scale-down 按比例内缩留边；fill / 其他保持整帧拉伸（同屏幕）。
 *
 * 返回 JSON：{ x, y, w, h, dpr, frame }，坐标是 CSS 像素（相对视口），要乘 dpr 才是位图像素。
 * 页面上没视频 / 视频还没解出画面 / canvas 被跨域污染，都返回空串。
 */
private val JS_VIDEO_FRAME = """
(function(){
  try{
    var v=document.querySelector('video');
    if(!v||!v.videoWidth||!v.videoHeight) return '';
    var r=v.getBoundingClientRect();
    if(r.width<1||r.height<1) return '';
    var vw=v.videoWidth, vh=v.videoHeight;
    var dpr=window.devicePixelRatio||1;
    var cw=Math.max(1,Math.round(r.width*dpr));
    var chh=Math.max(1,Math.round(r.height*dpr));
    var c=document.createElement('canvas');
    c.width=cw; c.height=chh;
    var ctx=c.getContext('2d');
    var fit=(window.getComputedStyle(v).objectFit||'fill');
    var boxRatio=r.width/r.height, vidRatio=vw/vh;
    if(fit==='cover'){
      // 填满框、裁掉多出来的部分（短视频站基本都是这个）
      var sx=0, sy=0, sw=vw, sh=vh;
      if(vidRatio>boxRatio){ sw=vh*boxRatio; sx=(vw-sw)/2; }
      else { sh=vw/boxRatio; sy=(vh-sh)/2; }
      ctx.drawImage(v, sx,sy,sw,sh, 0,0,cw,chh);
    }else if(fit==='contain'||fit==='scale-down'){
      // 完整塞进框里，四周留边
      var dw, dh;
      if(vidRatio>boxRatio){ dw=cw; dh=cw/vidRatio; }
      else { dh=chh; dw=chh*vidRatio; }
      ctx.drawImage(v, 0,0,vw,vh, (cw-dw)/2,(chh-dh)/2,dw,dh);
    }else{
      // fill 等：跟屏幕一样，整帧拉伸铺满
      ctx.drawImage(v, 0,0,vw,vh, 0,0,cw,chh);
    }
    var d=c.toDataURL('image/jpeg',0.85);
    return JSON.stringify({x:r.left,y:r.top,w:r.width,h:r.height,dpr:dpr,
      vpW:window.innerWidth,vpH:window.innerHeight,frame:d});
  }catch(e){ return ''; }
})()
""".trimIndent()

/** 在主线程跑一段 JS，取回它的返回值（已剥掉一层 JSON 引号）。 */
private suspend fun evalJs(webView: WebView, js: String): String =
    suspendCancellableCoroutine { cont ->
        Handler(Looper.getMainLooper()).post {
            try {
                webView.evaluateJavascript(js) { raw ->
                    val text = if (raw.isNullOrEmpty() || raw == "null") {
                        ""
                    } else {
                        runCatching { Json.decodeFromString<String>(raw) }.getOrElse { raw }
                    }
                    if (cont.isActive) cont.resume(text)
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume("")
            }
        }
    }

private fun jsString(s: String): String = buildString {
    append('"')
    for (c in s) {
        when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }
    append('"')
}

private val READ_JS = """
(function(){
  try {
    var t = document.body ? (document.body.innerText || '') : '';
    var links = [];
    var as = document.querySelectorAll('a[href]');
    for (var i = 0; i < as.length && links.length < 40; i++) {
      var a = as[i];
      var txt = (a.innerText || a.title || '').replace(/\s+/g, ' ').trim().slice(0, 60);
      if (txt) links.push(txt + '  ->  ' + a.href);
    }
    return JSON.stringify({
      url: location.href,
      title: document.title || '',
      textLen: t.length,
      text: t.slice(0, 12000),
      links: links
    });
  } catch (e) {
    return JSON.stringify({ error: String(e) });
  }
})()
""".trimIndent()

private fun buildClickJs(text: String, selector: String): String {
    val t = jsString(text)
    val s = jsString(selector)
    return """
(function(){
  try {
    var t = $t, sel = $s;
    var el = null;
    if (sel) { try { el = document.querySelector(sel); } catch (e) {} }
    if (!el && t) {
      var cands = document.querySelectorAll('a,button,[role=button],input,li,span,div,p,h1,h2,h3');
      var best = null, bestScore = -1;
      for (var i = 0; i < cands.length; i++) {
        var c = cands[i];
        var txt = (c.innerText || c.value || '').replace(/\s+/g, ' ').trim();
        if (!txt || txt.length > 120) continue;
        var idx = txt.indexOf(t);
        if (idx < 0) continue;
        var score = 1000 - txt.length - idx;
        var tag = c.tagName;
        if (tag === 'A' || tag === 'BUTTON') score += 500;
        if (score > bestScore) { bestScore = score; best = c; }
      }
      el = best;
    }
    if (!el) return JSON.stringify({ ok: false, msg: '页面上没找到能点的东西' });
    try { el.scrollIntoView({ block: 'center' }); } catch (e) {}
    el.click();
    return JSON.stringify({
      ok: true,
      clicked: (el.innerText || el.value || '').replace(/\s+/g, ' ').trim().slice(0, 60),
      href: el.href || ''
    });
  } catch (e) {
    return JSON.stringify({ ok: false, error: String(e) });
  }
})()
""".trimIndent()
}

/** 读当前页面：网址、标题、正文文字、前若干条链接。 */
fun browserReadTool(): Tool = Tool(
    name = "browser_read",
    description = "读取宝此刻打开着的那个小程序页面：返回网址、标题、正文文字（截断到 12000 字）和页面上前 40 条链接。页面没开着时会说明。",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = { _ ->
        val wv = currentBrowser()
        if (wv == null) {
            listOf(UIMessagePart.Text(NO_PAGE))
        } else {
            val raw = evalJs(wv, READ_JS)
            listOf(UIMessagePart.Text(raw.ifBlank { "(页面没有可读的文字)" }))
        }
    },
)

/** 让当前页面跳到另一个网址。 */
fun browserOpenTool(): Tool = Tool(
    name = "browser_open",
    description = "让宝打开着的那个小程序页面跳到指定网址。只在有打开着的页面时可用。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("url") {
                    put("type", "string")
                    put("description", "要打开的网址，例如 https://example.com")
                }
            },
            required = listOf("url"),
        )
    },
    execute = { args ->
        val url = args.jsonObject["url"]?.jsonPrimitive?.content?.trim().orEmpty()
        val wv = currentBrowser()
        when {
            wv == null -> listOf(UIMessagePart.Text(NO_PAGE))
            url.isBlank() -> listOf(UIMessagePart.Text("没给网址。"))
            else -> {
                Handler(Looper.getMainLooper()).post { wv.loadUrl(url) }
                listOf(UIMessagePart.Text("已经让页面往 $url 走了，等一两秒它加载完再读。"))
            }
        }
    },
)

/** 在当前页面里点一个东西：给文字就按文字找，给选择器就按选择器找。 */
fun browserClickTool(): Tool = Tool(
    name = "browser_click",
    description = "在宝打开着的小程序页面里点一个东西。优先用 selector（CSS 选择器）精确定位；也可以只给 text，会找页面上文字匹配的元素来点（链接和按钮优先）。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("text") {
                    put("type", "string")
                    put("description", "要点的东西上的文字（匹配可见文字，链接/按钮优先）")
                }
                putJsonObject("selector") {
                    put("type", "string")
                    put("description", "CSS 选择器（可选，比 text 更精确）")
                }
            },
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val text = obj["text"]?.jsonPrimitive?.content?.trim().orEmpty()
        val selector = obj["selector"]?.jsonPrimitive?.content?.trim().orEmpty()
        val wv = currentBrowser()
        when {
            wv == null -> listOf(UIMessagePart.Text(NO_PAGE))
            text.isBlank() && selector.isBlank() ->
                listOf(UIMessagePart.Text("得给 text 或者 selector 其中一个。"))
            else -> listOf(UIMessagePart.Text(evalJs(wv, buildClickJs(text, selector))))
        }
    },
)

/** 在主线程跑一段操作（动 WebSettings 必须回主线程），跑完才返回。 */
private suspend fun onMain(block: () -> Unit) = suspendCancellableCoroutine { cont ->
    Handler(Looper.getMainLooper()).post {
        try {
            block()
        } catch (e: Throwable) {
            android.util.Log.w("BrowserTools", "onMain: ${e.message}")
        }
        if (cont.isActive) cont.resume(Unit)
    }
}

/**
 * 桌面版 / 手机版切换。
 *
 * 为什么要它：抖音这类页面看到「移动版 UA」就先给你引流那一层（"打开抖音看精彩视频"），
 * 正经内容压根不给；桌面版才给。宝 2026-10-26 提的。
 * 逻辑跟小程序页顶栏那个菜单项一样（同一个 DESKTOP_UA），只是让猫也能按。
 */
fun browserUaTool(): Tool = Tool(
    name = "browser_ua",
    description = "把宝打开着的页面切成桌面版（网页以为自己在电脑上）或切回手机版，切完自动重载。抖音那种“非桌面版就弹打开App”的页面，切桌面版才给正经内容。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("action") {
                    put("type", "string")
                    put("description", "desktop = 切桌面版；mobile = 切回手机版")
                }
            },
            required = listOf("action"),
        )
    },
    execute = { args ->
        val act = args.jsonObject["action"]?.jsonPrimitive?.content?.trim()?.lowercase().orEmpty()
        val wv = currentBrowser()
        when {
            wv == null -> listOf(UIMessagePart.Text(NO_PAGE))
            act != "desktop" && act != "mobile" ->
                listOf(UIMessagePart.Text("action 只能是 desktop 或 mobile。"))
            else -> {
                onMain {
                    val next = act == "desktop"
                    wv.settings.userAgentString = if (next) {
                        DESKTOP_UA
                    } else {
                        android.webkit.WebSettings.getDefaultUserAgent(wv.context).replace("; wv", "")
                    }
                    wv.settings.useWideViewPort = !next
                    wv.settings.loadWithOverviewMode = !next
                    wv.reload()
                }
                listOf(
                    UIMessagePart.Text(
                        if (act == "desktop") "已切到桌面版，正在重载。"
                        else "已切回手机版，正在重载。"
                    )
                )
            }
        }
    },
)

/** 在当前页面里跑一小段 JS，把结果拿回来（诊断怪毛病用）。 */
fun browserEvalTool(): Tool = Tool(
    name = "browser_eval",
    description = "在宝打开着的小程序页面里执行一段 JavaScript，并把返回值拿回来。用来诊断页面排布为什么不对（量元素高度、看视口数字、读某个变量）。只读为主，别拿它改页面。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("js") {
                    put("type", "string")
                    put("description", "要执行的 JavaScript。写成最后返回一个字符串的表达式，比如 JSON.stringify({...})。")
                }
            },
            required = listOf("js"),
        )
    },
    execute = { args ->
        val js = args.jsonObject["js"]?.jsonPrimitive?.content?.trim().orEmpty()
        val wv = currentBrowser()
        when {
            wv == null -> listOf(UIMessagePart.Text(NO_PAGE))
            js.isBlank() -> listOf(UIMessagePart.Text("得给一段 js。"))
            else -> listOf(UIMessagePart.Text(evalJs(wv, js).ifBlank { "(没有返回值)" }))
        }
    },
)

/** 让当前页面后退 / 前进一页。卡在某个页面里出不来的时候用它。 */
fun browserBackTool(): Tool = Tool(
    name = "browser_back",
    description = "让宝打开着的小程序页面退回上一页（或前进一页）。卡在某个页面里出不来的时候用它。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("step") {
                    put("type", "integer")
                    put("description", "退几页：-1 后退（默认），1 前进")
                }
            },
        )
    },
    execute = { args ->
        val step = args.jsonObject["step"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
        val wv = currentBrowser()
        if (wv == null) {
            listOf(UIMessagePart.Text(NO_PAGE))
        } else {
            val can = try {
                if (step > 0) wv.canGoForward() else wv.canGoBack()
            } catch (_: Exception) {
                false
            }
            if (!can) {
                listOf(
                    UIMessagePart.Text(
                        if (step > 0) "已经是最前面那一页了，没得往前。" else "已经是最初那一页了，没得后退。"
                    )
                )
            } else {
                Handler(Looper.getMainLooper()).post {
                    if (step > 0) wv.goForward() else wv.goBack()
                }
                listOf(
                    UIMessagePart.Text(
                        if (step > 0) "往前翻了一页，等一两秒再读。" else "退回上一页了，等一两秒再读。"
                    )
                )
            }
        }
    },
)

/** 从 View 的 context 往上找 Activity 的 Window（PixelCopy 要它）。 */
private fun findHostWindow(view: android.view.View): android.view.Window? {
    var ctx = view.context
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx.window
        ctx = ctx.baseContext
    }
    return (ctx as? android.app.Activity)?.window
}

/**
 * 把 WebView 当前可见的部分抓成 PNG，存进缓存目录。主线程做，失败返回 null。
 *
 * 优先走 PixelCopy：它是问系统要「这块窗口合成之后长什么样」，所以网页里的视频
 * （硬件叠加层，不在 View 画布上）也能带上。PixelCopy 拿不到时再退回 draw ——
 * draw 只画 View 树，视频层会是空的。
 */
private suspend fun captureWebView(webView: WebView, videoJson: String = ""): java.io.File? =
    suspendCancellableCoroutine { cont ->
        Handler(Looper.getMainLooper()).post {
            try {
                val w = webView.width
                val h = webView.height
                if (w <= 0 || h <= 0) {
                    if (cont.isActive) cont.resume(null)
                    return@post
                }
                val bmp = android.graphics.Bitmap.createBitmap(
                    w, h, android.graphics.Bitmap.Config.ARGB_8888
                )
                val dir = java.io.File(webView.context.cacheDir, "browser_shots").apply { mkdirs() }
                val file = java.io.File(dir, "shot_" + System.currentTimeMillis() + ".png")

                // ★ 2026-10-26：把网页自己抓的那一帧贴回去。
                //   PixelCopy 抓不到 WebView 里的视频层（硬件叠加层），底图那块只有糊的背景；
                //   所以先让页面把 video 画进 canvas 吐出 base64，再按它的位置盖上来。
                //   贴不上就算了 —— 有 UI 的那张总比什么都没有强。
                fun overlayVideo() {
                    if (videoJson.isBlank()) return
                    try {
                        val o = Json.parseToJsonElement(videoJson).jsonObject
                        val dataUrl = o["frame"]?.jsonPrimitive?.content ?: return
                        val idx = dataUrl.indexOf(",")
                        if (idx <= 0) return
                        val bytes = android.util.Base64.decode(
                            dataUrl.substring(idx + 1), android.util.Base64.DEFAULT
                        )
                        val frame = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            ?: return
                        // ★ 2026-10-26 晚：浮窗能缩放，devicePixelRatio 不能信（宝指出的）。
                        //   改成「WebView 实际宽 ÷ 页面视口宽」自己算缩放比，两边用同一把尺。
                        val vpW = o["vpW"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f
                        val dpr = if (vpW > 1f) w.toFloat() / vpW
                            else (o["dpr"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 1f)
                        val fx = (o["x"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f) * dpr
                        val fy = (o["y"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f) * dpr
                        val fw = (o["w"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f) * dpr
                        val fh = (o["h"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f) * dpr
                        AppLogBuffer.log(
                            "BrowserTools",
                            "overlay: wv=${w}x$h vpW=$vpW scale=$dpr rect=($fx,$fy,$fw,$fh) frame=${frame.width}x${frame.height}"
                        )
                        if (fw < 1f || fh < 1f) return
                        val target = android.graphics.Rect(
                            fx.toInt(), fy.toInt(), (fx + fw).toInt(), (fy + fh).toInt()
                        )
                        android.graphics.Canvas(bmp).drawBitmap(
                            frame, null, target, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
                        )
                        frame.recycle()
                    } catch (_: Throwable) {
                    }
                }

                fun saveFile() {
                    try {
                        java.io.FileOutputStream(file).use { out ->
                            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 92, out)
                        }
                        bmp.recycle()
                        if (cont.isActive) cont.resume(file)
                    } catch (e: Throwable) {
                        if (cont.isActive) cont.resume(null)
                    }
                }

                fun fallbackDraw() {
                    try {
                        webView.draw(android.graphics.Canvas(bmp))
                        overlayVideo()
                        saveFile()
                    } catch (e: Throwable) {
                        if (cont.isActive) cont.resume(null)
                    }
                }

                val win = findHostWindow(webView)
                if (win == null || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
                    fallbackDraw()
                } else {
                    val loc = IntArray(2)
                    webView.getLocationInWindow(loc)
                    val rect = android.graphics.Rect(loc[0], loc[1], loc[0] + w, loc[1] + h)
                    try {
                        android.view.PixelCopy.request(
                            win, rect, bmp,
                            { result ->
                                if (result == android.view.PixelCopy.SUCCESS) { overlayVideo(); saveFile() } else fallbackDraw()
                            },
                            Handler(Looper.getMainLooper())
                        )
                    } catch (e: Throwable) {
                        fallbackDraw()
                    }
                }
            } catch (e: Throwable) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

/**
 * 把当前网页"画出来的样子"截成一张图，直接放进对话。
 *
 * 走的是 WebView 自己的 draw —— 只截网页那一块，不碰宝的屏幕，也不用无障碍服务。
 * 图会作为图片 part 回到对话里，于是图片转述会把它讲给猫听（猫就这样"看见"页面）。
 */
fun browserScreenshotTool(): Tool = Tool(
    name = "browser_screenshot",
    description = "把宝打开着的小程序页面截成一张图（只截网页那块，不碰屏幕，不用无障碍），图片会带进对话。" +
        "用来\"看一眼\"页面长什么样：排布怪、样式不对、或者只是想看看的时候用它。",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = { _ ->
        val wv = currentBrowser()
        if (wv == null) {
            listOf(UIMessagePart.Text(NO_PAGE))
        } else {
            // ★ 2026-10-26：先问页面「视频在哪、这一帧长什么样」，再截图。
            val videoJson = runCatching { evalJs(wv, JS_VIDEO_FRAME) }.getOrDefault("")
            val file = captureWebView(wv, videoJson)
            if (file == null) {
                listOf(
                    UIMessagePart.Text("截图没成 —— 页面可能还没画出东西，或者这会儿尺寸是 0。等它加载完再试一次。")
                )
            } else {
                listOf(
                    UIMessagePart.Text("截了一张（" + file.name + "，" + (file.length() / 1024) + " KB）。"),
                    UIMessagePart.Image(url = "file://" + file.absolutePath),
                )
            }
        }
    },
)
