/* 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.memorywatch

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.Pulse01
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.ExternalMemory
import me.rerere.rikkahub.data.service.ExternalMemoryMessage
import me.rerere.rikkahub.data.service.ExternalMemoryService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalCurrentAssistant
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * 记忆监工台：三源状态灯 + 召回体检 + 记忆浏览(150条) + 召回条数调节
 * V1 先做外置记忆库完整功能，OB / Mem0 状态灯与召回体检后续接入。
 */
@Composable
fun MemoryWatchPage() {
    val settings = LocalSettings.current
    val currentAssistant = LocalCurrentAssistant.current
    val settingsStore: SettingsStore = koinInject()
    val scope = rememberCoroutineScope()

    val assistantId = currentAssistant?.id?.toString() ?: ""
    val externalConfigs = remember(settings.externalMemories) {
        settings.externalMemories.filter { it.enabled }
    }

    // 每个外置库的状态（灯 + 条数 + 说明）
    data class SourceStatus(val ok: Boolean, val info: String, val loading: Boolean = false)
    var statusMap by remember { mutableStateOf<Map<Uuid, SourceStatus>>(emptyMap()) }
    // 当前浏览的库
    var selectedConfigId by remember { mutableStateOf<Uuid?>(externalConfigs.firstOrNull()?.id) }
    // 记忆列表
    var messages by remember { mutableStateOf<List<ExternalMemoryMessage>>(emptyList()) }
    var messagesLoading by remember { mutableStateOf(false) }
    // 召回体检
    var recallQuery by remember { mutableStateOf("") }
    var recallResults by remember { mutableStateOf<Map<String, List<ExternalMemoryMessage>>>(emptyMap()) }
    var recallRunning by remember { mutableStateOf(false) }
    // 详情弹窗
    var detailMessage by remember { mutableStateOf<ExternalMemoryMessage?>(null) }

    // 进页面自动刷新状态灯（每个启用外置库：连得上吗 + 多少条）
    LaunchedEffect(externalConfigs, assistantId) {
        if (assistantId.isBlank()) {
            statusMap = emptyMap()
            return@LaunchedEffect
        }
        statusMap = externalConfigs.associate { cfg ->
            cfg.id to SourceStatus(ok = false, info = "检测中…", loading = true)
        }
        val results = externalConfigs.map { cfg ->
            async {
                val service = ExternalMemoryService(cfg)
                val count = service.countMessages(assistantId).getOrElse { -1 }
                cfg.id to if (count >= 0) {
                    SourceStatus(ok = true, info = "在线 · 共 $count 条")
                } else {
                    SourceStatus(ok = false, info = "连不上（检查网络/配置）")
                }
            }
        }.awaitAll()
        statusMap = results.toMap()
    }

    // 选中库变化 -> 加载最近 150 条
    LaunchedEffect(selectedConfigId, assistantId) {
        messages = emptyList()
        val cfg = externalConfigs.find { it.id == selectedConfigId } ?: return@LaunchedEffect
        if (assistantId.isBlank()) return@LaunchedEffect
        messagesLoading = true
        val result = ExternalMemoryService(cfg).queryLatestMessages(
            assistantId = assistantId,
            limit = 150,
        )
        messages = result.getOrDefault(emptyList())
        messagesLoading = false
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("记忆监工台") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("statusSection") {
                StatusSection(
                    externalConfigs = externalConfigs,
                    statusMap = statusMap,
                )
            }

            item("recallTestSection") {
                RecallTestSection(
                    externalConfigs = externalConfigs,
                    assistantId = assistantId,
                    recallQuery = recallQuery,
                    onQueryChange = { recallQuery = it },
                    recallResults = recallResults,
                    recallRunning = recallRunning,
                    onRunRecall = {
                        if (recallQuery.isBlank() || assistantId.isBlank()) return@RecallTestSection
                        recallRunning = true
                        recallResults = emptyMap()
                        scope.launch {
                            val results = externalConfigs.map { cfg ->
                                async {
                                    val service = ExternalMemoryService(cfg)
                                    val kw = recallQuery.trim()
                                    val hits = service.searchMessages(
                                        assistantId = assistantId,
                                        keyword = kw,
                                        limit = cfg.recallCount,
                                    ).getOrDefault(emptyList())
                                    cfg.name to hits
                                }
                            }.awaitAll().toMap()
                            recallResults = results
                            recallRunning = false
                        }
                    },
                )
            }

            item("recallCountSection") {
                RecallCountSection(
                    externalConfigs = externalConfigs,
                    onCountChange = { cfg, newCount ->
                        settingsStore.update { s ->
                            s.copy(externalMemories = s.externalMemories.map { m ->
                                if (m.id == cfg.id) m.copy(recallCount = newCount) else m
                            })
                        }
                    },
                )
            }

            item("memoryListHeader") {
                MemoryListHeader(
                    externalConfigs = externalConfigs,
                    selectedConfigId = selectedConfigId,
                    onSelect = { selectedConfigId = it },
                    messagesLoading = messagesLoading,
                )
            }

            if (messages.isEmpty() && !messagesLoading) {
                item("emptyHint") {
                    Text(
                        text = "（这个库里还没有消息，或当前助手无记录）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            items(messages, key = { it.id }) { msg ->
                MessageRow(
                    msg = msg,
                    onClick = { detailMessage = msg },
                )
            }
        }
    }

    // 记忆详情 + 剔除弹窗
    detailMessage?.let { msg ->
        val cfg = externalConfigs.find { it.id == selectedConfigId }
        AlertDialog(
            onDismissRequest = { detailMessage = null },
            title = { Text(if (msg.role == "user") "用户消息" else if (msg.role == "assistant") "AI 消息" else msg.role) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = msg.content,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "时间：${msg.createdAt}\nID：${msg.id}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { detailMessage = null }) {
                    Text("关闭")
                }
            },
            dismissButton = {
                if (cfg != null) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                ExternalMemoryService(cfg).deleteMessage(msg.id)
                                detailMessage = null
                                // 刷新列表
                                val result = ExternalMemoryService(cfg).queryLatestMessages(
                                    assistantId = assistantId,
                                    limit = 150,
                                )
                                messages = result.getOrDefault(emptyList())
                            }
                        }
                    ) {
                        Text("剔除", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
        )
    }
}

