/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable

@Serializable
data class ProactiveMessageSetting(
    val enabled: Boolean = false,
    val minIntervalMinutes: Int = 30,
    val maxIntervalMinutes: Int = 90,
    val assistantId: String = "",
    // 是否允许 AI 根据上下文判断后强制跳转屏幕到聊天界面
    val allowForceJump: Boolean = false,
    val jumpIdleThresholdMinutes: Int = 120, // 用户多久没回复(分钟)才允许跳转屏幕，默认2小时
    // 激进模式：每次手机切换应用/开屏锁屏/回桌面都触发AI思考
    val aggressiveModeEnabled: Boolean = false,
    // 激进模式下两次AI思考之间的最小间隔（秒），防抖+限流
    val aggressiveMinIntervalSeconds: Int = 60,
    // 激进模式下，检测到设备事件（切应用/开关屏/回桌面）后，
    // 等待多少秒的防抖时间才真正触发 AI 思考。原来硬编码 30 秒，现在可调节。
    val aggressiveDebounceSeconds: Int = 30,
    // 【2026-10-03 橘仔加 · 宝拍板】激进模式下：同一个 App 在前台连续停留
    // 超过这么多分钟，就记一笔"停留"事件（跟切应用/开关屏并排进事件流）。
    // 它是"人在那儿泡着"的信号，不是"人醒了"——补现有事件类型缺的那一格。
    // 锁屏会重置计时（人走了，这段停留就结束了）；橘瓣自己前台时不计。
    // 0 = 关闭这个能力（默认关，不改变老行为）。
    val aggressiveDwellMinutes: Int = 0,
    // 悬浮球：主动消息到达时以 Telegram 风格悬浮球提醒，点击直接进入聊天页
    val floatingBubbleEnabled: Boolean = false,
)