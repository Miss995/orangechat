/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.MessageMultiple01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.FavouriteCircle
import me.rerere.hugeicons.stroke.GitFork
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.Share04
import me.rerere.hugeicons.stroke.StopCircle
import me.rerere.hugeicons.stroke.TextSelection
import me.rerere.hugeicons.stroke.Translate
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.hugeicons.stroke.WebDesign01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalDisplaySettings
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.utils.copyMessageToClipboard
import me.rerere.rikkahub.utils.extractQuotedContentAsText
import me.rerere.rikkahub.utils.toLocalString
import java.util.Locale
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.DpOffset
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.Surface

@Composable
fun ChatMessageActionMenu(
    message: UIMessage,
    expanded: Boolean,
    offset: DpOffset,
    onDismissRequest: () -> Unit,
    onRegenerate: () -> Unit,
    onOpenActionSheet: () -> Unit,
    // 【点按菜单 · 2026-10-10】编辑只给用户消息（宝说她从没用过 AI 那条的编辑）
    onEdit: (() -> Unit)? = null,
    onTranslate: ((UIMessage, Locale) -> Unit)? = null,
    onClearTranslation: (UIMessage) -> Unit = {},
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var showTranslateDialog by remember { mutableStateOf(false) }
    var showRegenerateConfirm by remember { mutableStateOf(false) }

    // 【点按菜单 · 2026-10-10】原来是消息下面那排小图标，改成浮在手指旁边的菜单。
    // offset = 手指落点（相对整条消息），由 ChatMessage 那边的 pointerInput 量出来。
    // 【点按菜单换皮 · 2026-11-18 21:35 宝选方案①】原来挂在 DropdownMenu 上，底子是系统的：
    // 弹出动画、阴影调法、宽度脾气都改不动，跟家里的纸感/玻璃怎么刷都不像一家。
    // 现在改成自己画的 Popup，皮肤照抄选中浮条（深色 0xFF2B2723 + 圆角 12 + 阴影 8），
    // 于是"点正文"和"选文字"这两个手势叫出来的东西长得一模一样。
    if (expanded) {
        Popup(
            popupPositionProvider = remember(offset) {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize,
                    ): IntOffset {
                        val m = with(density) { 8.dp.roundToPx() }
                        val ox = with(density) { offset.x.roundToPx() }
                        val oy = with(density) { offset.y.roundToPx() }
                        val maxX = (windowSize.width - popupContentSize.width - m).coerceAtLeast(m)
                        val maxY = (windowSize.height - popupContentSize.height - m).coerceAtLeast(m)
                        // 原来由 DropdownMenu 内部做的"出屏自动躲"，现在自己夹一下：
                        // 贴边就收进来，宁可歪一点，也不让菜单跑出屏幕。
                        return IntOffset(
                            (anchorBounds.left + ox).coerceIn(m, maxX),
                            (anchorBounds.top + oy).coerceIn(m, maxY),
                        )
                    }
                }
            },
            onDismissRequest = onDismissRequest,
            // focusable 才收得到"点外面"和返回键 —— 菜单要能点空处关掉
            properties = PopupProperties(focusable = true),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF2B2723).copy(alpha = 0.94f),
                contentColor = Color(0xFFF3EFE8),
                shadowElevation = 8.dp,
                modifier = Modifier.widthIn(min = 110.dp),
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
        // 复制
        CompactMenuItem(
            icon = HugeIcons.Copy01,
            label = stringResource(R.string.copy),
            onClick = {
                onDismissRequest()
                context.copyMessageToClipboard(message)
            }
        )

        // 重新生成（用户消息先弹确认框：免得误点把自己的话重发一遍）
        CompactMenuItem(
            icon = HugeIcons.Refresh03,
            label = stringResource(R.string.regenerate),
            onClick = {
                onDismissRequest()
                if (message.role == MessageRole.USER) {
                    showRegenerateConfirm = true
                } else {
                    onRegenerate()
                }
            }
        )

        if (message.role == MessageRole.ASSISTANT) {
            val tts = LocalTTSState.current
            val displaySettings = LocalDisplaySettings.current
            val isSpeaking by tts.isSpeaking.collectAsState()
            val isAvailable by tts.isAvailable.collectAsState()

            // 朗读 / 停止朗读
            CompactMenuItem(
                icon = if (isSpeaking) HugeIcons.StopCircle else HugeIcons.VolumeHigh,
                label = stringResource(R.string.tts),
                enabled = isAvailable,
                onClick = {
                    onDismissRequest()
                    if (!isSpeaking) {
                        val text = message.toText()
                        val textToSpeak = if (displaySettings.ttsOnlyReadQuoted) {
                            text.extractQuotedContentAsText() ?: text
                        } else {
                            text
                        }
                        tts.speak(textToSpeak)
                    } else {
                        tts.stop()
                    }
                }
            )

            // 翻译
            if (onTranslate != null) {
                CompactMenuItem(
                    icon = HugeIcons.Translate,
                    label = stringResource(R.string.translate),
                    onClick = {
                        onDismissRequest()
                        showTranslateDialog = true
                    }
                )
            }
        }

        // 编辑（只给用户消息）
        if (message.role == MessageRole.USER && onEdit != null) {
            CompactMenuItem(
                icon = HugeIcons.Edit01,
                label = stringResource(R.string.edit),
                onClick = {
                    onDismissRequest()
                    onEdit()
                }
            )
        }

        // 更多 → 原来的底部操作面板（那个不动）
        CompactMenuItem(
            icon = HugeIcons.MoreVertical,
            label = stringResource(R.string.more_options),
            onClick = {
                onDismissRequest()
                onOpenActionSheet()
            }
        )
                }
            }
        }
    }

    // Translation dialog
    if (showTranslateDialog && onTranslate != null) {
        LanguageSelectionDialog(
            onLanguageSelected = { language ->
                showTranslateDialog = false
                onTranslate(message, language)
            },
            onClearTranslation = {
                showTranslateDialog = false
                onClearTranslation(message)
            },
            onDismissRequest = {
                showTranslateDialog = false
            },
        )
    }

    // Regenerate confirmation dialog
    RikkaConfirmDialog(
        show = showRegenerateConfirm,
        title = stringResource(R.string.regenerate),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            showRegenerateConfirm = false
            onRegenerate()
        },
        onDismiss = { showRegenerateConfirm = false },
        text = { Text(stringResource(R.string.regenerate_confirm_message)) }
    )
}
/**
 * 【点按菜单 · 2026-10-10 宝要求再缩一半】自己拼的一行。
 *
 * 为什么不用系统的 DropdownMenuItem：它内部有硬性的 minWidth(约 280dp) / minHeight(约 48dp)，
 * 字号和 contentPadding 都改了也压不下去 —— 想真正缩一半只能自己画。
 * 这里每行高约 26dp（12dp 图标 + 5dp 上下内边距）。
 */
