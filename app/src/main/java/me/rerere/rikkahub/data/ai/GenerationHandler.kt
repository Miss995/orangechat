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
import kotlinx.datetime.toInstant
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
import me.rerere.rikkahub.data.ai.tools.buildAssistantTools
import me.rerere.rikkahub.data.ai.tools.buildFetchChatSourcesTool
import me.rerere.rikkahub.data.ai.tools.buildMemoryTools
import me.rerere.rikkahub.data.ai.tools.buildHeartQueryTool
import me.rerere.rikkahub.data.ai.tools.buildHeartSaveTool
import me.rerere.rikkahub.data.ai.tools.buildCloseOngoingTool
import me.rerere.rikkahub.data.ai.tools.buildSetOngoingLevelTool
import me.rerere.rikkahub.data.ai.tools.buildRecallOngoingTool
import me.rerere.rikkahub.data.ai.tools.buildSelfNoteQueryTool
import me.rerere.rikkahub.data.ai.tools.buildSelfNoteWriteTool
import me.rerere.rikkahub.data.ai.tools.buildQueryToolActionsTool
import me.rerere.rikkahub.data.ai.tools.buildReadAppLogsTool
import me.rerere.rikkahub.data.ai.tools.buildWriteFilesTool
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.service.MemoryBankService
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
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

