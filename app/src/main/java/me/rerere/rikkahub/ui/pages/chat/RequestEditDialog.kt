/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import me.rerere.rikkahub.data.ai.RequestEditController

/**
 * 请求编辑弹窗：发送前展示将要发出的请求内容，
 * 用户可以勾选/删除 system 分节与历史消息、点 ✏️ 编辑某段 system，
 * 并选择性勾选本轮要注入的工具（默认全选，取消勾选 = 不注入）。
 */
@Composable
fun RequestEditDialog(
    data: RequestEditController.RequestEditData,
    onConfirm: (RequestEditController.RequestEditData) -> Unit,
    onCancel: () -> Unit,
) {
    var sections by remember(data) { mutableStateOf(data.sections) }
    var history by remember(data) { mutableStateOf(data.history) }
    var tools by remember(data) { mutableStateOf(data.tools) }
    var editing by remember { mutableStateOf<Pair<Int, RequestEditController.EditSection>?>(null) }
    // 历史消息展开原文：按 index 记录哪些条展开了
    var expandedIds by remember { mutableStateOf(setOf<Int>()) }

    Dialog(onDismissRequest = onCancel) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f),
        ) {
            Column(Modifier.fillMaxSize()) {
                Text(
                    "编辑请求 · 手动控制上下文",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
                HorizontalDivider()
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    item {
                        Text(
                            "System 分节（勾选 = 这轮带上，点文字可编辑）",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    itemsIndexed(sections) { index, section ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { editing = index to section }
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Checkbox(
                                checked = section.enabled,
                                onCheckedChange = { checked ->
                                    sections = sections.toMutableList().also {
                                        it[index] = section.copy(enabled = checked)
                                    }
                                },
                            )
                            Text(
                                section.title.ifBlank { "(无标题)" },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text("✏️", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    item {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Text(
                                "历史消息（勾选 = 这轮带上）",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.padding(top = 4.dp),
                            ) {
                                Text("快捷:", style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "10条", style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable {
                                        history = history.mapIndexed { i, it -> it.copy(enabled = i >= history.size - 10) }
                                    },
                                )
                                Text(
                                    "20条", style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable {
                                        history = history.mapIndexed { i, it -> it.copy(enabled = i >= history.size - 20) }
                                    },
                                )
                                Text(
                                    "50条", style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable {
                                        history = history.mapIndexed { i, it -> it.copy(enabled = i >= history.size - 50) }
                                    },
                                )
                                Text(
                                    "全部", style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable {
                                        history = history.map { it.copy(enabled = true) }
                                    },
                                )
                                Text(
                                    "清空", style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.clickable {
                                        history = history.map { it.copy(enabled = false) }
                                    },
                                )
                            }
                        }
                    }
                    itemsIndexed(history) { index, item ->
                        val expanded = index in expandedIds
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Checkbox(
                                checked = item.enabled,
                                onCheckedChange = { checked ->
                                    history = history.toMutableList().also {
                                        it[index] = item.copy(enabled = checked)
                                    }
                                },
                            )
                            Text(
                                "[${item.role}] ${item.text}",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = if (expanded) Int.MAX_VALUE else 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                if (expanded) "收起" else "展开",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(start = 4.dp)
                                    .clickable {
                                        expandedIds = if (expanded) expandedIds - index else expandedIds + index
                                    },
                            )
                        }
                    }
                    item {
                        Text(
                            "工具（勾选 = 本轮注入，默认全选，取消 = 省 token）",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    itemsIndexed(tools) { index, tool ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Checkbox(
                                checked = tool.enabled,
                                onCheckedChange = { checked ->
                                    tools = tools.toMutableList().also {
                                        it[index] = tool.copy(enabled = checked)
                                    }
                                },
                            )
                            Text(
                                tool.name,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onCancel) { Text("取消发送") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            onConfirm(
                                RequestEditController.RequestEditData(
                                    sections = sections,
                                    history = history,
                                    tools = tools,
                                )
                            )
                        },
                    ) { Text("发送这轮请求") }
                }
            }
        }
    }

    // 编辑某段 system 的弹窗
    editing?.let { (index, section) ->
        var newContent by remember(section) { mutableStateOf(section.content) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(section.title.ifBlank { "编辑分节" }) },
            text = {
                OutlinedTextField(
                    value = newContent,
                    onValueChange = { newContent = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        sections = sections.toMutableList().also {
                            it[index] = section.copy(content = newContent)
                        }
                        editing = null
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("取消") }
            },
        )
    }
}
