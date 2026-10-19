package me.rerere.rikkahub.data.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 打印机状态条 · 2026-10-20 宝提的方向
 *
 * 本来那个"出纸"是发送动画：纸从缝里出来、飞上去、变成聊天气泡。
 * 问题是纸和气泡压根是两个东西，动画再顺也穿帮（宝逐帧看了 0.几秒，三条毛病全中）。
 *
 * 她换了方向：**纸不假装变成消息，它报"机器现在在干嘛"**。
 * 那就全是真的了，不用接，也不会穿帮。
 *
 * 每一步：门控判断 → 召回记忆 → 拼请求 → 小猫正在回复。
 * 打完的留勾和耗时，正在做的那行不勾（界面画个光标）。
 */
object PrinterStatus {

    data class Step(
        val label: String,
        val done: Boolean = false,
        val elapsedMs: Long = 0L,
    )

    private val _steps = mutableStateListOf<Step>()
    val steps: List<Step> get() = _steps

    /** 生成中 = true，纸就一直在（跟"插话条"共用一个弹窗） */
    var active: Boolean by mutableStateOf(false)
        private set

    private var lastAt = 0L

    /** 开一张新纸，第一条 */
    fun begin(label: String) {
        _steps.clear()
        active = true
        lastAt = System.currentTimeMillis()
        _steps.add(Step(label))
    }

    /** 推进：把正在做的那条打勾（记耗时），再加一条新的 */
    fun next(label: String) {
        if (!active) {
            begin(label)
            return
        }
        val now = System.currentTimeMillis()
        val i = _steps.indexOfLast { !it.done }
        if (i >= 0) {
            _steps[i] = _steps[i].copy(done = true, elapsedMs = now - lastAt)
        }
        lastAt = now
        _steps.add(Step(label))
        // 纸只留四行，新的进来旧的从上面滚出去（不然一张长条会挡住聊天）
        while (_steps.size > 4) _steps.removeAt(0)
    }

    /** 收尾：把最后一条也打勾，纸开始往回收 */
    fun finish() {
        if (!active) return
        val now = System.currentTimeMillis()
        val i = _steps.indexOfLast { !it.done }
        if (i >= 0) {
            _steps[i] = _steps[i].copy(done = true, elapsedMs = now - lastAt)
        }
        active = false
    }

    fun reset() {
        _steps.clear()
        active = false
    }
}