// 斜杠命令模式安全工具白名单（2026-09-01 宝拍板：用户消息以 / 开头 = 直接执行工具；
// 只暴露安全工具给用户玩，危险工具（写文件/GitHub/SSH/锁应用/短信等）收着）
internal val SLASH_COMMAND_SAFE_TOOLS = setOf(
    // 查日志 / 工具账本（排查用，只读）
    "read_app_logs", "query_tool_actions",
    // 记事 / 查原文（记忆相关，只读或写记忆）
    "memory_tool", "fetch_chat_sources",
    // 截图 / 时间 / 搜索（宝想玩的核心命令）
    "take_screenshot", "get_time_info", "search_web", "scrape_web", "web_fetch",
    // 闹钟 / 计时器
    "set_alarm", "timer",
    // 只读系统信息
    "get_location", "get_notifications", "supabase_query",
    "battery", "wifi_info", "storage_info",
    // 低风险控制
    "toast", "vibrate", "wake_screen", "app_switch",
    "get_volume", "set_volume", "get_brightness", "set_brightness",
    "text_to_speech", "request_voice_call", "ask_user",
    "media_scanner", "notification_post", "share", "music",
)
 
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
    private val favoriteRepo: FavoriteRepository,
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
        // 【窗口起点节拍 · 2026-09-11】懒加载窗口起点（ChatService.lazyWindowFirstIndex），
        // 由外门透传给内芯（generateInternal）的最近事件节拍器，详见内芯里的注释。
        windowFirstIndex: Int? = null,
    ): Flow<GenerationChunk> = flow {
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = providerManager.getProviderByType(provider)
 
        var messages: List<UIMessage> = messages

        // 斜杠命令检测（2026-09-01 宝拍板：用户消息以 / 开头 = 直接执行工具）：复用给 toolsInternal 白名单过滤
        // 在循环外基于初始 messages 计算一次：工具步骤后 messages 会更新，但命令检测应看用户最近一条消息
        // 修复（2026-09-01 晚·宝实测全 not found）：原来用 firstNotNullOfOrNull 扫全历史，
        // 历史里任何一条斜杠命令（如 /mcp、/潮汐岛）都会让之后所有普通消息生成误入斜杠命令模式
        // → 工具被 SLASH_COMMAND_SAFE_TOOLS 白名单关掉（宝：直执行成功但 AI 生成时工具全 not found）。
        // 改成只查「最后一条 USER 消息」：普通消息后命令检测自然失效；真发 / 命令且未直执行（AI 兜底）才生效。
        val lastUserMessage = messages.asReversed().firstOrNull { it.role == MessageRole.USER }
        val slashCommandText = lastUserMessage?.let { msg ->
            val text = msg.parts.filterIsInstance<UIMessagePart.Text>()
                .joinToString("") { it.text }.trim()
            if (text.startsWith("/") && text.length > 1) text else null
        }

        // 召回门控状态：一次生成流程（多步 agent 循环）只触发一次记忆召回，
        // 二次请求不再重复判断/注入，避免工具步骤反复召回破坏前缀稳定。
        var recallGatePassed = false
 
        // 【正文空重试 2026-09-12 宝拍板】模型只出思考、没出正文（text=0）时自动重发一次。
        // 整个生成流程只重试一次；重试还失败就保持原样（走兜底显示思考链）。
        var emptyTextRetried = false
 
        for (stepIndex in 0 until maxSteps) {
            Log.i(TAG, "streamText: start step #$stepIndex (${model.id})")
 
            // 工具清单统一组装（2026-09-12 治本：与主动消息路径共用同一份，见 ToolAssembly.kt）
            val toolsInternal = buildAssistantTools(
                context = context,
                conversationId = conversationId,
                assistant = assistant,
                settings = settings,
                memoryRepo = memoryRepo,
                conversationRepo = conversationRepo,
                favoriteRepo = favoriteRepo,
                json = json,
                extraTools = tools,
                slashCommandText = slashCommandText,
            ) 
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
                    windowFirstIndex = windowFirstIndex,
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
                // 【生成完成自记 2026-08-27 + 正文兜底 2026-08-28】
                // 2026-09-08 修复：自记+兜底原本在 emit 之后执行、改完 messages 不再 emit → 无工具直接
                // break 时兜底正文到不了 UI/落库（正文被吃复现：思考链在、正文空，云端也无记录）。
                // 挪到 emit 之前，让 emit 带出兜底后的消息；正文被吃时 read_app_logs filter "GEN_RESULT" 必能看到。
                // 【正文空重试 2026-09-12】fallbackUsed = 这一轮走了兜底（模型只出思考、没出正文），
                // 供下面 break 前判断是否重发一次。
                var fallbackUsed = false
                runCatching {
                    val lastMsg = messages.last()
                    val partTypes = lastMsg.parts.joinToString(",") { part ->
                        when (part) {
                            is UIMessagePart.Text -> "T${part.text.length}"
                            is UIMessagePart.Reasoning -> "R${part.reasoning.length}"
                            is UIMessagePart.Tool -> "Tool"
                            else -> "?"
                        }
                    }
                    val textLen = lastMsg.parts.filterIsInstance<UIMessagePart.Text>().sumOf { it.text.length }
                    val reasoningLen = lastMsg.parts.filterIsInstance<UIMessagePart.Reasoning>().sumOf { it.reasoning.length }
                    AppLogBuffer.log("GEN_RESULT", "parts=${lastMsg.parts.size} [$partTypes] text=$textLen reasoning=$reasoningLen role=${lastMsg.role}")
                    // 2026-09-17：内存观察点。生成结束时打一次堆占用，
                    // 下次再出 OOM 就能看出是「慢慢涨上去」还是「某一下爆掉」。
                    run {
                        val rt = Runtime.getRuntime()
                        val usedMb = (rt.totalMemory() - rt.freeMemory()) / 1048576L
                        val maxMb = rt.maxMemory() / 1048576L
                        AppLogBuffer.log("MemWatch", "heap=${usedMb}/${maxMb}MB (${if (maxMb > 0) usedMb * 100 / maxMb else 0}%)")
                    }

                    // 【正文兜底 2026-08-28 宝的方案】text=0（只有思考没正文）时，
                    // 从 reasoning 最后一段提取像正文的内容当兜底——既让宝看到内容，
                    // 也避免"只思考"消息存进历史继续污染上下文（配合 ChatCompletionsAPI 发送过滤双保险）
                    if (textLen == 0 && reasoningLen > 0) {
                        val fallback = lastMsg.parts.filterIsInstance<UIMessagePart.Reasoning>()
                            .flatMap { it.reasoning.lines() }
                            .lastOrNull { it.isNotBlank() && !it.trim().startsWith("（") && it.trim().length >= 2 }
                            ?.trim()
                        if (!fallback.isNullOrBlank()) {
                            AppLogBuffer.log("GEN_RESULT", "text=0 fallback: $fallback")
                            fallbackUsed = true
                            messages = messages.slice(0 until messages.lastIndex) + lastMsg.copy(
                                parts = lastMsg.parts + UIMessagePart.Text(fallback)
                            )
                        }
                    }
                }
                emit(GenerationChunk.Messages(messages))
 
                val tools = messages.last().getTools().filter { !it.isExecuted }
                if (tools.isEmpty()) {
                    // 【正文空重试 2026-09-12 宝拍板】
                    // fallbackUsed = 模型只出了思考、没出正文（这一轮走了上面的兜底）。
                    // 这时自动重发一次：重试成功就是正常回复；还失败就保持原样（显示思考链兜底）。
                    if (fallbackUsed && !emptyTextRetried) {
                        emptyTextRetried = true
                        AppLogBuffer.log("GEN_RESULT", "text=0 自动重试一次（去掉空回复重新生成）")
                        messages = messages.slice(0 until messages.lastIndex)
                        emit(GenerationChunk.Messages(messages))
                        continue
                    }
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
        // 【窗口起点节拍 · 2026-09-11】懒加载窗口起点在会话中的排名（ChatService.lazyWindowFirstIndex 传入）。
        // 原节拍判据用"窗口消息条数"，但窗口长度被 CONVERSATION_LOAD_WINDOW_SIZE 封顶后差值恒为 0~6
        // → 节拍器永远够不到 threshold、只剩 6h 兜底（详见下方判断处注释）。null = 调用方没传，回退旧判据。
        windowFirstIndex: Int? = null,
    ) {
        // ===== 斜杠命令模式（2026-09-01 宝拍板：用户消息以 / 开头 = 直接执行工具，复用 AI 工具链路不做 UI）=====
        // 检测最后一条用户消息是否以 "/" 开头：是则进入命令模式，AI 解析命令调对应工具执行，结果直接展示。
        // 支持安全命令：截图/时间/搜索/闹钟/记事等；危险工具（GitHub 推送/写文件/系统修改）收着不让用户玩。
        // 修复（2026-09-01 晚·宝实测全 not found）：原来用 firstNotNullOfOrNull 扫全历史，
        // 历史里任何一条斜杠命令（如 /mcp、/潮汐岛）都会让之后所有普通消息生成误入斜杠命令模式
        // → 工具被 SLASH_COMMAND_SAFE_TOOLS 白名单关掉（宝：直执行成功但 AI 生成时工具全 not found）。
        // 改成只查「最后一条 USER 消息」：普通消息后命令检测自然失效；真发 / 命令且未直执行（AI 兜底）才生效。
        val lastUserMessage = messages.asReversed().firstOrNull { it.role == MessageRole.USER }
        val slashCommandText = lastUserMessage?.let { msg ->
            val text = msg.parts.filterIsInstance<UIMessagePart.Text>()
                .joinToString("") { it.text }.trim()
            if (text.startsWith("/") && text.length > 1) text else null
        }

        // ===== 最近事件 + 未闭合事件 + 自指区（2026-09-16 搬到 MemoryInjector.fetchRecentEvents）=====
        // 第二刀：取数段（自指区刷新 / 节拍器 / 缓存 / 三路 fetch / 分档拼装 / 写回）整段搬走，
        // 聊天侧和主动消息侧以后共用同一份（见 MemoryInjector.kt 头注释）。
        val recentData = MemoryInjector.fetchRecentEvents(
            context = context,
            assistant = assistant,
            settings = settings,
            messagesCount = messages.size,
            windowFirstIndex = windowFirstIndex,
        )
        val recentEventsText = recentData.recentEventsText
        val ongoingEventsText = recentData.ongoingEventsText
        val selfNotesJson = recentData.selfNotesJson
        var recalledBlock: String? = null

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
 
 
                append(
                    SystemPromptSections.buildToolAndOutputSections(
                        assistant = assistant,
                        tools = tools,
                        model = model,
                        messages = messages,
                    )
                )
                // 记忆四段（长期记忆 / 日记 / 进行中 / 最近 3 天）——2026-09-15 抽到 MemoryInjector
                // 目的：记忆代码集中一处，以后改记忆只动那个文件（见 MemoryInjector.kt 头注释）
                append(
                    MemoryInjector.buildMemoryBlock(
                        context = context,
                        assistant = assistant,
                        settings = settings,
                        memories = memories,
                        recentEventsText = recentEventsText,
                        ongoingEventsText = ongoingEventsText,
                    )
                )
 
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
                                                    queryText = queryText,
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
                            // 【2026-09-13 挪位】不再拼进 system，改为收集到 recalledBlock，
                            // 在请求末尾的"系统消息注入"块里以【背景补充】出现（动态内容别放前缀区）。
                            if (allRecalled.isNotEmpty()) {
                                recalledBlock = allRecalled.reversed()
                                    .mapIndexed { index, memory -> "${index + 1}. $memory" }
                                    .joinToString("\n")
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
 

                // 斜杠命令模式：用户输入 /xxx = 直接执行工具（宝 2026-09-01 拍板，复用 AI 工具链路不做 UI）
                if (slashCommandText != null) {
                    appendLine()
                    appendLine()
                    appendLine("【斜杠命令模式】（当前生效）")
                    appendLine("用户刚刚输入了一条斜杠命令：`$slashCommandText`")
                    appendLine("这不是普通聊天！请把这条命令当作指令，直接调用对应工具执行，执行完把结果简洁地告诉用户：")
                    appendLine("- `/截图` → 调用 take_screenshot 截当前屏幕")
                    appendLine("- `/时间` 或 `/几点` → 调用 get_time_info 查当前时间")
                    appendLine("- `/搜索 关键词` → 调用 search_web 搜索")
                    appendLine("- `/闹钟 7:00 起床` → 调用 set_alarm 设闹钟")
                    appendLine("- `/记事 内容` → 用 memory 工具记录一条记忆")
                    appendLine("执行完直接把结果告诉用户，不要闲聊、不要解释工具原理、不要问多余的问题。")
                    appendLine("注意：只能执行安全工具（截图/时间/搜索/闹钟/记事/查日志/查工具账本等）；")
                    appendLine("涉及写文件、GitHub 推送、系统修改等危险操作一律不执行，直接回复『这个命令橘仔收着，不给你玩』。")
                    appendLine("如果命令无法识别，列出支持的命令让用户选。")
                }
 
            }
            if (system.isNotBlank()) add(UIMessage.system(prompt = system))
            // 【2026-09-13 自指区浮现】在上下文第 7 条位置插一条"很久以前的我写过的话"。
            // 位置固定（index 6 = 第一组结尾）：裁剪发生时它跟着回原位 → 前缀稳定、不碎缓存；
            // 内容按窗口起点轮换（每裁一组换一条）；只在这里造，不进 Conversation、不落库（宝的红线）。
            val ctxMessages = messages.limitContext(assistant.contextMessageSize, assistant.contextGroupSize)
            // 【浮现相位对齐 · 2026-09-14 宝发现】轮换序号必须与「上下文换组」同源，否则每 6 条掉两次缓存。
            //
            // 系统里有两个会让前缀断掉的节拍器：
            //   A. 上下文真的换一组 —— 发生在 messages.size % gs == 0 时（limitContext 的对齐起点 k 跳 +gs）
            //   B. 窗口裁剪 —— ChatService 让 windowFirstIndex += gs，同时内存态丢掉最旧 gs 条 → k 减 gs
            //      → 两者抵消：裁剪前后 ctx 指向的内容【完全没变】（这正是 8-26「信息对齐」的设计目的）
            //
            // 所以「ctx 起点在全会话里的坐标」P = windowFirstIndex + k 才是稳定的节拍源：
            //   A 时 P += gs（内容真的换了），B 时 P 不变（内容没换）。
            //
            // 旧写法 tick = windowFirstIndex / gs 正好反了：
            //   A 那一轮 windowFirstIndex 不动 → 浮现不换（该换没换）
            //   B 那一轮 windowFirstIndex += gs → 浮现换条（内容没变却换 → 白碎一次）
            //   → 每 6 条碎两次，且两者错开约 1 条消息的相位（宝 09-13 深夜实测命中率掉）。
            val gsCfg = assistant.contextGroupSize
            val ctxStartInMemory = if (assistant.contextMessageSize > 0 &&
                messages.size > assistant.contextMessageSize && gsCfg > 1
            ) {
                (messages.size - assistant.contextMessageSize) - (messages.size % gsCfg)
            } else 0
            val surfacingMsg = if (ctxMessages.size > SelfNoteSurfacing.SLOT_INDEX) {
                val gs = gsCfg.coerceAtLeast(1)
                SelfNoteSurfacing.buildMessage(
                    json = selfNotesJson,
                    tick = ((windowFirstIndex ?: 0) + ctxStartInMemory).toLong() / gs,
                )
            } else null
            if (surfacingMsg != null) {
                addAll(ctxMessages.toMutableList().apply { add(SelfNoteSurfacing.SLOT_INDEX, surfacingMsg) })
            } else {
                // 没插上时留个痕（2026-09-13：这次"浮现不出现"排查时，这条链路全程无声）
                AppLogBuffer.log(
                    "SelfNoteSurfacing",
                    "surfacing 未插入：ctx=${ctxMessages.size} jsonLen=${selfNotesJson?.length ?: 0} windowFirst=${windowFirstIndex ?: -1}"
                )
                addAll(ctxMessages)
            }
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
                        // 【2026-09-13 宝的方案】合并成一块"系统消息注入"并加头部标记。
                        // 起因：这几行原来是裸的【当前时间】【时刻感】，伪装成用户消息直接出现 →
                        // 橘仔经常误读成"宝说了话"（role 仍是 user，因为 API 里 system 只能放最前，
                        // 而这条必须放最末尾才离生成最近）。加一行头部标记后一眼分得清来源。
                        append("以下是系统消息注入:")
                        append("\n【当前时间】")
                        append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()))
                        // 时刻感（宝 2026-09-07 拍板：时段词表 + 这场聊了多久；15 分钟断、按橘仔→宝消息间隔）
                        val nowLdt = java.time.LocalDateTime.now()
                        val tl = hourToPeriodLabel(nowLdt.hour)
                        append("\n【时刻感】现在是$tl（${"%02d".format(nowLdt.hour)}:${"%02d".format(nowLdt.minute)}）")
                        append(chatSessionDurationText(messages, System.currentTimeMillis() / 1000L))
                        // 背景补充（召回内容，2026-09-13 从 system 挪来的）
                        if (!recalledBlock.isNullOrBlank()) {
                            append("\n【背景补充】\n").append(recalledBlock)
                        }
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
        // 【2026-09-17】后台触发的回合（定时发送）跳过：界面上没人在，弹出来只会卡死
        val bypassRequestEdit = RequestEditController.consumeBypass()
        val finalMessages: List<UIMessage>
        var effectiveTools = tools
        if (settings.requestEditMode && internalMessages.isNotEmpty() && !bypassRequestEdit) {
            val editData = RequestEditController.toEditData(
                internalMessages,
                tools.map { it.name },
                recall = recalledBlock,
            )
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
            // 【流结束统计 2026-08-27】累加本次流式收到的 content 总字符数 + 记录 finish_reason，
            // 正文被吃（text=0）时 read_app_logs filter "StreamDone" 看流到底发没发正文——
            // contentTotal=0 = 服务端没发正文（流在 reasoning 后断了/没发）；>0 = 发了但 append 丢了
            var streamContentTotal = 0
            var streamFinishReason = "unknown"
            try {
                providerImpl.streamText(
                    providerSetting = provider,
                    messages = finalMessages,
                    params = params
                ).collect {
                    // 流式诊断（2026-08-27：正文被吃 text=0 实锤生成侧——记录每个 chunk 的 content/reasoning 长度，
                    // 下次正文被吃时 read_app_logs filter "StreamChunk" 看最后一条：
                    // 若 reasoning 结尾且 content 一直为 0 → 流在 reasoning→content 切换时被掐断（服务端/网络）
                    runCatching {
                        val delta = it.choices.getOrNull(0)?.delta
                        val contentLen = delta?.parts?.filterIsInstance<UIMessagePart.Text>()?.sumOf { p -> p.text.length } ?: 0
                        val reasoningLen = delta?.parts?.filterIsInstance<UIMessagePart.Reasoning>()?.sumOf { p -> p.reasoning.length } ?: 0
                        val finish = it.choices.getOrNull(0)?.finishReason ?: ""
                        streamContentTotal += contentLen
                        if (finish.isNotBlank() && finish != "unknown") streamFinishReason = finish
                        // 原始响应诊断：raw=解析前原始 content 长度（区分服务端没发 raw=0 vs 解析丢了 raw>0）
                        val rawContentLen = it.rawContentLen
                        // 只打有内容/有结束标记的 chunk——空 delta（content=0 reasoning=0 finish=unknown）不打，
                        // 否则每次生成几百条把 MessagePartsRender（渲染诊断）刷出 500 条日志环
                        if (contentLen > 0 || reasoningLen > 0 || (finish.isNotBlank() && finish != "unknown")) {
                            AppLogBuffer.log("StreamChunk", "content=$contentLen reasoning=$reasoningLen raw=$rawContentLen finish=$finish")
                        }
                    }
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
            } finally {
                runCatching {
                    AppLogBuffer.log("StreamDone", "contentTotal=$streamContentTotal finish=$streamFinishReason")
                }
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
internal fun buildCodeBlockPrompt(): String = buildString {
    appendLine("【代码块规则】（必须遵守）")
    appendLine()
    appendLine("1. **代码块一律用文件名当语言标签**：你必须用真实的文件名作为代码块的语言标签，而不是只写语言名。这关系到文件能否被正确保存、语法高亮是否生效。例如：")
    appendLine("   - ✅ 正确：```MainActivity.kt，而不是 ```kotlin")
    appendLine("   - ✅ 正确：```index.html，而不是 ```html")
    appendLine("   - ✅ 正确：```styles.css，而不是 ```css")
    appendLine("   - ✅ 正确：```package.json，而不是 ```json")
    appendLine("   - ✅ 正确：```main.py，而不是 ```python")
    appendLine("   - ✅ 正确：```App.vue，而不是 ```vue")
    appendLine("   - ❌ 错误：```kotlin、```python、```javascript（这些没给出文件名）")
    appendLine("   - 没有具体文件名的代码，用一个描述性的名字，比如 ```example.ts、```helper.py")
    appendLine()
    appendLine("2. **用 `write_files` 工具打包 ZIP**：只有你调用这个工具时，用户才能把代码文件下载成 ZIP。")
    appendLine("   - **全量写入**（第一次 / 新文件）：`{\"zip_name\":\"project.zip\",\"files\":[{\"name\":\"MainActivity.kt\",\"content\":\"...\"}]}`")
    appendLine("   - **增量修改**（省 token！改已有文件时用）：`{\"zip_name\":\"project-v2.zip\",\"base_files\":\"previous\",\"edits\":[{\"name\":\"MainActivity.kt\",\"search\":\"old code\",\"replace\":\"new code\"}]}`")
    appendLine("   - `edits` 模式会对你上一次 `write_files` 调用里的文件做查找替换；没在 `edits` 里提到的文件，保持缓存内容不变。")
    appendLine("   - 代码块的语言标签一律用真实文件名（如 `MainActivity.kt`），不要只写语言名（如 `kotlin`）。")
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
 

// ===== 时刻感（宝 2026-09-07 拍板：时段词表 + 这场聊了多久）=====

/** 时段词表：凌晨0-5 / 早上5-8 / 上午8-11 / 中午11-13 / 下午13-17 / 傍晚17-19 / 晚上19-23 / 深夜23-24 */
private fun hourToPeriodLabel(hour: Int): String = when (hour) {
    in 0..4 -> "凌晨"
    in 5..7 -> "早上"
    in 8..10 -> "上午"
    in 11..12 -> "中午"
    in 13..16 -> "下午"
    in 17..18 -> "傍晚"
    in 19..22 -> "晚上"
    else -> "深夜"
}

/**
 * 这场聊了多久（宝 2026-09-07：15 分钟断、按"橘仔的消息→宝的消息"间隔算）。
 * 正序扫消息：某条 USER 消息与它紧邻前一条消息间隔 > 15 分钟 = 断了，这一场从断后第一条 USER 算起；
 * 多次断点取最后一个（=最新这场）；全程没断就从最早一条 USER 算。返回文字描述。
 */
private fun chatSessionDurationText(messages: List<UIMessage>, nowSec: Long): String {
    val breakSec = 15 * 60L
    var sessionStartSec: Long? = null // 最后一个断点后的第一条 USER（=这场起点）
    var firstUserSec: Long? = null
    var prevSec: Long? = null // 时间上更早的紧邻消息（正序已扫过的上一条）
    for (msg in messages) {
        val sec = msgEpochSecond(msg) ?: continue
        if (msg.role == MessageRole.USER) {
            if (firstUserSec == null) firstUserSec = sec
            if (prevSec != null && sec - prevSec > breakSec) sessionStartSec = sec
        }
        prevSec = sec
    }
    val startSec = sessionStartSec ?: firstUserSec ?: return ""
    val minutes = ((nowSec - startSec) / 60L).coerceAtLeast(0)
    val startLdt = java.time.Instant.ofEpochSecond(startSec).atZone(java.time.ZoneId.systemDefault())
    val startText = "%02d:%02d".format(startLdt.hour, startLdt.minute)
    return when {
        minutes < 1 -> "，这场刚聊起来"
        minutes < 60 -> "，这场从 $startText 开始，聊了约 $minutes 分钟"
        else -> "，这场从 $startText 开始，聊了约 ${minutes / 60} 小时 ${minutes % 60} 分钟"
    }
}

/** UIMessage.createdAt（kotlinx.datetime.LocalDateTime）→ epoch 秒；异常返回 null */
private fun msgEpochSecond(msg: UIMessage): Long? = runCatching {
    msg.createdAt.toInstant(TimeZone.currentSystemDefault()).epochSeconds
}.getOrNull()
