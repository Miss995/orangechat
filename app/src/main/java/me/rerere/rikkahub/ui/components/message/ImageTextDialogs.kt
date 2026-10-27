/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 【图片文字引用 · 2026-10-12】
 *
 * 长按图片之后弹的小菜单。目前只有一项：引用图里的内容。
 * 【2026-10-12 改】它取的内容优先用消息里存好的转述（metadata["imageNote"]），
 * 读不到才退回本地认字 —— 所以文案写中性的"图里的内容"，不写"描述"。
 * 以后要加「保存图片」「分享」之类，往这里加就行，入口不用再动。
 */
@Composable
internal fun ImageTextMenuDialog(
    onDismiss: () -> Unit,
    /** 【视频也走这个框 · 2026-10-27】null = 不显示「引用里面的内容」这一项（视频用不上） */
    onRecognize: (() -> Unit)? = null,
    onViewAiNote: (() -> Unit)? = null,
    /** 【整条消息只有一张图时编辑不了 · 2026-10-19 宝发现的】 */
    onEdit: (() -> Unit)? = null,
    /** 【2026-10-27】标题和第一项的文案按类型给：图 = "这张图" / 视频 = "这个视频" */
    title: String = "这张图",
    recognizeLabel: String = "引用图里的内容（划选要引用的部分）",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = { Text(title) },
        text = {
            Column {
                if (onRecognize != null) {
                    Surface(
                        onClick = onRecognize,
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = recognizeLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                        )
                    }
                }
                // 【图片转述三段 · 2026-10-13】第三段「图片理解」不发到模型眼前
                //（只发前两段当口径锚），所以只在这儿给宝一个人看。
                // 老图（没写标记 / 本地认字来的）没有第三段 → 这一项直接不出现。
                if (onViewAiNote != null) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        onClick = onViewAiNote,
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "查看 AI 理解（它怎么读这张图的）",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                        )
                    }
                }
                // 【单图消息补编辑入口 · 2026-10-19】只在用户消息时出现
                if (onEdit != null) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        onClick = {
                            onDismiss()
                            onEdit()
                        },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "编辑这条消息",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        },
    )
}

/**
 * 【图片文字引用 · 2026-10-12】
 *
 * 识别结果。宝定的形态（19:26）：
 * **不切句、不列表 —— 直接摊成一坨，像正文那样自己划选**，划中哪段引哪段。
 *
 * 行与行的拼接：上一行要是没以标点收尾（说明它在图里被视觉换行切断了），
 * 就直接跟下一行接上，不硬换行 —— 这样"半句半句"的情况会少很多，
 * 但**不做句子重排**（宝明确说不要"重新切"）。
 */
@Composable
internal fun ImageTextPickDialog(
    lines: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val selectionState = rememberSelectionState()
    val picked = selectionState.selectedTexts.joinToString("") { it.text }.trim()
    val body = joinOcrLines(lines)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onPick(picked) },
                enabled = picked.isNotBlank(),
            ) { Text("引用选中的") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        title = { Text("划选要引用的部分") },
        text = {
            SelectionContainer(
                state = selectionState,
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
    )
}

/**
 * 【图片转述三段 · 2026-10-13】
 * 「查看 AI 理解」—— 转述的第三段：转述模型自己怎么读这张图。
 * 这一段不发到模型眼前（发过去的只有前两段：原文照抄 + 画面简述），
 * 所以它只在这里露一次面，给宝一个人看。
 */
@Composable
internal fun ImageAiNoteDialog(
    text: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
        title = { Text("AI 怎么看这张图") },
        text = {
            SelectionContainer(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
    )
}

/** 上一行没以标点收尾就跟下一行接上（图里被视觉换行切开的句子，拼回来）。 */
private fun joinOcrLines(lines: List<String>): String {
    val enders = "。！？；：!?;:”』】）)"
    val sb = StringBuilder()
    lines.forEachIndexed { index, rawLine ->
        val line = rawLine.trim()
        if (line.isEmpty()) return@forEachIndexed
        if (sb.isNotEmpty()) {
            val last = sb.last()
            if (last in enders) sb.append('\n')
        }
        sb.append(line)
    }
    return sb.toString().ifBlank { "（没认出文字）" }
}
