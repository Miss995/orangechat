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
import androidx.compose.material3.Icon
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.Pulse01
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpStatus
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.ExternalMemory
import me.rerere.rikkahub.data.service.ExternalMemoryMessage
import me.rerere.rikkahub.data.service.ExternalMemoryService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalCurrentAssistant
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/** 单个记忆源的状态（监工台状态灯） */
data class SourceStatus(
    val ok: Boolean,
    val info: String,
    val loading: Boolean = false,
)

/** MCP 记忆源（OB / Mem0）的状态灯信息 */
data class McpSourceStatus(
    val color: Color,
    val info: String,
)

/** 把 MCP 连接状态转成状态灯颜色 + 文案 */
fun McpStatus.toSourceStatus(): McpSourceStatus {
    return when (this) {
        is McpStatus.Connected -> McpSourceStatus(Color(0xFF4CAF50), "已连接")
        is McpStatus.Error -> McpSourceStatus(Color(0xFFF44336), "出错：${message.take(40)}")
        is McpStatus.NeedsAuthorization -> McpSourceStatus(Color(0xFFFFC107), "需要授权")
        is McpStatus.Connecting -> McpSourceStatus(Color(0xFFFFC107), "连接中…")
        is McpStatus.Reconnecting -> McpSourceStatus(Color(0xFFFFC107), "重连中…")
        is McpStatus.Authorizing -> McpSourceStatus(Color(0xFFFFC107), "授权中…")
        else -> McpSourceStatus(Color(0xFFFFC107), "未连接")
    }
}

/**
 * 判断召回文本是否为"空结果"（OB 搜空会返回"未匹配到相关内容"之类的正式声明）
 */
private fun isNoResultText(text: String): Boolean {
    val t = text.trim()
    if (t.isEmpty()) return true
    return t.contains("未匹配") ||
        t.contains("没有搜到") ||
        t.contains("未找到") ||
        t.contains("没有找到") ||
        t.contains("无相关") ||
        t.contains("没有相关") ||
        t.contains("未检索") ||
        t.contains("（空")
}

/**
 * 记忆监工台：记忆源状态灯 + 召回体检 + 记忆浏览(150条) + 召回条数调节 + OB/Mem0 全部浏览
 * V3：OB 记忆目录（catalog）+ Mem0 全部记忆（list_memories）。
 */
