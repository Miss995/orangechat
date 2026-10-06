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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChatUser
import me.rerere.rikkahub.data.model.Conversation
import kotlin.math.roundToInt

/* ---------------------------------------------------------------------------
 * 【小任务卡 · 第二刀 · 2026-10-06 宝定的】
 * 第一刀让 [指令 名称]内容[/指令] 在正文里原地变成一条"任务条"；
 * 这一刀再往上浮一张卡片：任务写完之后从右边滑进来，挂在聊天顶部
 * （宝拍板：放上面，不挡输入框）。左滑收掉，长按也收掉。
 * 收掉只关这张卡片，不动消息里的任务条（历史不改）。
 * 格式跟 ChatMessage.kt 里的 splitTaskSegments 是同一套。
 * ------------------------------------------------------------------------- */

private val OVERLAY_TASK_REGEX =
    Regex("\\[指令\\s*([^\\]]*)\\]([\\s\\S]*?)\\[/指令\\]", RegexOption.IGNORE_CASE)

internal data class OverlayTask(val name: String, val content: String)

internal fun findLatestTask(conversation: Conversation): OverlayTask? {
    val node = conversation.messageNodes.asReversed().firstOrNull {
        it.currentMessage.role == MessageRole.ASSISTANT
    } ?: return null
    val text = node.currentMessage.parts
        .filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
    val match = OVERLAY_TASK_REGEX.find(text) ?: return null
    return OverlayTask(
        name = match.groupValues[1].trim(),
        content = match.groupValues[2].trim(),
    )
}

@Composable
fun TaskCardOverlay(
    conversation: Conversation,
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    // 只在写完（不在生成中）时才认任务 —— 流式写一半的正则匹配不上，正好。
    val task = remember(conversation.messageNodes, loading) {
        if (loading) null else findLatestTask(conversation)
    }
    val key = task?.let { "${it.name}#${it.content.hashCode()}" }
    var dismissedKey by remember(conversation.id) { mutableStateOf<String?>(null) }
    var dragX by remember { mutableFloatStateOf(0f) }

    val visible = task != null && key != dismissedKey

    Box(modifier = modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
        ) {
            if (task != null) {
                TaskCard(
                    task = task,
                    dragX = dragX,
                    onDrag = { delta -> dragX += delta },
                    onDragEnd = {
                        if (dragX < -150f) dismissedKey = key
                        dragX = 0f
                    },
                    onLongPress = {
                        dismissedKey = key
                        dragX = 0f
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskCard(
    task: OverlayTask,
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
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = HugeIcons.ChatUser,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (task.name.isBlank()) "橘仔给的小任务" else "橘仔给的小任务 · ${task.name}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = task.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "左滑收掉 · 长按也收掉",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.55f),
            )
        }
    }
}
