/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.message
 
import android.content.Intent
import android.net.Uri
import android.media.MediaPlayer
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.transformers.ImageDescriber
import me.rerere.rikkahub.data.ocr.ImageTextRecognizer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.min
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.LinkCard
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyUIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.ChatUser
import me.rerere.hugeicons.stroke.Clipboard
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.PlayCircle
import me.rerere.hugeicons.stroke.PauseCircle
import me.rerere.hugeicons.stroke.Video01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.AppLogBuffer
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.data.ai.tools.resolveAwemeId
import me.rerere.rikkahub.data.ai.tools.fetchAweme
import me.rerere.rikkahub.data.ai.tools.bestMp4
import me.rerere.rikkahub.data.ai.tools.resolveXhsMp4
import me.rerere.rikkahub.data.ai.tools.resolveNeteaseMp3
import me.rerere.rikkahub.data.ai.tools.resolveQiShuiAudio
import me.rerere.rikkahub.data.ai.tools.resolveNeteaseCover
import me.rerere.rikkahub.data.ai.transformers.DyLinkTransformer
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.richtext.buildMarkdownPreviewHtml
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import me.rerere.rikkahub.ui.components.ui.Favicon
import me.rerere.rikkahub.ui.components.ui.LiquidGlassStyle
import me.rerere.rikkahub.ui.components.ui.LocalLiquidGlassHost
import me.rerere.rikkahub.ui.components.ui.liquidGlass
import me.rerere.rikkahub.ui.components.ui.LocalCardColor
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.ui.components.ui.toComposeColor
import me.rerere.rikkahub.ui.context.LocalDisplaySettings
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.data.datastore.ChatFontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.base64Encode
import me.rerere.rikkahub.utils.openUrl
import coil3.compose.AsyncImage
import me.rerere.rikkahub.utils.splitIntoBubbleSegments
import me.rerere.rikkahub.utils.urlDecode
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.ui.geometry.Rect
import kotlin.uuid.Uuid
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
 
