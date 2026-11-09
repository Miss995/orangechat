/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

/*
 * 液态玻璃（骨架版）
 *
 * 参考 github.com/TangfanOVO/qiaosi 的 components/liquid-glass/liquid-core.js（AGPL-3.0）。
 * 只取了骨架：圆角 SDF + 贴边高度场 + 两次折射 + 三路采样。
 * 砍掉的部分（以后往上加）：色散、GGX 高光、磨砂层、接地影、字、多块融合。
 *
 * 用法：
 *   val host = rememberLiquidGlassHost()
 *   Box {
 *       Box(Modifier.liquidGlassSource(host)) { 聊天列表() }   // 被录的一层
 *       TopBar(Modifier.liquidGlass(host, rect))                // 套玻璃的那块
 *   }
 */

package me.rerere.rikkahub.ui.components.ui

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import kotlinx.coroutines.delay

internal const val LIQUID_GLASS_AGSL = """
uniform shader uBg;
uniform float4  uRect;
// 这块自己的宽高（像素）。【形状只看它】—— 跟滚动位置无关，所以高光不会跟着跑。
uniform float2  uSize;
uniform float   uRadius;
uniform float   uIOR;
uniform float   uRefract;
uniform float   uThick;
uniform float   uAlpha;
uniform float   uBlur;
// 光心（相对本元素的局部坐标）。屏幕中心转到元素局部 = 光照在世界里不动、玻璃动了光就游。
// 2026-11-07 傍晚：不传屏幕绝对坐标，Kotlin 侧算好局部值直接传，shader 不用知道屏幕多大。
uniform float2  uLightLocal;
uniform float2  uBgInv;
// 【液态玻璃 V2 · 2026-11-08 宝在网页上调出来的核心】原来中间写死"放大 3%"，而真正像液态玻璃的是
// 「中间缩小 + 边缘一圈夸张放大」。拆成三个参数，默认值保持旧行为（气泡上的小果冻不受影响）。
//   uZoom    : 中间缩放。1.03 = 旧行为（放大 3%）；<1 = 内容缩小
//   uRim     : 边缘放大强度。0 = 关（旧行为）
//   uRimDist : 边缘影响多宽（像素）
uniform float   uZoom;
uniform float   uRim;
uniform float   uRimDist;
// 【2026-11-09 补 · 对着网页原型 glass-input-proto.html 的八根滑杆】
//   边缘 uEdge：内圈白（整圈，管"这是块有厚度的东西"），网页 r3，范围 0..1.2
//   高光 uSpec：左上/右下那两段细亮线（管清透），网页 r4，范围 0..2.5
uniform float   uEdge;
uniform float   uSpec;

// 【形状 · 2026-11-07 宝指的】只吃局部坐标 + 自己的宽高。
// 之前用整页矩形算，位置一变高光就跟着缩，看着像玻璃自己缩起来了。
float sdf(float2 p) {
    float2 hs = uSize * 0.5;
    float2 c  = hs;
    float r = min(uRadius, min(hs.x, hs.y));
    float2 q = abs(p - c) - hs + r;
    float m = max(q.x, q.y);
    if (m <= 0.0) return m - r;
    return length(max(q, float2(0.0))) - r;
}

// p 是这个元素自己的局部坐标，图是整屏的 —— 采样要补上元素在图里的位置
half4 sampleBg(float2 p) { return uBg.eval(p + uRect.xy); }

// 磨砂：九点散开取平均。采样点一散，细节就被抹平了。
// 纯折射是"水"，加了这一层才是"磨砂"。
half4 sampleBgBlur(float2 p) {
    if (uBlur < 0.5) return sampleBg(p);
    float2 q = p + uRect.xy;
    float  a = uBlur;
    float  i1 = a * 0.55;          // 内环
    float  i2 = i1 * 0.7071;       // 内环对角
    float  o1 = a;                 // 外环
    float  o2 = o1 * 0.7071;       // 外环对角
    // 中心最重，内环次之，外环最轻 —— 权重按距离衰减，平均才平滑；九点均等分是颗粒感的来源
    half4 acc = uBg.eval(q) * 0.16;
    acc += uBg.eval(q + float2( i1, 0.0)) * 0.06;
    acc += uBg.eval(q + float2(-i1, 0.0)) * 0.06;
    acc += uBg.eval(q + float2(0.0,  i1)) * 0.06;
    acc += uBg.eval(q + float2(0.0, -i1)) * 0.06;
    acc += uBg.eval(q + float2( i2,  i2)) * 0.06;
    acc += uBg.eval(q + float2(-i2,  i2)) * 0.06;
    acc += uBg.eval(q + float2( i2, -i2)) * 0.06;
    acc += uBg.eval(q + float2(-i2, -i2)) * 0.06;
    acc += uBg.eval(q + float2( o1, 0.0)) * 0.045;
    acc += uBg.eval(q + float2(-o1, 0.0)) * 0.045;
    acc += uBg.eval(q + float2(0.0,  o1)) * 0.045;
    acc += uBg.eval(q + float2(0.0, -o1)) * 0.045;
    acc += uBg.eval(q + float2( o2,  o2)) * 0.045;
    acc += uBg.eval(q + float2(-o2,  o2)) * 0.045;
    acc += uBg.eval(q + float2( o2, -o2)) * 0.045;
    acc += uBg.eval(q + float2(-o2, -o2)) * 0.045;
    return acc;
}

half4 main(float2 p) {
    // 【坐标系 · 2026-11-07】两套坐标分开：
    //   形状（轮廓/高光/圆角）→ 只用局部 p（0..w），位置动它不动
    //   采样 → sampleBg 内部自己 +uRect.xy，这里照样传局部
    float d = sdf(p);
    // 玻璃外面：什么都不画（让下面原本的内容露出来）
    if (d > 1.5) return half4(0.0);

    float e = clamp(-d / uThick, 0.0, 1.0);
    float h = sqrt(max(0.0, 1.0 - (1.0 - e) * (1.0 - e)));
    float slope = (1.0 - e) / max(h, 0.08);

    float d1 = sdf(p + float2(1.0, 0.0));
    float d2 = sdf(p + float2(0.0, 1.0));
    float2 g = normalize(float2(d1 - d, d2 - d) + float2(0.000001));
    float3 n = normalize(float3(-g * slope * 0.55, 1.0));
    float3 v = float3(0.0, 0.0, 1.0);

    float3 r1 = refract(-v, n, 1.0 / uIOR);
    float2 o1 = r1.xy / max(-r1.z, 0.25) * uThick * 0.85 * uRefract;
    float3 r2 = refract(normalize(r1), float3(0.0, 0.0, -1.0), uIOR);
    float2 o2 = r2.xy / max(-r2.z, 0.25) * uThick * 0.45 * uRefract;

    float rim  = 1.0 - h;
    float wOut = pow(rim, 7.0);
    float wIn  = pow(rim, 2.6) * (1.0 - wOut);
    float wFace = max(1.0 - wIn - wOut, 0.0);

    float reachIn  = uThick * 0.95 * uRefract;
    float reachOut = uThick * 0.55 * 1.28 * uRefract;

    // 【折射 · 2026-11-07 重做】宝说"太夸张不好、太弱又太普通"，根因是只有"推"一个参数：
    //   整块同一个位移强度 → 边缘斜面区直接推 10px，一调大就歪成一团；调小则整块没感觉。
    // 现在拆成三件互不干扰的事：
    //   ① 中心轻微放大（1.03）—— 隔着玻璃看东西会稍微大一点。这一下几乎看不出，但少了它就像贴张膜。
    //   ② 位移只活在边缘：用 wIn（只在内侧斜面区非零）压住，中间平面完全不推。
    //   ③ 色散：RGB 三条光分开位移（差 1.5px 左右），边缘会有一点彩 —— 它不靠位移大，所以永远不会夸张。
    float acc = o1.x * 0.0 + o2.x * 0.0 + reachOut * 0.0 + wFace * 0.0; // 占位，防未用告警

    // ① 中心放大：以元素中心为原点缩放采样坐标（>1 表示放大背后的内容）
    float2 ctr0 = uSize * 0.5;
    // 【V2】kz > 1 → 采样点离中心更近 → 中心内容被放大；kz < 1 → 内容缩小。
    //      边缘再乘 (1 - uRim*edgeZone)：越靠边 kz 越小 → 内容被拉出来放大。
    float  dEdge    = max(-d, 0.0);
    float  edgeZone = 1.0 - smoothstep(0.0, max(uRimDist, 0.5), dEdge);
    float  kz       = uZoom * (1.0 - uRim * edgeZone);
    // 【凸度调 0 应该等于"关掉" · 2026-11-09 21:37 宝："全调零他为什么把内容缩得这么小？你整我？"】
    //   kz 是除数，0 会除零，原来写 max(kz, 0.2) —— 保底只想着别崩，
    //   没管"调 0 的人想要什么"：0 被顶成 0.2，采样点被推到 5 倍远，内容缩成一小块。
    //   改成 0 和负数一律当 1.0（不缩放），滑杆推到底就是真的关掉。
    float  safeKz = (kz <= 0.0) ? 1.0 : kz;
    float2 zoom  = (p - ctr0) / safeKz + ctr0;

    // ② 位移收窄：wIn 只在边缘斜面区非零，乘上去之后中间彻底不推
    // 【边缘尖峰 · 2026-11-07 19:22 宝说"能不能让边缘一圈的折射特别夸张一点"】
    // 原来 wIn = pow(rim, 2.6)，从边缘往内是缓升的，看着像整块微微弯了一下。
    // 加一条只活在**最外几像素**的尖峰：pow(rim, 9) —— 越靠边越陡，尖峰处额外推开 2 倍。
    // 中间平面完全不参与，所以"夸张"只出现在一圈边上，不会把整块拽歪。
    float rimSpike = pow(rim, 9.0);
    float2 off = n.xy * reachIn * (wIn + rimSpike * 2.0);

    // ③ 色散：【2026-11-07 14:02 宝说"翻动时出现彩色的线，好恐怖"】直接删掉。
    // 技术上是无害的（纯软件算色，屏幕只是显示），但边缘整条边都会挂一圈彩，
    // 在深色背景上看着像坏点。不值得，去掉。
    float3 col = sampleBgBlur(zoom + off).rgb * 0.62
               + sampleBgBlur(zoom + off * 0.45).rgb * 0.38;

    // 边缘高光：只有朝光的那一侧边亮（光从左上来，跟阴影方向对上）

    // 【这层删了 · 2026-11-09 22:18 宝："到底为什么还有一条白边在啊，是不是前两天的底胶里也有高光"】
    //   它是 11-07 那版留下的 GGX 高光：wIn = pow(rim,2.6) 只在边缘非零 →
    //   等于**又给整圈提亮 45%**。今天新加的整圈带（下面 edgeBand*0.55*0.85）再提 47%，
    //   两层叠起来 ≈ 0.92，视觉上就是一条纯白的描边。
    //   网页里只有最后那一层，这里把旧的收掉，只留新版那条带 + 那条细线。

    // 【立体 · 2026-11-07】原来那条"整体上亮下暗"已删（宝说看着黑黑的）。
    // 只留背光那侧一道弱的暗，跟高光拉开对比就够。

    // 【背光侧压暗 · 2026-11-07 14:34 删掉】宝说气泡边缘有一圈深色阴影，浅色背景上特别明显。
    // 真凶就是这行：它只在背光侧压暗，而且边缘权重给到 1.6 倍 → 那一圈比周围暗 28% → 看着像阴影。
    // 玻璃的"存在感"不该靠压暗（压暗=蒙灰），靠饱和度 + 迎光侧的亮就够了。

    // 【边 · v2 · 2026-11-07 傍晚】照 whereyanlands v3 PDF 第 5 页的做法重写。
    // 它把边缘高光拆成两件事**相乘**：一条极细的线 × 按角度分布的亮度。
    //   ① hairline：高斯，中心在边框内侧半像素，σ 只有 0.62px —— 窄到不像描边
    //   ② 两个角度高斯：左上最亮（0.66）、右下次亮（0.48），其余几乎为零 —— 不是整圈均匀
    //   ③ mix 往白里靠 + clamp 封顶 0.76 —— 不死白，留四分之一给底色
    // 对比旧版：亮带 17.6px（宽 16 倍）、单方向点积、加法混合（会过曝）。
    float distIn = -d;


    // 【高光游走 · 2026-11-07 傍晚】角度改从"光心"算，不从元素中心。
    // 气泡在屏幕上滚动时 uRect 变、uLightLocal 跟着变 → 角度变 → 亮边沿气泡游走，
    // 就像光照在世界里没动、玻璃自己走过来了。要"静"就把 uLightLocal 钉成元素中心。
    float2 toLight = normalize(p - uLightLocal + float2(0.000001));
    float ang = atan(toLight.y, toLight.x);


    // 【雾 · 2026-11-07 18:46 宝说"像小猫在边缘画了一条线，太细了"】
    // 规矩：不加粗线本身（线细才是玻璃），在它外面罩一层宽 3px、强度 0.32 的雾。
    // 线负责"有边界"，雾负责"有厚度"。σ 从 0.62 → 3.0 是宽 5 倍，但中心值仍由 clamp 0.76 封住，
    // 所以不会过曝 —— 变化全在边缘往外那 3px 的衰减上。


    // 【边缘改成"整圈一条亮带" · 2026-11-09 21:34 宝："网页原版那个边缘和这个边缘的设计不一样"】
    //   网页（glass-input-proto）：g += vec3(edge * uEdge * 0.55)
    //     —— edge 只跟"离边界多远"有关，跟角度完全无关 → 整圈一条从边往里渐变的亮带，加法叠上去。
    //   App 之前：(细线 + 雾) × (左上/右下角度分布) → 只有那两段亮，其余地方根本没边；
    //     而且 uEdge × uSpec 是乘在一起的，两根滑杆互相掐。
    //   现在拆开：边缘走整圈加法（uEdge 管），高光只管那两段（uSpec 管），各管各的。
    // 【整圈亮带砍掉 · 2026-11-09 22:39 宝："反正我们也不要那个白边的效果，为什么要把它绑在滑条上强买强卖"】
    //   这条就是白边：pow(rim,2.2) 在边界处=1，×0.55×0.85 后边界被提亮 47%，
    //   在浅背景上就是一道白描边。网页那边淡，是猫系数给重了。
    //   现在只留一丝（0.10）做厚度感的根，带也收窄（指数 4.0）—— 不再是"边"，是"往里渐淡"。
    float edgeBand  = pow(rim, 2.2);
    // 【高光换成网页那套 · 2026-11-09 21:48 宝："你的高光是错的吧，网页里那个高光真的是这么调的吗"】
    //   网页（glass-input-proto 第 248~252 行）是两个固定位置的亮，跟角度、跟光心都无关：
    //     spec1 = 左上角一块压扁的柔光斑（中心 0.13/0.24，纵轴压到 0.6，半径 0.34，强度 0.18）
    //     spec2 = 对角线一条细线（|x+y 归一 - 0.28| 的窄带，只在上半部分，强度 0.11）
    //     两个相加 × uSpec，直接加到颜色上。
    //   App 原来那套是 (细线+雾) × (左上/右下角度分布)、跟着光心游走 —— 另一回事，宝说不对。
    // 【高光 = 沿边界的一条细亮线 · 22:04 照 11-09 04:27 版网页（tmp/glass-proto.html 303~341 行）】
    //   猫上一版搬的是 proto/ 下 11-08 09:27 那份旧稿，里面那套「椭圆斑 + 45° 直线」
    //   在新版注释里被明确删掉了（宝："圆角玻璃为什么高光是直线"）。
    //   新版做法：角度用**边界法线**算（atan(点-中心) 在圆角处会跳，法线不会）；
    //     只留左上/右下两段高斯，中间天然到 0；线是「平顶 + 半像素收边」，
    //     宽度跟着 mask 用 sqrt 收（亮处粗、暗处细、末端收成一根毫毛，不会一刀切）。
    float2 nrm = normalize(g);
    float  ang2 = atan(nrm.y, nrm.x);
    float  tA = (ang2 + 2.36) / 0.72;
    float  tB = (ang2 - 0.79) / 0.72;
    float  glintMask = exp(-tA * tA) * 1.00 + exp(-tB * tB) * 0.55;

    float  wC   = max(uRadius * 0.050, 1.6);   // 线中心离边界多远
    float  hw   = max(uRadius * 0.036, 1.1);   // 半宽
    float  dLine  = abs(distIn - wC);
    float  hwLocal = hw * sqrt(max(glintMask, 0.0));
    float  aaw    = max(hwLocal, 0.9);
    float  line   = 1.0 - smoothstep(aaw - 0.8, aaw + 0.8, dLine);
    float  specGlint = line * glintMask * uSpec;

    // 【边缘光染环境色 · 2026-11-07 傍晚，抄 Gale 开源那份 glass.css 的 ::before 渐变】
    // 它那条边不是纯白：白 0.62 → 壁纸主色 0.38 → 白 0.04 → 壁纸主色 0.22 → 白 0.30。
    // 这里用玻璃底下已经采到的颜色当"环境色"，混 38% 进去 —— 光像从背景里长出来的，不是贴上去的。
    float3 ambient = clamp(col * 1.35, 0.0, 1.0);
    float3 glintCol = mix(ambient, float3(1.0), 0.62);
    col = mix(col, glintCol, clamp(specGlint * 0.9, 0.0, 0.76));   // 高光：那条细线
    // 边缘：网页是「从边界往里渐淡的带」（innerWhite = edge*0.55 + line*0.08），不是一条线
        // 【均匀白边 · 2026-11-09 23:19 宝："白得特别均匀、特别扎眼、特别抽象"】
    //   shader 里唯一跟角度无关、整圈一样亮的就是这条厚度带：pow(rim,2.2) 在边界=1，
    //   ×0.55×0.85 = 提亮 47% → 在浅背景上就是一圈均匀的白。
    //   压到 0.12（≈8%）：厚度感还在，白边不再是"边"。
    col += float3((edgeBand * 0.12 + line * 0.08) * uEdge * 0.85);

    // 【左右色散 · 2026-11-07 傍晚，抄它 box-shadow 里那两条 inset】
    //   inset 1px 0 0  rgba(255,140,180,.10)  ← 左暖
    //   inset -1px 0 0 rgba(130,190,255,.14)  ← 右冷
    // 只挂在 hairline 上（细线区域），且量很小 —— 加多了会脏。
    float sideX = (p.x / max(uSize.x, 1.0)) * 2.0 - 1.0;   // -1 左 .. +1 右
    float warm  = clamp(-sideX, 0.0, 1.0);
    float cool  = clamp( sideX, 0.0, 1.0);
    // 【接上滑杆 · 2026-11-09 22:34 宝："边缘有黑黑的东西，我怀疑是前天加的那个高光和阴影"】
    //   这行是 11-07 抄 glass.css ::before 的左右色散，**从来没接过 uEdge/uSpec**：
    //   边缘调 0 它照样画，而且两个 float3 里都有负分量（-0.02）→ 减亮成暗边。
    //   现在挂到 uEdge 上：边缘推到 0，它跟着一起消失。

    // 【饱和度 · 2026-11-07 新增】CSS 那边是 blur + saturate(1.6)：玻璃后面的颜色会**更浓一点**。
    // 少了这一下，画面就是"糊"而不是"玻璃"。
    float lum = dot(col, float3(0.299, 0.587, 0.114));
    col = mix(float3(lum), col, 1.16);

    // 边上一点点柔化，免得硬边像描线
    float aa = 1.0 - smoothstep(-1.5, 0.5, d);
    float outA = uAlpha * aa;

    // 【外阴影 · 2026-11-07 傍晚 宝提】"生硬可能因为没有阴影" —— 对，之前六层删光了一刀切。
    // 这次不压在玻璃内部（内部压暗=蒙灰，14:34 已经翻过车），而是画在**外面**：
    //   形状往左上挪 5,7 → 阴影就落在右下（光从左上来，影子往右下掉）
    //   从边界 0.5px 开始，26px 内淡到 0，最浓 22%
    // 【外阴影整块删 · 2026-11-09 23:07 宝："他长得跟那个后面加的阴影一毛一样，还有三个角黑黑的"】
    //   11-07 18:26（4fdae488）加的那层：形状往左上挪 5,7 → 影子落右下，
    //   从边界 0.5px 到 26px 淡出、最浓 22%（黑）。三个角黑就是因为只往右下掉、左上没有。
    //   在浅背景上它同时就是那圈"白边的脏底"。宝说不要了 → 整块删，不是调小。
    return half4(half3(col), outA);
}
"""

