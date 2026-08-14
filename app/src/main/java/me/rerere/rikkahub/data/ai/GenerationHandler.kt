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
import me.rerere.rikkahub.data.ai.tools.buildMemoryTools
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
private const val STREAM_UI_THROTTLE_MS = 50L

// OB 记忆按需搜索（搜索型）：节流间隔与单次注入上限
private const val OB_BREATH_INTERVAL_MS = 5 * 60 * 1000L
private const val OB_BREATH_MAX_CHARS = 3000

// 外置记忆库召回节流：同一会话内每 5 分钟最多召回一次
// （工具循环的二次请求不再重复搜索，上下文里已有首次召回结果）
private const val EXTERNAL_RECALL_INTERVAL_MS = 5 * 60 * 1000L
// 外置库召回单次超时（Supabase 响应慢时放宽到 15 秒，减少超时空手）
private const val EXTERNAL_RECALL_TIMEOUT_MS = 15_000L
// 召回时排除最近 10 分钟内的消息（当前对话尾巴上下文里已有，避免把刚发的消息回显进外置记忆库）
private const val EXTERNAL_RECALL_SKIP_RECENT_MS = 10 * 60 * 1000L
 
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
 
        for (stepIndex in 0 until maxSteps) {
            Log.i(TAG, "streamText: start step #$stepIndex (${model.id})")
 
            val toolsInternal = buildList {
                Log.i(TAG, "generateInternal: build tools($assistant)")
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
    ) {
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
 
                // 外置记忆库召回（动态）——节流：5 分钟内不重复召回（工具循环二次请求跳过，上下文已有首次结果）
                val externalRecallNow = Clock.System.now().toEpochMilliseconds()
                if (externalRecallNow - lastExternalRecallMs > EXTERNAL_RECALL_INTERVAL_MS) {
                    try {
                        val externalMemoryConfigs = settings.externalMemories.filter {
                            it.enabled && it.id in assistant.externalMemoryIds
                        }
                        externalMemoryConfigs.forEach { config ->
                            Log.i(TAG, "ExternalMemory config: name=${config.name}, url=${config.supabaseUrl}, table=${config.tableName}, summaryTable=${config.summariesTableName}, embeddingModelId=${config.embeddingModelId}, autoSaveDiarySummary=${config.autoSaveDiarySummary}")
                        }
                        if (externalMemoryConfigs.isNotEmpty()) {
                            val lastUserMessage = messages.lastOrNull { it.role == MessageRole.USER }
                            val queryText = lastUserMessage?.toText()?.take(200)?.trim() ?: ""
                            // 并发检索所有外置记忆库配置，每个配置最多 15 秒超时
                            val allRecalled = coroutineScope {
                                externalMemoryConfigs.map { config ->
                                    async {
                                        withTimeoutOrNull(EXTERNAL_RECALL_TIMEOUT_MS) {
                                            runCatching {
                                                val service = me.rerere.rikkahub.data.service.ExternalMemoryService(config)
                                                val recalled = mutableListOf<String>()

                                // 1. 日记摘要：固定前一天一篇（最新1篇，不再混合召回）
                                val latestSummaries = service.queryLatestSummaries(
                                    assistantId = assistant.id.toString(),
                                    limit = 1,
                                ).getOrDefault(emptyList())
                                latestSummaries.forEach { summary ->
                                    recalled.add(summary.content)
                                }

                                // 2. 聊天记录：向量语义搜索优先（需 embedding 模型配置），结果不足时 ILIKE 兜底；排除最近10分钟避免回显当前对话
                                val seenMsg = mutableSetOf<String>()
                                val cutoffMs = System.currentTimeMillis() - EXTERNAL_RECALL_SKIP_RECENT_MS
                                val timeSdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                                fun isHistorical(msg: me.rerere.rikkahub.data.service.ExternalMemoryMessage): Boolean {
                                    // Supabase timestamptz 返回 ISO 8601（如 2026-06-29T05:58:38.057815+00:00 / ...Z），
                                    // 本地写入是 "yyyy-MM-dd HH:mm:ss"。归一化后再解析，避免解析失败把全部消息过滤成空召回。
                                    val createdMs = runCatching {
                                        val clean = msg.createdAt
                                            .replace('T', ' ')
                                            .substringBefore('+')
                                            .substringBefore('Z')
                                            .substringBefore('.')
                                        timeSdf.parse(clean)?.time ?: 0L
                                    }.getOrDefault(0L)
                                    return createdMs > 0 && createdMs < cutoffMs
                                }
                                if (queryText.isNotBlank()) {
                                    // 2a. 向量语义召回：queryText 生成 embedding -> 事件级召回（memory_events 带原文展开）+ 聊天记录 RPC 相似度搜索
                                    var vectorHits = emptyList<me.rerere.rikkahub.data.service.ExternalMemoryMessage>()
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
                                                // 2a-0. 事件级召回：命中事件 -> 展开原文证据（问题无原词也能按语义命中事件）
                                                val recalledEvents = service.vectorRecallEvents(
                                                    queryEmbedding = queryEmbedding,
                                                    assistantId = assistant.id.toString(),
                                                    count = config.recallCount,
                                                ).getOrDefault(emptyList())
                                                recalledEvents.forEach { event ->
                                                    val sources = service.fetchEventSources(event).take(4)
                                                    val sb = StringBuilder()
                                                    sb.append("【${event.title}】${event.content}")
                                                    if (event.sourceDate.isNotBlank()) {
                                                        sb.append("（${event.sourceDate}）")
                                                    }
                                                    if (sources.isNotEmpty()) {
                                                        sb.append("\n原文：").append(sources.joinToString(" | "))
                                                    }
                                                    recalled.add(sb.toString())
                                                }
                                                Log.d(TAG, "Vector recall ${recalledEvents.size} events from ${config.name}")
                                                // 2a-1. 聊天记录向量召回
                                                vectorHits = service.vectorRecallMessages(
                                                    queryEmbedding = queryEmbedding,
                                                    assistantId = assistant.id.toString(),
                                                    count = config.recallCount,
                                                ).getOrDefault(emptyList())
                                                    .filter { isHistorical(it) }
                                                Log.d(TAG, "Vector recall ${vectorHits.size} messages from ${config.name}")
                                            }
                                        }.onFailure {
                                            Log.w(TAG, "Vector recall failed for ${config.name}", it)
                                        }
                                    }
                                    vectorHits.forEach { msg ->
                                        val prefix = when (msg.role) {
                                            "assistant" -> "AI"
                                            "user" -> "用户"
                                            else -> msg.role
                                        }
                                        val line = "[$prefix] ${msg.content}"
                                        if (seenMsg.add(line)) recalled.add(line)
                                    }
                                    // 2b. 向量结果不足时 ILIKE 兜底（AI 拆词省钱版优先，失败退回中文 ngram 片段搜索）
                                    if (vectorHits.size < 3) {
                                        // AI 拆词：把整句交给便宜 LLM 拆成真关键词（如"速速给自己点一个大鸡腿吃"→"鸡腿"），
                                        // 避免 ngram 拆出"速速/速给"这类碎片关键词；只在向量不足时才调，省钱。
                                        val aiKeywords = runCatching {
                                            (embeddingProvider as? me.rerere.ai.provider.ProviderSetting.OpenAI)?.let { openAi ->
                                                me.rerere.rikkahub.data.service.QueryKeywordExtractor(
                                                    apiKey = openAi.apiKey,
                                                    apiBase = openAi.baseUrl,
                                                ).extract(queryText)
                                            }.orEmpty()
                                        }.getOrDefault(emptyList())
                                        val keywords = aiKeywords.ifEmpty {
                                            buildList {
                                                queryText.split(Regex("[\\s，。！？、；：,.!?;:（）()\"']+[）)]*"))
                                                    .filter { it.isNotBlank() }
                                                    .forEach { part ->
                                                        if (part.length <= 4) {
                                                            add(part)
                                                        } else {
                                                            var i = 0
                                                            while (i < part.length - 1) {
                                                                add(part.substring(i, minOf(i + 2, part.length)))
                                                                if (i + 3 <= part.length) add(part.substring(i, i + 3))
                                                                i += 2
                                                            }
                                                        }
                                                    }
                                            }.distinct()
                                                .filter { it.length >= 2 && it !in me.rerere.rikkahub.data.service.ExternalMemoryService.STOP_WORDS }
                                                .take(6)
                                        }
                                        keywords.forEach { kw ->
                                            service.searchMessages(
                                                assistantId = assistant.id.toString(),
                                                keyword = kw,
                                                limit = config.recallCount,
                                            ).getOrDefault(emptyList())
                                                .filter { isHistorical(it) }
                                                .forEach { msg ->
                                                    val prefix = when (msg.role) {
                                                        "assistant" -> "AI"
                                                        "user" -> "用户"
                                                        else -> msg.role
                                                    }
                                                    val line = "[$prefix] ${msg.content}"
                                                    if (seenMsg.add(line)) recalled.add(line)
                                                }
                                        }
                                    }
                                } else {
                                    service.queryLatestMessages(
                                        assistantId = assistant.id.toString(),
                                        limit = config.recallCount,
                                    ).getOrDefault(emptyList())
                                        .filter { isHistorical(it) }
                                        .forEach { msg ->
                                            val prefix = when (msg.role) {
                                                "assistant" -> "AI"
                                                "user" -> "用户"
                                                else -> msg.role
                                            }
                                            recalled.add("[$prefix] ${msg.content}")
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
                        lastExternalRecallMs = Clock.System.now().toEpochMilliseconds()
                    } catch (e: Exception) {
                        Log.w(TAG, "External memory recall failed", e)
                        lastExternalRecallMs = Clock.System.now().toEpochMilliseconds()
                    }
                }

                // OB 记忆按需搜索（搜索型：不自动浮现，按用户消息内容调 breath_search，不吵不费token）
                try {
                    val now = Clock.System.now().toEpochMilliseconds()
                    if (now - lastObBreathMs > OB_BREATH_INTERVAL_MS) {
                        val lastUserMessage = messages.lastOrNull { it.role == MessageRole.USER }
                        val queryText = lastUserMessage?.toText()?.take(200)?.trim() ?: ""
                        if (queryText.isNotBlank()) {
                            val searchTool = tools.find { it.name.endsWith("_breath_search") }
                            if (searchTool != null) {
                                val args = buildJsonObject {
                                    put("query", JsonPrimitive(queryText))
                                }
                                val result = searchTool.execute(json.parseToJsonElement(args.toString()).jsonObject)
                                val recalledText = result.filterIsInstance<UIMessagePart.Text>()
                                    .joinToString("\n") { it.text }
                                if (recalledText.isNotBlank()) {
                                    appendLine()
                                    appendLine("## 记忆浮现")
                                    append(recalledText.take(OB_BREATH_MAX_CHARS))
                                    Log.i(TAG, "OB search injected ${recalledText.length} chars")
                                }
                                lastObBreathMs = now
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "OB search auto-recall failed", e)
                }

                // Mem0 第三大脑自动召回（动态，语义搜索）
                try {
                    val lastUserMessage = messages.lastOrNull { it.role == MessageRole.USER }
                    val queryText = lastUserMessage?.toText()?.take(200)?.trim() ?: ""
                    if (queryText.isNotEmpty()) {
                        val mem0Tool = tools.find { it.name.endsWith("_search_memory") }
                        if (mem0Tool != null) {
                            val args = buildJsonObject {
                                put("query", JsonPrimitive(queryText))
                            }
                            val result = mem0Tool.execute(json.parseToJsonElement(args.toString()).jsonObject)
                            val recalledText = result.filterIsInstance<UIMessagePart.Text>()
                                .joinToString("\n") { it.text }
                            if (recalledText.isNotBlank()) {
                                appendLine()
                                appendLine("## Mem0 记忆（第三大脑语义召回）")
                                append(recalledText.take(OB_BREATH_MAX_CHARS))
                                Log.i(TAG, "Mem0 recall injected ${recalledText.length} chars")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Mem0 recall failed", e)
                }
 
                // 最近聊天引用（动态）
                if (assistant.enableRecentChatsReference) {
                    appendLine()
                    append(buildRecentChatsPrompt(assistant, conversationRepo))
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
            addAll(messages.limitContext(assistant.contextMessageSize))
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
    appendLine("   - ✅ Correct: ```manifest.xml instead of ```xml")
    appendLine("   - ✅ Correct: ```main.py instead of ```python")
    appendLine("   - ✅ Correct: ```App.vue instead of ```vue")
    appendLine("   - ❌ Wrong: ```kotlin, ```python, ```javascript (these don't provide filenames)")
    appendLine("   - For code without a specific filename, use a descriptive name like ```example.ts, ```helper.py")
    appendLine()
    appendLine("2. **ZIP Download via `write_files` tool**: Users can download code files as a ZIP ONLY when you call this tool.")
    appendLine("   - **Full write** (first time / new files): `{\"zip_name\":\"project.zip\",\"files\":[{\"name\":\"MainActivity.kt\",\"content\":\"...\"}]}`")
    appendLine("   - **Incremental edit** (saves tokens! For modifying existing files): `{\"zip_name\":\"project-v2.zip\",\"base_files\":\"previous\",\"edits\":[{\"name\":\"MainActivity.kt\",\"search\":\"old code\",\"replace\":\"new code\"}]}`")
    appendLine("   - The `edits` mode applies search/replace to the files from your previous `write_files` call. Files not mentioned in `edits` keep their content unchanged.")
    appendLine("   - Always use actual filenames (e.g. `MainActivity.kt`) as code block language tags, not just language names (e.g. `kotlin`).")
}
 