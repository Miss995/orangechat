/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.log

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.repository.ConversationRepository

/**
 * 工具账本（2026-08-28 加，愿望清单 id68-⑥ 落地）：
 * 展示最近的工具调用记录（参数/结果/状态），数据直接来自本地数据库的消息记录，不额外存储。
 */
@Composable
fun ToolActionsPage(repository: ConversationRepository) {
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        loading = true
        lines = withContext(Dispatchers.IO) {
            runCatching { repository.getRecentToolActions(100) }.getOrDefault(listOf("(加载失败)"))
        }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            "工具账本（最近 100 条工具调用）",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp),
        )
        HorizontalDivider()
        if (loading) {
            Text("加载中…", modifier = Modifier.padding(16.dp))
        } else if (lines.isEmpty()) {
            Text("（还没有工具调用记录）", modifier = Modifier.padding(16.dp))
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(lines) { line ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
