/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.message
 
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.ui.components.ui.icons.OrangePetalIcon
import me.rerere.rikkahub.ui.context.LocalDisplaySettings
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.extractThinkingTitle
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
 
enum class ReasoningCardState(val expanded: Boolean) {
    Collapsed(false),
    Preview(true),
    Expanded(true),
}
 
@Stable
private class ReasoningState(
    val scrollState: ScrollState,
    initialDuration: Duration,
) {
    var expandState by mutableStateOf(ReasoningCardState.Collapsed)
    var duration by mutableStateOf(initialDuration)
 
    fun onExpandedChange(nextExpanded: Boolean, loading: Boolean) {
        expandState = if (loading) {
            if (nextExpanded) ReasoningCardState.Expanded else ReasoningCardState.Preview
        } else {
            if (nextExpanded) ReasoningCardState.Expanded else ReasoningCardState.Collapsed
        }
    }
}
 
@Composable
private fun rememberReasoningState(reasoning: UIMessagePart.Reasoning): Pair<ReasoningState, Boolean> {
    val displaySettings = LocalDisplaySettings.current
    val loading = reasoning.finishedAt == null
    val scrollState = rememberScrollState()
 
    val state = remember(reasoning.createdAt) {
        ReasoningState(
            scrollState = scrollState,
            initialDuration = reasoning.finishedAt?.let { it - reasoning.createdAt }
                ?: (Clock.System.now() - reasoning.createdAt)
        )
    }
 
    LaunchedEffect(reasoning.reasoning, loading) {
        if (loading) {
            if (!state.expandState.expanded && displaySettings.showThinkingContent)
                state.expandState = ReasoningCardState.Preview
            scrollState.animateScrollTo(scrollState.maxValue)
        } else {
            if (state.expandState.expanded) {
                state.expandState = if (displaySettings.autoCloseThinking)
                    ReasoningCardState.Collapsed
                else
                    ReasoningCardState.Expanded
            }
        }
    }
 
    LaunchedEffect(loading) {
        if (loading) {
            while (isActive) {
                state.duration = (reasoning.finishedAt ?: Clock.System.now()) - reasoning.createdAt
                delay(50)
            }
        }
    }
 
    return state to loading
}
 
/** 【流式降级 · 2026-10-02】生成中思考链只渲染尾部这么多字符（保住观感，把每次重排的开销压成常数）。 */
private const val STREAMING_TAIL_CHARS = 800

@Composable
private fun ReasoningContent(
    reasoning: UIMessagePart.Reasoning,
    assistant: Assistant?,
    expandState: ReasoningCardState,
    scrollState: ScrollState,
    fadeHeight: Float,
    // 【流式降级 · 2026-10-02】生成中也传下来：正文那边生成中只铺纯文本，
    // 思考链一直走完整 Markdown 解析，长思考（几万字）下每 100ms 重解析一次 = 主线程堵死。
    loading: Boolean = false,
) {
    val isPreview = expandState == ReasoningCardState.Preview
    val displaySettings = LocalDisplaySettings.current
    val thinkingStyle = MaterialTheme.typography.bodySmall.copy(
        fontSize = MaterialTheme.typography.bodySmall.fontSize * displaySettings.thinkingFontSizeRatio,
        lineHeight = MaterialTheme.typography.bodySmall.lineHeight * displaySettings.thinkingFontSizeRatio,
    )
 
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .let { contentModifier ->
                if (isPreview) {
                    contentModifier
                        .graphicsLayer { alpha = 0.99f }
                        .drawWithCache {
                            val brush = Brush.verticalGradient(
                                startY = 0f,
                                endY = size.height,
                                colorStops = arrayOf(
                                    0.0f to Color.Transparent,
                                    (fadeHeight / size.height) to Color.Black,
                                    (1 - fadeHeight / size.height) to Color.Black,
                                    1.0f to Color.Transparent
                                )
                            )
                            onDrawWithContent {
                                drawContent()
                                drawRect(
                                    brush = brush,
                                    size = Size(size.width, size.height),
                                    blendMode = BlendMode.DstIn,
                                )
                            }
                        }
                        .heightIn(max = 100.dp)
                        .verticalScroll(scrollState)
                } else {
                    contentModifier
                }
            }
    ) {
        SelectionContainer {
            // 【流式降级 · 2026-10-02】生成中只渲染尾巴（照样走 Markdown，只是截短）：
            // 几万字的思考每 100ms 全量重解析是主线程卡死的来源；只画最后一段，
            // 观感保住了（还是带格式的思考往下滚），每次开销也从 O(全文) 压到 O(尾巴)。
            // 写完（loading=false）自动整篇恢复，显示效果不变。
            if (loading) {
                val tail = if (reasoning.reasoning.length > STREAMING_TAIL_CHARS) {
                    "…" + reasoning.reasoning.takeLast(STREAMING_TAIL_CHARS)
                } else {
                    reasoning.reasoning
                }
                // 【反查 · 2026-10-02】改回纯文本：17:54 那版为了保住格式换回了 Markdown，
                // 现在怀疑那一换就是卡死的来源（流式期间每 100ms 重跑一次 Markdown 渲染管线）。
                Text(
                    text = tail,
                    style = thinkingStyle,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                MarkdownBlock(
                    content = reasoning.reasoning.replaceRegexes(
                        assistant = assistant,
                        scope = AssistantAffectScope.ASSISTANT,
                        visual = true,
                    ),
                    style = thinkingStyle,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
 
@Composable
fun ChainOfThoughtScope.ChatMessageReasoningStep(
    reasoning: UIMessagePart.Reasoning,
    model: Model?,
    assistant: Assistant?,
    fadeHeight: Float = 64f,
    collapsedAdaptiveWidth: Boolean = false,
) {
    val (state, loading) = rememberReasoningState(reasoning)
    val thinkingTitle = reasoning.reasoning.extractThinkingTitle()
    val showThinkingTitle = loading && thinkingTitle != null
 
    ControlledChainOfThoughtStep(
        expanded = state.expandState == ReasoningCardState.Expanded,
        onExpandedChange = { state.onExpandedChange(it, loading) },
        icon = {
            Icon(
                imageVector = OrangePetalIcon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
        },
        label = {
            if (showThinkingTitle) {
                ReasoningTitle(title = thinkingTitle!!)
            } else {
                Text(
                    text = stringResource(
                        R.string.deep_thinking_seconds,
                        state.duration.toDouble(DurationUnit.SECONDS).toFloat()
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.shimmer(isLoading = loading),
                )
            }
        },
        extra = {
            if (showThinkingTitle && state.duration > 0.seconds) {
                Text(
                    text = state.duration.toString(DurationUnit.SECONDS, 1),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.shimmer(isLoading = loading),
                )
            }
        },
        collapsedAdaptiveWidth = collapsedAdaptiveWidth,
        contentVisible = state.expandState != ReasoningCardState.Collapsed,
        content = {
            ReasoningContent(
                reasoning = reasoning,
                assistant = assistant,
                expandState = state.expandState,
                scrollState = state.scrollState,
                fadeHeight = fadeHeight,
                loading = loading,
            )
        },
    )
}
 
 
@Composable
private fun ReasoningTitle(title: String) {
    AnimatedContent(
        targetState = title,
        transitionSpec = {
            (slideInVertically { height -> height } + fadeIn()).togetherWith(
                slideOutVertically { height -> -height } + fadeOut()
            )
        }
    ) {
        Text(
            text = it,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .shimmer(true),
        )
    }
}