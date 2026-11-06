package me.rerere.rikkahub.ui.components.glass

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.toSize

/**
 * 液态玻璃 · 第一版骨架
 *
 * 思路照技术方案 v3（@whereyanlands）+ Abdullajon1881/LiquidGlass：
 *
 *   ① Provider：每帧把页面内容录进一个 GraphicsLayer，所有玻璃共享这一份
 *   ② Glass：把自己那一块从 Provider 的层里切出来 → 模糊 → AGSL shader
 *   ③ Shader：圆角遮罩 + 边缘折射 + 左上强/右下弱的高光
 *
 * 这一版先不接界面，只保证编译通过。
 */
class LiquidGlassProviderState {
    internal var layer: GraphicsLayer? = null

    /** provider 内容左上角在窗口坐标系里的位置 */
    internal var originInWindow: Offset = Offset.Zero
}

/**
 * 背景源。
 *
 * 用法：包住会滚动的内容（聊天列表等），玻璃元素必须放在它**外面**，
 * 否则会自己画自己。
 */
@Composable
fun LiquidGlassProvider(
    state: LiquidGlassProviderState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val layer = rememberGraphicsLayer()

    SideEffect { state.layer = layer }

    Box(
        modifier = modifier
            .onGloballyPositioned {
                state.originInWindow = it.boundsInWindow().topLeft
            }
            .drawWithContent {
                // 每帧只录一次，所有玻璃共享
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            }
    ) {
        content()
    }
}

/**
 * 玻璃。
 *
 * 把 provider 的层按偏移画进一块临时层，再对那块临时层套 shader。
 */
@Composable
fun Modifier.liquidGlass(
    state: LiquidGlassProviderState,
    cornerRadiusDp: Float = 24f,
    refractPx: Float = 26f,
    edgeWidthPx: Float = 18f,
    tint: Float = 0f,
): Modifier = composed {
    val glassLayer = rememberGraphicsLayer()
    var selfInWindow by remember { mutableStateOf(Offset.Zero) }
    var selfSize by remember { mutableStateOf(Size.Zero) }

    val density = LocalDensity.current
    val radiusPx = cornerRadiusDp * density.density

    this
        .onGloballyPositioned {
            selfInWindow = it.boundsInWindow().topLeft
            selfSize = it.size.toSize()
        }
        .drawWithContent {
            val provider = state.layer
            if (provider == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                // 没背景源 / 设备不支持 AGSL → 老实画自己
                drawContent()
                return@drawWithContent
            }

            // 这块玻璃相对 provider 原点的偏移
            val offset = selfInWindow - state.originInWindow

            glassLayer.record {
                translate(-offset.x, -offset.y) {
                    drawLayer(provider)
                }
            }
            glassLayer.renderEffect = buildGlassEffect(
                size = Size(size.width, size.height),
                radiusPx = radiusPx,
                refractPx = refractPx,
                edgeWidthPx = edgeWidthPx,
                tint = tint,
            )

            drawLayer(glassLayer)

            // 玻璃上面的字 / 图标
            drawContent()
        }
}

/**
 * 组装 RenderEffect 链：平台 blur → AGSL 玻璃。
 *
 * 注意：RenderEffect 是不可变的，uniform 一变就得重建。
 * 第一版每次绘制都重建，先求能跑，后面再谈缓存。
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun buildGlassEffect(
    size: Size,
    radiusPx: Float,
    refractPx: Float,
    edgeWidthPx: Float,
    tint: Float,
): RenderEffect {
    val shader = RuntimeShader(GLASS_AGSL)
    shader.setFloatUniform("size", size.width, size.height)
    shader.setFloatUniform("radius", radiusPx)
    shader.setFloatUniform("refractPx", refractPx)
    shader.setFloatUniform("edgeWidthPx", edgeWidthPx)
    shader.setFloatUniform("tint", tint)

    // 平台 blur 比自己写快得多，先糊再把结果喂进 shader 的 content
    val blurRadius = edgeWidthPx * 0.35f
    val blur = android.graphics.RenderEffect.createBlurEffect(
        blurRadius,
        blurRadius,
        android.graphics.Shader.TileMode.CLAMP,
    )
    val glass = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content")

    return android.graphics.RenderEffect
        .createChainEffect(blur, glass)
        .asComposeRenderEffect()
}

/**
 * AGSL。
 *
 * 照着技术方案 v3 的四样：
 *   ① 圆角遮罩（圆角矩形 SDF）
 *   ② 边缘折射（圆形透镜剖面，越靠边斜率越大）
 *   ③ 左上强 / 右下弱的边缘高光（不是整圈死白描边）
 *   ④ 一点点 tint
 */
private const val GLASS_AGSL = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float refractPx;
uniform float edgeWidthPx;
uniform float tint;

// 圆角矩形有符号距离：内部为负，外部为正
float sdRoundBox(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

// SDF 梯度（AGSL 没有导数内置，用中心差分）
float2 sdfGrad(float2 p, float2 b, float r) {
    float e = 0.6;
    float dx = sdRoundBox(p + float2(e, 0.0), b, r) - sdRoundBox(p - float2(e, 0.0), b, r);
    float dy = sdRoundBox(p + float2(0.0, e), b, r) - sdRoundBox(p - float2(0.0, e), b, r);
    return float2(dx, dy);
}

half4 main(float2 coord) {
    float2 p = coord - size * 0.5;
    float2 halfSize = size * 0.5;
    float r = min(radius, min(halfSize.x, halfSize.y));

    float d = sdRoundBox(p, halfSize, r);

    // ① 圆角遮罩（顺便当抗锯齿的裁切）
    float mask = 1.0 - smoothstep(-1.0, 1.0, d);
    if (mask <= 0.001) {
        return half4(0.0);
    }

    // ② 边缘折射：越靠近边界，采样点越往内偏
    float borderDist = -d;
    float t = clamp(borderDist / edgeWidthPx, 0.0, 1.0);
    // 圆形透镜剖面：t=0（最边上）斜率最大，t=1（内侧）斜率归零
    float inv = 1.0 - t;
    float lens = 1.0 - sqrt(max(1.0 - inv * inv, 0.0));

    float2 g = sdfGrad(p, halfSize, r);
    float2 dir = g / max(length(g), 0.0001);
    float2 samplePos = coord + dir * lens * refractPx;

    half4 col = content.eval(samplePos);

    // ③ 边缘高光：一道细线 × 左上强、右下弱
    float ang = atan(p.y, p.x);
    float hairline = exp(-pow((borderDist - 0.6) / 1.1, 2.0));
    float upperLeft = exp(-pow((ang + 2.38) / 0.62, 2.0));
    float lowerRight = exp(-pow((ang - 0.76) / 0.64, 2.0));
    float glint = hairline * (upperLeft * 0.66 + lowerRight * 0.46);
    glint = clamp(glint, 0.0, 0.72);

    // ④ 一点点染色（tint=0 时几乎不改变）
    float3 rgb = col.rgb;
    rgb = mix(rgb, float3(0.62, 0.68, 0.78), tint * 0.18);
    rgb = mix(rgb, float3(1.0), glint);

    // 预乘 alpha
    return half4(rgb * mask, mask);
}
"""