/** 玻璃的视觉参数。默认值来自 qiaosi README 给的起点。 */
data class LiquidGlassStyle(
    val radius: Float = 22f,
    val ior: Float = 1.44f,
    /** 折射强度。1.75 太夸张（推开 80px），压到 0.6≈12px 才像玻璃 */
    val refract: Float = 0.6f,
    /** 玻璃厚度（决定弯折的范围） */
    val thickness: Float = 22f,
    /** 整体不透明度，先给 1（先把形做对，透明度以后按需要调） */
    val alpha: Float = 1f,
    /** 磨砂半径（像素）。0 = 纯折射（像水），越大越糊。宝 2026-11-07 选的就是 0 */
    val blur: Float = 0f,
    /** 【V2】凸度，对应网页的「凸度」uZoom。1.03 = 旧行为（放大 3%）；<1 = 内容缩小 */
    val zoom: Float = 1.03f,
    /** 【V2】边缘放大强度，对应网页的「边放」uRim。0 = 关（旧行为）。0.8~1.5 是宝试出来的范围 */
    val rim: Float = 0f,
    /** 【V2】边缘影响多宽（**像素**，调用方用 with(density){dp.toPx()} 换算）。对应网页的「边距」 */
    val rimDist: Float = 13f,
    /** 【2026-11-09】边缘这一圈的强弱，对应网页的「边缘」uEdge。整圈，管"有厚度" */
    val edge: Float = 1f,
    /** 【2026-11-09】两段高光的亮度，对应网页的「高光」uSpec。只管左上/右下两段，管"清透" */
    val spec: Float = 1f,
)