/** 状态灯区 */
@Composable
private fun StatusSection(
    externalConfigs: List<ExternalMemory>,
    statusMap: Map<Uuid, MemoryWatchPageStatus>,
) {
    CardGroup(
        modifier = Modifier.padding(horizontal = 8.dp),
        title = { Text("记忆源状态") },
    ) {
        // 外置库
        if (externalConfigs.isEmpty()) {
            item(headlineContent = { Text("没有启用的外置记忆库") })
        } else {
            externalConfigs.forEach { cfg ->
                val status = statusMap[cfg.id]
                item(
                    leadingContent = {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(
                                    color = when {
                                        status == null || status.loading -> Color(0xFFFFC107) // 黄
                                        status.ok -> Color(0xFF4CAF50) // 绿
                                        else -> Color(0xFFF44336) // 红
                                    },
                                    shape = CircleShape,
                                )
                        )
                    },
                    headlineContent = { Text("外置库 · ${cfg.name}") },
                    supportingContent = { Text(status?.info ?: "检测中…") },
                )
            }
        }
        // OB / Mem0（后续接入）
        item(
            leadingContent = {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(Color(0xFFFFC107), CircleShape)
                )
            },
            headlineContent = { Text("OB（Termux）") },
            supportingContent = { Text("待接入 · MCP 对接中") },
        )
        item(
            leadingContent = {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(Color(0xFFFFC107), CircleShape)
                )
            },
            headlineContent = { Text("Mem0") },
            supportingContent = { Text("待接入 · MCP 对接中") },
        )
    }
}

