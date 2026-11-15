package me.rerere.rikkahub.ui.components.ui

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 水 · 折射版
 *
 * 【跟上一版的区别 · 2026-11-16】
 * 上一版（骨架）是自己在画一片水，跟聊天背景图是两张皮。
 * 这一版走 RenderEffect.createRuntimeShaderEffect(shader, "uBg")：
 * **底下已经画好的东西会自动作为纹理喂进来**（uBg），
 * 水只管按浪的斜率把它拧一下 —— 你的背景图、以后换成任何图，它都自动跟着。
 *
 * 这条路跟液态玻璃（LiquidGlass.kt）是同一套：RenderEffect 不可变，uniform 一变就重建。
 */
private const val WATER_REFRACT = """
uniform shader uBg;
uniform float2 uSize;
uniform float uTime;
uniform float uStrength;
uniform float uShine;

half4 main(float2 p) {
    float2 uv = p / uSize;

    // ── 两个方向各自的"斜率"（先用相位差近似，不追求物理，只要像水在动）
    float dx = sin(uv.y * 6.2831 * 2.1 + uTime * 0.90) * 0.55
             + sin((uv.x + uv.y) * 6.2831 * 1.70 + uTime * 1.60) * 0.45;
    float dy = sin(uv.x * 6.2831 * 1.40 + uTime * 1.10) * 0.55
             + sin((uv.y - uv.x) * 6.2831 * 2.60 + uTime * 1.30) * 0.45;

    // ── 折射：采样点偏一点。偏多少 = 折射强弱
    float2 off = float2(dx, dy) * uStrength;
    half4 bg = uBg.eval(p + off);

    // ── 一点高光（浪尖发亮）
    float spec = pow(max(dx * 0.5 + 0.5, 0.0), 7.0) * uShine;
    bg.rgb += half3(spec);

    return bg;
}
"""

/**
 * 给某一层加上"水在动"的折射。
 * 用法：挂在背景那一层上（里面放背景图/颜色），别挂在整个页面（消息也会被拧）。
 */
@Composable
fun rememberWaterRefraction(enabled: Boolean, strengthPx: Float = 14f): Modifier {
    if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return Modifier

    val shader = remember { RuntimeShader(WATER_REFRACT) }
    val time by produceState(0f) {
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> value = (now - start) / 1_000_000_000f }
        }
    }

    return Modifier.graphicsLayer {
        if (size.width < 1f || size.height < 1f) return@graphicsLayer
        shader.setFloatUniform("uSize", size.width, size.height)
        shader.setFloatUniform("uTime", time)
        shader.setFloatUniform("uStrength", strengthPx)
        shader.setFloatUniform("uShine", 0.10f)
        renderEffect = RenderEffect
            .createRuntimeShaderEffect(shader, "uBg")
            .asComposeRenderEffect()
    }
}
