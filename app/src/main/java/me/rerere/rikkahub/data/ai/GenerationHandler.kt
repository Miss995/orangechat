/* 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai
 
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.core.merge
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.MessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.ai.tools.buildFetchChatSourcesTool
import me.rerere.rikkahub.data.ai.tools.buildMemoryTools
import me.rerere.rikkahub.data.ai.tools.buildReadAppLogsTool
import me.rerere.rikkahub.data.ai.tools.buildWriteFilesTool
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.service.MemoryBankService
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.utils.applyPlaceholders
import java.util.Locale
import kotlin.time.Clock
 
private const val TAG = "GenerationHandler"
 
// 流式生成时往 UI 推送消息更新的最小间隔。
// AI 的 SSE 增量可能每秒到达几十次，如果每次都原样同步到 UI 的 StateFlow，
// 会导致 Compose 高频重组（Markdown 全量重解析、代码高亮重新分词、
// animateContentSize 的尺寸补间动画被不断打断重启），表现为打字机效果的"抖动/掉帧"。
// 这里把推送频率限制在这个间隔以内，肉眼完全感知不到延迟，但能大幅降低重组频率。
// 2026-08-18 性能优化：50ms -> 100ms（重组频率减半，配合 ChatMessage 生成中纯文本渲染，
// 修复长对话 + 超长回复时流式更新全量重解析导致的主线程卡顿/整页滑动掉帧）。
private const val STREAM_UI_THROTTLE_MS = 100L

// 外置库召回单次超时（Supabase 响应慢时放宽到 15 秒，减少超时空手）
private const val EXTERNAL_RECALL_TIMEOUT_MS = 15_000L
 
@Serializable
sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>
    ) : GenerationChunk
}
 
class GenerationHandler(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val memoryRepo: MemoryRepository,
    private val conversationRepo: ConversationRepository,
    private val aiLoggingManager: AILoggingManager,
    private val memoryBankService: MemoryBankService,
    private var lastObBreathMs: Long = 0L,
    private var lastExternalRecallMs: Long = 0L,
) {
    fun generateText(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
        assistant: Assistant,
        memories: List<AssistantMemory>? = null,
        tools: List<Tool> = emptyList(),
        maxSteps: Int = 256,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        workspaceCwd: String? = null,
        pluginPromptInjections: List<String> = emptyList(),
        conversationId: String? = null,
    ): Flow<GenerationChunk> = flow {
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = providerManager.getProviderByType(provider)
 
        var messages: List<UIMessage> = messages

        // 召回门控状态：一次生成流程（多步 agent 循环）只触发一次记忆召回，
        // 二次请求不再重复判断/注入，避免工具步骤反复召回破坏前缀稳定。
        var recallGatePassed = false
 
        for (stepIndex in 0 until maxSteps) {
            Log.i(TAG, "streamText: start step #$stepIndex (${model.id})")
 
            val toolsInternal = buildList {
                Log.i(TAG, "generateInternal: build tools($assistant)")
                // 查日志工具（2026-08-21：宝 8-19 待办落地——排查静默失败用，如 fetchRecentEvents 不注入；始终注入，排查随时可用）
                add(buildReadAppLogsTool())
                if (assistant?.enableMemory == true) {
                    val memoryAssistantId = if (assistant.useGlobalMemory) {
                        MemoryRepository.GLOBAL_MEMORY_ID
                    } else {
                        assistant.id.toString()
                    }
                    buildMemoryTools(
                        json = json,
                        onCreation = { content ->
                            memoryRepo.addMemory(memoryAssistantId, content)
                        },
                        onUpdate = { id, content ->
                            memoryRepo.updateContent(id, content)
                        },
                        onDelete = { id ->
                            memoryRepo.deleteMemory(id)
                        }
                    ).let(this::addAll)
                }
                // 文件写入工具 - AI可直接将文件内容写入设备或打包ZIP（缓存持久化到 App files 目录）
                add(buildWriteFilesTool(context, conversationId))
                // 查原文工具（主动版）：外置记忆库事件召回后，模型可按 日期+消息号 主动拉原始聊天记录深挖
                val extConfigsForChatSources = settings.externalMemories.filter { it.enabled && it.id in assistant.externalMemoryIds }
                if (extConfigsForChatSources.isNotEmpty()) {
                    add(buildFetchChatSourcesTool { date, ids ->
                        val service = me.rerere.rikkahub.data.service.ExternalMemoryService(extConfigsForChatSources.first())
                        val messages = service.queryMessagesByDate(date).getOrDefault(emptyList()).sortedBy { it.createdAt }
                        val pick = if (ids.isEmpty()) messages.take(30) else ids.mapNotNull { id ->
                            messages.getOrNull(id - 1) // 消息号=当天1-based序号（与 fetchEventSources 同口径）
                        }
                        pick.map { msg ->
                            val prefix = when (msg.role) {
                                "assistant" -> "AI"
                                "user" -> "用户"
                                else -> msg.role
                            }
                            "[$prefix] ${msg.content}"
                        }
                    })
                }
                addAll(tools)
            }
 
            // Check if we have tool calls ready to continue after user interaction.
            val pendingTools = messages.lastOrNull()?.getTools()?.filter {
                it.canResumeExecution
            } ?: emptyList()
 
            val toolsToProcess: List<UIMessagePart.Tool>
 
            // Skip generation if we have approved/denied tool calls to handle
            if (pendingTools.isEmpty()) {
                generateInternal(
                    assistant = assistant,
                    settings = settings,
                    messages = messages,
                    pluginPromptInjections = pluginPromptInjections,
                    onUpdateMessages = {
                        messages = it.transforms(
                            transformers = outputTransformers,
                            context = context,
                            model = model,
                            assistant = assistant,
                            settings = settings
                        )
                        emit(
                            GenerationChunk.Messages(
                                messages.visualTransforms(
                                    transformers = outputTransformers,
                                    context = context,
                                    model = model,
                                    assistant = assistant,
                                    settings = settings
                                )
                            )
                        )
                    },
                    transformers = inputTransformers,
                    model = model,
                    providerImpl = providerImpl,
                    provider = provider,
                    tools = toolsInternal,
                    memories = memories ?: emptyList(),
                    stream = assistant.streamOutput,
                    processingStatus = processingStatus,
                    conversationSystemPrompt = conversationSystemPrompt,
                    workspaceCwd = workspaceCwd,
                    recallGate = recallGatePassed,
                    onRecallGatePassed = { recallGatePassed = true },
                )
                messages = messages.visualTransforms(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.onGenerationFinish(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.slice(0 until messages.lastIndex) + messages.last().copy(
                    finishedAt = Clock.System.now()
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                )
                emit(GenerationChunk.Messages(messages))
 
                val tools = messages.last().getTools().filter { !it.isExecuted }
                if (tools.isEmpty()) {
                    // no tool calls, break
                    break
                }
 
                // Check for tools that need approval
                var hasPendingApproval = false
                val updatedTools = tools.map { tool ->
                    val toolDef = toolsInternal.find { it.name == tool.toolName }
                    when {
                        // Auto-approve everything (lazy mode) -> skip approval
                        settings.autoApproveAllTools -> tool

                        // Tool needs approval (or global force confirm) and state is Auto -> set to Pending
                        (settings.forceConfirmToolCalls || toolDef?.needsApproval == true) && tool.approvalState is ToolApprovalState.Auto -> {
                            hasPendingApproval = true
                            tool.copy(approvalState = ToolApprovalState.Pending)
                        }
                        // State is Pending -> keep waiting
                        tool.approvalState is ToolApprovalState.Pending -> {
                            hasPendingApproval = true
                            tool
                        }

                        else -> tool
                    }
                }
 
                // If any tools were updated to Pending, update the message and break
                if (updatedTools != tools) {
                    val lastMessage = messages.last()
                    val updatedParts = lastMessage.parts.map { part ->
                        if (part is UIMessagePart.Tool) {
                            updatedTools.find { it.toolCallId == part.toolCallId } ?: part
                        } else {
                            part
                        }
                    }
                    messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
                    emit(GenerationChunk.Messages(messages))
                }
 
                // If there are pending approvals, break and wait for user
                if (hasPendingApproval) {
                    Log.i(TAG, "generateText: waiting for tool approval")
                    break
                }
 
                toolsToProcess = updatedTools
            } else {
                // Resuming after user interaction - use the resumable tools directly.
                Log.i(TAG, "generateText: resuming with ${pendingTools.size} resumable tools")
                toolsToProcess = messages.last().getTools().filter { it.canResumeExecution }
            }
 
            // Handle tools (execute approved tools, handle denied tools)
            val executedTools = arrayListOf<UIMessagePart.Tool>()
            toolsToProcess.forEach { tool ->
                when (tool.approvalState) {
                    is ToolApprovalState.Denied -> {
                        // Tool was denied by user
                        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                        executedTools += tool.copy(
                            output = listOf(
                                UIMessagePart.Text(
                                    json.encodeToString(
                                        buildJsonObject {
                                            put(
                                                "error",
                                                JsonPrimitive("Tool execution denied by user. Reason: ${reason.ifBlank { "No reason provided" }}")
                                            )
                                        }
                                    )
                                )
                            )
                        )
                    }

                    is ToolApprovalState.Answered -> {
                        // Tool was answered by user (e.g., ask_user tool)
                        val answer = (tool.approvalState as ToolApprovalState.Answered).answer
                        executedTools += tool.copy(
                            output = listOf(
                                UIMessagePart.Text(answer)
                            )
                        )
                    }

                    is ToolApprovalState.Pending -> {
                        // Should not reach here, but just in case
                    }

                    else -> {
                        // Auto or Approved - execute the tool
                        runCatching {
                            val toolDef = toolsInternal.find { toolDef -> toolDef.name == tool.toolName }
                                ?: error("Tool ${tool.toolName} not found")
                            val args = runCatching {
                                json.parseToJsonElement(tool.input.ifBlank { "{}" })
                            }.getOrElse {
                                error("Invalid tool arguments JSON for ${tool.toolName}: ${it.message}")
                            }
                            Log.i(TAG, "generateText: executing tool ${toolDef.name} with args: $args")
                            val result = toolDef.execute(args)
                            executedTools += tool.copy(output = result)
                        }.onFailure {
                            it.printStackTrace()
                            executedTools += tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(
                                            buildJsonObject {
                                                put(
                                                    "error",
                                                    JsonPrimitive(buildString {
                                                        append("[${it.javaClass.name}] ${it.message}")
                                                        append("\n${it.stackTraceToString()}")
                                                    })
                                                )
                                            }
                                        )
                                    )
                                )
                            )
                        }
                    }
                }
            }
 
            if (executedTools.isEmpty()) {
                // No results to add (all tools were pending)
                break
            }
 
            // Update last message with executed tools (NOT create TOOL message)
            val lastMessage = messages.last()
            val updatedParts = lastMessage.parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    executedTools.find { it.toolCallId == part.toolCallId } ?: part
                } else part
            }
            messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
            emit(
                GenerationChunk.Messages(
                    messages.transforms(
                        transformers = outputTransformers,
                        context = context,
                        model = model,
                        assistant = assistant,
                        settings = settings
                    )
                )
            )
        }
 
    }.throttleLatest(STREAM_UI_THROTTLE_MS)
        .flowOn(Dispatchers.IO)
 
    private suspend fun generateInternal(
        assistant: Assistant,
        settings: Settings,
        messages: List<UIMessage>,
        pluginPromptInjections: List<String> = emptyList(),
        onUpdateMessages: suspend (List<UIMessage>) -> Unit,
        transformers: List<MessageTransformer>,
        model: Model,
        providerImpl: Provider<ProviderSetting>,
        provider: ProviderSetting,
        tools: List<Tool>,
        memories: List<AssistantMemory>,
        stream: Boolean,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        workspaceCwd: String? = null,
        recallGate: Boolean = false,
        onRecallGatePassed: () -> Unit = {},
    ) {
        // ===== 最近事件（实时层，2026-08-21 宝的记忆实时化方案定稿）=====
        // 服务器 incremental_listener.py（每满 60 条总结 30 条，滞后半拍）让今天的事件也实时入库——
        // 这里注入最近 3 天事件：今天全文、昨天前天 title。固定位置 + 稳定排序（source_date ASC + id ASC）
        // = 前缀稳定（保 DS 缓存命中）。本地 15 分钟缓存：同 15 分钟内前缀稳定 + 防 Supabase 慢/挂。
        var recentEventsText: String? = null
        try {
            val recentConfigs = settings.externalMemories.filter { it.enabled && it.id in assistant.externalMemoryIds }
            if (recentConfigs.isNotEmpty()) {
                val prefs = context.getSharedPreferences("recent_events_cache", Context.MODE_PRIVATE)
                val cacheKey = "recent_events_${assistant.id}"
                val nowMs = System.currentTimeMillis()
                recentEventsText = prefs.getString(cacheKey, null)
                val cacheTs = prefs.getLong("${cacheKey}_ts", 0L)
                if (recentEventsText == null || nowMs - cacheTs > 15 * 60 * 1000L) {
                    val service = me.rerere.rikkahub.data.service.ExternalMemoryService(recentConfigs.first())
                    val events = service.fetchRecentEvents(assistant.id.toString(), days = 3).getOrDefault(emptyList())
                    if (events.isNotEmpty()) {
                        val today = java.time.LocalDate.now().toString()
                        val yesterday = java.time.LocalDate.now().minusDays(1).toString()
                        val sb = StringBuilder()
                        // 分天注入：今天全文（≤50 条）、昨天（≤30 条）、前天及更早（≤20 条）只 title
                        events.groupBy { it.sourceDate }.toSortedMap().forEach { (date, list) ->
                            val cap = when (date) {
                                today -> 50
                                yesterday -> 30
                                else -> 20
                            }
                            sb.appendLine("【$date】")
                            list.take(cap).forEach { e ->
                                if (date == today) {
                                    sb.appendLine("${e.title}：${e.content}")
                                } else {
                                    sb.appendLine(e.title)
                                }
                            }
                        }
                        recentEventsText = sb.toString()
                        prefs.edit().putString(cacheKey, recentEventsText).putLong("${cacheKey}_ts", nowMs).apply()
                        Log.i(TAG, "Recent events [supabase] refreshed (${events.size} events, ${recentEventsText.length} chars)")
                        AppLogBuffer.log(TAG, "Recent events refreshed: ${events.size} events, ${recentEventsText.length} chars")
                    } else {
                        // 拉不到：保留旧缓存（recentEventsText 已是缓存值）
                        Log.w(TAG, "Recent events fetch empty, keep cache")
                        AppLogBuffer.log(TAG, "Recent events fetch EMPTY (assistantId=${assistant.id})")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Recent events load failed", e)
            AppLogBuffer.log(TAG, "Recent events load failed: ${e.javaClass.simpleName}: ${e.message}")
        }

        val internalMessages = buildList {
            val system = buildString {
                val effectiveSystemPrompt =
                    if (assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
                        conversationSystemPrompt
                    } else {
                        assistant.systemPrompt
                    }
                if (effectiveSystemPrompt.isNotBlank()) {
                    append(effectiveSystemPrompt)
                }
 
                // 代码文件命名和ZIP打包功能说明（稳定前缀，置于动态内容之前以提升前缀缓存命中率）
                appendLine()
                append(buildCodeBlockPrompt())
 
                // 工具prompt（稳定前缀）
                tools.forEach { tool ->
                    appendLine()
                    append(tool.systemPrompt(model, messages))
                }
 
                // 记忆（动态内容统一放到稳定前缀之后）
                if (assistant.enableMemory) {
                    appendLine()
                    append(buildMemoryPrompt(memories = memories))
                }

                // 日记摘要（稳定前缀：每天一篇，从外置记忆库拉最新日记摘要，单独成段——不随搜索门控走）
                // 本地按天缓存：同一天只调一次 Supabase，之后一整天直接用缓存——前缀稳定（保 DS 缓存命中率）+ 防 Supabase 慢/挂
                try {
                    val diaryConfigs = settings.externalMemories.filter {
                        it.enabled && it.id in assistant.externalMemoryIds
                    }
                    if (diaryConfigs.isNotEmpty()) {
                        val prefs = context.getSharedPreferences("diary_cache", Context.MODE_PRIVATE)
                        // 日记凌晨 4 点更新，缓存 key 按「凌晨 4 点为界」切日：
                        // 0~4 点用昨天日期（读昨天 4 点生成的日记=最新可用），4 点后用今天日期（首次 miss 拉今天新日记）→ 一整天跟上进度
                        val now = java.util.Date()
                        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(
                            if (java.util.Calendar.getInstance().apply { time = now }
                                    .get(java.util.Calendar.HOUR_OF_DAY) < 4
                            ) {
                                java.util.Calendar.getInstance().apply { time = now; add(java.util.Calendar.DAY_OF_YEAR, -1) }.time
                            } else {
                                now
                            }
                        )
                        val cacheKey = "diary_${assistant.id}_$today"
                        var diaryText = prefs.getString(cacheKey, null)
                        var source = "cache"
                        if (diaryText == null) {
                            source = "supabase"
                            val service = me.rerere.rikkahub.data.service.ExternalMemoryService(diaryConfigs.first())
                            val latestDiaries = service.queryLatestSummaries(
                                assistantId = assistant.id.toString(),
                                limit = 1,
                            ).getOrDefault(emptyList())
                            if (latestDiaries.isNotEmpty()) {
                                diaryText = latestDiaries.joinToString("\n") { it.content }
                                prefs.edit().putString(cacheKey, diaryText).apply()
                            } else {
                                // 拉不到：回退最近一次缓存（昨天的），保证日记段有内容（前缀稳定）
                                diaryText = prefs.all.entries
                                    .filter { it.key.startsWith("diary_${assistant.id}_") }
                                    .maxByOrNull { it.key }?.value as? String
                                if (diaryText != null) source = "fallback-cache"
                            }
                        }
                        if (!diaryText.isNullOrBlank()) {
                            Log.i(TAG, "Diary [$source] injected ($cacheKey)")
                            appendLine()
                            appendLine("## 日记")
                            append(diaryText)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Diary summary load failed", e)
                }

                // 最近事件（实时层，2026-08-21 宝的方案）：最近 3 天事件——增量总结后今天也实时有；
                // 固定位置 + 稳定排序（source_date ASC + id ASC）= 前缀稳定（保 DS 缓存命中）
                if (!recentEventsText.isNullOrBlank()) {
                    appendLine()
                    appendLine("## 最近事件（最近 3 天）")
                    append(recentEventsText)
                }
 
                // 外置记忆库事件召回（主召方案：唯一自动召回通道——门控：仅搜索意图时触发；日记摘要已独立为稳定前缀）
                // 主召说明（2026-08-17）：OB breath_search / Mem0 search_memory 自动注入已停用（数据保留归档），
                // 外置库事件召回升格为主召回。如需恢复 OB/Mem0 自动注入，见 git 历史 9b7a6ce8 之前的代码。
                try {
                    val externalMemoryConfigs = settings.externalMemories.filter {
                        it.enabled && it.id in assistant.externalMemoryIds
                    }
                    if (externalMemoryConfigs.isNotEmpty()) {
                        val lastUserMessage = messages.lastOrNull { it.role == MessageRole.USER }
                        // 2026-08-18 大修：长句截断 200 -> 500（搜索意图词藏在长句后半段时不再被切掉）
                        val queryText = lastUserMessage?.toText()?.take(500)?.trim() ?: ""
                        // 【门控升级 2026-08-19】词表预筛 + 硅基免费 LLM 判断（MemoryIntentJudge.needsRecall，替代纯词表 hasSearchIntent）
                        // 强回忆词直接过（省延迟）；其余调硅基免费模型（Qwen/Qwen2-7B-Instruct，零成本）判断"需不需要召回记忆"；
                        // LLM 不可用时回退旧词表（不哑火）。judgeProvider 取外置库 embedding provider（硅基）。
                        val judgeProvider = externalMemoryConfigs.firstNotNullOfOrNull { config ->
                            config.embeddingModelId?.let { settings.findModelById(it) }?.findProvider(settings.providers)
                        }
                        if (!recallGate && MemoryIntentJudge.needsRecall(queryText, judgeProvider)) {
                            onRecallGatePassed()
                            // 时间定位：从用户消息解析时间范围（date_from/date_to），传给事件召回/OB 搜索
                            val timeRange = TimeRangeParser.parse(queryText)
                            // 并发检索所有外置记忆库配置，每个配置最多 15 秒超时
                            val allRecalled = coroutineScope {
                                externalMemoryConfigs.map { config ->
                                    async {
                                        withTimeoutOrNull(EXTERNAL_RECALL_TIMEOUT_MS) {
                                            runCatching {
                                                val service = me.rerere.rikkahub.data.service.ExternalMemoryService(config)
                                                val recalled = mutableListOf<String>()

                                // 事件级召回（搜索通道）：向量搜 memory_events -> 命中事件 -> 展开原文（克制 take(4)，同日同段去重）
                                if (queryText.isNotBlank()) {
                                    val embeddingModel = config.embeddingModelId?.let { settings.findModelById(it) }
                                    val embeddingProvider = embeddingModel?.findProvider(settings.providers)
                                    if (embeddingProvider != null) {
                                        val embeddingProviderImpl = providerManager.getProviderByType(embeddingProvider)
                                        runCatching {
                                            val embedResult = embeddingProviderImpl.generateEmbedding(
                                                providerSetting = embeddingProvider,
                                                params = EmbeddingGenerationParams(
                                                    model = embeddingModel,
                                                    input = listOf(queryText),
                                                )
                                            )
                                            val queryEmbedding = embedResult.embeddings.firstOrNull()
                                            if (queryEmbedding != null) {
                                                val recalledEvents = service.vectorRecallEvents(
                                                    queryEmbedding = queryEmbedding,
                                                    assistantId = assistant.id.toString(),
                                                    count = config.recallCount,
                                                    dateFrom = timeRange.dateFrom,
                                                    dateTo = timeRange.dateTo,
                                                ).getOrDefault(emptyList())
                                                val seenMsg = mutableSetOf<String>()
                                                recalledEvents.forEach { event ->
                                                    val sources = service.fetchEventSources(event).take(4)
                                                    val uniqueSources = sources.filter { seenMsg.add(it) }
                                                    val sb = StringBuilder()
                                                    sb.append("【${event.title}】${event.content}")
                                                    if (event.sourceDate.isNotBlank()) {
                                                        sb.append("（${event.sourceDate}）")
                                                    }
                                                    if (uniqueSources.isNotEmpty()) {
                                                        sb.append("\n原文：").append(uniqueSources.joinToString(" | "))
                                                    }
                                                    recalled.add(sb.toString())
                                                }
                                                Log.d(TAG, "Event recall ${recalledEvents.size} events from ${config.name} (timeRange: ${timeRange.dateFrom}~${timeRange.dateTo})")
                                            }
                                        }.onFailure {
                                            Log.w(TAG, "Event recall failed for ${config.name}", it)
                                        }
                                    }
                                }
                                                recalled
                                            }.onFailure {
                                                Log.w(TAG, "External memory recall failed for ${config.name}", it)
                                            }.getOrNull()
                                        } ?: run {
                                            Log.w(TAG, "External memory recall timed out for ${config.name}")
                                            null
                                        }
                                    }
                                }.awaitAll()
                                    .filterNotNull()
                                    .flatten()
                            }
                            if (allRecalled.isNotEmpty()) {
                                appendLine()
                                appendLine("## 外置记忆库")
                                allRecalled.reversed().forEachIndexed { index, memory ->
                                    appendLine("${index + 1}. ${memory}")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "External memory recall failed", e)
                }
 
                // 插件提示词注入（动态）
                if (pluginPromptInjections.isNotEmpty()) {
                    pluginPromptInjections.forEach { injection ->
                        appendLine()
                        appendLine()
                        append(injection)
                    }
                }
 
                // 允许跳过回复
                if (assistant.allowSkipReply) {
                    appendLine()
                    appendLine()
                    appendLine("## Skip Reply")
                    appendLine("If you determine that no reply is needed (e.g., the user's message doesn't require a response, or you have nothing meaningful to add), you may reply with exactly `[SKIP]` (without any other text). This message will be hidden from the user. Use this sparingly and only when truly appropriate.")
                }

                // 屏幕跳转能力（AI总是可以跳转，不需要开关）
                if (true) {
                    appendLine()
                    appendLine()
                    appendLine("## 屏幕跳转能力")
                    appendLine("你可以在回复末尾追加 [JUMP] 标记（单独一行）来把聊天界面拉到用户屏幕最前面。")
                    appendLine("适用场景：")
                    appendLine("- 用户说要去别的应用，你觉得需要把用户拉回来时")
                    appendLine("- 你觉得接下来的内容需要用户立即看到时")
                    appendLine("不适用场景：")
                    appendLine("- 一般闲聊不需要跳转")
                    appendLine("- 用户正在跟你正常对话时不需要跳转")
                    appendLine("[JUMP] 标记不会展示给用户，仅用于触发屏幕跳转。")
                }
 
                // 分气泡: 告知模型它自己能控制消息如何被拆成多个气泡
                if (assistant.splitBubbleByLine) {
                    appendLine()
                    appendLine()
                    appendLine("## Message Bubbles")
                    appendLine("Your reply will be automatically split into separate chat bubbles at every line break (\\n) you write, similar to how a person sends several short texts in a row instead of one long message. You are fully in control of this: write a line break whenever you want the previous thought/sentence to appear as its own bubble, and keep things on the same line when they belong together. Do not insert blank lines purely for spacing — every line break becomes a new bubble, so use them intentionally. Exception: line breaks inside fenced code blocks (```) and Markdown tables are preserved as-is and will NOT create new bubbles, since those must stay intact as a single block.")
                }
 
            }
            if (system.isNotBlank()) add(UIMessage.system(prompt = system))
            addAll(messages.limitContext(assistant.contextMessageSize, assistant.contextGroupSize))
            // 实时时间戳（宝的方案 2026-08-18）：不动原机制（长时间离开才注入一次的时间注入保留），
            // 在聊天消息末尾追加单独一条实时时间——放在最后一条 = 不破坏 DS 前缀缓存
            // （前缀全部命中，只有这条动态尾部变化），模型每次生成都能看到真实当前时间，
            // 不再误用冻住的注入时间回答"现在几点"。
            // 2026-08-19 修复（宝实测没生效）：system → user 角色！DeepSeek 是 OpenAI 兼容 API，
            // system 消息通常要求在最前，放末尾会被忽略/拒绝 → 改成 user 角色放最后完全合法
            // （最后一条=user），且 hasSearchIntent 的 lastUserMessage 取自 UI messages（不受影响），
            // 召回逻辑安全；请求编辑模式也能正常显示这条。
            // 2026-08-22 归档状态条件注入（宝的方案⑤）：【当前时间】尾巴"（设备本地时间...）"去掉（宝说没啥用）；
            // 归档状态平时不注入（零上下文开销），只有异常（上次成功归档 ≥2 天前 / 最近一次失败）才附加 ⚠️ 一行，
            // 让橘仔每轮生成都看得到"归档断了"——机制自己说话，不靠记性。10 分钟缓存防 Supabase 慢/挂。
            val archiveWarn = try {
                val prefs = context.getSharedPreferences("archive_status_cache", Context.MODE_PRIVATE)
                val nowMs = System.currentTimeMillis()
                val cacheTs = prefs.getLong("archive_warn_ts", 0L)
                if (nowMs - cacheTs > 10 * 60 * 1000L) {
                    val warn = runCatching {
                        val configs = settings.externalMemories.filter { it.enabled && it.id in assistant.externalMemoryIds }
                        if (configs.isEmpty()) null
                        else {
                            val service = me.rerere.rikkahub.data.service.ExternalMemoryService(configs.first())
                            val status = service.queryLatestArchiveStatus().getOrNull()
                            if (status == null) null
                            else {
                                val today = java.time.LocalDate.now()
                                val daysSince = runCatching {
                                    java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(status.date), today)
                                }.getOrDefault(-1L)
                                when {
                                    !status.success -> "【归档状态】⚠️ ${status.date} 归档失败：${status.error.take(80)}"
                                    daysSince >= 2 -> "【归档状态】⚠️ 归档中断：上次成功 = ${status.date}（${daysSince} 天前）"
                                    else -> null
                                }
                            }
                        }
                    }.getOrNull()
                    prefs.edit().putString("archive_warn", warn).putLong("archive_warn_ts", nowMs).apply()
                    warn
                } else {
                    prefs.getString("archive_warn", null)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Archive status warn failed", e)
                null
            }
            add(
                UIMessage.user(
                    buildString {
                        append("【当前时间】")
                        append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()))
                        if (!archiveWarn.isNullOrBlank()) {
                            append("\n").append(archiveWarn)
                        }
                    }
                )
            )
        }.transforms(
            transformers = transformers,
            context = context,
            model = model,
            assistant = assistant,
            settings = settings,
            processingStatus = processingStatus,
            workspaceCwd = workspaceCwd,
        )

        // === 请求编辑模式：发送前拦截，交给用户手动控制上下文 ===
        val finalMessages: List<UIMessage>
        var effectiveTools = tools
        if (settings.requestEditMode && internalMessages.isNotEmpty()) {
            val editData = RequestEditController.toEditData(internalMessages, tools.map { it.name })
            val edited = RequestEditController.waitForEdit(editData)
                ?: throw CancellationException("Request edit cancelled by user")
            finalMessages = RequestEditController.toMessages(edited, internalMessages)
            // 按用户勾选过滤工具：只注入勾选的（默认全选；全不勾 = 本轮不带工具，省 token）
            val enabledNames = edited.tools.filter { it.enabled }.map { it.name }.toSet()
            effectiveTools = if (enabledNames.isEmpty()) emptyList() else tools.filter { it.name in enabledNames }
            Log.i(TAG, "requestEditMode: user edited request, ${internalMessages.size} -> ${finalMessages.size} messages, tools ${tools.size} -> ${effectiveTools.size}")
        } else {
            finalMessages = internalMessages
        }
 
        var messages: List<UIMessage> = messages
        val params = TextGenerationParams(
            model = model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = assistant.maxTokens,
            tools = effectiveTools,
            reasoningLevel = assistant.reasoningLevel,
            customHeaders = buildList {
                addAll(assistant.customHeaders)
                addAll(model.customHeaders)
            },
            customBody = buildList {
                addAll(assistant.customBodies)
                addAll(model.customBodies)
            }
        )
        if (stream) {
            aiLoggingManager.addLog(
                AILogging.Generation(
                    params = params,
                    messages = finalMessages,
                    providerSetting = provider,
                    stream = true
                )
            )
            providerImpl.streamText(
                providerSetting = provider,
                messages = finalMessages,
                params = params
            ).collect {
                messages = messages.handleMessageChunk(chunk = it, model = model)
                it.usage?.let { usage ->
                    messages = messages.mapIndexed { index, message ->
                        if (index == messages.lastIndex) {
                            message.copy(usage = message.usage.merge(usage))
                        } else {
                            message
                        }
                    }
                }
                onUpdateMessages(messages)
            }
        } else {
            aiLoggingManager.addLog(
                AILogging.Generation(
                    params = params,
                    messages = finalMessages,
                    providerSetting = provider,
                    stream = false
                )
            )
            val chunk = providerImpl.generateText(
                providerSetting = provider,
                messages = finalMessages,
                params = params,
            )
            messages = messages.handleMessageChunk(chunk = chunk, model = model)
            chunk.usage?.let { usage ->
                messages = messages.mapIndexed { index, message ->
                    if (index == messages.lastIndex) {
                        message.copy(
                            usage = message.usage.merge(usage)
                        )
                    } else {
                        message
                    }
                }
            }
            onUpdateMessages(messages)
        }
    }
 
    fun translateText(
        settings: Settings,
        sourceText: String,
        targetLanguage: Locale,
        onStreamUpdate: ((String) -> Unit)? = null
    ): Flow<String> = flow {
        val model = settings.providers.findModelById(settings.translateModeId)
            ?: error("Translation model not found")
        val provider = model.findProvider(settings.providers)
            ?: error("Translation provider not found")
 
        val providerHandler = providerManager.getProviderByType(provider)
 
        if (!ModelRegistry.QWEN_MT.match(model.modelId)) {
            // Use regular translation with prompt
            val prompt = settings.translatePrompt.applyPlaceholders(
                "source_text" to sourceText,
                "target_lang" to targetLanguage.toString(),
            )
 
            var messages = listOf(UIMessage.user(prompt))
            var translatedText = ""
 
            providerHandler.streamText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.translateThinkingBudget),
                ),
            ).collect { chunk ->
                messages = messages.handleMessageChunk(chunk)
                translatedText = messages.lastOrNull()?.toText() ?: ""
 
                if (translatedText.isNotBlank()) {
                    onStreamUpdate?.invoke(translatedText)
                    emit(translatedText)
                }
            }
        } else {
            // Use Qwen MT model with special translation options
            val messages = listOf(UIMessage.user(sourceText))
            val chunk = providerHandler.generateText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    temperature = 0.3f,
                    topP = 0.95f,
                    customBody = listOf(
                        CustomBody(
                            key = "translation_options",
                            value = buildJsonObject {
                                put("source_lang", JsonPrimitive("auto"))
                                put(
                                    "target_lang",
                                    JsonPrimitive(targetLanguage.getDisplayLanguage(Locale.ENGLISH))
                                )
                            }
                        )
                    )
                ),
            )
            val translatedText = chunk.choices.firstOrNull()?.message?.toText() ?: ""
 
            if (translatedText.isNotBlank()) {
                onStreamUpdate?.invoke(translatedText)
                emit(translatedText)
            }
        }
    }.flowOn(Dispatchers.IO)
}
 
/**
 * 把原始 Flow 的高频发射节流成"每 periodMillis 毫秒最多发一次最新值"。
 *
 * 实现方式：对上游调用 conflate()（只保留未被消费的最新一个值，中间值会被丢弃），
 * 然后在 collect 里每处理完一个值就 delay(periodMillis)。这样在上游快速连续发射时，
 * delay 期间产生的多个值会被 conflate 自动合并成"最新一个"，delay 结束后立刻拿到它；
 * 但由于用的是"发一个、等一段时间、再要下一个"的顺序结构，上游结束前最后一次真正的发射
 * 一定会被完整地 collect 到并 emit 出去，不会像 sample() 那样有丢失最终值的风险。
 *
 * 用于把 AI 流式输出的高频消息更新（可能每秒几十次）降频到 UI 友好的节奏，从源头
 * 消除打字机效果的抖动/掉帧，同时保证生成结束时 UI 一定能拿到完整的最终内容。
 */
