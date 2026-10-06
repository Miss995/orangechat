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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clipboard
import me.rerere.rikkahub.data.model.Conversation
import kotlin.math.roundToInt

/* ---------------------------------------------------------------------------
 * 【小任务卡 · 2026-10-06 宝定的】
 * 第一刀：正文里写 [指令 名称]内容[/指令] → 原地变成一条"任务条"（ChatMessage.kt）。
 * 这一刀：写完之后从右边滑进来一张卡片，挂聊天顶部。
 *   · 底色浅（跟聊天框一致），左边一条主题色竖线
 *   · 手势：左滑 = 完成（这条收尾了）；右滑 = 收掉；长按 = 收掉
 *   · 收掉不是消失 → 缩成右边一个小圆圈，倒计时住在圆圈上；点圆圈再展开
 *   · 倒计时写在任务名里：[指令 背课文 · 15分] → 15 分钟；不写就不显示
 * ------------------------------------------------------------------------- */

private val OVERLAY_TASK_REGEX =
    Regex("\\[指令\\s*([^\\]]*)\\]([\\s\\S]*?)\\[/指令\\]", RegexOption.IGNORE_CASE)

/** 名字尾巴上的时长，如「背课文 · 15分」「喝水 20min」 */
private val TAIL_DURATION_REGEX = Regex("[·•・\\-—]?\\s*(\\d+)\\s*(?:分|分钟|min|m)\\s*$")

internal data class OverlayTask(
    val name: String,
    val content: String,
    val minutes: Int?,
)

internal fun findLatestTask(conversation: Conversation): OverlayTask? {
    val node = conversation.messageNodes.asReversed().firstOrNull {
        it.currentMessage.role == MessageRole.ASSISTANT
    } ?: return null
    val text = node.currentMessage.parts
        .filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
    val match = OVERLAY_TASK_REGEX.find(text) ?: return null

    val rawName = match.groupValues[1].trim()
    val dm = TAIL_DURATION_REGEX.find(rawName)
    val minutes = dm?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..600 }
    val name = if (dm != null) rawName.substring(0, dm.range.first).trim() else rawName

    return OverlayTask(
        name = name.ifBlank { "小任务" },
        content = match.groupValues[2].trim(),
        minutes = minutes,
    )
}

@Composable
fun TaskCardOverlay(
    conversation: Conversation,
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    // 只在写完（不在生成中）时才认任务 —— 流式写一半正则匹配不上，正好。
    val task = remember(conversation.messageNodes, loading) {
        if (loading) null else findLatestTask(conversation)
    }
    val key = task?.let { "${it.name}#${it.content.hashCode()}" }
    val totalSec = (task?.minutes ?: 0) * 60

    var doneKey by remember(conversation.id) { mutableStateOf<String?>(null) }
    var collapsedKey by remember(conversation.id) { mutableStateOf<String?>(null) }
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
                            dragX < -140f -> doneKey = key
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
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 6.dp,
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            // 左边一条主题色竖线（宝说的「」那种边缘色）
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary),
            )
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = HugeIcons.Clipboard,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "橘仔给的小任务 · ${task.name}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (task.minutes != null) {
                        Text(
                            text = formatRemain(remainSec),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = task.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(9.dp))
                Text(
                    text = "左滑完成 · 右滑收掉",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
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
    val trackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.25f)
    val progressColor = MaterialTheme.colorScheme.onPrimary

    Surface(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .size(50.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
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
                text = if (totalSec > 0) "${(remainSec + 59) / 60}" else "任",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary,
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
