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
uniform float   uRadius;
uniform float   uIOR;
uniform float   uRefract;
uniform float   uThick;
uniform float   uAlpha;
uniform float2  uBgInv;

float sdf(float2 p) {
    float2 c = (uRect.xy + uRect.zw) * 0.5;
    float2 hs = (uRect.zw - uRect.xy) * 0.5;
    float r = min(uRadius, min(hs.x, hs.y));
    float2 q = abs(p - c) - hs + r;
    float m = max(q.x, q.y);
    if (m <= 0.0) return m - r;
    return length(max(q, float2(0.0))) - r;
}

// ImageShader 把整张图铺在 0..1 的坐标空间里，p 是像素坐标，这里换算过去
half4 sampleBg(float2 p) { return uBg.eval(p * uBgInv); }

half4 main(float2 p) {
    float d = sdf(p);
    // 玻璃外面：什么都不画（让下面原本的内容露出来）
    if (d > 1.5) return half4(0.0);

    float e = clamp(-d / uThick, 0.0, 1.0);
    float h = sqrt(max(0.0, 1.0 - (1.0 - e) * (1.0 - e)));
    float slope = (1.0 - e) / max(h, 0.08);

    float d1 = sdf(p + float2(1.0, 0.0));
    float d2 = sdf(p + float2(0.0, 1.0));
    float2 g = normalize(float2(d1 - d, d2 - d) + float2(0.000001));
    float3 n = normalize(float3(-g * slope * 0.95, 1.0));
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

    float3 col = sampleBg(p + o1 + o2).rgb * wFace;
    col += sampleBg(p - g * reachIn).rgb * wIn;
    col += sampleBg(p + g * reachOut).rgb * wOut;

    // 边上一点点柔化，免得硬边像描线
    float aa = 1.0 - smoothstep(-1.5, 0.5, d);
    return half4(half3(col), uAlpha * aa);
}
"""

/** 玻璃的视觉参数。默认值来自 qiaosi README 给的起点。 */
data class LiquidGlassStyle(
    val radius: Float = 22f,
    val ior: Float = 1.44f,
    val refract: Float = 1.75f,
    val thickness: Float = 48f,
    /** 整体不透明度，先给 1（先把形做对，透明度以后按需要调） */
    val alpha: Float = 1f,
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
    val shader = runCatching {
        RuntimeShader(LIQUID_GLASS_AGSL).apply {
            setFloatUniform("uRect", rect.left, rect.top, rect.right, rect.bottom)
            setFloatUniform("uRadius", style.radius)
            setFloatUniform("uIOR", style.ior)
            setFloatUniform("uRefract", style.refract)
            setFloatUniform("uThick", style.thickness)
            setFloatUniform("uAlpha", style.alpha)
            // ⚠️ ImageShader 把整张图铺在 0..1 的坐标空间里，而 shader 里的 p 是像素坐标。
            // ImageShader 没有 matrix 参数（2026-11-06 编译报错过），所以在 shader 里用 uBgInv 换算。
            setFloatUniform("uBgInv", 1f / bg.width, 1f / bg.height)
            setInputShader("uBg", ImageShader(bg, TileMode.Clamp, TileMode.Clamp))
        }
    }.getOrNull() ?: return this

    return drawWithContent {
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint().apply {
                this.shader = shader
                isAntiAlias = true
            }
            canvas.nativeCanvas.drawRect(
                rect.left, rect.top, rect.right, rect.bottom, paint
            )
        }
        drawContent()
    }
}