/**
 * 背景图持有者。
 *
 * - 调用方用 [liquidGlassSource] 包住要被采样的那一层，它每 300ms 录一张图进来。
 * - 用 [liquidGlass] 的那块玻璃从这里取背景。
 */
class LiquidGlassHost {
    internal var layer: GraphicsLayer? by mutableStateOf(null)
    internal var background: ImageBitmap? by mutableStateOf(null)
    internal var logged: Boolean = false
    internal var debugCount: Int = 0
}

@Composable
fun rememberLiquidGlassHost(): LiquidGlassHost {
    val host = remember { LiquidGlassHost() }
    val layer = rememberGraphicsLayer()
    LaunchedEffect(layer) {
        host.layer = layer
        while (true) {
            delay(300)
            val bmp = runCatching { layer.toImageBitmap() }.getOrNull()
            if (bmp != null && bmp.width > 0 && bmp.height > 0) {
                host.background = bmp
            }
        }
    }
    return host
}

/**
 * 录制器往下传的通道。
 *
 * 气泡在列表深处（ChatPage → ChatList → ChatMessage → BubbleSurface），
 * 一层层当参数传下去太脏，所以用 CompositionLocal。ChatPage 那边 provide，
 * 底下谁想套玻璃就 LocalLiquidGlassHost.current 拿。
 */