private fun <T> Flow<T>.throttleLatest(periodMillis: Long): Flow<T> {
    val upstream = this
    return flow {
        upstream.conflate().collect { value ->
            emit(value)
            delay(periodMillis)
        }
    }
}
 
/**
 * 构建代码块提示 - 告知AI代码文件命名和ZIP打包功能
 */
private fun buildCodeBlockPrompt(): String = buildString {
    appendLine("## Code Block Rules (MUST FOLLOW)")
    appendLine()
    appendLine("1. **ALWAYS name code blocks with filenames**: You MUST use the actual filename as the code block language tag instead of just the language name. This is critical for proper file saving and syntax highlighting. Examples:")
    appendLine("   - ✅ Correct: ```MainActivity.kt instead of ```kotlin")
    appendLine("   - ✅ Correct: ```index.html instead of ```html")
    appendLine("   - ✅ Correct: ```styles.css instead of ```css")
    appendLine("   - ✅ Correct: ```package.json instead of ```json")
    appendLine("   - ✅ Correct: ```main.py instead of ```python")
    appendLine("   - ✅ Correct: ```App.vue instead of ```vue")
    appendLine("   - ❌ Wrong: ```kotlin, ```python, ```javascript (these don't provide filenames)")
    appendLine("   - For code without a specific filename, use a descriptive name like ```example.ts, ```helper.py")
    appendLine()
    appendLine("2. **ZIP Download via `write_files` tool**: Users can download code files as a ZIP ONLY when you call this tool.")
    appendLine("   - **Full write** (first time / new files): `{\"zip_name\":\"project.zip\",\"files\":[{\"name\":\"MainActivity.kt\",\"content\":\"...\"}]}`")
    appendLine("   - **Incremental edit** (saves tokens! For modifying existing files): `{\"zip_name\":\"project-v2.zip\",\"base_files\":\"previous\",\"edits\":[{\"name\":\"MainActivity.kt\",\"search\":\"old code\",\"replace\":\"new code\"}]}`")
    appendLine("   - The `edits` mode applies search/replace to the files from your previous `write_files` call. Files not mentioned in `edits` keep their cached content unchanged.")
    appendLine("   - Always use actual filenames (e.g. `MainActivity.kt`) as code block language tags, not just language names (e.g. `kotlin`).")
}

