package me.rerere.rikkahub.ui.pages.miniapp

/**
 * 给某些系统 WebView 补一个漏掉的视口单位。
 *
 * 背景：部分 Android WebView（实测 Chrome/150 的 WebView）里，CSS 的 vh / dvh 会被算成 0
 * —— 同一个页面在系统浏览器里是好的，在小程序的 WebView 里高度就整个塌掉。
 * 这是内核行为，App 侧那几个 viewport 开关（useWideViewPort / loadWithOverviewMode）
 * 都拨不动它。
 *
 * 做法：页面加载完之后先探一下这个 WebView 的 vh 准不准。
 *   - 准 → 什么都不做，零影响。
 *   - 不准 → 把样式表里所有 "Nvh" 悄悄重写成 "calc(var(--app-vh, 1vh) * N)"，
 *            再把真实的窗口高度（innerHeight/100）写进 --app-vh。
 * 页面源码一个字不动，改的是运行时的 CSSOM；窗口尺寸一变就刷新一次变量。
 */
internal val viewportFixScript: String = """
(function () {
  try {
    if (window.__vhFixInstalled) return;
    window.__vhFixInstalled = true;

    // 探针：这个 WebView 的 vh 好不好使
    var probe = document.createElement('div');
    probe.style.cssText = 'height:100vh;width:0;visibility:hidden;position:static';
    (document.body || document.documentElement).appendChild(probe);
    var probeH = probe.offsetHeight;
    if (probe.parentNode) probe.parentNode.removeChild(probe);
    if (probeH > 0) return;   // vh 正常，不插手

    function patchStyle(style) {
      if (!style) return;
      var props = ['height', 'minHeight', 'maxHeight'];
      for (var i = 0; i < props.length; i++) {
        var p = props[i];
        var v = '';
        try { v = style[p] || ''; } catch (e) { continue; }
        if (!v || v.indexOf('vh') < 0) continue;
        if (v.indexOf('--app-vh') >= 0) continue;
        var nv = v.replace(/(\d*\.?\d+)vh/g, 'calc(var(--app-vh, 1vh) * ${'$'}1)');
        try { style[p] = nv; } catch (e) { /* 值不支持就保持原样 */ }
      }
    }

    function walk(rules) {
      if (!rules) return;
      for (var k = 0; k < rules.length; k++) {
        var r = null;
        try { r = rules[k]; } catch (e) { continue; }
        if (!r) continue;
        try { if (r.style) patchStyle(r.style); } catch (e) {}
        try { if (r.cssRules && r.cssRules.length) walk(r.cssRules); } catch (e) {}
      }
    }

    function rewriteAll() {
      var sheets = document.styleSheets;
      for (var i = 0; i < sheets.length; i++) {
        try { walk(sheets[i].cssRules); } catch (e) { /* 跨域样式表跳过 */ }
      }
    }

    function setVh() {
      // 优先用 App 喂进来的高度。这个 WebView 里 window.innerHeight 常年是 0
      // （实测 innerWidth/innerHeight 都是 0、文档可视高 1px）——
      // 问页面是问不出来的，只能让外面告诉它。
      var h = window.__vhFixHostHeight || window.innerHeight || 0;
      if (h > 0) {
        document.documentElement.style.setProperty('--app-vh', (h / 100) + 'px');
      }
    }

    // App 侧调用：把真实可视高度（px）告诉页面，随后立刻重算一遍。
    window.__vhFixSetHeight = function (h) {
      h = Number(h);
      if (!isFinite(h) || h <= 0) return;
      window.__vhFixHostHeight = h;
      setVh();
    };

    setVh();
    rewriteAll();
    // 有些页面的脚本还在后续往里插样式，隔一会儿再扫两遍
    setTimeout(function () { rewriteAll(); setVh(); }, 300);
    setTimeout(function () { rewriteAll(); setVh(); }, 1200);

    window.addEventListener('resize', setVh);
    window.addEventListener('orientationchange', function () { setTimeout(setVh, 250); });
  } catch (e) { /* 静默：补丁挂了也不能影响页面本身 */ }
})();
""".trimIndent()