@Composable
fun ChatMessage(
    node: MessageNode,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    model: Model? = null,
    assistant: Assistant? = null,
    lastMessage: Boolean = false,
    onFork: () -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: (MessageNode) -> Unit,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onTranslate: ((UIMessage, Locale) -> Unit)? = null,
    onClearTranslation: (UIMessage) -> Unit = {},
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    // 【消息引用 2026-09-22】长按菜单里选"引用"（上层负责把这条挂进输入框）
    onQuote: (() -> Unit)? = null,
    // 【消息引用 2026-09-22】这条消息引用的那一条（上层查好传进来，null = 没引用）
    quotedMessage: UIMessage? = null,
    // 【引用一句 2026-10-10】长按选中一段文字后点"引用这句"→ 把选中那段交给上层。
    // null = 不支持（退回系统的复制/全选）。
    onQuoteText: ((String) -> Unit)? = null,
    // 【引用一句 2026-10-10】这条消息只引用了某一句时，那句原文（画小条用；null = 引用整条）
    quotedText: String? = null,
    // 【引用跳回 2026-10-10】点被引的小条 → 跳回那条消息（参数 = 那条消息所在节点的 id）
    onQuotedClick: ((Uuid) -> Unit)? = null,
    // 【插话贴猫 · 2026-09-30】宝在猫生成过程中插的那句：不占自己的气泡，
    // 跟猫同侧、缩进、不带头像，像工具结果那样贴在前一条猫消息底下。
    // 判据（上层算好传进来）：她这条的 createdAt 早于前一条猫消息的 finishedAt。
    isInterjection: Boolean = false,
    // 【插话定位 · 2026-09-30】插话锚点：parts 的 metadata["interject"] 里记的 id → 那条消息。
    // 渲染到带锚点的 part 之后，就把宝的话画在正文中间（正文A｜她的话｜正文B），
    // 不再走"独立气泡"那条路。判据和收集都在上层（ChatList）。
    interjections: Map<String, UIMessage> = emptyMap(),
) {

    val message = node.messages[node.selectIndex]
    val settings = LocalDisplaySettings.current
    val textStyle = LocalTextStyle.current.copy(
        fontSize = LocalTextStyle.current.fontSize * settings.fontSizeRatio,
        color = settings.chatTextColor?.let { it.toComposeColor() } ?: Color.Unspecified,
        lineHeight = LocalTextStyle.current.lineHeight * settings.fontSizeRatio,
        fontFamily = when (settings.chatFontFamily) {
            ChatFontFamily.DEFAULT -> FontFamily.Default
            ChatFontFamily.SERIF -> FontFamily.Serif
            ChatFontFamily.MONOSPACE -> FontFamily.Monospace
            ChatFontFamily.CUSTOM -> {
                val fontPath = settings.customFontPath
                if (fontPath.isNotBlank() && java.io.File(fontPath).exists()) {
                    FontFamily(Font(java.io.File(fontPath)))
                } else {
                    FontFamily.Default
                }
            }
        }
    )
    var showActionsSheet by remember { mutableStateOf(false) }
    var showSelectCopySheet by remember { mutableStateOf(false) }
    // 【点按菜单 · 2026-10-10】"开合"和"位置"分成两个状态：
    // 位置只在点按那一刻更新，关菜单时**不动** ——
    // 否则 offset 归零，退场那一瞬菜单会跳回父元素底部（宝看到的"左上角闪一下"）。
    var menuVisible by remember { mutableStateOf(false) }
    var menuOffset by remember { mutableStateOf(DpOffset.Zero) }
    // 【点按菜单 · 只有正文响应】手势挂在正文块上，拿到的是"相对正文"的局部坐标；
    // 而菜单锚点在这条 Column 上 —— 用两边的窗口坐标相减换算。
    // 【卡顿修复 · 2026-11-08】这两个只是"点按菜单"换算用的窗口坐标，点的时候读一次就够。
    // 之前用 mutableStateOf 存 → 滚动时每条可见消息每帧写 state → 每帧重组（感知：滑起来重）。
    // 换成普通变量持有：写它不触发重组，onGloballyPositioned 布局后照旧刷新到最新。
    val windowPos = remember { WindowPosRef() }
    val navController = LocalNavController.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val colorScheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            // 【插话贴猫 · 2026-09-30】插话往里缩一点，视觉上贴着上面那条猫消息
            .then(if (isInterjection) Modifier.padding(start = 40.dp) else Modifier)
            // 【点按菜单 · 2026-10-10】只量自己的窗口位置，给"正文局部坐标"做换算基准。
            // 手势本身挪到正文块上了（见 MessagePartsBlock 的 onBodyTap）——
            // 点思考链/工具块不再弹菜单。
            .onGloballyPositioned { coords ->
                windowPos.col = coords.positionInWindow()
            },
        horizontalAlignment = if (!isInterjection && message.role == MessageRole.USER) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 【点按菜单 · 2026-10-10】操作菜单（原来是消息下面那排小图标）。
        // 放在 Column 第一个子项，Popup 的锚点就是这条消息的顶部，offset = 手指落点。
        ChatMessageActionMenu(
            message = message,
            expanded = menuVisible,
            offset = menuOffset,
            onDismissRequest = { menuVisible = false },
            onRegenerate = onRegenerate,
            onEdit = onEdit,
            onOpenActionSheet = { showActionsSheet = true },
            onTranslate = onTranslate,
            onClearTranslation = onClearTranslation,
        )

        // 插话不画头像行（头像跟方向一样会"跳"，跟工具结果看齐）
        if (!isInterjection && !message.parts.isEmptyUIMessage()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                ChatMessageAssistantAvatar(
                    message = message,
                    model = model,
                    assistant = assistant,
                    loading = loading,
                    modifier = Modifier.weight(1f)
                )
                ChatMessageUserAvatar(
                    message = message,
                    avatar = settings.userAvatar,
                    nickname = settings.userNickname,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        ProvideTextStyle(textStyle) {
            MessagePartsBlock(
                assistant = assistant,
                role = message.role,
                parts = message.parts,
                annotations = message.annotations,
                loading = loading,
                model = model,
                // 【链接卡 · 2026-10-30】卡改成消息字段了，这里把它送进去
                linkCard = message.linkCard,
                onToolApproval = onToolApproval,
                onToolAnswer = onToolAnswer,
                // 【点按菜单 · 2026-10-10】原来点用户消息 = 编辑，现在点按统一是"弹操作菜单"，
                // 编辑挪进菜单里（只给用户消息）。
                onUserMessageClick = null,
                // 用户消息才给编辑（AI 的回复本来就不能编辑）
                onEdit = if (message.role == MessageRole.USER) onEdit else null,
                // 【点按菜单 · 2026-10-10】只有正文响应点击：局部坐标 + Column 的窗口坐标换算
                onBodyTap = if (!loading && !isInterjection) {
                    { localOffset ->
                        val abs = localOffset + windowPos.body
                        val rel = abs - windowPos.col
                        menuOffset = with(density) { DpOffset(rel.x.toDp(), rel.y.toDp()) }
                        menuVisible = true
                    }
                } else null,
                onBodyPlaced = { windowPos.body = it },
                onQuoteText = onQuoteText,
                interjections = interjections,
            )
 
            message.translation?.let { translation ->
                CollapsibleTranslationText(
                    content = translation,
                    onClickCitation = {}
                )
            }
        }

        // 【引用挪位 · 2026-10-10 宝要求】小条从"正文上方"挪到"正文下方"：
        // 橘仔引用宝是生成中途才决定的（先调 quote_message、接着写正文），
        // 画在最上面看着像"这条一开头就在引用"，不对。挪到正文结束之后。
        if (quotedMessage != null) {
            QuotedMessageChip(
                quoted = quotedMessage,
                pickedText = quotedText,
                onClick = onQuotedClick,
            )
        }
 
        // 【2026-09-24 召回留痕】用户消息下面一行小字：本次门控 / 拆词 / 命中数。
        // 只挂在自己发的消息上（门控和拆词本来就是拿这句话去做的）。
        // 数据来自 UIMessage.recallDebug，由 ChatService 在召回后补写。
        val recallDebugText = message.recallDebug
        if (message.role == MessageRole.USER && !recallDebugText.isNullOrBlank()) {
            Text(
                text = recallDebugText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
            )
        }
 
        // 【点按菜单 · 2026-10-10】原来这里有一排小图标（复制/重新生成/朗读/翻译/更多/版本选择器），
        // 已整体撤掉，功能搬进上面的 ChatMessageActionMenu。
 
        ProvideTextStyle(textStyle) {
            ChatMessageNerdLine(message = message)
        }
    }
    if (showActionsSheet) {
        ChatMessageActionsSheet(
            message = message,
            onEdit = onEdit,
            onDelete = onDelete,
            onShare = onShare,
            onFork = onFork,
            model = model,
            onSelectAndCopy = {
                showSelectCopySheet = true
            },
            onQuote = onQuote,
            isFavorite = isFavorite,
            onToggleFavorite = onToggleFavorite,
            onWebViewPreview = {
                val textContent = message.parts
                    .filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()
                if (textContent.isNotBlank()) {
                    val htmlContent = buildMarkdownPreviewHtml(
                        context = context,
                        markdown = textContent,
                        colorScheme = colorScheme
                    )
                    navController.navigate(Screen.WebView(content = htmlContent.base64Encode()))
                }
            },
            onDismissRequest = {
                showActionsSheet = false
            }
        )
    }
 
    if (showSelectCopySheet) {
        ChatMessageCopySheet(
            message = message,
            onDismissRequest = {
                showSelectCopySheet = false
            }
        )
    }
}
 
@OptIn(FlowPreview::class)
/**
 * 【用户消息思考链 2026-09-01 宝的功能】
 * 把用户消息里的 <think>...</think>（兼容 </thinking> 结尾，宝的笔误版）识别为思考链段，
 * 其余为正文段。渲染时思考链段折叠展示（跟 AI 思考链一致），正文段照常渲染。
 */
private sealed interface ThinkSegment {
    data class Think(val content: String) : ThinkSegment
    data class Text(val content: String) : ThinkSegment
}

private fun String.splitThinkSegments(): List<ThinkSegment> {
    if (isBlank()) return listOf(ThinkSegment.Text(this))
    val regex = Regex("<think>([\\s\\S]*?)<\\/think(?:ing)?>", RegexOption.IGNORE_CASE)
    val segments = mutableListOf<ThinkSegment>()
    var lastEnd = 0
    for (match in regex.findAll(this)) {
        if (match.range.first > lastEnd) {
            segments.add(ThinkSegment.Text(substring(lastEnd, match.range.first)))
        }
        segments.add(ThinkSegment.Think(match.groupValues[1]))
        lastEnd = match.range.last + 1
    }
    if (lastEnd < length) {
        segments.add(ThinkSegment.Text(substring(lastEnd)))
    }
    if (segments.isEmpty()) segments.add(ThinkSegment.Text(this))
    return segments
}

/* ---------------------------------------------------------------------------
 * 【小任务卡 · 2026-10-06 宝定的】
 * 正文里写 [指令 名称]内容[/指令]，渲染时不在正文里露标记，而是原地换成一条
 * "橘仔给的小任务 · 名称"的条（跟插话条一样撑满、不可展开）。
 * 切分思路照抄上面的 splitThinkSegments（用户 think 那套）。
 * ------------------------------------------------------------------------- */
private sealed interface TaskSegment {
    data class Task(val name: String, val content: String) : TaskSegment
    data class Text(val content: String) : TaskSegment
}

private fun String.splitTaskSegments(): List<TaskSegment> {
    if (isBlank()) return listOf(TaskSegment.Text(this))
    val regex = Regex("\\[指令\\s*([^\\]]*)\\]([\\s\\S]*?)\\[/指令\\]", RegexOption.IGNORE_CASE)
    val segments = mutableListOf<TaskSegment>()
    var lastEnd = 0
    for (match in regex.findAll(this)) {
        if (match.range.first > lastEnd) {
            segments.add(TaskSegment.Text(substring(lastEnd, match.range.first)))
        }
        segments.add(TaskSegment.Task(match.groupValues[1].trim(), match.groupValues[2]))
        lastEnd = match.range.last + 1
    }
    if (lastEnd < length) {
        segments.add(TaskSegment.Text(substring(lastEnd)))
    }
    if (segments.isEmpty()) segments.add(TaskSegment.Text(this))
    return segments
}

@Composable
private fun TaskStrip(name: String) {
    val accent = MaterialTheme.colorScheme.secondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = HugeIcons.Clipboard,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = if (name.isBlank()) "橘仔给的小任务" else "橘仔给的小任务 · $name",
            style = LocalTextStyle.current.copy(color = accent),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 正文渲染的公用小件：认出 [指令] 段就画任务条，其余照常走 Markdown。 */
@Composable
private fun TaskAwareBody(
    text: String,
    assistant: Assistant?,
    scope: AssistantAffectScope,
    onClickCitation: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val segments = remember(text) { text.splitTaskSegments() }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        segments.fastForEach { seg ->
            when (seg) {
                is TaskSegment.Task -> TaskStrip(name = seg.name)
                is TaskSegment.Text -> {
                    if (seg.content.isNotBlank()) {
                        MarkdownBlock(
                            content = seg.content.replaceRegexes(
                                assistant = assistant,
                                scope = scope,
                                visual = true,
                            ),
                            onClickCitation = onClickCitation,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessagePartsBlock(
    assistant: Assistant?,
    role: MessageRole,
    model: Model?,
    parts: List<UIMessagePart>,
    annotations: List<UIMessageAnnotation>,
    loading: Boolean,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onUserMessageClick: (() -> Unit)? = null,
    // 【编辑 · 2026-10-19】onUserMessageClick 在 10-10「点按菜单」那次被置成 null 了
    //（编辑挪进了点按菜单里），所以图片长按菜单不能挂它 —— 得单独送一个进来。
    onEdit: (() -> Unit)? = null,
    // 【点按菜单 · 2026-10-10】只有正文响应点击：局部坐标回调 + 正文块窗口位置上报
    onBodyTap: ((Offset) -> Unit)? = null,
    onBodyPlaced: ((Offset) -> Unit)? = null,
    // 【引用一句 2026-10-10】长按选中一段文字 → 引用选中那句（上层负责挂进输入框）
    onQuoteText: ((String) -> Unit)? = null,
    // 【插话定位 · 2026-09-30】插话锚点表（id → 那条消息）。渲染到带锚点的 part 之后
    // 就把宝的话画在正文中间，由上层 ChatList 收集好传进来。
    interjections: Map<String, UIMessage> = emptyMap(),
    // 【链接卡 · 2026-10-30】字段形式的卡。老消息的卡还住在 parts 里（Image + videoLink），
    // 两种都认：字段有就画字段，没有再画 parts 里那张。
    linkCard: LinkCard? = null,
) {
    val context = LocalContext.current
    val contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)

    // 【链接卡 · 2026-10-29】这条消息里有没有卡片 —— 有的话，那段原始分享文本就不再画
    // （否则界面上是"一串链接 + 一张卡"，宝说多余）。
    // 【2026-10-30】卡改成消息字段之后，这里也要认字段。
    val hasLinkCard = remember(parts, linkCard) {
        linkCard != null || parts.any { p ->
            p is UIMessagePart.Image &&
                p.metadata?.containsKey(DyLinkTransformer.META_VIDEO_LINK) == true
        }
    }
 
    // 消息输出HapticFeedback
    val hapticFeedback = LocalHapticFeedback.current
    val displaySettings = LocalDisplaySettings.current
    val bubbleAlpha = 1f - displaySettings.chatBubbleTransparency / 100f
    val partsState by rememberUpdatedState(parts)
 
    // 【正文被吃排查 2026-08-22】loading 状态变化时打渲染日志（生成完成那一刻最关键）：
    // 记录 parts 结构与正文/思考链长度——下次正文被吃时 read_app_logs 一锤定音：
    // 「parts 里有正文但没渲染」 vs 「parts 里压根没正文」。
    LaunchedEffect(loading) {
        val textLen = parts.filterIsInstance<UIMessagePart.Text>().sumOf { it.text.length }
        val reasoningLen = parts.filterIsInstance<UIMessagePart.Reasoning>().sumOf { it.reasoning.length }
        // 【2026-08-27 升级】parts 结构打出来（每个 part 类型+长度）：
        // 下次正文被吃时 read_app_logs filter "MessagePartsRender" 一锤定音——
        // [R1000,T200,T191] = 两个 Text（渲染问题）；[R1000,T391] = 单个 Text（MarkdownBlock 吞渲染）
        val partTypes = parts.joinToString(",") { part ->
            when (part) {
                is UIMessagePart.Text -> "T${part.text.length}"
                is UIMessagePart.Reasoning -> "R${part.reasoning.length}"
                is UIMessagePart.Tool -> "Tool"
                else -> part::class.simpleName ?: "?"
            }
        }
        val msg = "render loading=$loading parts=${parts.size} [$partTypes] text=$textLen reasoning=$reasoningLen role=$role"
        AppLogBuffer.log("MessagePartsRender", msg)
        Log.d("MessagePartsRender", msg)
    }
 
    val handleClickCitation: (String) -> Unit = remember {
        handler@{ citationId ->
            partsState.forEach { part ->
                if (part is UIMessagePart.Tool && part.toolName == "search_web" && part.isExecuted) {
                    val outputText = part.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                    val items =
                        runCatching { JsonInstant.parseToJsonElement(outputText).jsonObject["items"]?.jsonArray }.getOrNull()
                            ?: return@forEach
                    items.forEach { item ->
                        val id = item.jsonObject["id"]?.jsonPrimitive?.content ?: return@forEach
                        val url = item.jsonObject["url"]?.jsonPrimitive?.content ?: return@forEach
                        if (citationId == id) {
                            context.openUrl(url)
                            return@handler
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(displaySettings) {
        snapshotFlow { partsState }
            .debounce(50.milliseconds)
            .collect { parts ->
                if (parts.isNotEmpty() && loading && displaySettings.enableMessageGenerationHapticEffect) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                }
            }
    }
 
    // Render parts in original order (group thinking/tool as chain-of-thought)
    // 【空气泡 · 2026-10-02】长度为 0 的文本 part 会占一个块、画出一个空泡
    // （宝报"猫上下半句之间有个空气泡"）。渲染前滤掉；数据层不动——它进请求也只是 0 字节，不影响缓存。
    // 【链接卡去重 · 2026-10-30】老消息里同一条链接可能叠了两三张卡（旧写法"找不到占位就追加"
    // 造成的），渲染时只留第一张，免得界面上一串一模一样的卡。新写法有了字段，不会再产生重复。
    val dedupedParts = remember(parts) {
        val seen = mutableSetOf<String>()
        parts.filter { p ->
            if (p is UIMessagePart.Image) {
                val lk = p.metadata?.get(DyLinkTransformer.META_VIDEO_LINK)
                    ?.jsonPrimitive?.contentOrNull
                if (!lk.isNullOrBlank()) return@filter seen.add(lk)
            }
            true
        }
    }
    val groupedParts = remember(dedupedParts) {
        dedupedParts.filterNot { it is UIMessagePart.Text && it.text.isEmpty() }.groupMessageParts()
    }
    // 【宽度统一 · 2026-10-04 宝定的】思考链折叠态原来会"按内容自适应"（收成一条窄标签），
    // 宝要它跟工具块一样贴合其他块——不要自适应，一律铺满。
    // （前两版想只让"插话后面那段"铺满：靠"前一个块是插话"「见过插话没有」都没咬中，
    //  因为分组是外部库做的、看不到规则。索性不判了，全部铺满。）
    groupedParts.fastForEach { block ->
        when (block) {
            is MessagePartBlock.ThinkingBlock -> {
                if (block.steps.isNotEmpty()) {
                    // 【折叠态宽度 · 2026-10-04 收尾】纯思考块折叠时收成小窄条、含工具的块铺满
                    // （原版行为）。插话已经住进思考链（InterjectStep），它跟前后的宽度天然一致，
                    // 所以之前那版"一律铺满"的临时办法撤掉。
                    val isReasoningOnlyBlock = block.steps.fastAll { it is ThinkingStep.ReasoningStep }
                    // 【流式降级 · 2026-10-02】生成中不做尺寸动画：内容每 100ms 变一次，
                    // 动画会不停重启、每帧重新测量整块。写完（loading=false）再恢复。
                    // 【思考链独立成气泡 · 2026-11-07】外面包一层气泡（跟正文同款），
                    // 内部 bare=true 不再画 Card —— 这样它才采得到背景，玻璃也能用上。
                    BubbleSurface(
                        // 【宽度跟正文一致 · 2026-11-07 20:28 宝纠正】
                        // 之前想用宽度上限把它收窄，错了：正文/工具/思考三个宽度并排站着会散。
                        // 宽度统一，只在高度上省（见 contentPadding）。
                        whiteText = false,
                        contentPadding = 4.dp,
                        imagePath = displaySettings.assistantBubbleImagePath,
                        cornerRadius = displaySettings.bubbleCornerRadius.dp,
                        color = displaySettings.assistantBubbleColor?.let { it.toComposeColor() }
                            ?: MaterialTheme.colorScheme.surfaceContainerHigh,
                        overlayEnabled = displaySettings.bubbleImageOverlayEnabled,
                        bubbleAlpha = bubbleAlpha,
                    ) {
                        ChainOfThought(
                            modifier = if (loading) Modifier else Modifier.animateContentSize(),
                            steps = block.steps,
                            collapsedAdaptiveWidth = isReasoningOnlyBlock,
                            animateChanges = !loading,
                            bare = true,
                        ) { step ->
                            when (step) {
                                is ThinkingStep.ReasoningStep -> {
                                    key(step.reasoning.createdAt) {
                                        ChatMessageReasoningStep(
                                            reasoning = step.reasoning,
                                            model = model,
                                            assistant = assistant,
                                            collapsedAdaptiveWidth = isReasoningOnlyBlock,
                                        )
                                    }
                                }
 
                                is ThinkingStep.ToolStep -> {
                                    key(step.tool.toolCallId.ifBlank { step.hashCode().toString() }) {
                                        ChatMessageToolStep(
                                            tool = step.tool,
                                            loading = loading && !step.tool.isExecuted,
                                            allParts = parts,
                                            onToolApproval = onToolApproval,
                                            onToolAnswer = onToolAnswer,
                                        )
                                    }
                                }

                                // 【插话行 · 2026-10-04 宝定的】宝插的那句，作为思考链里的一行渲染——
                                // 跟工具、思考同住一张卡片，宽度圆角天然一致。
                                // embedded = true：不再自己包一层卡片（外面已经是 ChainOfThought 的卡）。
                                is ThinkingStep.InterjectStep -> {
                                    key("interject-${step.part.hashCode()}") {
                                        ChatMessageInterjectedMessage(
                                            part = step.part,
                                            embedded = true,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
 
            is MessagePartBlock.ContentBlock -> key(block.index) {
                /*
                 * 【实况·六张都进消息 · 2026-11-04 宝提的】
                 *
                 * 之前小红书笔记只把封面放进 parts，其余五个只在卡的 metadata 里 ——
                 * 结果界面上看得见六张，猫这边只收到一张，笔记里写了什么猫得自己去扒页面。
                 *
                 * 现在 transformer 把六张全放进 parts，第 2 张往后打 hiddenFromUi 标记：
                 *   模型 → 六张全看得见（这才是重点）
                 *   界面 → 在这儿直接跳过（卡里已经横排了一行缩略图，再画就是重复）
                 *
                 * ⚠️ 别把这个判断挪到 when 里面去：when 是穷举表达式，加条件分支会变难读，
                 * 而且 Image 那个分支有七十多行，包一层 if 要整体缩进。
                 */
                // ⚠️ 别写成 `if (...) { return@key }`：key 是 inline 的，lambda 里 return 会变成
                // 非局部返回，编译器造出 $$$$$NON_LOCAL_RETURN$$$$$ 那个合成类，dex 装不下
                // （2026-11-04 实测：dexBuilderDebug FAILED，报 "Method name '<anonymous>'
                //  in class '$$$$$NON_LOCAL_RETURN$$$$$' cannot be represented in dex format"）。
                // 用 else 挂上去，不返回。
                if (block.part is UIMessagePart.Image &&
                    block.part.metadata?.get("hiddenFromUi")?.jsonPrimitive?.contentOrNull == "true"
                ) {
                    Unit // 什么都不画
                } else when (val part = block.part) {
                    is UIMessagePart.Text -> {
                        // 从显示文本中移除[zip:...]标记
                        // 【分享文本分家 · 2026-10-30 宝要的】之前是"这段 part 里只要出现链接
                        // 域名就整段不画"——那时消息里只有分享文本、没有宝自己的话，所以够用。
                        // 但现在宝会"一句话 + 一串分享文本"一起发，整段跳过会把她的字也吃掉。
                        // 改成逐行剥：<xhs_note>整段剥掉、含链接域名的行剥掉，剩下的照画。
                        // （不依赖 splitUserBubbleByLine：分行开关是另一回事，这里只解决"该藏哪几行"。）
                        val displayText = remember(part.text, hasLinkCard) {
                            val base = part.text.replace(
                                Regex("\\[zip:[^\\]]+\\]", RegexOption.IGNORE_CASE), ""
                            )
                            if (hasLinkCard) {
                                base.replace(Regex("(?s)<xhs_note>.*?</xhs_note>"), "")
                                    .lines()
                                    .filterNot { line ->
                                        line.contains("douyin.com") ||
                                            line.contains("xhslink") ||
                                            line.contains("xiaohongshu") ||
                                            // 【音乐卡 · 2026-10-30】少了这两个，摘要会显示成「原文链接 + 卡片」两样
                                            line.contains("163cn.tv") ||
                                            line.contains("music.163.com")
                                    }
                                    .joinToString("\n")
                                    .trim()
                            } else base
                        }
                        
                        // 【插话不重复画 · 2026-10-04 宝实测】带插话标（metadata{"interject": true}）的
                        // Text part 不该在这里当正文画——它的位置由下面 MessagePartsBlock 末尾那段
                        // 折叠条渲染（isInterjectPart 那个检查）。不拦的话同一句会画两遍：
                        // 一次当正文（左对齐白底，看着像"独立消息"），一次当折叠条。
                        // 【链接卡 · 2026-10-29】小红书展开的那段（<xhs_note>…</xhs_note>）是给猫读的，
                        // 界面上已经有卡片了，正文块不再重复画一遍（否则一大段"标题：… 正文：…"挂在卡下面）。
                        if (displayText.isBlank()) {
                            // 跳过：这一行剩下的全是分享文本/展开块，内容已经在卡里了
                        } else if (!isInterjectPart(part)) {
                        // 【思考链卡片不弹菜单 · 2026-11-05】这一块的窗口原点 + 思考链卡片的窗口矩形。
                        // 手势在 Initial 阶段等抬起，点卡片展开键时也会收到 down/up，
                        // 靠"落点压没压在卡片上"来筛，而不是靠 pass。
                        var bodyWindowOrigin by remember { mutableStateOf(Offset.Zero) }
                        var thinkRectInWindow by remember { mutableStateOf<Rect?>(null) }
                        // 【防正文被吃 2026-08-22】key(loading)：生成完成（loading true→false）时强制重建渲染子树，
                        // 确保最后一批 parts（含正文）一定会被渲染——不依赖流式增量触发的最后一次重组
                        // （思考链渲染间隙里完成的正文不再静默消失，memory 91 的渲染竞态修复）。
                        key(loading) {
                        // 【点按菜单 · 2026-10-10】手势只挂在正文这一块上 —— 点思考链、点工具块都不再弹菜单。
                        // Initial pass 只观察不消费，"长按选文字"照常。
                        SelectableWithQuote(
                            onTextPicked = onQuoteText,
                            modifier = Modifier
                                .onGloballyPositioned { coords ->
                                    val origin = coords.positionInWindow()
                                    bodyWindowOrigin = origin
                                    onBodyPlaced?.invoke(origin)
                                }
                                .then(
                                    if (onBodyTap != null) {
                                        Modifier.pointerInput("bodyTap") {
                                            awaitPointerEventScope {
                                                while (true) {
                                                    val down = awaitFirstDown(
                                                        requireUnconsumed = false,
                                                        pass = PointerEventPass.Initial
                                                    )
                                                    // 【长按不弹菜单 · 2026-10-11 宝报：长按选字抬手时跟"引用"打架】
                                                    // 长按选文字抬手那一下也走"按下→抬起"，不掐时长就会误弹菜单。
                                                    // 卡 500ms：单击在 200ms 内、长按选择在 500ms 以上，分得干净。
                                                    val downAt = System.currentTimeMillis()
                                                    val up = waitForUpOrCancellation(
                                                        pass = PointerEventPass.Initial
                                                    )
                                                    if (up != null &&
                                                        System.currentTimeMillis() - downAt < 500L
                                                    ) {
                                                        // 【思考链卡片不弹菜单 · 2026-11-05】按在思考链卡片上时不弹正文菜单
                                                        // （点卡片的展开键会顺带弹出复制/编辑那一条）。
                                                        // 靠 pass 判断走过两条死路：Initial 阶段子元素还没消费、
                                                        // Main/Final 阶段文字选择的常驻消费者一律消费掉。
                                                        // 改成看落点：卡片自己报窗口坐标，这里把按下的局部坐标换算成窗口坐标去比。
                                                        val tapInWindow = bodyWindowOrigin + down.position
                                                        if (thinkRectInWindow?.contains(tapInWindow) != true) {
                                                            onBodyTap(down.position)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } else Modifier
                                )
                        ) {
                            Column {
                                // 生成中（loading=true 且是 AI 消息）：用纯文本渲染，跳过 Markdown 解析/代码高亮/正则替换，
                                // 避免流式更新时（100ms 一次）对超长消息全量重解析导致主线程卡顿（整页滑动掉帧）。
                                // 生成完成 loading=false 后自动切回 MarkdownBlock 富文本，最终显示效果不变。
                                // 【全妆 · 2026-10-02 宝要求】撤回 2026-08-18 的"生成中纯文本"降级，
                                // 生成期间也走 MarkdownBlock（宝：想看全妆小猫）。
                                // 当初加它是为了治"流式时对超长消息全量重解析、主线程卡顿"；
                                // 但 10-02 查明真正的 13.8 秒凶手是 collapseInterjections 的刷屏日志
                                //（每秒 80 行，已修 commit 2c59d879），这刀可以撤了。
                                // ★ 要恢复"素颜"：把本行换回
                                //   if (role == MessageRole.ASSISTANT && loading) {
                                //       Text(text = displayText)
                                //   } else if (role == MessageRole.USER) {
                                if (role == MessageRole.USER) {
                                    // 【用户消息思考链 2026-09-01】<think>...</think> 段渲染成思考链卡片（跟 AI 一致灰色折叠），正文照常。
                                    val thinkSegments = remember(displayText) { displayText.splitThinkSegments() }
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                        horizontalAlignment = Alignment.End,
                                    ) {
                                        thinkSegments.fastForEach { seg ->
                                            when (seg) {
                                                is ThinkSegment.Think -> {
                                                    // 【思考链卡片不弹菜单 · 2026-11-05】把卡片自己的窗口矩形报出去，
                                                    // 正文那块的手势靠它判断"这一下是不是按在卡片上"。
                                                    Box(
                                                        modifier = Modifier.onGloballyPositioned { c ->
                                                            thinkRectInWindow = c.boundsInWindow()
                                                        }
                                                    ) {
                                                        BubbleSurface(
                                                            imagePath = displaySettings.userBubbleImagePath,
                                                            cornerRadius = displaySettings.bubbleCornerRadius.dp,
                                                            color = displaySettings.userBubbleColor?.let { it.toComposeColor() }
                                                                ?: MaterialTheme.colorScheme.secondaryContainer,
                                                            overlayEnabled = displaySettings.bubbleImageOverlayEnabled,
                                                            bubbleAlpha = bubbleAlpha,
                                                            contentPadding = 4.dp,
                                                            whiteText = false,
                                                        ) {
                                                        ChainOfThought(
                                                            steps = listOf(
                                                                ThinkingStep.ReasoningStep(
                                                                    UIMessagePart.Reasoning(reasoning = seg.content)
                                                                )
                                                            ),
                                                            collapsedAdaptiveWidth = true,
                                                            bare = true,
                                                        ) { step ->
                                                            ChatMessageReasoningStep(
                                                                reasoning = step.reasoning,
                                                                model = null,
                                                                assistant = assistant,
                                                                collapsedAdaptiveWidth = true,
                                                                isUserReasoning = true,
                                                            )
                                                        }  // ChainOfThought
                                                        }  // BubbleSurface
                                                    }
                                                }
                                                is ThinkSegment.Text -> {
                                                    if (assistant?.splitUserBubbleByLine == true) {
                                                        // 分气泡: 按用户输入的换行 (\n) 拆成多个独立气泡,
                                                        // 拆分逻辑见 splitIntoBubbleSegments (会保护代码块/表格内部的换行)
                                                        val bubbleSegments = remember(seg.content) {
                                                            seg.content.splitIntoBubbleSegments()
                                                        }
                                                        Column(
                                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                                            horizontalAlignment = Alignment.End,
                                                        ) {
                                                            bubbleSegments.fastForEachIndexed { segIndex, segment ->
                                                                key(segIndex) {
                                                                    BubbleSurface(
                                                                        imagePath = displaySettings.userBubbleImagePath,
                                                                        cornerRadius = displaySettings.bubbleCornerRadius.dp,
                                                                        color = displaySettings.userBubbleColor?.let { it.toComposeColor() } ?: MaterialTheme.colorScheme.secondaryContainer,
                                                                        overlayEnabled = displaySettings.bubbleImageOverlayEnabled,
                                                                        bubbleAlpha = bubbleAlpha,
                                                                        onClick = { onUserMessageClick?.invoke() },
                                                                    ) {
                                                                        MarkdownBlock(
                                                                            content = segment.replaceRegexes(
                                                                                assistant = assistant,
                                                                                scope = AssistantAffectScope.USER,
                                                                                visual = true,
                                                                            ),
                                                                            onClickCitation = handleClickCitation
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    } else {
                                                        BubbleSurface(
                                                            imagePath = displaySettings.userBubbleImagePath,
                                                            cornerRadius = displaySettings.bubbleCornerRadius.dp,
                                                            color = displaySettings.userBubbleColor?.let { it.toComposeColor() } ?: MaterialTheme.colorScheme.secondaryContainer,
                                                            overlayEnabled = displaySettings.bubbleImageOverlayEnabled,
                                                            bubbleAlpha = bubbleAlpha,
                                                            onClick = { onUserMessageClick?.invoke() },
                                                        ) {
                                                            MarkdownBlock(
                                                                content = seg.content.replaceRegexes(
                                                                    assistant = assistant,
                                                                    scope = AssistantAffectScope.USER,
                                                                    visual = true,
                                                                ),
                                                                onClickCitation = handleClickCitation
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else if (assistant?.splitBubbleByLine == true) {
                                    // 分气泡: 按模型自己写的换行 (\n) 拆成多个独立气泡,
                                    // 拆分逻辑见 splitIntoBubbleSegments (会保护代码块/表格内部的换行)
                                    val bubbleSegments = remember(displayText) {
                                        displayText.splitIntoBubbleSegments()
                                    }
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        bubbleSegments.fastForEachIndexed { segIndex, segment ->
                                            key(segIndex) {
                                                if (displaySettings.showAssistantBubble) {
                                                    BubbleSurface(
                                                        imagePath = displaySettings.assistantBubbleImagePath,
                                                        cornerRadius = displaySettings.bubbleCornerRadius.dp,
                                                        color = displaySettings.assistantBubbleColor?.let { it.toComposeColor() } ?: MaterialTheme.colorScheme.surfaceContainerHigh,
                                                        overlayEnabled = displaySettings.bubbleImageOverlayEnabled,
                                                        bubbleAlpha = bubbleAlpha,
                                                    ) {
                                                        TaskAwareBody(
                                                            text = segment,
                                                            assistant = assistant,
                                                            scope = AssistantAffectScope.ASSISTANT,
                                                            onClickCitation = handleClickCitation,
                                                        )
                                                    }
                                                } else {
                                                    TaskAwareBody(
                                                        text = segment,
                                                        assistant = assistant,
                                                        scope = AssistantAffectScope.ASSISTANT,
                                                        onClickCitation = handleClickCitation,
                                                        modifier = Modifier.animateContentSize(),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    if (displaySettings.showAssistantBubble) {
                                        BubbleSurface(
                                            imagePath = displaySettings.assistantBubbleImagePath,
                                            cornerRadius = displaySettings.bubbleCornerRadius.dp,
                                            color = displaySettings.assistantBubbleColor?.let { it.toComposeColor() } ?: MaterialTheme.colorScheme.surfaceContainerHigh,
                                            overlayEnabled = displaySettings.bubbleImageOverlayEnabled,
                                            bubbleAlpha = bubbleAlpha,
                                        ) {
                                            TaskAwareBody(
                                                text = displayText,
                                                assistant = assistant,
                                                scope = AssistantAffectScope.ASSISTANT,
                                                onClickCitation = handleClickCitation,
                                            )
                                        }
                                    } else {
                                        TaskAwareBody(
                                            text = displayText,
                                            assistant = assistant,
                                            scope = AssistantAffectScope.ASSISTANT,
                                            onClickCitation = handleClickCitation,
                                        )
                                    }
                                }
                                
                            }
                        }
                        } // key(loading)
                        }
                    }
 
                    is UIMessagePart.Video -> {
                        VideoBubble(url = part.url, onEdit = onEdit)
                    }
 
                    is UIMessagePart.Audio -> {
                        AudioPlayerBubble(url = part.url)
                    }
 
                    is UIMessagePart.VoiceMessage -> {
                        VoiceMessageBubble(
                            voiceMessage = part,
                            isUser = role == MessageRole.USER,
                            // 【语音条长按 · 2026-10-28】长按 = 跟正文块同样的"弹点按菜单"，
                            // 位置给 Zero（弹在这条消息左上角附近），够用。
                            onLongPress = onBodyTap?.let { tap -> { tap(Offset.Zero) } },
                        )
                    }
 
                    is UIMessagePart.Image -> {
                        // 【抖音视频链接 → 封面卡 · 2026-10-28】带 videoLink 标记的图不是普通图，
                        // 是「视频封面」：画成卡片，点开时现解析直链再原地播（不下载视频、不管直链过期）。
                        val vLink = part.metadata?.get(DyLinkTransformer.META_VIDEO_LINK)
                            ?.jsonPrimitive?.contentOrNull
                        val vSource = part.metadata?.get(DyLinkTransformer.META_VIDEO_SOURCE)
                            ?.jsonPrimitive?.contentOrNull
                        if (!vLink.isNullOrBlank()) {
                            // 【2026-10-29】抖音和红色书共用一张卡：source 只决定"点开干什么"
                            val vTitle = part.metadata?.get(DyLinkTransformer.META_LINK_TITLE)
                                ?.jsonPrimitive?.contentOrNull
                            VideoLinkCard(
                                link = vLink,
                                coverUrl = part.url,
                                title = vTitle,
                                source = vSource ?: "douyin",
                                body = part.metadata?.get(DyLinkTransformer.META_LINK_BODY)
                                    ?.jsonPrimitive?.contentOrNull,
                                // 【多图缩略图 · 2026-10-29】XhsLinkTransformer 存的那串路径（## 分隔）
                                images = part.metadata?.get("linkImages")
                                    ?.jsonPrimitive?.contentOrNull
                                    ?.split("##")
                                    ?.filter { it.isNotBlank() }
                                    .orEmpty(),
                                isNote = part.metadata?.get(DyLinkTransformer.META_IS_NOTE)
                                    ?.jsonPrimitive?.contentOrNull == "true",
                                /*
                                 * 【实况 · 2026-11-04】XhsLinkTransformer 存的实况地址（## 分隔）。
                                 * ⚠️ 这里绝对不能 filter 掉空串 —— 空串是"这张不是实况"的占位，
                                 * 滤掉之后位序就塌了，第 3 张的地址会跑到第 1 张头上。
                                 */
                                liveVideos = part.metadata?.get("linkLiveVideos")
                                    ?.jsonPrimitive?.contentOrNull
                                    ?.split("##")
                                    .orEmpty(),
                            )
                        } else {
                            val isImageLoading =
                                part.url.isBlank() || part.url.matches(Regex("^data:image/[^;]*;base64,\\s*$"))
                            if (isImageLoading) {
                                Box(
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(MaterialTheme.shapes.medium)
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .shimmer(isLoading = true)
                                )
                            } else {
                                // 【图片展示 · 2026-10-10 宝："直接像屏幕截下来一块，丑丑的"】
                                // 原先高度死定 72dp（那是缩略图尺寸）：竖着的小红书截图按 72dp 高缩下去，
                                // 宽度只剩三四十 dp —— 一条细缝。
                                // 现在按原图比例分两条路：
                                //   普通图 → 按比例完整显示（最大 240x320dp，小图不拉伸）
                                //   竖长图 → 卡片：宽度撑满 240dp、只露上半截，点开看全
                                val imgRatioState = remember(part.url) { mutableStateOf(0f) }
                                val imgRatio = imgRatioState.value
                                val isTallImage = imgRatio > 0f && imgRatio < 240f / 320f
                                // 【图片文字引用 · 2026-10-12】长按图片 → 认字 → 挑一句引用
                                val imgTextScope = rememberCoroutineScope()
                                var imgTextMenuOpen by remember(part.url) { mutableStateOf(false) }
                                var imgTextBusy by remember(part.url) { mutableStateOf(false) }
                                var imgTextLines by remember(part.url) { mutableStateOf<List<String>?>(null) }
                                // 【图片转述三段 · 2026-10-13】第三段「图片理解」：存着，但只给宝一个人看
                                var imgAiNoteOpen by remember(part.url) { mutableStateOf(false) }
                                val imgAiNote = part.metadata?.get("imageNote")?.jsonPrimitive?.contentOrNull
                                    ?.let { ImageDescriber.splitNote(it).second }
                                ZoomableAsyncImage(
                                    model = part.url,
                                    contentDescription = null,
                                    modifier = if (isTallImage) {
                                        Modifier
                                            .clip(MaterialTheme.shapes.medium)
                                            .width(240.dp)
                                            .height(320.dp)
                                    } else {
                                        Modifier
                                            .clip(MaterialTheme.shapes.medium)
                                            .widthIn(max = 240.dp)
                                            .heightIn(max = 320.dp)
                                    },
                                    contentScale = if (isTallImage) ContentScale.FillWidth else ContentScale.Fit,
                                    alignment = if (isTallImage) Alignment.TopCenter else Alignment.Center,
                                    onImageSize = { w, h ->
                                        if (h > 0) imgRatioState.value = w.toFloat() / h.toFloat()
                                    },
                                    onLongClick = { imgTextMenuOpen = true },
                                )
                                if (imgTextMenuOpen) {
                                    ImageTextMenuDialog(
                                        onDismiss = { imgTextMenuOpen = false },
                                        // 【单图消息编辑不了 · 2026-10-19】长按图片的菜单里补一个编辑出口。
                                        // 图片吃掉了点击，只有长按走得到这儿。
                                        // 不用自己判角色：外层已经在 232 行做过「USER 才给 onEdit」，
                                        // AI 消息传进来的本来就是 null，这一项自然不出现。
                                        onEdit = onEdit,
                                        onViewAiNote = if (imgAiNote.isNullOrBlank()) null else {
                                            { imgAiNoteOpen = true }
                                        },
                                        onRecognize = {
                                            imgTextMenuOpen = false
                                            if (!imgTextBusy) {
                                                imgTextBusy = true
                                                imgTextScope.launch {
                                                    // 【优先用格子 · 2026-10-12】转述已经跟着消息存下来了，直接用它
                                                    // （口径和分析时那份一致）；没有才退回本地认字（截图那类还得靠它）。
                                                    val cachedNote = part.metadata
                                                        ?.get("imageNote")
                                                        ?.jsonPrimitive
                                                        ?.contentOrNull
                                                        ?.trim()
                                                    // 【三段 · 2026-10-13】划选给的是前两段（原文照抄 + 画面简述）；
                                                    // 第三段「图片理解」走菜单里的「查看 AI 理解」，不跟素材混在一起。
                                                    val cachedBody = if (!cachedNote.isNullOrBlank()) {
                                                        ImageDescriber.splitNote(cachedNote).first
                                                    } else null
                                                    val lines = if (!cachedBody.isNullOrBlank()) {
                                                        listOf(cachedBody)
                                                    } else {
                                                        runCatching {
                                                            ImageTextRecognizer.recognize(context, part.url)
                                                        }.getOrDefault(emptyList())
                                                    }
                                                    imgTextBusy = false
                                                    if (lines.isEmpty()) {
                                                        Toast.makeText(
                                                            context,
                                                            "这张图里没认出文字",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    } else {
                                                        imgTextLines = lines
                                                    }
                                                }
                                            }
                                        },
                                    )
                                }
                                imgTextLines?.let { lines ->
                                    ImageTextPickDialog(
                                        lines = lines,
                                        onPick = { picked ->
                                            imgTextLines = null
                                            onQuoteText?.invoke(picked)
                                        },
                                        onDismiss = { imgTextLines = null },
                                    )
                                }
                                // 【图片转述三段 · 2026-10-13】「查看 AI 理解」—— 只给宝看的那一段
                                if (imgAiNoteOpen && !imgAiNote.isNullOrBlank()) {
                                    ImageAiNoteDialog(
                                        text = imgAiNote,
                                        onDismiss = { imgAiNoteOpen = false },
                                    )
                                }
                            }
                        }
                    }
 
                    is UIMessagePart.Document -> {
                        Surface(
                            tonalElevation = 2.dp,
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW)
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                intent.data = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    part.url.toUri().toFile()
                                )
                                val chooserIndent = Intent.createChooser(intent, null)
                                context.startActivity(chooserIndent)
                            },
                            modifier = Modifier,
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    when (part.mime) {
                                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> {
                                            Icon(
                                                painter = painterResource(R.drawable.docx),
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
 
                                        "application/pdf" -> {
                                            Icon(
                                                painter = painterResource(R.drawable.pdf),
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
 
                                        else -> {
                                            Icon(
                                                imageVector = HugeIcons.File02,
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
 
                                    Text(
                                        text = part.fileName,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 200.dp)
                                    )
                                }
                            }
                        }
                    }
 
                    else -> {
                        // Skip unknown part types (e.g., deprecated ToolCall, ToolResult, Search)
                    }
                }
            }
        }
        // 【插话改走思考链 · 2026-10-04 宝定的】插话不再在这里单独渲染——它现在作为
        // ThinkingStep.InterjectStep 收进思考链那张卡片（见上面的 ThinkingBlock 分支），
        // 跟工具、思考同住一块，宽度天然一致。
        // 留这段注释是为了记住：这里曾经有一条"认锚点单独画折叠条"的路，
        // 两套并存会各画一遍（同一句冒出三条的教训）。排队态仍由列表那边负责。
    }

    // 【链接卡 · 2026-10-30】字段形式的卡画在最后。
    // 老消息的卡住在 parts 里，由上面的 Image 分支自己画（重复的已经被 dedupedParts 滤掉）。
    linkCard?.let { card ->
        VideoLinkCard(
            link = card.link,
            coverUrl = card.cover ?: "",
            title = card.title,
            source = card.source,
            body = card.body,
            images = card.images,
            isNote = card.isNote,
            liveVideos = card.liveVideos,
        )
    }

    // Annotations (always rendered at the end)
    if (annotations.isNotEmpty()) {
        Column(
            modifier = Modifier.animateContentSize(),
        ) {
            var expand by remember { mutableStateOf(false) }
            if (expand) {
                ProvideTextStyle(
                    MaterialTheme.typography.labelMedium.copy(
                        color = MaterialTheme.extendColors.gray8.copy(alpha = 0.65f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .drawWithContent {
                                drawContent()
                                drawRoundRect(
                                    color = contentColor.copy(alpha = 0.2f),
                                    size = Size(width = 10f, height = size.height),
                                )
                            }
                            .padding(start = 16.dp)
                            .padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        annotations.fastForEachIndexed { index, annotation ->
                            when (annotation) {
                                is UIMessageAnnotation.UrlCitation -> {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Favicon(annotation.url, modifier = Modifier.size(20.dp))
                                        Text(
                                            text = buildAnnotatedString {
                                                append("${index + 1}. ")
                                                withLink(LinkAnnotation.Url(annotation.url)) {
                                                    append(annotation.title.urlDecode())
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            TextButton(
                onClick = {
                    expand = !expand
                }
            ) {
                Text(stringResource(R.string.citations_count, annotations.size))
            }
        }
    }
 
    // 工作区文件 chip: assistant 消息下方展示被 workspace_write_file/
    // workspace_edit_file 写入/编辑的文件, 点击可导出/分享。
    // 仅在归属工作区的 assistant 消息中渲染, 不影响用户消息和其它布局。
    if (role == MessageRole.ASSISTANT) {
        EditedFilesList(parts = parts, assistant = assistant)
    }
}
 
/**
 * 九宫格绘制：四个角按原像素贴，四条边只往一个方向拉，中间两个方向都拉。
 *
 * [insetFrac] = 四个角各占图的边长比例（0.25 = 四分之一）。
 * 图最好是正方形 —— 这样气泡再宽再窄，四角都不会被拉变形。
 * 2026-11-14 加：为气泡背景图服务，替换掉原来"整张 Crop"的画法。
 */
private fun DrawScope.drawNinePatch(bmp: ImageBitmap, insetFrac: Float = 0.25f) {
    val sw = bmp.width.toFloat()
    val sh = bmp.height.toFloat()
    val W = size.width
    val H = size.height
    if (sw <= 0f || sh <= 0f || W <= 0f || H <= 0f) return

    val ix = sw * insetFrac
    val iy = sh * insetFrac
    // 目标区太小的时候，角最多各占一半，别互相压过去
    val dx = min(ix, W / 2f)
    val dy = min(iy, H / 2f)

    fun patch(
        sx: Float, sy: Float, sw2: Float, sh2: Float,
        dx0: Float, dy0: Float, dw: Float, dh: Float,
    ) {
        if (sw2 <= 0f || sh2 <= 0f || dw <= 0f || dh <= 0f) return
        drawImage(
            image = bmp,
            srcOffset = IntOffset(sx.toInt(), sy.toInt()),
            srcSize = IntSize(sw2.toInt(), sh2.toInt()),
            dstOffset = IntOffset(dx0.toInt(), dy0.toInt()),
            dstSize = IntSize(dw.toInt(), dh.toInt()),
        )
    }

    // 四角：原样贴，不拉伸
    patch(0f, 0f, ix, iy, 0f, 0f, dx, dy)
    patch(sw - ix, 0f, ix, iy, W - dx, 0f, dx, dy)
    patch(0f, sh - iy, ix, iy, 0f, H - dy, dx, dy)
    patch(sw - ix, sh - iy, ix, iy, W - dx, H - dy, dx, dy)
    // 四边：只往一个方向拉
    patch(ix, 0f, sw - 2 * ix, iy, dx, 0f, W - 2 * dx, dy)
    patch(ix, sh - iy, sw - 2 * ix, iy, dx, H - dy, W - 2 * dx, dy)
    patch(0f, iy, ix, sh - 2 * iy, 0f, dy, dx, H - 2 * dy)
    patch(sw - ix, iy, ix, sh - 2 * iy, W - dx, dy, dx, H - 2 * dy)
    // 中间：两个方向都拉
    patch(ix, iy, sw - 2 * ix, sh - 2 * iy, dx, dy, W - 2 * dx, H - 2 * dy)
}

@Composable
private fun BubbleSurface(
    modifier: Modifier = Modifier,
    // 【白字只在正文气泡上用 · 2026-11-07 宝："代码和图标改成白的，看得眼花"】
    // 思考链里全是工具名/行内代码，白字落在浅底上就花了 → 那处传 false。
    whiteText: Boolean = true,
    // 【内边距可调 · 2026-11-07 20:28】思考链只有一行字，8dp 上下会把它撑肥。
    contentPadding: Dp = 8.dp,
    imagePath: String,
    cornerRadius: Dp,
    color: Color,
    overlayEnabled: Boolean,
    bubbleAlpha: Float,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val hasImage = imagePath.isNotBlank() && java.io.File(imagePath).exists()
    if (hasImage) {
        Box(
            modifier = modifier
                .animateContentSize()
                .clip(RoundedCornerShape(cornerRadius))
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        ) {
            // 【九宫格 · 2026-11-14 宝要"纯爱漫画框"那种四角带花纹的气泡】
            // 原来是 ContentScale.Crop：整张图缩放填满再居中裁。气泡是宽扁的，
            // 花边框的四个角会被裁掉两个。改成九宫格：四角原样贴、四边单向拉伸、
            // 中间双向拉伸 —— 她用正方形画的花边框，拉成任何宽高比例都不变形。
            val bubbleBitmap = remember(imagePath) {
                runCatching {
                    android.graphics.BitmapFactory.decodeFile(imagePath)?.asImageBitmap()
                }.getOrNull()
            }
            if (bubbleBitmap != null) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    drawNinePatch(bubbleBitmap)
                }
            }
            if (overlayEnabled) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(color.copy(alpha = bubbleAlpha))
                )
            }
            Column(modifier = Modifier.padding(contentPadding)) { content() }
        }
    } else {
        // 【液态玻璃 · 2026-11-07】气泡做成玻璃：把自己在根里的矩形交给 shader 去采背景图。
        // 拿不到 host（比如别的界面）就原样走 Surface，不影响别处。
        val glassHost = LocalLiquidGlassHost.current
        var glassRect by remember { mutableStateOf(Rect.Zero) }
        val density = LocalDensity.current
        val ds = LocalDisplaySettings.current
        // 【2026-11-07 日志实证】LazyColumn 复用/重建 item 时 glassRect 会被重置成 Rect.Zero，
        // 要等下一帧 onGloballyPositioned 才填回来。那一刻 uSize 也是 0，sdf 缩成一个点
        // → 高光缩在左上角、不贴气泡。所以 rect 无效时干脆不画玻璃、用回原底色。
        // ⚠️ 测量必须一直挂着：glassReady 靠 glassRect 判断，而 glassRect 靠 onGloballyPositioned 填。
        // 把测量一起关进 if 里会死锁（glassRect 永远是 0 → 永远不 ready → 永远不测量），2026-11-07 踩过。
        val glassReady = glassHost != null && glassRect.width > 0f
        val glassModifier = if (glassHost != null) {
            Modifier
                .onGloballyPositioned {
                    // 【真凶 · 2026-11-07 宝说"气泡往上滑，背景滑到一定程度就不动了"】
                    // 原来用 boundsInRoot()，而这个方法**会被父级裁剪**（文档原话：clipped by the parents）。
                    // 气泡滑出 LazyColumn 顶部后 top 就被钉在列表边界上、不再变 → 采样点钉死 → 背景不动。
                    // 换成 positionInRoot() + size：位置不受裁剪影响，滚多少报多少。
                    val r = Rect(it.positionInRoot(), it.size.toSize())
                    if (r.left != glassRect.left || r.top != glassRect.top) {
                        me.rerere.rikkahub.data.ai.AppLogBuffer.log(
                            "LiquidGlass",
                            "GPos ${glassRect.left.toInt()},${glassRect.top.toInt()} -> ${r.left.toInt()},${r.top.toInt()}"
                        )
                    }
                    glassRect = r
                }
                .then(
                    if (glassReady) {
                        Modifier.liquidGlass(
                            glassHost, glassRect,
                            LiquidGlassStyle(
                                radius = with(density) { cornerRadius.toPx() },
                                // 【气泡也接上滑杆 · 2026-11-09 21:51 宝："为什么改了他不给绑到设置滑条上？一直在挑衅我"】
                                // 之前只传了 radius/blur/refract，其余全走默认死值；
                                // 今天改了 shader 的 edge（整圈亮带），气泡拿到的还是默认 1.0，边就特别重。
                                blur = ds.liquidGlassBlur,
                                refract = ds.liquidGlassRefract,
                                thickness = ds.liquidGlassThickness,
                                zoom = ds.liquidGlassZoom,
                                rim = ds.liquidGlassRim,
                                rimDist = ds.liquidGlassRimDist,
                                edge = ds.liquidGlassEdge,
                                spec = ds.liquidGlassSpec,
                            ),
                            // 屏幕像素尺寸：给 shader 算"光心在哪"。取不到就传 0，光心退回元素中心。
                            screenW = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() },
                            screenH = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() },
                        )
                    } else Modifier
                )
        } else Modifier

        Surface(
            modifier = modifier.animateContentSize().then(glassModifier),
            shape = RoundedCornerShape(cornerRadius),
            // 玻璃模式下底色让出去，否则不透
            color = if (glassReady) Color.Transparent else color.copy(alpha = bubbleAlpha),
            // 【玻璃上的字 · 2026-11-07 宝说"可以把字调成白的"】
            // Surface 默认用 contentColorFor(背景色)：玻璃模式背景是 Transparent，
            // 它按浅色底算，于是给了深色字 —— 在花枝背景上糊成一团。
            // 玻璃上一律用白字（真玻璃控件都是这么做的）。
            contentColor = if (glassReady && whiteText) Color.White else LocalContentColor.current,
            onClick = onClick ?: {},
        ) {
            Column(modifier = Modifier.padding(contentPadding)) { content() }
        }
    }
}
 
/** 【视频别竖成一块板子 · 2026-10-27】读视频自己的宽高比 + 抽第一帧当封面。 */
private data class VideoMeta(val ratio: Float, val cover: android.graphics.Bitmap?, val hasVideo: Boolean = true)

private fun readVideoMeta(context: android.content.Context, url: String): VideoMeta {
    return runCatching {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, url.toUri())
            val w = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: 0
            val ratio = if (w > 0 && h > 0) w.toFloat() / h else 16f / 9f
            val frame = retriever.getFrameAtTime(
                1_000_000L,
                android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            )
            VideoMeta(ratio, frame, w > 0 && h > 0)
        } finally {
            retriever.release()
        }
    }.getOrDefault(VideoMeta(16f / 9f, null))
}

/**
 * 【视频就地播放 · 2026-10-27 宝：能让那个我发给你的视频能直接在用户消息里点击直看吗】
 * 收起时是一个方块，点一下原地展开成播放器。
 * 原来的做法是 ACTION_VIEW + chooser —— 跳出去、还得让你选一个应用，等于离开橘瓣。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun VideoBubble(
    url: String,
    onEdit: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var expanded = remember { mutableStateOf(false) }
    // 【单视频消息编辑不了 · 2026-10-27 宝发现的】跟图片同一套逻辑：
    // 点击被「播放」吃掉，外层气泡的点击永远收不到 → 长按当出口。
    var videoMenuOpen = remember { mutableStateOf(false) }
    // 【视频别竖成一块板子 · 2026-10-27 宝报】原来写死 16:9，竖屏视频被塞成中间窄条。
    // 改成读视频自己的宽高比，同时抽第一帧当封面（一次 MediaMetadataRetriever 拿两样）。
    val meta = remember(url) { readVideoMeta(context, url) }

    // 【音频链接别塞进视频播放器 · 2026-10-31】音乐链接解析出来的是纯音频，
    // PlayerView 没有画面可画，展开后整块全黑，只剩底下那排按钮。
    // 抽帧那一步本来就在读宽高，纯音频宽高都是 0 —— 拿它当判据，换成音频条。
    if (!meta.hasVideo) {
        AudioPlayerBubble(url = url)
        return
    }

    if (!expanded.value) {
        Surface(
            tonalElevation = 2.dp,
            modifier = modifier.combinedClickable(
                onClick = { expanded.value = true },
                onLongClick = { videoMenuOpen.value = true },
            ),
            shape = RoundedCornerShape(10.dp),
        ) {
            // 【2026-10-27 隐患修正】不靠 aspectRatio 猜（wrapContent 时它会按最小宽算，
            // 结果盒子塌成播放键那么大）—— 直接把宽高算出来。
            val boxW = if (meta.ratio >= 1f) 240.dp else (220.dp * meta.ratio)
            val boxH = if (meta.ratio >= 1f) (240.dp / meta.ratio) else 220.dp
            Box(
                modifier = Modifier.size(boxW, boxH),
                contentAlignment = Alignment.Center,
            ) {
                if (meta.cover != null) {
                    Image(
                        bitmap = meta.cover.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // 封面上面盖一层暗，再加个播放键，告诉人「这是能点的」
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.28f)),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(HugeIcons.Play, null, tint = Color.White)
                }
            }
        }
        if (videoMenuOpen.value) {
            ImageTextMenuDialog(
                onDismiss = { videoMenuOpen.value = false },
                onEdit = onEdit,
                title = "这个视频",
            )
        }
    } else {
        val player = remember(url) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                playWhenReady = true
            }
        }
        DisposableEffect(player) {
            onDispose { player.release() }
        }
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    setPlayer(player)
                    useController = true
                }
            },
            modifier = modifier
                .then(
                    // 【2026-10-27】按视频自己的比例；竖屏限高，免得一条视频占满整屏
                    if (meta.ratio >= 1f) Modifier.fillMaxWidth().aspectRatio(meta.ratio)
                    else Modifier.height(400.dp).aspectRatio(meta.ratio)
                ),
        )
    }
}

/**
 * 【音频中枢 · 2026-10-31】音乐卡片和波形条得控制同一首歌。
 *
 * 以前每条音频各开一个 MediaPlayer，而网易云那首是卡片自己另建的 ExoPlayer，
 * 两边完全不通 —— 点卡片播的是一首，点波形条播的是另一首，还各响各的。
 * 这里只留一个播放器、一份状态，谁点都调它。
 */
/**
 * 【音乐卡 · 2026-10-31】网易云 mp3 直链的小本子。
 *
 * 为什么不放卡片里：卡片一重建（列表回收、字段变化），remember(link) 就归零 ——
 * 再点又得重新解析一次；而且解出来的地址带新签名，中枢会当成另一首歌，从头上。
 * 宝实测就是这个：短时间点没事，放一会儿再点就重播。
 * 放外面按链接记，卡片怎么重建本子都还在。
 */
internal object NeteaseMp3Cache {
    private val map = mutableMapOf<String, String>()
    fun get(link: String): String? = map[link]
    fun put(link: String, url: String) { map[link] = url }
}

internal object AudioPlaybackHub {
    /** 现在这首歌的地址（null = 还没放过任何东西） */
    var currentUrl by mutableStateOf<String?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var durationMs by mutableIntStateOf(0)
        private set

    /**
     * 【波形条认它 · 2026-10-31】当前这首叫什么。
     * NowPlayingBar 原来只认 eryu 网页里的 audio，卡片走这里它就不知道 ——
     * 加了这个，网页没在播时它就回头看 hub，名字也有了。
     */
    var currentTitle by mutableStateOf<String?>(null)
        private set

    private var player: ExoPlayer? = null
    /** 第一次播放时记下的 applicationContext —— 波形条那边不一定拿得到 Context */
    private var appCtx: android.content.Context? = null

    fun positionMs(): Int = player?.currentPosition?.toInt()?.coerceAtLeast(0) ?: 0

    /** 【波形条 · 2026-10-31】给 NowPlayingBar 用的：不用传 context 就能切播放/暂停 */
    fun toggleCurrent() {
        val ctx = appCtx ?: return
        val url = currentUrl ?: return
        toggle(ctx, url, currentTitle)
    }

    /** 【波形条控进度 · 2026-10-31】拖那条进度就调这里 */
    fun seekTo(ms: Int) {
        player?.seekTo(ms.coerceAtLeast(0).toLong())
    }

    /** 停掉并清空（波形条上按"×"时用；现在还没接） */
    fun stop() {
        player?.stop()
        isPlaying = false
        currentUrl = null
        currentTitle = null
        durationMs = 0
    }

    /** 点一下：同一首就切播放/暂停；换了一首就从头放。title 只用来给波形条显示。 */
    fun toggle(context: android.content.Context, url: String, title: String? = null) {
        val ctx = context.applicationContext
        appCtx = ctx
        val p = player ?: run {
            val np = ExoPlayer.Builder(ctx).build()
            np.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        val d = np.duration
                        durationMs = if (d > 0) d.toInt() else 0
                    }
                }
            })
            player = np
            np
        }
        if (currentUrl != url) {
            currentUrl = url
            currentTitle = title
            durationMs = 0
            p.setMediaItem(MediaItem.fromUri(url))
            p.prepare()
            p.playWhenReady = true
        } else {
            // 【2026-10-31 修】播完之后再点没反应：位置停在结尾，
            // 切 playWhenReady 只是让它"立刻再结束一次"。到末尾就先回开头。
            val atEnd = p.playbackState == Player.STATE_ENDED ||
                (p.duration > 0 && p.currentPosition >= p.duration - 500)
            if (atEnd) {
                p.seekTo(0)
                p.playWhenReady = true
            } else {
                p.playWhenReady = !p.playWhenReady
            }
        }
    }
}

@Composable
@Suppress("UnusedCrossTarget")
internal fun AudioPlayerBubble(url: String) {
    val context = LocalContext.current
    // 【音频中枢 · 2026-10-31】播没播、多久、播到哪，全从中枢读，不再各存一份
    val isThisOne = AudioPlaybackHub.currentUrl == url
    val isPlaying = isThisOne && AudioPlaybackHub.isPlaying
    val durationMs = if (isThisOne) AudioPlaybackHub.durationMs else 0
    var currentMs by remember { mutableIntStateOf(0) }
 
    // Generate pseudo-random waveform bar heights (deterministic per url)
    val waveformBars = remember(url) {
        val rnd = java.util.Random(url.hashCode().toLong())
        List(40) { 0.15f + rnd.nextFloat() * 0.85f }
    }
 
    val progress = if (durationMs > 0) currentMs.toFloat() / durationMs else 0f
 
    // Progress ticker（进度从中枢问）
    LaunchedEffect(isPlaying, url) {
        while (isPlaying) {
            currentMs = AudioPlaybackHub.positionMs()
            kotlinx.coroutines.delay(50)
        }
        if (!isPlaying) currentMs = if (isThisOne) AudioPlaybackHub.positionMs() else 0
    }
 
    // Animate waveform bars when playing
    val animatedBars = remember { mutableStateOf(waveformBars) }
    LaunchedEffect(isPlaying, progress) {
        if (isPlaying) {
            val rnd = java.util.Random()
            val newBars = waveformBars.mapIndexed { index, base ->
                val playedRatio = if (progress > 0f) index.toFloat() / waveformBars.size else 0f
                if (playedRatio <= progress) {
                    // Already played bars stay at original height
                    base
                } else {
                    // Upcoming bars get slight animation
                    base * (0.85f + rnd.nextFloat() * 0.3f)
                }
            }
            animatedBars.value = newBars
        } else {
            animatedBars.value = waveformBars
        }
    }
 
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
 
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(start = 4.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Play / Pause button
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable { AudioPlaybackHub.toggle(context, url) },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPlaying) HugeIcons.PauseCircle else HugeIcons.PlayCircle,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(22.dp)
            )
        }
 
        Spacer(modifier = Modifier.width(8.dp))
 
        // Waveform bars
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(28.dp)
                .clickable { /* click waveform to seek (optional future) */ }
        ) {
            val barCount = animatedBars.value.size
            val totalWidth = size.width
            val barWidth = 2.5f
            val gap = (totalWidth - barWidth * barCount) / (barCount - 1).coerceAtLeast(1)
            val playedBarCount = (progress * barCount).toInt()
 
            animatedBars.value.forEachIndexed { index, barRatio ->
                val barHeight = size.height * barRatio.coerceIn(0.15f, 1f)
                val x = index * (barWidth + gap)
                val y = (size.height - barHeight) / 2f
                drawRoundRect(
                    color = if (index < playedBarCount) activeColor else inactiveColor,
                    topLeft = androidx.compose.ui.geometry.Offset(x, y),
                    size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f, 1.5f)
                )
            }
        }
 
        Spacer(modifier = Modifier.width(6.dp))
 
        // Duration text
        val displaySec = if (isPlaying || currentMs > 0) {
            val remaining = (durationMs - currentMs) / 1000
            remaining.coerceAtLeast(0)
        } else {
            durationMs / 1000
        }
        Text(
            text = String.format("%d:%02d", displaySec / 60, displaySec % 60),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            fontSize = 13.sp,
            modifier = Modifier.width(36.dp),
            textAlign = TextAlign.End
        )
    }
}
 
@Composable
internal fun VoiceMessageBubble(
    voiceMessage: UIMessagePart.VoiceMessage,
    isUser: Boolean,
    // 【语音条长按 · 2026-10-28】语音条原来被 Surface(onClick=播放) 整个包住，
    // 内部 clickable 在按下那一刻就消费了手势，外层正文块的 onBodyTap 收不到长按
    // → 点按菜单弹不出来 → 整条删不掉。这里自己接一个长按，回调去开同一个菜单。
    onLongPress: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
 
    val durationSec = (voiceMessage.duration / 1000).coerceAtLeast(1)
 
    DisposableEffect(voiceMessage.url) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }
 
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            mediaPlayer?.let {
                if (!it.isPlaying) {
                    isPlaying = false
                }
            }
            kotlinx.coroutines.delay(50)
        }
    }
 
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isUser) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.tertiaryContainer,
        // 【语音条长按 · 2026-10-28】改自 Surface(onClick=…)：那个内部就是 clickable，
        // 只认点击。换成 combinedClickable 才能同时拿到长按。
        modifier = Modifier.combinedClickable(
            onLongClick = { onLongPress?.invoke() },
            onClick = {
            if (isPlaying) {
                mediaPlayer?.let {
                    it.stop()
                    it.reset()
                }
                isPlaying = false
            } else {
                try {
                    val mp = MediaPlayer()
                    mp.setDataSource(voiceMessage.url)
                    mp.prepare()
                    mp.setOnCompletionListener {
                        isPlaying = false
                    }
                    mp.start()
                    isPlaying = true
                    mediaPlayer?.release()
                    mediaPlayer = mp
                } catch (e: Exception) {
                    // File might not exist
                }
            }
            },
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (isPlaying) HugeIcons.PauseCircle else HugeIcons.PlayCircle,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                // Waveform bars
                val waveformBars = remember(voiceMessage.url) {
                    val rnd = java.util.Random(voiceMessage.url.hashCode().toLong())
                    List(24) { 0.2f + rnd.nextFloat() * 0.8f }
                }
                val waveformColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f)
                Canvas(modifier = Modifier.width(60.dp).height(24.dp)) {
                    val barCount = waveformBars.size
                    val barWidth = 2.5f
                    val gap = (size.width - barWidth * barCount) / (barCount - 1).coerceAtLeast(1)
                    waveformBars.forEachIndexed { index, barRatio ->
                        val barHeight = size.height * barRatio.coerceIn(0.2f, 1f)
                        val x = index * (barWidth + gap)
                        val y = (size.height - barHeight) / 2f
                        drawRoundRect(
                            color = waveformColor,
                            topLeft = androidx.compose.ui.geometry.Offset(x, y),
                            size = Size(barWidth, barHeight),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f, 1.5f)
                        )
                    }
                }
                Text(
                    text = "${durationSec}″",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            // Show transcript text below the voice bubble (like WeChat)
            if (voiceMessage.transcript.isNotBlank()) {
                Text(
                    text = voiceMessage.transcript,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
 
/**
 * 带「引用这句」的选中容器。
 *
 * 【2026-10-10 改路】原本想换掉系统工具条（加一项"引用这句"），但 Compose 1.12 上
 * LocalTextToolbar 的覆盖不生效（Google issue 447192728 / 合并进 184950231），
 * 所以改成：系统工具条原封不动，这里只负责"盯住选中了什么"、回报给上层，
 * 由输入框上方那条自己冒的提示条来承接点击。
 *
 * [onTextPicked] 传 null 时完全退回系统默认（和以前一模一样）；
 * 没选中时回调空串 —— 上层据此把提示条收掉。
 */
@Composable
private fun SelectableWithQuote(
    onTextPicked: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (onTextPicked == null) {
        SelectionContainer(modifier = modifier) { content() }
    } else {
        val selectionState = rememberSelectionState()
        SelectionContainer(state = selectionState, modifier = modifier) { content() }

        // rememberUpdatedState 兜一下：LaunchedEffect 只在 picked 变化时重启，
        // 若直接捕获 onTextPicked，重组后换了新 lambda 它也还拿着旧那个。
        val latestPick by rememberUpdatedState(onTextPicked)
        val picked = selectionState.selectedTexts.joinToString("") { it.text }.trim()
        LaunchedEffect(picked) {
            latestPick(picked)
        }
    }
}

/**
 * 【消息引用 2026-09-22】被引消息的小条。
 * 左边一竖道 + 发送者 + 摘要（最多两行），挂在正文上方。
 * 宝引猫的、猫引宝的都走这一个。
 */
@Composable
private fun QuotedMessageChip(
    quoted: UIMessage,
    pickedText: String? = null,
    // 【引用跳回 2026-10-10】点它 → 跳回被引的那条（null = 不给点）
    onClick: ((Uuid) -> Unit)? = null,
) {
    val isUser = quoted.role == MessageRole.USER
    // 【引用一句 2026-10-10】只有某一句时优先显示那句；没有就照旧显示整条摘要
    val summary = pickedText?.replace("\n", " ")?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: quoted.parts
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString(" ") { it.text }
            .trim()
            .ifBlank { "（没有文字内容）" }
    // 【引用条上玻璃 · 2026-11-07】原来自己画底（Surface），采不到背景 → 换成 BubbleSurface。
    // 圆角 10dp 照旧，内边距交给里面那行（contentPadding = 0），视觉不变，只是多了玻璃这条路。
    BubbleSurface(
        cornerRadius = 10.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        overlayEnabled = false,
        bubbleAlpha = 1f,
        contentPadding = 0.dp,
        imagePath = "",
        modifier = Modifier
            .widthIn(max = 280.dp)
            // 【引用跳回 2026-10-10】点一下跳回被引的那条（没给回调就不给点）
            .then(
                if (onClick != null) {
                    Modifier.clickable { onClick(quoted.id) }
                } else Modifier
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(28.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    text = if (isUser) "宝" else "橘仔",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// 【插话折叠条 · 2026-10-01 / 外观对齐思考链 · 2026-10-04 宝提的四条】
// ①要有背景 ②宽度跟其他块一致 ③长得跟思考链一样（换文字+图标）④默认折叠。
// 做法：外面套一个跟 ChainOfThought 同色的 Surface（surfaceContainerHigh + 圆角 16 + 内边距 12/4），
// 里面那行照 ChainOfThoughtStepContent 的 label 排（图标槽 24dp + 8dp 间距 + 上下 8dp）。
// 不直接套 ChainOfThought：它是给 ThinkingStep 用的泛型 scope，插话塞不进去，硬包还会带时间线竖杠。
// 两种来源：①合并前的历史数据给整条 UIMessage；②合并后给的是那个带记号的 part。
@Composable
internal fun ChatMessageInterjectedMessage(
    message: UIMessage? = null,
    part: UIMessagePart? = null,
    // 【插话排队态 · 2026-10-01】true＝宝发了、猫还没轮到（还在列表里等着被并）；
    // false＝已经并进猫的回复里。两态共用这一套壳，标题跟着换，视觉上不跳。
    pending: Boolean = false,
    // 【住进思考链 · 2026-10-04 宝定的】true＝已经在 ChainOfThought 那张卡片里面了，
    // 只画这一行、不再自带 Surface（免得卡里套卡、宽度对不齐）；
    // false＝独立显示（比如排队态），自己包一层卡片。
    embedded: Boolean = false,
) {
    val text = remember(message, part) {
        when {
            part is UIMessagePart.Text -> part.text
            message != null -> message.parts
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString("\n") { it.text }
            else -> ""
        }
    }
    if (text.isBlank()) return
    // 【默认折叠 · 2026-10-04 宝定的】原来是默认展开；她说里面装的就是她的话，收着更干净
    var expanded by remember(message, part) { mutableStateOf(false) }
    // 【配色 · 2026-10-04 宝定的】字和图标跟思考链同色（思考链用的是 secondary，
    // 不是 primary）。独立显示时背景用 surfaceContainerHigh（跟思考链同款"卡片色"）。
    // ⚠️ 别用 surface——那是聊天区底色本身，卡片会跟背景糊成一片（10-04 试过）。
    val accent = MaterialTheme.colorScheme.secondary
    // 【住进思考链 · 2026-10-04】embedded 时不包 Surface：外面已经是 ChainOfThought 那张卡了。
    val inner: @Composable () -> Unit = {
        Column(
            // 【对齐修 · 2026-10-04 宝实测】住在思考链里（embedded）时不能再加水平内边距：
            // 外面 ChainOfThought 那一行本身没有水平 padding（水平是卡片给的），这里再加 12dp，
            // 图标就比旁边的（橘瓣、终端）多缩一截、对不上那条竖线。独立显示时才需要它。
            modifier = if (embedded) {
                Modifier.padding(vertical = 4.dp)
            } else {
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable { expanded = !expanded }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 【图标 · 2026-10-04 二稿】ChatUser（气泡+人）＝「用户说的那一句」。
                // 为什么不用引号：`“` 缩到 14dp 会变成歪歪的"66"（宝原话：我不行了）。
                // 气泡左右对称，缩多小都不飘。
                // 放在跟思考链/工具块一样的 24dp 图标槽里，横竖都能对上。
                // ⚠️ 教训（10-04 白跑一次构建）：这些图标是扩展属性，用之前必须
                // `import me.rerere.hugeicons.stroke.XXX` 逐个引进来，光有 HugeIcons 那个壳不够。
                Box(
                    modifier = Modifier.width(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // 【图标槽 · 2026-10-04 宝定的】照抄思考链的槽：外面 20dp 垫一层卡片底色
                    // （遮住背后的连线，也跟思考/工具块一样"图标后面有一块底"），里面图标 14dp。
                    // 原来给的是裸的 16dp 图标——比旁边大一圈、又没底色，所以看着不齐。
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .background(LocalCardColor.current),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = HugeIcons.ChatUser,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = accent,
                        )
                    }
                }
                // 【预览 · 2026-10-04 宝定的】标题带上内容开头半句：折叠条原来只写"你的插话"，
                // 两条并排时长一模一样，看着像同一条被复制了两遍（宝截图实证）。
                // 短的（≤14 字）原样放，长的截断加省略号，不硬凑。
                val preview = remember(text) {
                    val flat = text.trim().replace(Regex("\\s+"), " ")
                    if (flat.length <= 14) flat else flat.take(14) + "…"
                }
                val labelBase = stringResource(
                    if (pending) R.string.chat_message_interjected_pending
                    else R.string.chat_message_interjected_label
                )
                Text(
                    text = if (preview.isBlank()) labelBase else "$labelBase · $preview",
                    style = MaterialTheme.typography.titleSmall,
                    color = accent,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 32.dp, top = 4.dp, bottom = 8.dp),
                ) {
                    Text(
                        text = text,
                        style = LocalTextStyle.current.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        }
    }
    if (embedded) {
        inner()
    } else {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(16.dp),
        ) {
            inner()
        }
    }
}

/** 【插话合并 · 2026-10-01】这个 part 是不是宝插进来的那句。
 *  2026-10-04 起判据本体挪到 ChatMessageCot.kt（分组那边也要用），这里是薄转发。 */
private fun isInterjectPart(part: UIMessagePart): Boolean = part.isInterjectPart()

/**
 * 【视频链接封面卡 · 2026-10-28 宝要的「放个封面，然后点击播放」】
 *
 * 抖音链接进来时，DyLinkTransformer 只塞一张带 videoLink 标记的封面图（不下载视频）。
 * 点一下才去解析真直链 —— 解析到了就在原地换成播放器。
 * 好处：发完秒出卡片、不吃流量、也不必管直链过期（每次点都现解析）。
 */
@Composable
internal fun VideoLinkCard(
    link: String,
    coverUrl: String?,
    modifier: Modifier = Modifier,
    title: String? = null,
    source: String = "douyin",
    body: String? = null,
    /** 【多图缩略图 · 2026-10-29】这条笔记的全部配图（第一张是封面自己）。展开时横排一行小图。 */
    images: List<String> = emptyList(),
    /** 【图文标记 · 2026-10-30】true = 图文作品（多张图），点开原地展开看多图，不走"解析视频直链" */
    isNote: Boolean = false,
    /** 【实况 · 2026-11-04】跟 images 严格同位序；非空的那几张，点开大图后按住会动 */
    liveVideos: List<String?> = emptyList(),
) {
    /*
     * 【链接卡 · 2026-10-29 宝要的「左图右两行」】
     * 宝的原话：分享链接想包成卡片，「类似于这种效果」——左缩略图、右边标题＋域名两行。
     * 比原来 240x320 的竖图薄很多，一屏能放下好几条。
     * 抖音点开 = 现解析直链、原地换播放器；小红书点开 = 直接开网页。
     */
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 【音乐卡封面 · 2026-10-31】网易云那条传进来的是空封面。
    // 没封面就去歌曲页读一次 og:image（picUrl 混着推荐歌，不能认），抓到就留着。
    var fetchedCover by remember(link) { mutableStateOf<String?>(null) }
    LaunchedEffect(link, source, coverUrl) {
        if (source == "netease" && coverUrl.isNullOrBlank() && fetchedCover == null) {
            fetchedCover = resolveNeteaseCover(link)
        }
    }
    val shownCover = coverUrl?.takeIf { it.isNotBlank() } ?: fetchedCover
    var playUrl by remember(link) { mutableStateOf<String?>(null) }
    // 【音乐卡 · 2026-10-31 修】网易云解出来的地址单独存，绝不能写进 playUrl ——
    // playUrl 一有值就会唤醒下面那段"原地长播放器"的旧代码（黑播放器 + 自己播一遍 = 双声道）。
    // 【直链本子 · 2026-10-31】直链存在卡片外面（见 NeteaseMp3Cache），
    // 点的时候现读 —— 不能在这里取一个 val：组合那一刻取到 null 的话，
    // 之后不重组它一直是 null，第二次点还会重新解析。
    // （原来这里是 remember(link)：卡片一重建就归零 → 重新解析 → 新签名 → 当成另一首从头播。）
    var busy by remember(link) { mutableStateOf(false) }
    var failed by remember(link) { mutableStateOf(false) }
    // 【原地展开 · 2026-10-29】小红书的卡点一下就在原处长高，不跳浏览器
    var expanded by remember(link) { mutableStateOf(false) }

    val playing = playUrl
    if (playing != null) {
        val player = remember(playing) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(playing))
                prepare()
                playWhenReady = true
            }
        }
        DisposableEffect(player) { onDispose { player.release() } }
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    setPlayer(player)
                    useController = true
                }
            },
            modifier = modifier.fillMaxWidth().height(400.dp),
        )
    } else {
        val host = remember(link) {
            runCatching { java.net.URI(link).host?.removePrefix("www.") }
                .getOrNull().orEmpty().ifBlank {
                    when (source) {
                        "xhs" -> "xhslink.cn"
                        "netease" -> "163cn.tv"
                        "qishui" -> "qishui.douyin.com"
                        else -> "douyin.com"
                    }
                }
        }
        val fallbackTitle = when (source) {
            "xhs" -> "小红书笔记"
            "netease" -> "网易云音乐"
            "qishui" -> "汽水音乐"
            else -> "抖音视频"
        }
        val shownTitle = title?.takeIf { it.isNotBlank() } ?: fallbackTitle

        Surface(
            tonalElevation = 0.dp,
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(10.dp),
            modifier = modifier
                .widthIn(max = 252.dp)
                .combinedClickable(
                    onClick = {
                        if (busy) return@combinedClickable
                        // 【图文 · 2026-10-30 宝要的】图文作品跟小红书一样：点开原地展开看多图。
                        // 之前不分图文/视频，统统一路去解析 mp4 → 图文必然"解析失败"。
                        // 【小红书视频 · 2026-10-30】小红书有两种笔记，点之前分不清（页面才有答案）。
                        // 所以点了先试着解视频直链：解出来就播（视频笔记），解不出就展开（图文笔记）。
                        /*
                         * 【图文/实况 · 2026-11-04】有图就算图文。
                         *
                         * 双保险：新消息的卡带着 isNote 标记；但老消息（以及 11-04 之前存的卡）
                         * 没有这个标记，靠 linkImages 还在也能认出来 —— 一样要展开，
                         * 不该去解视频直链（实况笔记带着 masterUrl，一解就把第一张图的动播了）。
                         */
                        if (isNote || (source == "xhs" && images.isNotEmpty())) {
                            expanded = !expanded
                            return@combinedClickable
                        }
                        // 【音乐卡变开关 · 2026-10-31】网易云不再"原地长出 400dp 播放器"，
                        // 点一下就是调音频中枢：同一首切播放/暂停，换了歌从头上。
                        // 第一次点要先解析出 mp3 直链（一两秒），解到的地址记在 playUrl 里，之后不用再解。
                        // 【汽水音乐 · 2026-10-31】分享页里的直链带签名，每次现解，不缓存。
                        if (source == "qishui") {
                            busy = true
                            failed = false
                            scope.launch {
                                val resolved = runCatching { resolveQiShuiAudio(link) }.getOrNull()
                                busy = false
                                if (resolved != null) {
                                    AudioPlaybackHub.toggle(context, resolved.first, resolved.second)
                                } else {
                                    failed = true
                                }
                            }
                            return@combinedClickable
                        }
                        if (source == "netease") {
                            val known = NeteaseMp3Cache.get(link)
                            if (!known.isNullOrBlank()) {
                                AudioPlaybackHub.toggle(context, known, title)
                            } else {
                                busy = true
                                failed = false
                                scope.launch {
                                    val u = runCatching { resolveNeteaseMp3(link) }.getOrNull()
                                    busy = false
                                    if (!u.isNullOrBlank()) {
                                        NeteaseMp3Cache.put(link, u)
                                        AudioPlaybackHub.toggle(context, u, title)
                                    } else {
                                        failed = true
                                    }
                                }
                            }
                            return@combinedClickable
                        }
                        busy = true
                        failed = false
                        scope.launch {
                            val u = runCatching {
                                if (source == "xhs") {
                                    resolveXhsMp4(link)
                                } else if (source == "netease") {
                                    // 【音乐卡 · 2026-10-30】网易云：自己跟一次 302 并把 http 换成 https
                                    // （不换的话安卓禁明文，播放器拿不到时长，显示 00:00/00:00）
                                    resolveNeteaseMp3(link)
                                } else {
                                    val id = resolveAwemeId(link)
                                    val aweme = id?.let { fetchAweme(it) }
                                    aweme?.let { bestMp4(it) }
                                }
                            }.getOrNull()
                            busy = false
                            when {
                                !u.isNullOrBlank() -> playUrl = u
                                // 小红书解不出视频地址 = 大概率是图文笔记 → 展开看正文和配图
                                source == "xhs" -> expanded = !expanded
                                else -> failed = true
                            }
                        }
                    },
                    onLongClick = null,
                ),
        ) {
            Column {
            Row(
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.85f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!shownCover.isNullOrBlank()) {
                        AsyncImage(
                            model = shownCover,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (source == "douyin" && !isNote) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(HugeIcons.Play, null, tint = Color.White, modifier = Modifier.size(13.dp))
                        }
                    }
                }
                Spacer(Modifier.width(9.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = shownTitle,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = when {
                            busy -> "解析中…"
                            failed -> "没解析出来，再点一下"
                            else -> host
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (expanded) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                )
                Text(
                    text = body?.takeIf { it.isNotBlank() } ?: "这条笔记没存下正文",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                )
                // 【多图缩略图 · 2026-10-29 宝要的】展开之后横着排一行小图，
                // 想看哪张点开原笔记（这里不做大图预览，卡片的活到这儿就够了）。
                if (images.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(start = 10.dp, end = 10.dp, bottom = 9.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // 【封面也进来 · 2026-10-30 宝要的】原来从第 1 张开始（封面在卡左边已经有个小图，
                        // 怕重复）。但封面那张只剩卡片上那个小缩略图，看不到大的 —— 她说"封面那张图没法看"。
                        // 现在全列出来，封面也能点开放大；重复一下没关系，看得全更重要。
                        images.forEachIndexed { imgIdx, img ->
                            /*
                             * 【实况角标 · 2026-11-04 宝提的】缩略图右上角加个小圈标，
                             * 一眼看得出这张会动 —— 不然得点开、按住才知道。
                             * 形状抄相册那个：一圈白环 + 中间一个小实心圆。
                             */
                            val liveOfThis = liveVideos.getOrNull(imgIdx)
                            Box(modifier = Modifier.size(76.dp)) {
                                // 点一下就开大图：跟普通图片用的是同一个查看器
                                ZoomableAsyncImage(
                                    model = img,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    // 【实况 · 2026-11-04】这张会动，就把那段 mp4 一起带进去
                                    liveVideo = liveOfThis,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color.Black.copy(alpha = 0.06f)),
                                )
                                if (!liveOfThis.isNullOrBlank()) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(4.dp)
                                            .size(11.dp)
                                            .border(1.dp, Color.White.copy(alpha = 0.85f), CircleShape)
                                            .background(Color.Black.copy(alpha = 0.22f), CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(4.dp)
                                                .background(Color.White.copy(alpha = 0.95f), CircleShape)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                // 展开之后总得留一条去原笔记的路（不然点了没地方往回走）
                Row(
                    modifier = Modifier.fillMaxWidth().padding(end = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(link))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }) {
                        Text("打开原笔记", fontSize = 11.sp)
                    }
                }
            }
            }
        }
    }
}

/** 【卡顿修复 · 2026-11-08】窗口坐标持有器：不进快照系统，写它不会触发任何重组。 */
private class WindowPosRef {
    var col: Offset = Offset.Zero
    var body: Offset = Offset.Zero
}
