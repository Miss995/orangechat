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
 * 水 · 折射版（梯度）
 *
 * 【为什么改成求梯度 · 2026-11-16 00:37 宝：\"会晃，但是不像是折射，更像是扭曲\"】
 * 上一版 x、y 各拿两根正弦当位移 —— 那是「推」，推得不匀就是拉扯（扭曲）。
 * 真折射的位移必须来自**同一个高度场的梯度**：光线被水面坡度折了一下，落点跟着偏。
 * 只有位移场是某个标量场的梯度时，图像才不会被剪切变形，看着才像「隔着水看东西」。
 */
private const val WATER_REFRACT = """
uniform shader uBg;
uniform float2 uSize;
uniform float uTime;
uniform float uStrength;
uniform float uShine;

// ── 水面高度：几层不同方向、不同速度的正弦叠起来
float heightAt(float2 uv, float t) {
    float h = 0.0;
    h += sin(uv.x * 6.2831 * 1.40 + t * 1.10) * 0.50;
    h += sin(uv.y * 6.2831 * 2.10 - t * 0.80) * 0.30;
    h += sin((uv.x + uv.y) * 6.2831 * 1.70 + t * 1.60) * 0.20;
    h += sin((uv.x - uv.y) * 6.2831 * 3.30 - t * 2.30) * 0.12;
    return h;
}

half4 main(float2 p) {
    float2 uv = p / uSize;

    // 一个像素的步长（用来求偏导）
    float e = 1.0 / uSize.y;

    float h  = heightAt(uv, uTime);
    float hx = heightAt(uv + float2(e, 0.0), uTime);
    float hy = heightAt(uv + float2(0.0, e), uTime);

    // 梯度 = 坡度。位移取负梯度：坡度越陡，光线折得越厉害
    // uStrength 直接是「每单位坡度偏多少像素」（梯度量级约 4~5，所以这个数给个位数就够）
    float2 grad = float2((hx - h) / e, (hy - h) / e);
    float2 off = -grad * uStrength;

    // 折射：按坡度去别处取像素
    half4 c = uBg.eval(p + off);

    // ── 高光：浪尖发亮（高度越高越亮）
    float spec = pow(max(h * 0.5 + 0.55, 0.0), 8.0) * uShine;
    c.rgb += half3(spec);

    return c;
}
"""

/**
 * 给某一层加上"水在动"的折射。
 * 用法：挂在背景那一层上（里面放背景图/颜色），别挂在整个页面（消息也会被拧）。
 */
@Composable
fun rememberWaterRefraction(enabled: Boolean, strengthPx: Float = 4.5f): Modifier {
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
