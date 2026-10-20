/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Files02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Video01
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.hooks.ChatInputState
import org.koin.compose.koinInject

@Composable
internal fun MediaFileInputRow(
    state: ChatInputState,
) {
    val filesManager: FilesManager = koinInject()
    val managedFiles by filesManager.observe().collectAsState(initial = emptyList())
    val displayNameByRelativePath = remember(managedFiles) {
        managedFiles.associate { it.relativePath to it.displayName }
    }
    val displayNameByFileName = remember(managedFiles) {
        managedFiles.associate { it.relativePath.substringAfterLast('/') to it.displayName }
    }

    fun removePart(part: UIMessagePart, url: String) {
        state.messageContent = state.messageContent.filterNot { it == part }
        if (state.shouldDeleteFileOnRemove(part)) {
            filesManager.deleteChatFiles(listOf(url.toUri()))
        }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .horizontalScroll(rememberScrollState())
    ) {
        state.messageContent.fastForEach { part ->
            when (part) {
                is UIMessagePart.Image -> {
                    AttachmentChip(
                        title = attachmentNameFromUrl(
                            url = part.url,
                            fallback = "image",
                            displayNameByRelativePath = displayNameByRelativePath,
                            displayNameByFileName = displayNameByFileName
                        ),
                        leading = {
                            Surface(
                                modifier = Modifier.size(34.dp),
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ) {
                                AsyncImage(
                                    model = part.url,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        },
                        onRemove = { removePart(part, part.url) }
                    )
                }

                is UIMessagePart.Video -> {
                    AttachmentChip(
                        title = attachmentNameFromUrl(
                            url = part.url,
                            fallback = "video",
                            displayNameByRelativePath = displayNameByRelativePath,
                            displayNameByFileName = displayNameByFileName
                        ),
                        leading = { AttachmentLeadingIcon(icon = HugeIcons.Video01) },
                        onRemove = { removePart(part, part.url) }
                    )
                }

                is UIMessagePart.Audio -> {
                    AttachmentChip(
                        title = attachmentNameFromUrl(
                            url = part.url,
                            fallback = "audio",
                            displayNameByRelativePath = displayNameByRelativePath,
                            displayNameByFileName = displayNameByFileName
                        ),
                        leading = { AttachmentLeadingIcon(icon = HugeIcons.MusicNote03) },
                        onRemove = { removePart(part, part.url) }
                    )
                }

                is UIMessagePart.Document -> {
                    AttachmentChip(
                        title = attachmentNameFromUrl(
                            url = part.url,
                            fallback = part.fileName,
                            displayNameByRelativePath = displayNameByRelativePath,
                            displayNameByFileName = displayNameByFileName
                        ),
                        leading = { AttachmentLeadingIcon(icon = HugeIcons.Files02) },
                        onRemove = { removePart(part, part.url) }
                    )
                }

                else -> Unit
            }
        }
    }
}

@Composable
private fun AttachmentChip(
    title: String,
    leading: @Composable () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 1.dp,
        shadowElevation = 0.dp,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier
                .height(44.dp)
                .padding(start = 8.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            leading()
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.widthIn(min = 40.dp, max = 180.dp),
            )
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .size(26.dp)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = HugeIcons.Cancel01,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * 【附件 · 从纸仓里露出来的那张纸 · 2026-10-19】
 * 原来附件是 MediaFileInputRow：一排横向的 Material 胶囊，挂在机身顶上、LCD 上面。
 * 问题是语言不统一 —— 整台机器是"塑料件圆角 + 纸直角 + 暖白"，
 * 胶囊却是大圆角、横跨整机、还带描边，像在机身上贴了张标签。
 * 引用/选中/插话/编辑 已经统一成纸票了，附件再单独一套，就是一台机器上两种纸。
 * 所以这里给打印机的版本：一条一张、直角、暖白，从底部纸仓往上排。
 * （放到哪儿由 ChatInput 决定：LCD 下面、接缝上面。）
 */
@Composable
internal fun PaperAttachmentStack(
    state: ChatInputState,
) {
    val filesManager: FilesManager = koinInject()
    val managedFiles by filesManager.observe().collectAsState(initial = emptyList())
    val displayNameByRelativePath = remember(managedFiles) {
        managedFiles.associate { it.relativePath to it.displayName }
    }
    val displayNameByFileName = remember(managedFiles) {
        managedFiles.associate { it.relativePath.substringAfterLast('/') to it.displayName }
    }

    fun removePart(part: UIMessagePart, url: String) {
        state.messageContent = state.messageContent.filterNot { it == part }
        if (state.shouldDeleteFileOnRemove(part)) {
            filesManager.deleteChatFiles(listOf(url.toUri()))
        }
    }

    // 【附件吸入 · 2026-10-20 宝：发送的时候，附件吸入、还有抖一下】
    // 麻烦的地方：消息一发出去，messageContent 当场就被清空，纸跟着没了，动画没地方播。
    // 所以在这儿留一份上一次的样子，清空时拿它再画一遍、飞完再真清。
    var lastParts by remember { mutableStateOf<List<UIMessagePart>>(emptyList()) }
    val liveParts = state.messageContent
    LaunchedEffect(liveParts) {
        if (liveParts.isNotEmpty()) lastParts = liveParts
    }
    val showing = if (liveParts.isNotEmpty()) liveParts else lastParts
    val flying = liveParts.isEmpty() && lastParts.isNotEmpty()

    // 进度 0→1，多张按 index 错开 80ms（原型的 i*80）
    val flyAnim = remember { Animatable(0f) }
    LaunchedEffect(flying) {
        if (flying) {
            flyAnim.snapTo(0f)
            val totalMs = 440 + ((showing.size.coerceAtLeast(1)) - 1) * 80
            flyAnim.animateTo(1f, tween(durationMillis = totalMs, easing = LinearEasing))
            lastParts = emptyList()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 【接住纸边 · 2026-10-19 宝说"连不上"】
            // 机身底下那道纸边，最底下是 #D5D2CA（纸的厚度，暗的）；
            // 附件原来是整片 #F8F6F1（亮的）。一暗一亮贴在一起，看着就是两张纸。
            // 现在让附件从纸边那个暗色起步、几 dp 内渐到纸面白 —— 同一张纸往下延伸。
            .background(
                Brush.verticalGradient(
                    0.00f to Color(0xFFF4F2EC),
                    0.06f to Color(0xFFF7F5F0),
                    0.16f to Color(0xFFF8F6F1),
                    1.00f to Color(0xFFF8F6F1),
                )
            )
            // 【封顶 · 2026-10-19 宝发现的"能无限叠加"】
            // 一张纸 38dp，七张就把输入框顶出屏幕了。最多露两行（含间距 78dp），
            // 再多就在纸仓里往上滑 —— 纸仓是个窗口，纸可以抽，但不能把机器顶翻。
            .heightIn(max = 78.dp)
            .verticalScroll(rememberScrollState()),
        // 【别再在背景之后留白 · 2026-10-19 宝说"上面一条黑线"】
        // 原来这里有个 padding(bottom = 1.dp)，它排在 background 后面 ——
        // 那 1dp 没有背景盖着，露出的就是底下的深色机身，看着就是一条黑线。
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        showing.forEachIndexed { index, part ->
            // 每张自己的进度（前面按 index 错开 80ms）
            val fp = if (flying) cardProgress(flyAnim.value, index, showing.size) else 0f
            Box(
                modifier = Modifier.graphicsLayer {
                    // 从底边压 —— 纸是被下面吸上去的，不是从中间缩
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    translationY = -19.dp.toPx() * flyY(fp)
                    scaleY = flyScaleY(fp)
                    alpha = flyAlpha(fp)
                }
            ) {
            when (part) {
                is UIMessagePart.Image -> PaperAttachmentCard(
                    title = attachmentNameFromUrl(
                        url = part.url,
                        fallback = "image",
                        displayNameByRelativePath = displayNameByRelativePath,
                        displayNameByFileName = displayNameByFileName
                    ),
                    leading = {
                        AsyncImage(
                            model = part.url,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(2.dp)),
                        )
                    },
                    onRemove = { removePart(part, part.url) }
                )

                is UIMessagePart.Video -> PaperAttachmentCard(
                    title = attachmentNameFromUrl(
                        url = part.url,
                        fallback = "video",
                        displayNameByRelativePath = displayNameByRelativePath,
                        displayNameByFileName = displayNameByFileName
                    ),
                    leading = { AttachmentLeadingIcon(icon = HugeIcons.Video01) },
                    onRemove = { removePart(part, part.url) }
                )

                is UIMessagePart.Audio -> PaperAttachmentCard(
                    title = attachmentNameFromUrl(
                        url = part.url,
                        fallback = "audio",
                        displayNameByRelativePath = displayNameByRelativePath,
                        displayNameByFileName = displayNameByFileName
                    ),
                    leading = { AttachmentLeadingIcon(icon = HugeIcons.MusicNote03) },
                    onRemove = { removePart(part, part.url) }
                )

                is UIMessagePart.Document -> PaperAttachmentCard(
                    title = attachmentNameFromUrl(
                        url = part.url,
                        fallback = part.fileName,
                        displayNameByRelativePath = displayNameByRelativePath,
                        displayNameByFileName = displayNameByFileName
                    ),
                    leading = { AttachmentLeadingIcon(icon = HugeIcons.Files02) },
                    onRemove = { removePart(part, part.url) }
                )

                else -> Unit
            }
            }
        }
    }
}

/**
 * 一张附件纸：上沿直角（贴着纸仓那道缝）、下沿收 3dp。
 * 颜色跟引用那张纸票同一支暖白，字用纸上的墨色，不用主题色 —— 主题是给界面用的，纸不是。
 */
@Composable
private fun PaperAttachmentCard(
    title: String,
    leading: @Composable () -> Unit,
    onRemove: () -> Unit,
) {
    // 【单张不再自带底色 · 2026-10-19】底色由外面那层整组渐变提供，
    // 这样几张之间不会有各自的"边"，看着是同一张纸往下排。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 3.dp, bottomEnd = 3.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .padding(start = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            leading()
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 11.sp,
                color = Color(0xFF3A342C),
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .size(24.dp)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = HugeIcons.Cancel01,
                    contentDescription = null,
                    tint = Color(0xFF6B635A),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun AttachmentLeadingIcon(
    icon: ImageVector,
) {
    Surface(
        modifier = Modifier.size(34.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun attachmentNameFromUrl(
    url: String,
    fallback: String,
    displayNameByRelativePath: Map<String, String>,
    displayNameByFileName: Map<String, String>,
): String {
    val parsed = runCatching { url.toUri() }.getOrNull()
    val relativePath = parsed?.path?.substringAfter("/files/", missingDelimiterValue = "")?.takeIf { it.isNotBlank() }
    if (relativePath != null) {
        displayNameByRelativePath[relativePath]?.let { return it }
    }

    val storedFileName = parsed?.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    if (storedFileName != null) {
        displayNameByFileName[storedFileName]?.let { return it }
        return storedFileName
    }

    return fallback
}

// 【吸入动画的关键帧 · 2026-10-20】照 input-proto.html 的 suckIn 搬的：
//   .44s cubic-bezier(.5,0,.85,.5)，0% Y0/scale1 → 45% Y-9/.72 → 78% Y-15/.35 → 100% Y-19/.06/alpha0
// Compose 里没有分段关键帧，就把进度掰成三段线性走，形状一样。

/** 全局进度 → 第 index 张自己的进度（前面按 index 错开 80ms） */
private fun cardProgress(global: Float, index: Int, total: Int): Float {
    val totalMs = 440f + (total.coerceAtLeast(1) - 1) * 80f
    val startMs = index * 80f
    return ((global * totalMs - startMs) / 440f).coerceIn(0f, 1f)
}

/** 往上挪的比例（0~1，乘 19dp 就是位移） */
private fun flyY(p: Float): Float = when {
    p < 0.45f -> (p / 0.45f) * (9f / 19f)
    p < 0.78f -> (9f + (p - 0.45f) / 0.33f * 6f) / 19f
    else -> (15f + (p - 0.78f) / 0.22f * 4f) / 19f
}

/** 纵向压扁：1 → .72 → .35 → .06 */
private fun flyScaleY(p: Float): Float = when {
    p < 0.45f -> 1f - (p / 0.45f) * 0.28f
    p < 0.78f -> 0.72f - (p - 0.45f) / 0.33f * 0.37f
    else -> (0.35f - (p - 0.78f) / 0.22f * 0.29f).coerceAtLeast(0.06f)
}

/** 最后那一下才淡出（原型里到 78% 都还是 1） */
private fun flyAlpha(p: Float): Float =
    if (p < 0.85f) 1f else (1f - (p - 0.85f) / 0.15f).coerceAtLeast(0f)