val LocalLiquidGlassHost = androidx.compose.runtime.staticCompositionLocalOf<LiquidGlassHost?> { null }

/**
 * 把这一层的内容录给 [host] 当背景。画出来的还是原样，只是多录了一份。
 */
@Composable
fun Modifier.liquidGlassSource(
    host: LiquidGlassHost,
    background: Color = Color.Unspecified,
): Modifier {
    val layer = host.layer
    if (layer == null) return this
    return this.drawWithContent {
        layer.record {
            // 底色先铺一层：页面容器本身是透明的，不铺的话录出来的图整张 alpha=0，
            // 玻璃采样到一片空，画出来就是黑的（2026-11-06 第一版踩到）。
            if (background != Color.Unspecified) {
                drawRect(background)
            }
            this@drawWithContent.drawContent()
        }
        drawLayer(layer)
    }
}

/**
 * 给一块矩形套上液态玻璃。
 *
 * [rect] 要跟背景图**同一个坐标系**：都是被 [liquidGlassSource] 包的那一层里的坐标。
 * 系统低于 Android 13（没有 AGSL）或者背景还没录到时，原样返回，什么都不画。
 */
fun Modifier.liquidGlass(
    host: LiquidGlassHost,
    rect: Rect,
    style: LiquidGlassStyle = LiquidGlassStyle(),
    // 屏幕尺寸（像素）。Modifier 扩展不能标 @Composable（里面的 Local* 读不了），
    // 所以由调用方在 Composable 里算好传进来。传 0 = 光心退回元素中心（不游走）。
    screenW: Float = 0f,
    screenH: Float = 0f,
): Modifier {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this
    val bg = host.background ?: return this
    // 【调试 · 2026-11-06】打一次尺寸，确认坐标差在哪
    if (!host.logged) {
        host.logged = true
        me.rerere.rikkahub.data.ai.AppLogBuffer.log(
            "LiquidGlass",
            "bg=${bg.width}x${bg.height} rect=(${rect.left},${rect.top},${rect.right},${rect.bottom})"
        )
    }
    // 【光心 · 2026-11-07 傍晚】屏幕中心换算到本元素局部坐标。
    // 屏幕中心在世界里是固定的；元素滚动时 rect 变，这个局部值就变 → 亮边自己游。
    val lightX = if (screenW > 0f) screenW * 0.5f - rect.left else rect.width * 0.5f
    val lightY = if (screenH > 0f) screenH * 0.5f - rect.top else rect.height * 0.5f

    val shader = runCatching {
        RuntimeShader(LIQUID_GLASS_AGSL).apply {
            setFloatUniform("uRect", rect.left, rect.top, rect.right, rect.bottom)
            // 形状只认宽高（跟滚动位置无关），高光和轮廓才钉得住
            setFloatUniform("uSize", rect.width, rect.height)
            setFloatUniform("uRadius", style.radius)
            setFloatUniform("uIOR", style.ior)
            setFloatUniform("uRefract", style.refract)
            setFloatUniform("uThick", style.thickness)
            setFloatUniform("uAlpha", style.alpha)
            setFloatUniform("uBlur", style.blur)
            setFloatUniform("uZoom", style.zoom)
            setFloatUniform("uRim", style.rim)
            // ⚠️ 这个函数不是 @Composable，读不了 LocalDensity —— 所以 rimDist 由调用方换算成**像素**再传
            setFloatUniform("uRimDist", style.rimDist)
            setFloatUniform("uEdge", style.edge)
            setFloatUniform("uSpec", style.spec)
            setFloatUniform("uLightLocal", lightX, lightY)
            // ⚠️ ImageShader 把整张图铺在 0..1 的坐标空间里，而 shader 里的 p 是像素坐标。
            // ImageShader 没有 matrix 参数（2026-11-06 编译报错过），所以在 shader 里用 uBgInv 换算。
            setFloatUniform("uBgInv", 1f / bg.width, 1f / bg.height)
            setInputShader("uBg", ImageShader(bg, TileMode.Clamp, TileMode.Clamp))
        }
    }.onFailure {
        me.rerere.rikkahub.data.ai.AppLogBuffer.log(
            "LiquidGlass", "shader COMPILE FAILED: ${it.javaClass.simpleName}: ${it.message}"
        )
    }.getOrNull() ?: return this

    return drawWithContent {
        // 【2026-11-07 宝指出的 B】尺寸不能用测量值：animateContentSize 会让 size 从 0 长到满，
        // 测量的那一刻可能还是半截 → 玻璃只填上半部分。绘制这一刻的 size 才是真的。
        // 位置仍然用测量到的左上角（size 里没有位置）。
        runCatching {
            shader.setFloatUniform(
                "uRect",
                rect.left, rect.top,
                rect.left + size.width, rect.top + size.height
            )
            shader.setFloatUniform("uSize", size.width, size.height)
        }
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            // 【投影 · 2026-11-07 14:48 整个删掉】宝说气泡边缘外面有一圈"向外突出、像直角"的阴影，
            // 浅色背景上特别明显。真凶就是这里：6 层 drawRoundRect 往斜下方扩（每层 +2.8px），
            // 扩出去的矩形比气泡大一圈，圆角也跟着变大 → 在圆角外侧露出四块直角色。
            // 玻璃的"浮起感"应该靠迎光那侧的亮，不靠投影。
            val paint = android.graphics.Paint().apply {
                this.shader = shader
                isAntiAlias = true
            }
            // 【调试 · 2026-11-07】量：传进 shader 的 rect 和实际绘制区的 size 对不对得上。
            // 顶上那条错、滑下来就好 —— 先看是不是"rect 是旧位置/旧高度"。
            host.debugCount++
            if (host.debugCount % 30 == 1) {
                me.rerere.rikkahub.data.ai.AppLogBuffer.log(
                    "LiquidGlass",
                    "draw rect=(${rect.left.toInt()},${rect.top.toInt()},${rect.width.toInt()}x${rect.height.toInt()}) " +
                        "size=(${size.width.toInt()}x${size.height.toInt()}) " +
                        "dW=${(rect.width - size.width).toInt()} dH=${(rect.height - size.height).toInt()}"
                )
            }
            // 【物理裁圆角 · 2026-11-09 23:00 宝："他长得跟那个后面加的阴影一毛一样，你自己看像不像"】
            //   11-07 删掉的是"6 层 drawRoundRect 往外扩"那套投影；但**症状同源**：
            //   这里 drawRect 画的是正矩形，圆角全靠 shader 里 `if (d > 1.5) return 0` 抹掉，
            //   而它留了 1.5px 容差 —— 圆角外侧那一圈没抹干净，留成半透明。
            //   在浅背景上就是"一圈白边 + 四个角露出直角"。
            //   → 不再靠 shader 抹，先把画布裁成圆角矩形，圆角外面一个像素都出不去。
            nc.save()
            val clipPath = android.graphics.Path().apply {
                addRoundRect(
                    0f, 0f, size.width, size.height,
                    style.radius, style.radius,
                    android.graphics.Path.Direction.CW
                )
            }
            nc.clipPath(clipPath)
            // 【临时对照 · 2026-11-09 23:24】玻璃完全不画，只验那条白线在不在玻璃里。
            // nc.drawRect(0f, 0f, size.width, size.height, paint)
            nc.restore()
        }
        drawContent()
    }
}
