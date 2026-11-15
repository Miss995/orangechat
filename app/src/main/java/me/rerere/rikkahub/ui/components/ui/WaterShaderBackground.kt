package me.rerere.rikkahub.ui.components.ui

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeShader

/**
 * 水 · AGSL 骨架（第一版）
 *
 * 【为什么走这条路 · 2026-11-15】
 * 上一次试的是把 water.html 塞进聊天页背景（WebView），
 * 但 WebView 是硬件层，Compose 的 drawLayer 录不进 GraphicsLayer，
 * 重试三版全黑，最后回退。（memory 410）
 *
 * 这一次跟液态玻璃一样：把 GLSL 翻成 AGSL，用 RuntimeShader 直接画。
 *
 * 【这一版只求"能跑"】
 * 只有几层正弦叠加 + 一点高光，没有任何涟漪/焦散/折射。
 * 先确认 AGSL 能不能在聊天页背景上真的动起来，再往里加东西。
 */
private const val WATER_SKELETON = """
uniform float2 uSize;
uniform float uTime;
uniform float3 uDeep;
uniform float3 uShallow;

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / uSize;

    // ── 浪高：几层不同方向的正弦叠起来（先不管物理，只要"像水面在动"）
    float h = 0.0;
    h += sin(uv.x * 6.2831 * 1.4 + uTime * 1.1) * 0.50;
    h += sin(uv.y * 6.2831 * 2.1 - uTime * 0.8) * 0.30;
    h += sin((uv.x + uv.y) * 6.2831 * 1.7 + uTime * 1.6) * 0.20;
    h += sin((uv.x - uv.y) * 6.2831 * 3.3 - uTime * 2.3) * 0.12;

    // 归一化到 0..1
    float shade = h * 0.5 + 0.5;

    // ── 底色：深处偏暗蓝，浅处偏青
    half3 col = mix(half3(uDeep), half3(uShallow), half(shade));

    // ── 一点高光（把高度取个高次方，高处发亮）
    float spec = pow(max(shade, 0.0), 7.0);
    col += half3(spec * 0.28);

    // ── 边缘压暗一点，别让整屏一样亮
    float2 c = uv - float2(0.5, 0.5);
    float vig = 1.0 - smoothstep(0.35, 0.85, length(c));
    col *= half(0.75 + 0.25 * vig);

    return half4(col, 1.0);
}
"""

@Composable
fun WaterShaderBackground(modifier: Modifier = Modifier) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        // API 33 以下没有 RuntimeShader，画个渐变兜底，别白屏
        Canvas(modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color(0xFF0A1E2A), Color(0xFF1B4A5A), Color(0xFF2E6E7E))
                )
            )
        }
        return
    }

    val shader = remember { RuntimeShader(WATER_SKELETON) }

    val time by produceState(0f) {
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                value = (now - start) / 1_000_000_000f
            }
        }
    }

    Canvas(modifier.fillMaxSize()) {
        if (size.width < 1f || size.height < 1f) return@Canvas
        shader.setFloatUniform("uSize", size.width, size.height)
        shader.setFloatUniform("uTime", time)
        shader.setFloatUniform("uDeep", 0.04f, 0.12f, 0.17f)
        shader.setFloatUniform("uShallow", 0.24f, 0.58f, 0.66f)
        drawRect(brush = ShaderBrush(shader.asComposeShader()))
    }
}
