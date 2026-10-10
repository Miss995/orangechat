/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.message
 
import android.content.Intent
import android.media.MediaPlayer
import android.util.Log
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyUIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
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
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.richtext.buildMarkdownPreviewHtml
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import me.rerere.rikkahub.ui.components.ui.Favicon
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
import androidx.compose.ui.platform.LocalDensity
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
    var colTopLeft by remember { mutableStateOf(Offset.Zero) }
    var bodyTopLeft by remember { mutableStateOf(Offset.Zero) }
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
                colTopLeft = coords.positionInWindow()
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
                onToolApproval = onToolApproval,
                onToolAnswer = onToolAnswer,
                // 【点按菜单 · 2026-10-10】原来点用户消息 = 编辑，现在点按统一是"弹操作菜单"，
                // 编辑挪进菜单里（只给用户消息）。
                onUserMessageClick = null,
                // 【点按菜单 · 2026-10-10】只有正文响应点击：局部坐标 + Column 的窗口坐标换算
                onBodyTap = if (!loading && !isInterjection) {
                    { localOffset ->
                        val abs = localOffset + bodyTopLeft
                        val rel = abs - colTopLeft
                        menuOffset = with(density) { DpOffset(rel.x.toDp(), rel.y.toDp()) }
                        menuVisible = true
                    }
                } else null,
                onBodyPlaced = { bodyTopLeft = it },
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
    // 【点按菜单 · 2026-10-10】只有正文响应点击：局部坐标回调 + 正文块窗口位置上报
    onBodyTap: ((Offset) -> Unit)? = null,
    onBodyPlaced: ((Offset) -> Unit)? = null,
    // 【引用一句 2026-10-10】长按选中一段文字 → 引用选中那句（上层负责挂进输入框）
    onQuoteText: ((String) -> Unit)? = null,
    // 【插话定位 · 2026-09-30】插话锚点表（id → 那条消息）。渲染到带锚点的 part 之后
    // 就把宝的话画在正文中间，由上层 ChatList 收集好传进来。
    interjections: Map<String, UIMessage> = emptyMap(),
) {
    val context = LocalContext.current
    val contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
 
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
    val groupedParts = remember(parts) {
        parts.filterNot { it is UIMessagePart.Text && it.text.isEmpty() }.groupMessageParts()
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
                    ChainOfThought(
                        modifier = if (loading) Modifier else Modifier.animateContentSize(),
                        steps = block.steps,
                        collapsedAdaptiveWidth = isReasoningOnlyBlock,
                        animateChanges = !loading,
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
 
            is MessagePartBlock.ContentBlock -> key(block.index) {
                when (val part = block.part) {
                    is UIMessagePart.Text -> {
                        // 从显示文本中移除[zip:...]标记
                        val displayText = remember(part.text) {
                            part.text.replace(Regex("\\[zip:[^\\]]+\\]", RegexOption.IGNORE_CASE), "")
                        }
                        
                        // 【插话不重复画 · 2026-10-04 宝实测】带插话标（metadata{"interject": true}）的
                        // Text part 不该在这里当正文画——它的位置由下面 MessagePartsBlock 末尾那段
                        // 折叠条渲染（isInterjectPart 那个检查）。不拦的话同一句会画两遍：
                        // 一次当正文（左对齐白底，看着像"独立消息"），一次当折叠条。
                        if (!isInterjectPart(part)) {
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
                                    onBodyPlaced?.invoke(coords.positionInWindow())
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
                                                    val up = waitForUpOrCancellation(
                                                        pass = PointerEventPass.Initial
                                                    )
                                                    if (up != null) onBodyTap(down.position)
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
                                                    ChainOfThought(
                                                        steps = listOf(
                                                            ThinkingStep.ReasoningStep(
                                                                UIMessagePart.Reasoning(reasoning = seg.content)
                                                            )
                                                        ),
                                                        collapsedAdaptiveWidth = true,
                                                    ) { step ->
                                                        ChatMessageReasoningStep(
                                                            reasoning = step.reasoning,
                                                            model = null,
                                                            assistant = assistant,
                                                        )
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
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                                Icon(HugeIcons.Video01, null)
                            }
                        }
                    }
 
                    is UIMessagePart.Audio -> {
                        AudioPlayerBubble(url = part.url)
                    }
 
                    is UIMessagePart.VoiceMessage -> {
                        VoiceMessageBubble(
                            voiceMessage = part,
                            isUser = role == MessageRole.USER,
                        )
                    }
 
                    is UIMessagePart.Image -> {
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
                            )
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
 
@Composable
private fun BubbleSurface(
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
            modifier = Modifier
                .animateContentSize()
                .clip(RoundedCornerShape(cornerRadius))
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        ) {
            AsyncImage(
                model = imagePath,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
            if (overlayEnabled) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(color.copy(alpha = bubbleAlpha))
                )
            }
            Column(modifier = Modifier.padding(8.dp)) { content() }
        }
    } else {
        Surface(
            modifier = Modifier.animateContentSize(),
            shape = RoundedCornerShape(cornerRadius),
            color = color.copy(alpha = bubbleAlpha),
            onClick = onClick ?: {},
        ) {
            Column(modifier = Modifier.padding(8.dp)) { content() }
        }
    }
}
 
@Composable
@Suppress("UnusedCrossTarget")
internal fun AudioPlayerBubble(url: String) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var durationMs by remember { mutableIntStateOf(0) }
    var currentMs by remember { mutableIntStateOf(0) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPrepared by remember { mutableStateOf(false) }
 
    // Generate pseudo-random waveform bar heights (deterministic per url)
    val waveformBars = remember(url) {
        val rnd = java.util.Random(url.hashCode().toLong())
        List(40) { 0.15f + rnd.nextFloat() * 0.85f }
    }
 
    val progress = if (durationMs > 0) currentMs.toFloat() / durationMs else 0f
 
    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }
 
    // Progress ticker
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    currentMs = it.currentPosition
                }
            }
            kotlinx.coroutines.delay(50)
        }
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
                .clickable {
                    if (isPlaying) {
                        mediaPlayer?.pause()
                        isPlaying = false
                    } else {
                        if (mediaPlayer == null || !isPrepared) {
                            val mp = MediaPlayer()
                            try {
                                val uri = android.net.Uri.parse(url)
                                mp.setDataSource(context, uri)
                                mp.prepare()
                                durationMs = mp.duration
                                mp.setOnCompletionListener {
                                    isPlaying = false
                                    currentMs = 0
                                }
                                mp.start()
                                isPlaying = true
                                isPrepared = true
                                mediaPlayer = mp
                            } catch (e: Exception) {
                                mp.release()
                            }
                        } else {
                            mediaPlayer?.start()
                            isPlaying = true
                        }
                    }
                },
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
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
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
