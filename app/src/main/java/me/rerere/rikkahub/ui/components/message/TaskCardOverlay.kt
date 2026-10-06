/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clipboard
import me.rerere.rikkahub.data.model.Conversation
import kotlin.math.roundToInt

/* ---------------------------------------------------------------------------
 * 【小任务卡 · 2026-10-06 宝定的】
 * 第一刀：正文里写 [指令 名称]内容[/指令] → 原地变成一条"任务条"（ChatMessage.kt）。
 * 这一刀：写完之后从右边滑进来一张卡片，挂聊天顶部。
 *   · 卡片底 = 纯白；字/竖线/图标/倒计时 = 主题里那个"卡片色"（淡紫）
 *   · 手势：左滑 = 完成；右滑 = 收掉；长按 = 收掉
 *   · 收掉不是消失 → 缩成右边一个小圆圈，倒计时住在圆圈上；点圆圈再展开
 *   · 倒计时写在任务名里：[指令 背课文 · 15分] → 15 分钟；不写就不显示
 *   · 完成 / 倒计时到点 → 回调 onTaskDone（ChatPage 那边落一条回执小字）
 * ------------------------------------------------------------------------- */

private val OVERLAY_TASK_REGEX =
    Regex("\\[指令\\s*([^\\]]*)\\]([\\s\\S]*?)\\[/指令\\]", RegexOption.IGNORE_CASE)

/** 名字尾巴上的时长，如「背课文 · 15分」「喝水 20min」「盯着 10秒」。
 *  长的单位写前面，免得 s 抢了 sec 的位子。 */
private val TAIL_DURATION_REGEX =
    Regex("[·•・\\-—]?\\s*(\\d+)\\s*(小时|时|hour|hr|h|分钟|分|min|m|秒钟|秒|sec|s)\\s*$")

internal data class OverlayTask(
    val name: String,
    val content: String,
    val seconds: Int?,
)

/** 「15分」→ 900、「10秒」→ 10、「2小时」→ 7200。上限 24 小时。 */
private fun parseDurationSeconds(numText: String, unit: String): Int? {
    val num = numText.toIntOrNull() ?: return null
    val sec = when {
        unit.startsWith("秒") || unit.equals("s", true) || unit.startsWith("sec", true) -> num
        unit.startsWith("时") || unit.startsWith("hour", true) ||
            unit.equals("h", true) || unit.startsWith("hr", true) -> num * 3600
        else -> num * 60
    }
    return sec.takeIf { it in 1..86_400 }
}

/** 往前翻多少条 AI 消息之内算"当前任务"；太老的就不挂了 */
private const val TASK_LOOKBACK = 12

/**
 * 【2026-10-06 晚修】原来只认"最后一条 AI 消息" —— 猫一开口说话，最后一条变成新消息，
 * 卡片就跟着没了。改成往前翻几条；并且用回执反查：一个任务后面只要有
 * 〔任务完成〕/〔任务到点〕，就算结掉（重启 App 也不会复活）。
 */
internal fun findLatestTask(conversation: Conversation): OverlayTask? {
    val nodes = conversation.messageNodes
    if (nodes.isEmpty()) return null

    // 已经结掉的任务名（完成 / 倒计时到点都算）
    val settled = nodes.asSequence()
        .mapNotNull { taskReceiptText(it.currentMessage) }
        .map { it.substringAfter("〕") }
        .toSet()

    var seenAssistant = 0
    for (i in nodes.indices.reversed()) {
        val msg = nodes[i].currentMessage
        if (msg.role != MessageRole.ASSISTANT) continue
        seenAssistant += 1
        if (seenAssistant > TASK_LOOKBACK) break

        val text = msg.parts
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n") { it.text }
        val match = OVERLAY_TASK_REGEX.find(text) ?: continue

        val rawName = match.groupValues[1].trim()
        val dm = TAIL_DURATION_REGEX.find(rawName)
        val seconds = dm?.let { parseDurationSeconds(it.groupValues[1], it.groupValues[2]) }
        val name = (if (dm != null) rawName.substring(0, dm.range.first).trim() else rawName)
            .ifBlank { "小任务" }

        if (name in settled) continue        // 这条已经结过了 → 往前找更早的
        return OverlayTask(
            name = name,
            content = match.groupValues[2].trim(),
            seconds = seconds,
        )
    }
    return null
}

