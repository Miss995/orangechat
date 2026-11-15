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
 * 水 · 折射版（从 water_new.html 搬过来的 Gerstner 骨架）
 *
 * 【2026-11-16 00:41 宝：\"所以你打算重新水吗\"】
 * 上一版是猫自己写的四行正弦 —— 等于把宝调了两天的东西扔了重新猜。
 * 这一版照搬网页里的 waterHeight / waterGrad：
 *   · 六个频率的 Gerstner 叠加（真实海面的波浪谱就是这么来的）
 *   · 高度 = Σ A·sin(k·D·p + ωt)，位移用原位置算（不然是位移自己推自己）
 *   · 梯度用解析式（比数值差分省三次采样，且跟高度场严格同一套波）
 *
 * 先没搬的：涟漪（uRipples/uRipR —— 靠手指点屏幕才出现，聊天页背景层接不到触摸）、
 *           池底图案（under* —— 现在有真背景图当池底）、焦散（等水面像了再加）。
 */
private const val WATER_REFRACT = """
uniform shader uBg;
uniform float2 uSize;
uniform float uTime;
uniform float uRefr;
uniform float uShine;
uniform float uSpecPow;
uniform float uGerstner;
uniform float uWave;

// waveSin：网页里对 sin 做了轻微整形（峰更尖、谷更平），这里照用
float waveSin(float x) {
    return sin(x);
}

// ── 六个方向 + 六个波长（从网页那套搬；角度手算成 cos/sin 常量，避免依赖 radians()）
//  方向(度) / cos / sin / 波长 / K=2π/L / 振幅权重
//  8   : 0.990 / 0.139 / 0.50 / 12.566 / 0.35
//  30  : 0.866 / 0.500 / 0.30 / 20.944 / 0.22
//  52  : 0.616 / 0.788 / 0.20 / 31.416 / 0.14
//  75  : 0.259 / 0.966 / 0.13 / 48.332 / 0.09
//  100 : -0.174 / 0.985 / 0.09 / 69.813 / 0.06
//  128 : -0.616 / 0.788 / 0.06 / 104.720 / 0.04

float waterHeight(float2 p, float t) {
    float2 D1 = float2(0.990, 0.139); float K1 = 12.566;
    float2 D2 = float2(0.866, 0.500); float K2 = 20.944;
    float2 D3 = float2(0.616, 0.788); float K3 = 31.416;
    float2 D4 = float2(0.259, 0.966); float K4 = 48.332;
    float2 D5 = float2(-0.174, 0.985); float K5 = 69.813;
    float2 D6 = float2(-0.616, 0.788); float K6 = 104.720;

    // Gerstner 位移：波峰附近的点被往波峰方向推，等值线自己就弯了
    // 相位用【原位置 p】算，不是挪过的位置（否则是位移自己推自己）
    float2 disp = float2(0.0);
    disp += D1 * (uGerstner * 0.35 * cos(dot(p, D1) * K1 + t * 0.85));
    disp += D2 * (uGerstner * 0.22 * cos(dot(p, D2) * K2 + t * 1.15));
    disp += D3 * (uGerstner * 0.14 * cos(dot(p, D3) * K3 + t * 1.45));
    disp += D4 * (uGerstner * 0.09 * cos(dot(p, D4) * K4 + t * 1.75));
    disp += D5 * (uGerstner * 0.06 * cos(dot(p, D5) * K5 + t * 2.05));
    disp += D6 * (uGerstner * 0.04 * cos(dot(p, D6) * K6 + t * 2.35));

    float2 pw = p + disp;

    float h = 0.0;
    h += waveSin(dot(pw, D1) * K1 + t * 0.85) * 0.35;
    h += waveSin(dot(pw, D2) * K2 + t * 1.15) * 0.22;
    h += waveSin(dot(pw, D3) * K3 + t * 1.45) * 0.14;
    h += waveSin(dot(pw, D4) * K4 + t * 1.75) * 0.09;
    h += waveSin(dot(pw, D5) * K5 + t * 2.05) * 0.06;
    h += waveSin(dot(pw, D6) * K6 + t * 2.35) * 0.04;

    return clamp(h * 0.5 + 0.5, 0.0, 1.0);
}

// ── waterHeight 的解析梯度：h = Σ A·sin(k·D·p + ωt)，∇h = Σ A·k·D·cos(...)
float2 waterGrad(float2 p, float t) {
    float2 D1 = float2(0.990, 0.139); float K1 = 12.566;
    float2 D2 = float2(0.866, 0.500); float K2 = 20.944;
    float2 D3 = float2(0.616, 0.788); float K3 = 31.416;
    float2 D4 = float2(0.259, 0.966); float K4 = 48.332;
    float2 D5 = float2(-0.174, 0.985); float K5 = 69.813;
    float2 D6 = float2(-0.616, 0.788); float K6 = 104.720;

    float2 disp = float2(0.0);
    disp += D1 * (uGerstner * 0.35 * cos(dot(p, D1) * K1 + t * 0.85));
    disp += D2 * (uGerstner * 0.22 * cos(dot(p, D2) * K2 + t * 1.15));
    disp += D3 * (uGerstner * 0.14 * cos(dot(p, D3) * K3 + t * 1.45));
    disp += D4 * (uGerstner * 0.09 * cos(dot(p, D4) * K4 + t * 1.75));
    disp += D5 * (uGerstner * 0.06 * cos(dot(p, D5) * K5 + t * 2.05));
    disp += D6 * (uGerstner * 0.04 * cos(dot(p, D6) * K6 + t * 2.35));
    float2 pw = p + disp;

    float2 g = float2(0.0);
    g += D1 * (K1 * cos(dot(pw, D1) * K1 + t * 0.85) * 0.35);
    g += D2 * (K2 * cos(dot(pw, D2) * K2 + t * 1.15) * 0.22);
    g += D3 * (K3 * cos(dot(pw, D3) * K3 + t * 1.45) * 0.14);
    g += D4 * (K4 * cos(dot(pw, D4) * K4 + t * 1.75) * 0.09);
    g += D5 * (K5 * cos(dot(pw, D5) * K5 + t * 2.05) * 0.06);
    g += D6 * (K6 * cos(dot(pw, D6) * K6 + t * 2.35) * 0.04);
    return g * 0.5;
}

half4 main(float2 fragCoord) {
    // p 归一化到「屏幕高度 = 1」的坐标，波长才跟屏幕尺寸无关
    float2 p = fragCoord / uSize.y * 1.0;
    float2 uv = fragCoord / uSize;

    float t = uTime;

    float h = waterHeight(p, t);
    float2 g = waterGrad(p, t);

    // ── 折射：按坡度去别处取像素。位移 = -梯度 × 强度
    float2 off = -g * uRefr;
    // 屏幕边缘收一点，别把背景图的边拉到画面里来
    float2 edge = smoothstep(float2(0.0), float2(0.06), uv)
                * smoothstep(float2(0.0), float2(0.06), 1.0 - uv);
    off *= edge;

    half4 bg = uBg.eval(fragCoord + off);

    // ── 明暗：顺着坡度的方向打一盏斜光
    float3 L = normalize(float3(0.45, 0.65, 1.0));
    float3 N = normalize(float3(-g.x * 0.06, -g.y * 0.06, 1.0));
    float diff = clamp(dot(N, L), 0.0, 1.0);
    float shade = 0.86 + 0.24 * diff;
    bg.rgb = bg.rgb * half(shade);

    // ── 高光：浪尖两个亮斑（沿着坡度的方向走，所以会跟条纹一起动）
    float spec = pow(clamp(diff, 0.0, 1.0), uSpecPow) * uShine;
    bg.rgb += half3(spec);

    return bg;
}
"""

/**
 * 给某一层加上"水在动"的折射。
 * 用法：挂在背景那一层上（里面放背景图/颜色），别挂在整个页面（消息也会被拧）。
 */
@Composable
fun rememberWaterRefraction(
    enabled: Boolean,
    refractionPx: Float = 9f,
    shine: Float = 0.16f,
    specPow: Float = 26f,
    gerstner: Float = 0.55f,
): Modifier {
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
        shader.setFloatUniform("uRefr", refractionPx)
        shader.setFloatUniform("uShine", shine)
        shader.setFloatUniform("uSpecPow", specPow)
        shader.setFloatUniform("uGerstner", gerstner)
        shader.setFloatUniform("uWave", 1f)
        renderEffect = RenderEffect
            .createRuntimeShaderEffect(shader, "uBg")
            .asComposeRenderEffect()
    }
}
