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
uniform float2  uBgInv;

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
    float  b = uBlur * 0.7071;
    half4 acc = uBg.eval(q);
    acc += uBg.eval(q + float2( a,  0.0));
    acc += uBg.eval(q + float2(-a,  0.0));
    acc += uBg.eval(q + float2(0.0,  a));
    acc += uBg.eval(q + float2(0.0, -a));
    acc += uBg.eval(q + float2( b,  b));
    acc += uBg.eval(q + float2(-b,  b));
    acc += uBg.eval(q + float2( b, -b));
    acc += uBg.eval(q + float2(-b, -b));
    return acc * 0.1111111;
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
    float2 zoom  = (p - ctr0) / 1.03 + ctr0;

    // ② 位移收窄：wIn 只在边缘斜面区非零，乘上去之后中间彻底不推
    float2 off = n.xy * reachIn * wIn;

    // ③ 色散：RGB 各自一点点偏移，越靠边越明显（wIn 天然自带这个梯度）
    float2 disp = g * wIn * 1.6;

    float3 col;
    col.r = sampleBgBlur(zoom + off + disp).r;
    col.g = sampleBgBlur(zoom + off).g;
    col.b = sampleBgBlur(zoom + off - disp).b;
    col = col * 0.62 + sampleBgBlur(zoom + off * 0.45).rgb * 0.38;

    // 边缘高光：只有朝光的那一侧边亮（光从左上来，跟阴影方向对上）
    float3 lightDir = normalize(float3(-0.55, -0.75, 0.62));
    float spec = pow(max(0.0, dot(n, lightDir)), 6.0);
    col += spec * 0.32 * wIn;

    // 【立体 · 2026-11-07】原来那条"整体上亮下暗"已删（宝说看着黑黑的）。
    // 只留背光那侧一道弱的暗，跟高光拉开对比就够。
    float2 ctr      = uSize * 0.5;
    float2 toEdge   = normalize(p - ctr + float2(0.000001));
    float  backSide = smoothstep(-0.15, 0.85, dot(toEdge, normalize(float2(0.55, 0.83))));
    col *= mix(1.0, 0.72, backSide * wOut * 1.6 + backSide * wIn * 0.35);

    // 【边 · 2026-11-07 宝说"不够真"后重做】原来是一条 2.5px 的死硬亮线 + 9px 一圈暗，
    // 那是"描边"不是玻璃 —— 宽度跟厚度无关、亮度跟光向无关，所以假。现在：
    // ① 亮带宽度跟玻璃厚度走，中间最亮、两边淡（钟形，不是方块）
    // ② 迎光那半边亮、背光那半边暗，不是一个均匀的圈
    // 【边 · 2026-11-07 宝说"气泡被背景带走了"后重做】
    // 教训：玻璃的"存在感"全压在边缘上。上一版把贴边亮度降到 0.04，浅色背景上等于没有边
    // → 气泡就只是"背景的一块"，看着像被背景吞了。
    // 但也不能靠加粗（加粗=描边，宝已经说过粗）。真玻璃杯的边是**一亮一暗两条极细的线咬在一起**：
    // ① 1.5px 的亮线（切面反光）
    // ② 紧贴着它、到 4px 为止的一道暗（厚度吸收）
    // 两条都只有一两像素，对比极大 → 轮廓出来，但一点不粗。
    float distIn = -d;                                   // 到边的距离，里面为正
    float rimLine = 1.0 - smoothstep(0.0, 1.5, distIn);
    float lit     = dot(toEdge, normalize(float2(-0.55, -0.75)));
    float litAmt  = smoothstep(-0.60, 1.10, lit);
    // 亮线：迎光侧更亮，背光侧也留一点（不然轮廓断掉）
    col += rimLine * mix(0.42, 0.85, litAmt);

    // 紧贴的暗线
    float rimDark = (1.0 - smoothstep(0.0, 4.0, distIn)) - rimLine;
    col *= 1.0 - max(rimDark, 0.0) * 0.30;

    // 【2026-11-07】内晕只留一点点，几乎不动亮度（压暗会削清透感，已经踩过）
    float halo = smoothstep(0.0, uThick * 1.6, distIn);
    col *= 1.0 - (1.0 - halo) * 0.03;

    // 贴边最外沿
    col += wOut * 0.30;

    // 边上一点点柔化，免得硬边像描线
    float aa = 1.0 - smoothstep(-1.5, 0.5, d);
    return half4(half3(col), uAlpha * aa);
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
            // 投影：往下偏一点点、几层叠出软边，让玻璃浮起来。
            // （硬件加速下 BlurMaskFilter 不生效，所以用分层代替模糊）
            val shadowPaint = android.graphics.Paint().apply { isAntiAlias = true }
            val layers = 6
            for (i in layers downTo 1) {
                val spread = i * 2.8f
                shadowPaint.color = android.graphics.Color.argb(7, 0, 0, 0)
                // nativeCanvas 的坐标原点在这块自己的左上角，不是屏幕左上角 —— 这里一律用局部坐标
                nc.drawRoundRect(
                    -spread, spread * 0.55f,
                    size.width + spread, size.height + spread * 1.45f,
                    style.radius + spread, style.radius + spread,
                    shadowPaint
                )
            }
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
            nc.drawRect(0f, 0f, size.width, size.height, paint)
        }
        drawContent()
    }
}