/** 召回体检区 */
@Composable
private fun RecallTestSection(
    externalConfigs: List<ExternalMemory>,
    assistantId: String,
    recallQuery: String,
    onQueryChange: (String) -> Unit,
    recallResults: Map<String, List<ExternalMemoryMessage>>,
    recallRunning: Boolean,
    onRunRecall: () -> Unit,
) {
    CardGroup(
        modifier = Modifier.padding(horizontal = 8.dp),
        title = { Text("召回体检（输入一句话，看谁能召回）") },
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = recallQuery,
                    onValueChange = onQueryChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("比如：我们上次聊到的小屋") },
                    singleLine = true,
                )
                Button(
                    onClick = onRunRecall,
                    enabled = !recallRunning && recallQuery.isNotBlank() && assistantId.isNotBlank(),
                ) {
                    Text(if (recallRunning) "体检中…" else "体检")
                }
            }
        }
        if (recallResults.isNotEmpty()) {
            recallResults.forEach { (name, hits) ->
                item(
                    leadingContent = { Icon(HugeIcons.GlobalSearch, null) },
                    headlineContent = { Text("$name · 召回 ${hits.size} 条") },
                    supportingContent = {
                        if (hits.isEmpty()) {
                            Text("什么都没搜到")
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                hits.take(3).forEach { m ->
                                    Text(
                                        text = (if (m.role == "user") "用户：" else "AI：") + m.content.take(60),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (hits.size > 3) {
                                    Text("…还有 ${hits.size - 3} 条", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

/** 召回条数调节区 */
@Composable
private fun RecallCountSection(
    externalConfigs: List<ExternalMemory>,
    onCountChange: (ExternalMemory, Int) -> Unit,
) {
    CardGroup(
        modifier = Modifier.padding(horizontal = 8.dp),
        title = { Text("召回条数（改完保存，下次请求生效）") },
    ) {
        externalConfigs.forEach { cfg ->
            item(
                leadingContent = { Icon(HugeIcons.Database02, null) },
                headlineContent = { Text("外置库 · ${cfg.name}") },
                supportingContent = { Text("每次召回 ${cfg.recallCount} 条") },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { if (cfg.recallCount > 1) onCountChange(cfg, cfg.recallCount - 1) },
                            modifier = Modifier.width(40.dp),
                            contentPadding = PaddingValues(0.dp),
                        ) { Text("-") }
                        Text(
                            text = "${cfg.recallCount}",
                            modifier = Modifier.padding(horizontal = 12.dp),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        OutlinedButton(
                            onClick = { if (cfg.recallCount < 20) onCountChange(cfg, cfg.recallCount + 1) },
                            modifier = Modifier.width(40.dp),
                            contentPadding = PaddingValues(0.dp),
                        ) { Text("+") }
                    }
                },
            )
        }
        if (externalConfigs.isEmpty()) {
            item(headlineContent = { Text("没有启用的外置记忆库") })
        }
    }
}

/** 记忆列表头（选库 + 条数） */
@Composable
private fun MemoryListHeader(
    externalConfigs: List<ExternalMemory>,
    selectedConfigId: Uuid?,
    onSelect: (Uuid) -> Unit,
    messagesLoading: Boolean,
) {
    CardGroup(
        modifier = Modifier.padding(horizontal = 8.dp),
        title = { Text("记忆浏览（最近 150 条）") },
    ) {
        if (externalConfigs.size > 1) {
            externalConfigs.forEach { cfg ->
                val selected = cfg.id == selectedConfigId
                item(
                    onClick = { onSelect(cfg.id) },
                    leadingContent = { Icon(HugeIcons.Pulse01, null) },
                    headlineContent = { Text(if (selected) "▸ ${cfg.name}" else cfg.name) },
                    supportingContent = { Text(if (selected) "当前查看" else "点击切换") },
                )
            }
        }
        item(
            headlineContent = { Text(if (messagesLoading) "加载中…" else "共 ${0 + messagesCount()} 条") },
        )
    }
}

/** 单条记忆行 */
@Composable
private fun MessageRow(
    msg: ExternalMemoryMessage,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (msg.role == "user") "用户" else if (msg.role == "assistant") "AI" else msg.role,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = msg.createdAt,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = msg.content,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