@Composable
private fun CompactMenuItem(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val contentColor = LocalContentColor.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = if (enabled) contentColor else contentColor.copy(alpha = 0.38f),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (enabled) contentColor else contentColor.copy(alpha = 0.38f),
        )
    }
}

@Composable
fun ChatMessageActionsSheet(
    message: UIMessage,
    model: Model?,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onFork: () -> Unit,
    onSelectAndCopy: () -> Unit,
    // 【消息引用 2026-09-22】宝长按消息选"引用"，把这条挂到输入框上方
    onQuote: (() -> Unit)? = null,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onWebViewPreview: () -> Unit,
    onDismissRequest: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Select and Copy
            Card(
                onClick = {
                    onDismissRequest()
                    onSelectAndCopy()
                },
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Icon(
                        imageVector = HugeIcons.TextSelection,
                        contentDescription = null,
                        modifier = Modifier.padding(4.dp)
                    )
                    Text(
                        text = stringResource(R.string.select_and_copy),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            // Quote（消息引用 2026-09-22）：把这条挂到输入框上方的引用条
            if (onQuote != null) {
                Card(
                    onClick = {
                        onDismissRequest()
                        onQuote()
                    },
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = HugeIcons.MessageMultiple01,
                            contentDescription = null,
                            modifier = Modifier.padding(4.dp)
                        )
                        Text(
                            text = "引用",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }

            // WebView Preview (only show if message has text content)
            val hasTextContent = message.parts.filterIsInstance<UIMessagePart.Text>()
                .any { it.text.isNotBlank() }

            if (hasTextContent) {
                Card(
                    onClick = {
                        onDismissRequest()
                        onWebViewPreview()
                    },
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = HugeIcons.WebDesign01,
                            contentDescription = null,
                            modifier = Modifier.padding(4.dp)
                        )
                        Text(
                            text = stringResource(R.string.render_with_webview),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }

            // Edit
            Card(
                onClick = {
                    onDismissRequest()
                    onEdit()
                },
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Icon(
                        imageVector = HugeIcons.Edit01,
                        contentDescription = null,
                        modifier = Modifier.padding(4.dp)
                    )
                    Text(
                        text = stringResource(R.string.edit),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            // Share
            Card(
                onClick = {
                    onDismissRequest()
                    onShare()
                },
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Icon(
                        imageVector = HugeIcons.Share04,
                        contentDescription = null,
                        modifier = Modifier.padding(4.dp)
                    )
                    Text(
                        text = stringResource(R.string.share),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            // Create a Fork
            Card(
                onClick = {
                    onDismissRequest()
                    onFork()
                },
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Icon(
                        imageVector = HugeIcons.GitFork,
                        contentDescription = null,
                        modifier = Modifier.padding(4.dp)
                    )
                    Text(
                        text = stringResource(R.string.create_fork),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            if (onToggleFavorite != null) {
                Card(
                    onClick = {
                        onDismissRequest()
                        onToggleFavorite()
                    },
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = HugeIcons.FavouriteCircle,
                            contentDescription = null,
                            modifier = Modifier.padding(4.dp)
                        )
                        Text(
                            text = stringResource(
                                if (isFavorite) R.string.chat_message_remove_favorite
                                else R.string.chat_message_add_favorite
                            ),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }

            // Delete
            Card(
                onClick = {
                    onDismissRequest()
                    onDelete()
                },
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Icon(
                        imageVector = HugeIcons.Delete01,
                        contentDescription = null,
                        modifier = Modifier.padding(4.dp)
                    )
                    Text(
                        text = stringResource(R.string.delete),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            // Message Info
            ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                Text(message.createdAt.toJavaLocalDateTime().toLocalString())
                if (model != null) {
                    Text(model.displayName)
                }
            }
        }
    }
}
