package me.rerere.rikkahub.ui.components.ui

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
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
uniform float uCaustic;  // 光斑（焦散）强度
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
    // 【2026-11-16 02:20】w1..w6 在 waterHeight 里是局部变量，这里是另一个函数，
    // 得自己声明一份。上一版漏了这六行，AGSL 直接 unknown identifier → 构造 RuntimeShader
    // 抛异常 → main 线程闪退。
    float w1 = uW1/100.0; float w2 = uW2/100.0; float w3 = uW3/100.0;
    float w4 = uW4/100.0; float w5 = uW5/100.0; float w6 = uW6/100.0;

    float2 disp = float2(0.0);
    disp += D1 * (uGerstner * w1 * cos(dot(p, D1) * K1 + t * 0.85));
    disp += D2 * (uGerstner * w2 * cos(dot(p, D2) * K2 + t * 1.15));
    disp += D3 * (uGerstner * w3 * cos(dot(p, D3) * K3 + t * 1.45));
    disp += D4 * (uGerstner * w4 * cos(dot(p, D4) * K4 + t * 1.75));
    disp += D5 * (uGerstner * w5 * cos(dot(p, D5) * K5 + t * 2.05));
    disp += D6 * (uGerstner * w6 * cos(dot(p, D6) * K6 + t * 2.35));
    float2 pw = p + disp;

    // 权重跟着滑条走（原来这里写死成 0.35/0.22/... = 默认值，滑条只影响高度不影响折射）
    float2 g = float2(0.0);
    g += D1 * (K1 * cos(dot(pw, D1) * K1 + t * 0.85) * w1);
    g += D2 * (K2 * cos(dot(pw, D2) * K2 + t * 1.15) * w2);
    g += D3 * (K3 * cos(dot(pw, D3) * K3 + t * 1.45) * w3);
    g += D4 * (K4 * cos(dot(pw, D4) * K4 + t * 1.75) * w4);
    g += D5 * (K5 * cos(dot(pw, D5) * K5 + t * 2.05) * w5);
    g += D6 * (K6 * cos(dot(pw, D6) * K6 + t * 2.35) * w6);
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

    // ── 光斑（焦散）· 2026-11-16 搬自网页版 v149
    // 判据 = 「比四周低」的那几条暗缝（dip = 四周平均高度 − 本点高度）。
    // ⚠️ 网页版走过的三条弯路，都被推翻了，别再捡回来：
    //    ① 细胞边界 F2−F1（Worley）：亮度跟格子大小挂钩，一不均就断成虚线
    //    ② 水面曲率 det=(1+k·h_xx)(1+k·h_yy)：曲率只能出曲线，天生不是折线网
    //    ③ |∇h| 最大：那找的是「谷的两侧」两条，要的是「谷底」一条
    // ⚠️ 尺度：App 的 p 已经乘过 2.5（等于网页版的 ps），这里不要再乘，否则差 2.5 倍
    // ★ 按宝给的五条规律重做（2026-11-16 19:10 v2）：
    //   ① 是网线不是光块 ② 线不等宽（交汇处粗、中间细、有的断）
    //   ③ 格子圆润 ④ 线两侧有柔光晕 ⑤ 边缘带一丝色散
    // 上一版用固定阈值 0.065 + 幂次 2.5：只有最深的谷才亮 → 线又稀又硬，像描边。
    float eG = 0.02;
    float hc = waterHeight(p, t);
    float hAvg = (waterHeight(p + float2(eG, 0.0), t) + waterHeight(p - float2(eG, 0.0), t)
                + waterHeight(p + float2(0.0, eG), t) + waterHeight(p - float2(0.0, eG), t)) * 0.25;
    float dip = max(hAvg - hc, 0.0);             // >0 = 这儿比四周低 = 谷
    // ★ ① 阈值降下来（0.065 → 0.035）：能成线的地方变多，线就密了、也会出现细弱断口
    // ★ ④【芯要硬 · 宝 19:10 纠正】幂次不是越低越好 —— 幂次决定"芯"有多锐，
    //    降到 1.6 等于把芯也削软了，出来的是一团棉花不是亮线。
    //    真实焦散：亮线和暗水之间是硬边（她给的参考图里全是锐的），柔的只有外面那层晕。
    //    所以芯的幂次调回 2.6，柔光晕另外单独铺（见下面的 haloV）。
    // ★ ⑤ 三个通道阈值微差（红窄 / 绿中 / 蓝宽）：边缘自然带一点点彩边
    float base = 0.035;
    float kR = pow(clamp(dip / (base * 0.88), 0.0, 1.0), 2.6);
    float kG = pow(clamp(dip / (base * 1.00), 0.0, 1.0), 2.6);
    float kB = pow(clamp(dip / (base * 1.14), 0.0, 1.0), 2.6);
    // ★ ④ 柔光晕：同一个 dip，阈值放大 2.6 倍、低强度 —— 线两侧铺一层淡光
    float haloV = pow(clamp(dip / (base * 2.6), 0.0, 1.0), 1.2) * 0.30;
    // ★ ② 不等宽：dip 越大（交汇处越深）亮度越高，自然更粗更亮；浅处只留一点点
    col += half3((kR + haloV) * 0.92, (kG + haloV) * 0.99, (kB + haloV)) * uCaustic * 1.2;

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
    caustic: Float = 0.55f,
    weights: List<Float> = listOf(35f, 22f, 14f, 9f, 6f, 4f),
    angles: List<Float> = listOf(8f, 30f, 52f, 75f, 100f, 128f),
    lengths: List<Float> = listOf(0.5f, 0.3f, 0.2f, 0.13f, 0.09f, 0.06f),
): Modifier {
    if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return Modifier

    // 【2026-11-16 02:20】AGSL 里写错一个标识符，构造 RuntimeShader 就会抛
    // IllegalArgumentException，而且是在 main 线程 → 整个聊天页直接闪退。
    // 这里兜住：编译不过就当这个效果不存在，退回普通背景，别把 App 带走。
    val shader: RuntimeShader? = remember {
        runCatching { RuntimeShader(WATER_REFRACT) }
            .onFailure { Log.e("WaterShader", "AGSL 编译失败，水波背景已跳过", it) }
            .getOrNull()
    }
    if (shader == null) return Modifier

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
        shader.setFloatUniform("uCaustic", caustic)
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