@Composable
fun MemoryWatchPage() {
    val settings = LocalSettings.current
    val currentAssistant = LocalCurrentAssistant.current
    val settingsStore: SettingsStore = koinInject()
    val mcManager: McpManager = koinInject()
    val scope = rememberCoroutineScope()

    val assistantId = currentAssistant?.id?.toString() ?: ""
    val externalConfigs = remember(settings.externalMemories) {
        settings.externalMemories.filter { it.enabled }
    }

    // 找 OB / Mem0 的 MCP server 配置（按名字模糊匹配）
    val obServer = remember(settings.mcpServers) {
        settings.mcpServers.firstOrNull {
            it.commonOptions.enable && it.commonOptions.name.contains("ob", ignoreCase = true)
        }
    }
    val mem0Server = remember(settings.mcpServers) {
        settings.mcpServers.firstOrNull {
            it.commonOptions.enable && it.commonOptions.name.contains("mem0", ignoreCase = true)
        }
    }

    // OB / Mem0 的 MCP 连接状态
    var obStatus by remember { mutableStateOf<McpStatus>(McpStatus.Idle) }
    var mem0Status by remember { mutableStateOf<McpStatus>(McpStatus.Idle) }
    LaunchedEffect(obServer) {
        if (obServer != null) mcManager.getStatus(obServer).collect { obStatus = it }
    }
    LaunchedEffect(mem0Server) {
        if (mem0Server != null) mcManager.getStatus(mem0Server).collect { mem0Status = it }
    }

    // 每个外置库的状态（灯 + 条数 + 说明）
    var statusMap by remember { mutableStateOf<Map<Uuid, SourceStatus>>(emptyMap()) }
    // 当前浏览的库
    var selectedConfigId by remember { mutableStateOf<Uuid?>(externalConfigs.firstOrNull()?.id) }
    // 记忆列表
    var messages by remember { mutableStateOf<List<ExternalMemoryMessage>>(emptyList()) }
    var messagesLoading by remember { mutableStateOf(false) }
    // 召回体检
    var recallQuery by remember { mutableStateOf("") }
    var recallResults by remember { mutableStateOf<Map<String, List<ExternalMemoryMessage>>>(emptyMap()) }
    var mcpRecallResults by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var recallDone by remember { mutableStateOf(false) }
    var recallRunning by remember { mutableStateOf(false) }
    // 体检诊断（自报进度，方便定位问题）
    var recallDiag by remember { mutableStateOf("就绪——输入一句话，点「体检」") }
    // OB 目录 / Mem0 全部
    var obCatalogText by remember { mutableStateOf("") }
    var obCatalogLoading by remember { mutableStateOf(false) }
    var mem0ListText by remember { mutableStateOf("") }
    var mem0ListLoading by remember { mutableStateOf(false) }
    // 详情弹窗
    var detailMessage by remember { mutableStateOf<ExternalMemoryMessage?>(null) }

    // 进页面自动刷新状态灯（每个启用外置库：连得上吗 + 多少条）
    LaunchedEffect(externalConfigs, assistantId) {
        if (assistantId.isBlank()) {
            statusMap = emptyMap()
            recallDiag = "⚠ 未检测到当前助手（assistantId 为空）"
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
        recallDiag = "状态灯已刷新：${externalConfigs.size} 个外置库，助手 id=${assistantId.take(8)}…"
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
                    obServer = obServer,
                    obStatus = obStatus,
                    mem0Server = mem0Server,
                    mem0Status = mem0Status,
                )
            }

            item("recallTestSection") {
                RecallTestSection(
                    externalConfigs = externalConfigs,
                    assistantId = assistantId,
                    recallQuery = recallQuery,
                    onQueryChange = { recallQuery = it },
                    recallResults = recallResults,
                    mcpRecallResults = mcpRecallResults,
                    recallDone = recallDone,
                    recallRunning = recallRunning,
                    recallDiag = recallDiag,
                    onRunRecall = {
                        recallDiag = "已触发点击：词=\"${recallQuery.trim()}\" 助手id=\"${assistantId.take(8)}…\" 外置库=${externalConfigs.size} 个"
                        val canRun = recallQuery.isNotBlank() &&
                            (externalConfigs.isNotEmpty() || obServer != null || mem0Server != null)
                        if (canRun) {
                            recallRunning = true
                            recallDone = true
                            recallResults = emptyMap()
                            mcpRecallResults = emptyMap()
                            val querySnapshot = recallQuery.trim()
                            val configsSnapshot = externalConfigs
                            val assistantSnapshot = assistantId
                            val obSnapshot = obServer
                            val mem0Snapshot = mem0Server
                            val mcSnapshot = mcManager
                            recallDiag = "开始搜索：外置库 ${configsSnapshot.size} 个 + MCP ${listOfNotNull(obSnapshot, mem0Snapshot).size} 个…（词=\"$querySnapshot\"）"
                            scope.launch {
                                // 外置库搜索
                                val results = configsSnapshot.map { cfg ->
                                    async {
                                        val service = ExternalMemoryService(cfg)
                                        val hits = service.searchMessages(
                                            assistantId = assistantSnapshot,
                                            keyword = querySnapshot,
                                            limit = cfg.recallCount,
                                        ).getOrDefault(emptyList())
                                        cfg.name to hits
                                    }
                                }.awaitAll().toMap()
                                recallResults = results

                                // OB / Mem0（MCP）搜索
                                val mcpResults = mutableMapOf<String, String>()
                                if (obSnapshot != null) {
                                    mcpResults["OB"] = runCatching {
                                        val parts = mcSnapshot.callTool(
                                            serverId = obSnapshot.id,
                                            toolName = "breath_search",
                                            args = buildJsonObject {
                                                put("query", JsonPrimitive(querySnapshot))
                                                put("max_results", JsonPrimitive(5))
                                            },
                                        )
                                        parts.filterIsInstance<UIMessagePart.Text>()
                                            .joinToString("\n") { it.text }
                                            .ifBlank { "（空返回）" }
                                    }.getOrElse { "调用失败：${it.message?.take(50)}" }
                                }
                                if (mem0Snapshot != null) {
                                    mcpResults["Mem0"] = runCatching {
                                        val parts = mcSnapshot.callTool(
                                            serverId = mem0Snapshot.id,
                                            toolName = "search_memory",
                                            args = buildJsonObject {
                                                put("query", JsonPrimitive(querySnapshot))
                                                put("limit", JsonPrimitive(5))
                                            },
                                        )
                                        parts.filterIsInstance<UIMessagePart.Text>()
                                            .joinToString("\n") { it.text }
                                            .ifBlank { "（空返回）" }
                                    }.getOrElse { "调用失败：${it.message?.take(50)}" }
                                }
                                mcpRecallResults = mcpResults

                                recallRunning = false
                                val summary = results.entries.joinToString("；") { (name, hits) ->
                                    "$name 召回 ${hits.size} 条"
                                } + mcpResults.entries.joinToString("；") { (name, text) ->
                                    "$name ${when {
                                        text.startsWith("调用失败") -> "失败"
                                        isNoResultText(text) -> "未匹配"
                                        else -> "已返回"
                                    }}"
                                }
                                recallDiag = "✅ 体检完成：$summary"
                            }
                        } else {
                            val why = when {
                                recallQuery.isBlank() -> "体检词为空"
                                externalConfigs.isEmpty() && obServer == null && mem0Server == null -> "没有可用的记忆源"
                                else -> "未知原因"
                            }
                            recallDiag = "❌ 无法体检：$why"
                        }
                    },
                )
            }

            item("recallCountSection") {
                RecallCountSection(
                    externalConfigs = externalConfigs,
                    onCountChange = { cfg, newCount ->
                        scope.launch {
                            settingsStore.update { s ->
                                s.copy(externalMemories = s.externalMemories.map { m ->
                                    if (m.id == cfg.id) m.copy(recallCount = newCount) else m
                                })
                            }
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
                    messageCount = messages.size,
                )
            }

            if (messages.isEmpty() && !messagesLoading) {
                item("emptyHint") {
                    Text(
                        text = if (assistantId.isBlank()) {
                            "（未检测到当前助手，无法读取记忆）"
                        } else {
                            "（这个库里还没有消息，或当前助手无记录）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            items(messages, key = { "${it.id}-${it.createdAt}" }) { msg ->
                MessageRow(
                    msg = msg,
                    onClick = { detailMessage = msg },
                )
            }

            // OB 记忆目录（全部记忆桶）
            item("obCatalogSection") {
                ObCatalogSection(
                    obServer = obServer,
                    catalogText = obCatalogText,
                    catalogLoading = obCatalogLoading,
                    onLoadCatalog = {
                        if (obServer != null) {
                            obCatalogLoading = true
                            val obSnapshot = obServer
                            val mcSnapshot = mcManager
                            scope.launch {
                                obCatalogText = runCatching {
                                    val parts = mcSnapshot.callTool(
                                        serverId = obSnapshot.id,
                                        toolName = "breath_advanced",
                                        args = buildJsonObject {
                                            put("catalog", JsonPrimitive(true))
                                            put("max_results", JsonPrimitive(50))
                                        },
                                    )
                                    parts.filterIsInstance<UIMessagePart.Text>()
                                        .joinToString("\n") { it.text }
                                        .ifBlank { "（空目录）" }
                                }.getOrElse { "调用失败：${it.message?.take(60)}" }
                                obCatalogLoading = false
                            }
                        }
                    },
                )
            }

            // Mem0 全部记忆
            item("mem0ListSection") {
                Mem0ListSection(
                    mem0Server = mem0Server,
                    listText = mem0ListText,
                    listLoading = mem0ListLoading,
                    onLoadList = {
                        if (mem0Server != null) {
                            mem0ListLoading = true
                            val mem0Snapshot = mem0Server
                            val mcSnapshot = mcManager
                            scope.launch {
                                mem0ListText = runCatching {
                                    val parts = mcSnapshot.callTool(
                                        serverId = mem0Snapshot.id,
                                        toolName = "list_memories",
                                        args = buildJsonObject {
                                            put("limit", JsonPrimitive(100))
                                        },
                                    )
                                    parts.filterIsInstance<UIMessagePart.Text>()
                                        .joinToString("\n") { it.text }
                                        .ifBlank { "（空）" }
                                }.getOrElse { "调用失败：${it.message?.take(60)}" }
                                mem0ListLoading = false
                            }
                        }
                    },
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
    statusMap: Map<Uuid, SourceStatus>,
    obServer: McpServerConfig?,
    obStatus: McpStatus,
    mem0Server: McpServerConfig?,
    mem0Status: McpStatus,
) {
    CardGroup(
        modifier = Modifier.padding(horizontal = 8.dp),
        title = { Text("记忆源状态") },
    ) {
        if (externalConfigs.isEmpty()) {
            item(
                leadingContent = { Icon(HugeIcons.Database02, null) },
                headlineContent = { Text("没有启用的外置记忆库") },
                supportingContent = { Text("去「进阶记忆」配置里启用一个") },
            )
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
        // OB
        val obSource = obStatus.toSourceStatus()
        item(
            leadingContent = {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(obSource.color, CircleShape)
                )
            },
            headlineContent = { Text(if (obServer != null) "OB（Termux）" else "OB（Termux）未配置") },
            supportingContent = { Text(obServer?.let { obSource.info } ?: "MCP 设置里加个名字含 OB 的服务器") },
        )
        // Mem0
        val mem0Source = mem0Status.toSourceStatus()
        item(
            leadingContent = {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(mem0Source.color, CircleShape)
                )
            },
            headlineContent = { Text(if (mem0Server != null) "Mem0" else "Mem0 未配置") },
            supportingContent = { Text(mem0Server?.let { mem0Source.info } ?: "MCP 设置里加个名字含 Mem0 的服务器") },
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
    mcpRecallResults: Map<String, String>,
    recallDone: Boolean,
    recallRunning: Boolean,
    recallDiag: String,
    onRunRecall: () -> Unit,
) {
    CardGroup(
        modifier = Modifier.padding(horizontal = 8.dp),
        title = { Text("召回体检（输入一句话，看谁能召回）") },
    ) {
        item(
            leadingContent = { Icon(HugeIcons.Pulse01, null) },
            headlineContent = { Text("体检进度", color = MaterialTheme.colorScheme.primary) },
            supportingContent = { Text(recallDiag) },
        )
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
                    enabled = !recallRunning && recallQuery.isNotBlank(),
                ) {
                    Text(if (recallRunning) "体检中…" else "体检")
                }
            }
        }
        if (recallDone) {
            if (recallResults.isEmpty() && mcpRecallResults.isEmpty()) {
                item(
                    leadingContent = { Icon(HugeIcons.GlobalSearch, null) },
                    headlineContent = { Text("体检完成") },
                    supportingContent = { Text("没有可体检的记忆源（未配置外置库/OB/Mem0）") },
                )
            } else {
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
                mcpRecallResults.forEach { (name, text) ->
                    val noResult = isNoResultText(text)
                    item(
                        leadingContent = { Icon(HugeIcons.GlobalSearch, null) },
                        headlineContent = { Text("$name · MCP 召回${if (noResult) "（未匹配）" else ""}") },
                        supportingContent = {
                            Text(
                                text = if (noResult) {
                                    "❌ 没搜到相关内容（OB 搜空会发正式声明）\n" + text.take(150)
                                } else {
                                    text.take(150)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
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
        if (externalConfigs.isEmpty()) {
            item(
                leadingContent = { Icon(HugeIcons.Database02, null) },
                headlineContent = { Text("没有启用的外置记忆库") },
            )
        }
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
    }
}

/** 记忆列表头（选库 + 条数） */
@Composable
private fun MemoryListHeader(
    externalConfigs: List<ExternalMemory>,
    selectedConfigId: Uuid?,
    onSelect: (Uuid) -> Unit,
    messagesLoading: Boolean,
    messageCount: Int,
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
            leadingContent = { Icon(HugeIcons.Pulse01, null) },
            headlineContent = { Text(if (messagesLoading) "加载中…" else "共 $messageCount 条") },
        )
    }
}

/** OB 记忆目录（全部记忆桶） */
@Composable
private fun ObCatalogSection(
    obServer: McpServerConfig?,
    catalogText: String,
    catalogLoading: Boolean,
    onLoadCatalog: () -> Unit,
) {
    CardGroup(
        modifier = Modifier.padding(horizontal = 8.dp),
        title = { Text("OB 记忆目录（全部记忆桶）") },
    ) {
        item(
            leadingContent = { Icon(HugeIcons.Pulse01, null) },
            headlineContent = { Text(if (obServer != null) "OB 全部记忆桶" else "OB 未配置") },
            supportingContent = { Text(if (obServer != null) "点「加载」列出全部桶（名称|域|重要度）" else "MCP 设置里加个名字含 OB 的服务器") },
            trailingContent = {
                Button(
                    onClick = onLoadCatalog,
                    enabled = obServer != null && !catalogLoading,
                ) {
                    Text(if (catalogLoading) "加载中…" else "加载")
                }
            },
        )
        if (catalogText.isNotBlank()) {
            item(
                leadingContent = { Icon(HugeIcons.GlobalSearch, null) },
                headlineContent = { Text(catalogText) },
            )
        }
    }
}

/** Mem0 全部记忆 */
@Composable
private fun Mem0ListSection(
    mem0Server: McpServerConfig?,
    listText: String,
    listLoading: Boolean,
    onLoadList: () -> Unit,
) {
    CardGroup(
        modifier = Modifier.padding(horizontal = 8.dp),
        title = { Text("Mem0 全部记忆") },
    ) {
        item(
            leadingContent = { Icon(HugeIcons.Database02, null) },
            headlineContent = { Text(if (mem0Server != null) "Mem0 全部记忆（无前端，在这里看）" else "Mem0 未配置") },
            supportingContent = { Text(if (mem0Server != null) "点「加载」列出全部记忆（需要服务器已加 list_memories 工具）" else "MCP 设置里加个名字含 Mem0 的服务器") },
            trailingContent = {
                Button(
                    onClick = onLoadList,
                    enabled = mem0Server != null && !listLoading,
                ) {
                    Text(if (listLoading) "加载中…" else "加载")
                }
            },
        )
        if (listText.isNotBlank()) {
            item(
                leadingContent = { Icon(HugeIcons.GlobalSearch, null) },
                headlineContent = { Text(listText) },
            )
        }
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