/**
 * 判断用户消息是否含"搜索记忆"意图——自动召回门控：
 * 含意图词才触发外置库事件召回（正常闲聊不触发，保持前缀稳定）。
 * 配合 recallGate：一次生成流程只判断/触发一次，二次请求不重复召回。
 *
 * 2026-08-18 大修特修（宝发现：长句/带时间的句子识别不了搜索意图）：
 * - 词表扩充：补时间词（昨天/前天/上周/上个月…）+ 口语回忆问句（聊了什么/说了什么/发生了什么…）
 * - 组合判断兜底：时间词 + 疑问词 同时出现 -> 大概率是回忆性提问（如"我们上周聊的那个是啥来着"）
 * - 修之前脱节：TimeRangeParser（9b7a6ce8）能解析时间，但门控词表没时间词
 *   -> 带时间的句子进不了门控、到不了解析器（宝实测："昨天咱俩互发文案"不触发搜索）
 *
 * 2026-08-19 门控升级：主判断已迁移到 MemoryIntentJudge（词表预筛 + 硅基免费 LLM 判断），
 * 本函数保留作为 MemoryIntentJudge 的回退参考/旧行为兜底（不再被主链路直接调用）。
 */
private fun hasSearchIntent(text: String): Boolean {
    if (text.isBlank()) return false
    val intentKeywords = listOf(
        // 记忆/搜索/回忆直接词
        "记得", "记不记得", "还记得", "忘了", "忘记", "没印象", "有印象", "想起来了",
        "上次", "之前", "以前", "说过", "提到", "提过", "聊过", "讲过",
        "什么时候", "哪一天", "哪天", "搜", "搜索", "找找", "查一下", "查查",
        "回忆", "回想", "回顾", "叫什么", "来着",
        // 口语回忆式问句（8-18 补）
        "聊了什么", "说了什么", "讲了什么", "发生了什么", "发生什么", "怎么回事",
        "怎么说的", "怎么聊的", "说过啥", "聊过啥", "啥来着", "啥事",
        // 时间词（8-18 补：配合 TimeRangeParser；单独出现也大概率是回忆性提问）
        "昨天", "前天", "昨晚", "今早", "今天早上", "上周", "上上周", "上个月", "今年", "去年",
        "前几天", "前段时间", "最近", "这几天", "那天", "当时", "那时候", "几点", "几号", "几月",
        "周一", "周二", "周三", "周四", "周五", "周六", "周日", "周天", "星期天", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六",
    )
    if (intentKeywords.any { text.contains(it) }) return true
    // 组合判断兜底：时间词 + 疑问词 同时出现 -> 大概率回忆性提问
    val timeWords = listOf("昨天", "前天", "昨晚", "上周", "上个月", "之前", "以前", "上次", "那天", "当时", "最近", "前几天", "刚刚", "刚才")
    val questionWords = listOf("什么", "怎么", "哪里", "哪儿", "哪个", "哪些", "谁", "吗", "呢", "啥", "回事", "为什么")
    return timeWords.any { text.contains(it) } && questionWords.any { text.contains(it) }
}
 