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
import me.rerere.rikkahub.ui.pages.miniapp.MiniAppWebViewBridge
import kotlin.coroutines.resume

/*
 * 「猫的浏览器」第一版：让橘仔能摸到宝开着的那个小程序页面 —— 读文字、跳网址、点东西。
 *
 * 眼下的边界（有意为之）：页面得宝在小程序里开着，工具才摸得到。
 * "猫自己能起一个后台浏览器"是第二步，那要动 Activity 生命周期，不在这版里。
 */

private const val NO_PAGE =
    "浏览器这会儿起不来 —— 猫还不知道把窝安在哪儿。宝随便进一次小程序页就好了（一次就够，之后猫就能自己开）。"

private fun currentBrowser(): WebView? = MiniAppWebViewBridge.current

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
