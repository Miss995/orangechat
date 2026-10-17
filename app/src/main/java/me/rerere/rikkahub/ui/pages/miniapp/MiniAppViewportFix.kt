package me.rerere.rikkahub.ui.pages.miniapp

/**
 * 给某些系统 WebView 补一个漏掉的视口。
 *
 * 背景：部分 Android WebView（实测 Chrome/150 的 WebView）里，同一个页面在系统浏览器里是好的，
 * 到了小程序的 WebView 里就整个塌掉 —— 塌的不止一处：
 *   ① CSS 的 vh / dvh 被算成 0
 *   ② 连 window.innerWidth / window.innerHeight 都是 0，document 可视高是 1px
 * 都是内核行为，App 侧那几个 viewport 开关（useWideViewPort / loadWithOverviewMode）拨不动它。
 *
 * 做法：把"窗口多高、多宽"这件事从外面接管进来。
 *   - App 侧把真实高度喂进页面（页面自己问不出来，只能外面告诉它）
 *   - 用 getter 接管 window.innerHeight / innerWidth（SPA 全靠这两个数算布局）
 *   - 把样式表里的 "Nvh" 重写成 calc(var(--app-vh, 1vh) * N)
 *   - 页面自己撑不起来时，给 html 兜一个高度
 * 页面源码一个字不动，改的全是运行时的东西；探针显示 vh 正常时一律不插手。
 */
internal val viewportFixScript: String = """
(function () {
  try {
    // 这里原来有一句"装过就跳过"。删掉它 —— 它害过一次：
    // 页面中途换过 document 时，标记（__vhFixInstalled）跟着 window 留下来了，
    // 但同一次注入里后装的 __vhFixSetHeight 没留下 —— 于是后面每次注入都被那句
    // 直接 return 掉，函数永远补不回来，视口就一直塌着。
    // 现在改成每次都确保函数在位；重复改写有防重（patchStyle 里跳过含 --app-vh 的），
    // 所以多跑几次是安全的。
    window.__vhFixInstalled = true;

    // 探针：这个 WebView 的 vh 好不好使
    var probe = document.createElement('div');
    probe.style.cssText = 'height:100vh;width:0;visibility:hidden;position:static';
    (document.body || document.documentElement).appendChild(probe);
    var probeH = probe.offsetHeight;
    if (probe.parentNode) probe.parentNode.removeChild(probe);
    // 判据注意：这个内核有时候给的是 1px（不是 0），一样是坏的 ——
    // 别拿"非零"当正常，拿"够不够一个正常视口"当标准。
    if (probeH >= 100) return;   // vh 正常，不插手

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

    // ★ 关键的一刀：这个内核连 window.innerHeight 都是 0，
    // 而抖音这类页面的布局全靠它算 —— 光把 CSS 的 vh 换掉，页面照样是 0 高。
    // 所以用 getter 把这两个数接管过来（实测：接管之后 html/body/滚动高度才从 0 起来）。
    function installViewportGetters() {
      // 不设早退：defineProperty 重复设没有副作用，而"留了标记却没设成"
      // 已经害过一次（见上面那段注释），这里每次进来都重设一遍。
      var getH = function () { return window.__vhFixHostHeight || 0; };
      var getW = function () { return window.screen.width || 360; };
      try {
        Object.defineProperty(window, 'innerHeight', { configurable: true, get: getH });
        Object.defineProperty(window, 'innerWidth', { configurable: true, get: getW });
      } catch (e) { /* 有的内核不让改，改不了就算了 */ }
      // ★ 实测这两个也坏：documentElement.clientHeight 给 1、visualViewport.height 给 1。
      // 而现代框架（抖音这类 SPA 的瀑布流就是）算"还能不能再渲染一屏"时读的正是它们 ——
      // 只接管 innerHeight 不够，页面排出来了却只铺半屏，下面空着。
      try {
        Object.defineProperty(document.documentElement, 'clientHeight', { configurable: true, get: getH });
        Object.defineProperty(document.documentElement, 'clientWidth', { configurable: true, get: getW });
      } catch (e) {}
      try {
        if (document.body) {
          Object.defineProperty(document.body, 'clientHeight', { configurable: true, get: getH });
        }
      } catch (e) {}
      try {
        if (window.visualViewport) {
          Object.defineProperty(window.visualViewport, 'height', { configurable: true, get: getH });
          Object.defineProperty(window.visualViewport, 'width', { configurable: true, get: getW });
        }
      } catch (e) {}
    }

    function ensureRootHeight(h) {
      try {
        // 只在页面自己明显没撑起来时兜底，别去盖页面自己的布局
        var cur = document.documentElement.getBoundingClientRect().height;
        if (cur < h * 0.5 && !document.documentElement.style.height) {
          document.documentElement.style.height = h + 'px';
        }
      } catch (e) {}
    }

    // notify=true 时才广播 resize；监听里再传 false，避免自己叫自己转圈。
    function setVh(notify) {
      // ★ 以"页面自己量到的视口"为准（2026-10-17）。
      // 小窝改成整屏之后，documentElement.clientHeight / visualViewport 终于是对的了，
      // 页面自己就能问出真值 —— 不用再完全依赖外面喂。
      // （实测踩过：外面的布局回调量到的是"WebView 半高"那一下，喂了个 350 进来，
      //   把真值 700 盖掉了，页面就按 350 排，下面空一半。）
      // App 喂的值只在视口还量不出来（<=100）时当兜底。
      var h = 0;
      try { h = document.documentElement.clientHeight || 0; } catch (e) {}
      if (!(h > 100)) h = window.__vhFixHostHeight || 0;
      if (h > 0) {
        window.__vhFixHostHeight = h;
        document.documentElement.style.setProperty('--app-vh', (h / 100) + 'px');
        installViewportGetters();
        ensureRootHeight(h);
      }
      if (notify) {
        try { window.dispatchEvent(new Event('resize')); } catch (e) {}
      }
    }

    // App 侧调用：把真实可视高度（px，逻辑像素）告诉页面，随后立刻重算一遍。
    window.__vhFixSetHeight = function (h) {
      h = Number(h);
      if (!isFinite(h) || h <= 0) return;
      window.__vhFixHostHeight = h;
      setVh(true);
    };

    setVh(false);
    rewriteAll();
    // 有些页面的脚本还在后续往里插样式，隔一会儿再扫两遍
    setTimeout(function () { rewriteAll(); setVh(false); }, 300);
    setTimeout(function () { rewriteAll(); setVh(false); }, 1200);

    window.addEventListener('resize', function () { setVh(false); });
    window.addEventListener('orientationchange', function () { setTimeout(function () { setVh(false); }, 250); });
  } catch (e) { /* 静默：补丁挂了也不能影响页面本身 */ }
})();
""".trimIndent()
