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
uniform float uAbsorb;   // 水体吸收：红光先被吃掉，深处偏蓝绿（宝说的"透光"）
uniform float uDisp;     // 色散：三通道偏移差
uniform float uLens;     // 边缘放大：中心放大、四周往回收
// 六波各自的权重/方向/波长（十八根滑条）
uniform float uW1; uniform float uW2; uniform float uW3;
uniform float uW4; uniform float uW5; uniform float uW6;
uniform float uA1; uniform float uA2; uniform float uA3;
uniform float uA4; uniform float uA5; uniform float uA6;
uniform float uL1; uniform float uL2; uniform float uL3;
uniform float uL4; uniform float uL5; uniform float uL6;

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
    float2 D1 = float2(cos(radians(uA1)), sin(radians(uA1))); float K1 = 6.2832 / max(uL1, 0.02);
    float2 D2 = float2(cos(radians(uA2)), sin(radians(uA2))); float K2 = 6.2832 / max(uL2, 0.02);
    float2 D3 = float2(cos(radians(uA3)), sin(radians(uA3))); float K3 = 6.2832 / max(uL3, 0.02);
    float2 D4 = float2(cos(radians(uA4)), sin(radians(uA4))); float K4 = 6.2832 / max(uL4, 0.02);
    float2 D5 = float2(cos(radians(uA5)), sin(radians(uA5))); float K5 = 6.2832 / max(uL5, 0.02);
    float2 D6 = float2(cos(radians(uA6)), sin(radians(uA6))); float K6 = 6.2832 / max(uL6, 0.02);
    float w1 = uW1/100.0; float w2 = uW2/100.0; float w3 = uW3/100.0;
    float w4 = uW4/100.0; float w5 = uW5/100.0; float w6 = uW6/100.0;

    // Gerstner 位移：波峰附近的点被往波峰方向推，等值线自己就弯了
    // 相位用【原位置 p】算，不是挪过的位置（否则是位移自己推自己）
    float2 disp = float2(0.0);
    disp += D1 * (uGerstner * w1 * cos(dot(p, D1) * K1 + t * 0.85));
    disp += D2 * (uGerstner * w2 * cos(dot(p, D2) * K2 + t * 1.15));
    disp += D3 * (uGerstner * w3 * cos(dot(p, D3) * K3 + t * 1.45));
    disp += D4 * (uGerstner * w4 * cos(dot(p, D4) * K4 + t * 1.75));
    disp += D5 * (uGerstner * w5 * cos(dot(p, D5) * K5 + t * 2.05));
    disp += D6 * (uGerstner * w6 * cos(dot(p, D6) * K6 + t * 2.35));

    float2 pw = p + disp;

    float h = 0.0;
    h += waveSin(dot(pw, D1) * K1 + t * 0.85) * w1;
    h += waveSin(dot(pw, D2) * K2 + t * 1.15) * w2;
    h += waveSin(dot(pw, D3) * K3 + t * 1.45) * w3;
    h += waveSin(dot(pw, D4) * K4 + t * 1.75) * w4;
    h += waveSin(dot(pw, D5) * K5 + t * 2.05) * w5;
    h += waveSin(dot(pw, D6) * K6 + t * 2.35) * w6;

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
    disp += D1 * (uGerstner * w1 * cos(dot(p, D1) * K1 + t * 0.85));
    disp += D2 * (uGerstner * w2 * cos(dot(p, D2) * K2 + t * 1.15));
    disp += D3 * (uGerstner * w3 * cos(dot(p, D3) * K3 + t * 1.45));
    disp += D4 * (uGerstner * w4 * cos(dot(p, D4) * K4 + t * 1.75));
    disp += D5 * (uGerstner * w5 * cos(dot(p, D5) * K5 + t * 2.05));
    disp += D6 * (uGerstner * w6 * cos(dot(p, D6) * K6 + t * 2.35));
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
    // 【坐标系照搬网页 · 2026-11-16 01:09 宝："看不出什么门道，就是一大团"】
    // 网页：p = (uv-0.5)*vec2(aspect,1)；pp = p*2.5  →  跨度 2.5
    // 原来是 fragCoord/uSize.y（跨度 1）→ 同样的波长占半屏，纹理全糊成一团
    float2 uv = fragCoord / uSize;
    float aspect = uSize.x / uSize.y;
    float2 p = (uv - 0.5) * float2(aspect, 1.0) * 2.5;

    float t = uTime;

    float h = waterHeight(p, t);
    float2 g = waterGrad(p, t);

    // ── 折射：按坡度去别处取像素。位移 = -梯度 × 强度
    float2 off = -g * uRefr;

    // ── 边缘放大（透镜感）：越靠边，采样点越往中心收 → 中心看着胀、四周收
    float2 c = (uv - 0.5) * 2.0;
    float r2 = dot(c, c);
    float2 lens = c * (1.0 - uLens * r2);
    off += (lens - c) * 0.5 * uSize;

    // 屏幕边缘收一点，别把背景图的边拉进来
    float2 edge = smoothstep(float2(0.0), float2(0.06), uv)
                * smoothstep(float2(0.0), float2(0.06), 1.0 - uv);
    off *= edge;

    // ── 色散：三个通道偏得不一样（红折得最少、蓝折得最多），边缘就出彩边
    float2 oR = off * (1.0 + uDisp);
    float2 oG = off;
    float2 oB = off * (1.0 - uDisp);

    half4 cR = uBg.eval(fragCoord + oR);
    half4 cG = uBg.eval(fragCoord + oG);
    half4 cB = uBg.eval(fragCoord + oB);

    half3 col = half3(cR.r, cG.g, cB.b);
    half alpha = cG.a;

    // ── 明暗：顺着坡度打一盏斜光
    float3 L = normalize(float3(-0.46, 0.58, 0.67));   // 跟网页一致：光源在左上外
    float3 N = normalize(float3(-g.x * 0.06, -g.y * 0.06, 1.0));
    float diff = clamp(dot(N, L), 0.0, 1.0);
    col *= half(0.86 + 0.24 * diff);

    // ── 水体吸收（宝说的"透光"）：颜色不写死，
    //    从底下的东西来，再按「深度」吃点红光 —— 浅处接近原色，深处偏蓝绿
    // 【2026-11-16 01:22 宝换成粉壁纸，水还是青的】
    // 原来 absorb=(0.62,0.94,1.00)：红吃 38%，任何浅色乘上去都变青。
    // 真实的水是红光先被吃没错，但只在「深」的地方明显 —— 幅度收小、并且从 0 起步。
    float depthAmt = clamp(1.0 - h, 0.0, 1.0) * uAbsorb;
    half3 absorb = half3(0.88, 0.98, 1.00);   // 红只吃 12%，绿 2%
    col = col * mix(half3(1.0), absorb, half(depthAmt));

    // ── 高光：浪尖发亮
    float spec = pow(clamp(diff, 0.0, 1.0), uSpecPow) * uShine;
    col += half3(spec);

    return half4(col, alpha);
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
    absorb: Float = 0.45f,
    dispersion: Float = 0.035f,
    lens: Float = 0.10f,
    weights: List<Float> = listOf(35f, 22f, 14f, 9f, 6f, 4f),
    angles: List<Float> = listOf(8f, 30f, 52f, 75f, 100f, 128f),
    lengths: List<Float> = listOf(0.5f, 0.3f, 0.2f, 0.13f, 0.09f, 0.06f),
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
        shader.setFloatUniform("uAbsorb", absorb)
        shader.setFloatUniform("uDisp", dispersion)
        shader.setFloatUniform("uLens", lens)
        setSix(shader, "uW", weights); setSix(shader, "uA", angles); setSix(shader, "uL", lengths)
        renderEffect = RenderEffect
            .createRuntimeShaderEffect(shader, "uBg")
            .asComposeRenderEffect()
    }
}

/** 一次塞六个同名不同号的 uniform：uW1..uW6 / uA1..uA6 / uL1..uL6 */
private fun setSix(shader: RuntimeShader, prefix: String, values: List<Float>) {
    val v = values.take(6) + List(maxOf(0, 6 - values.size)) { 0f }
    for (i in 0 until 6) shader.setFloatUniform("$prefix${i + 1}", v[i])
}