@Composable
fun TaskCardOverlay(
    conversation: Conversation,
    loading: Boolean,
    onTaskDone: (name: String, byTimeout: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 只在写完（不在生成中）时才认任务 —— 流式写一半正则匹配不上，正好。
    val task = remember(conversation.messageNodes, loading) {
        if (loading) null else findLatestTask(conversation)
    }
    val key = task?.let { "${it.name}#${it.content.hashCode()}" }
    val totalSec = task?.seconds ?: 0

    var doneKey by remember(conversation.id) { mutableStateOf<String?>(null) }
    var collapsedKey by remember(conversation.id) { mutableStateOf<String?>(null) }
    var timeoutReportedKey by remember(conversation.id) { mutableStateOf<String?>(null) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var remain by remember(key) { mutableIntStateOf(totalSec) }

    LaunchedEffect(key) {
        remain = totalSec
        if (totalSec <= 0) return@LaunchedEffect
        while (remain > 0) {
            delay(1000L)
            remain -= 1
        }
    }

    // 倒计时到点、而且她没点完成 → 也回执一次（只报一次）
    LaunchedEffect(key, remain) {
        val current = task
        if (current != null && key != null && totalSec > 0 &&
            remain == 0 && timeoutReportedKey != key && doneKey != key
        ) {
            timeoutReportedKey = key
            onTaskDone(current.name, true)
        }
    }

    val show = task != null && key != doneKey
    val collapsed = show && collapsedKey == key

    Box(modifier = modifier.fillMaxWidth()) {
        // 展开态：顶部大卡片
        AnimatedVisibility(
            visible = show && !collapsed,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
        ) {
            if (task != null) {
                TaskCard(
                    task = task,
                    remainSec = remain,
                    dragX = dragX,
                    onDrag = { delta -> dragX += delta },
                    onDragEnd = {
                        when {
                            dragX < -140f -> {
                                doneKey = key
                                onTaskDone(task.name, false)
                            }
                            dragX > 140f -> collapsedKey = key
                        }
                        dragX = 0f
                    },
                    onLongPress = {
                        collapsedKey = key
                        dragX = 0f
                    },
                )
            }
        }

        // 收起态：右边的小圆圈（倒计时住在上面）
        AnimatedVisibility(
            visible = collapsed,
            modifier = Modifier.align(Alignment.TopEnd),
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = fadeOut(),
        ) {
            TaskBubble(
                remainSec = remain,
                totalSec = totalSec,
                onClick = { collapsedKey = null },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskCard(
    task: OverlayTask,
    remainSec: Int,
    dragX: Float,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onLongPress: () -> Unit,
) {
    // 宝定的配色：底纯白。
    // "读"的地方（标题/正文/图标）用深紫保证看得清；"看"的地方（竖线/倒计时/圆圈）留她喜欢的那个淡紫。
    // 【2026-10-07 修】主题的 onSecondaryContainer 在宝这套配色里近乎纯黑。
    // 换成写死的紫：不跟主题走，保证"是紫的、看得清"。
    val ink = androidx.compose.ui.graphics.Color(0xFF6C5BA8)
    val accentSoft = MaterialTheme.colorScheme.surfaceContainerHigh

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .offset { IntOffset(dragX.roundToInt(), 0) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                    onHorizontalDrag = { _, delta -> onDrag(delta) },
                )
            }
            .combinedClickable(
                onClick = {},
                onLongClick = { onLongPress() },
            ),
        shape = RoundedCornerShape(14.dp),
        color = Color.White,
        tonalElevation = 0.dp,
        shadowElevation = 6.dp,
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            // 左边一条竖线（跟字同色）
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(accentSoft),
            )
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = HugeIcons.Clipboard,
                        contentDescription = null,
                        tint = ink,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "橘仔给的小任务 · ${task.name}",
                        style = MaterialTheme.typography.labelMedium,
                        color = ink,
                        modifier = Modifier.weight(1f),
                    )
                    if (task.seconds != null) {
                        Text(
                            text = formatRemain(remainSec),
                            style = MaterialTheme.typography.labelMedium,
                            color = accentSoft,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = task.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ink,
                )
                Spacer(Modifier.height(9.dp))
                Text(
                    text = "左滑完成 · 右滑收掉",
                    style = MaterialTheme.typography.labelSmall,
                    color = ink.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun TaskBubble(
    remainSec: Int,
    totalSec: Int,
    onClick: () -> Unit,
) {
    val progress = if (totalSec > 0) remainSec.toFloat() / totalSec.toFloat() else 0f
    val bg = MaterialTheme.colorScheme.surfaceContainerHigh
    val trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.25f)
    val progressColor = MaterialTheme.colorScheme.onSecondaryContainer

    Surface(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .size(50.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = bg,
        shadowElevation = 6.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (totalSec > 0) {
                Canvas(modifier = Modifier.fillMaxSize().padding(3.dp)) {
                    val stroke = Stroke(width = 2.5.dp.toPx())
                    drawArc(
                        color = trackColor,
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = stroke,
                    )
                    drawArc(
                        color = progressColor,
                        startAngle = -90f,
                        sweepAngle = 360f * progress.coerceIn(0f, 1f),
                        useCenter = false,
                        style = stroke,
                    )
                }
            }
            Text(
                text = when {
                    totalSec <= 0 -> "任"
                    totalSec < 60 -> "${remainSec}s"
                    else -> "${(remainSec + 59) / 60}"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

private fun formatRemain(sec: Int): String {
    val s = sec.coerceAtLeast(0)
    return if (s >= 3600) {
        "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    } else {
        "%02d:%02d".format(s / 60, s % 60)
    }
}

/* ---------------------------------------------------------------------------
 * 【任务回执小字 · 2026-10-06 宝定的】
 * 完成 / 倒计时到点 → 往会话里落一条"〔任务完成〕名称"的小字。
 * 它不画成气泡，就一行居中的灰字，卡在她和猫两条消息中间。
 * ------------------------------------------------------------------------- */

private val RECEIPT_PREFIXES = listOf("〔任务完成〕", "〔任务到点〕")

internal fun taskReceiptText(message: UIMessage): String? {
    if (message.role != MessageRole.USER) return null
    val text = message.parts
        .filterIsInstance<UIMessagePart.Text>()
        .joinToString("") { it.text }
        .trim()
    return if (RECEIPT_PREFIXES.any { text.startsWith(it) }) text else null
}

@Composable
fun TaskReceiptLine(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}
