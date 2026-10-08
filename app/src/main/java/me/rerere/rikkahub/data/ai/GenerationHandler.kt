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
import me.rerere.rikkahub.data.ai.tools.ToolNaming
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
        // 【2026-09-25 · MCP 工具面实时刷新】只重算 MCP 那一段的提供者。
        // 传了它就每步取一次最新 MCP 清单（替掉 tools 里那份旧的），同一回合内用
        // mcp_switch 开关服务器后下一步请求立刻生效；不传则行为跟以前完全一样。
        mcpToolsProvider: (suspend () -> List<Tool>)? = null,
        // 【2026-09-29 · 插件工具面实时刷新】跟 MCP 同一套做法：
        // 传了它就每步取一次最新插件清单（替掉 tools 里那份旧的），同一回合内用
        // plugin_switch 开关插件后，下一步请求立刻生效；不传则行为跟以前完全一样。
        // 插件没有助手级设置，启停就是全局的，所以这里直接取全量。
        pluginToolsProvider: (suspend () -> List<Tool>)? = null,
        maxSteps: Int = 256,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        workspaceCwd: String? = null,
        pluginPromptInjections: List<String> = emptyList(),
        conversationId: String? = null,
        // 【窗口起点节拍 · 2026-09-11】懒加载窗口起点（ChatService.lazyWindowFirstIndex），
        // 由外门透传给内芯（generateInternal）的最近事件节拍器，详见内芯里的注释。
        windowFirstIndex: Int? = null,
        // 【浮现节拍 · 2026-09-30 宝纠正】累计跳量（ChatService 从 SharedPreferences 读回）。
        // 记的是【请求体起点 k 往前跳的累计量】，不是窗口裁掉多少条。
        // 07-29 那版记错了量：浮现该对齐的是请求体的断点（k 跳），不是窗口裁剪（它和 k 正好抵消，请求体没变）。
        surfacingScroll: Long? = null,
        // 【浮现节拍 · 2026-09-30】上一轮见到的请求体起点 k。用来判断这轮 k 有没有往前跳。
        surfacingLastK: Int? = null,
        // 【浮现节拍 · 2026-09-30】k 变了就回传（新的累计值, 新的 k），由 ChatService 落盘。
        onSurfacingAdvance: ((Long, Int) -> Unit)? = null,
        // 【插话搭车 · 2026-09-30 宝的方案】本回合排队中的用户消息（宝在猫生成过程中插的话）。
        // 每步发请求之前取一次：有就并进这一步的请求——宝的话跟着猫的下一口气出去，不用另开一轮。
        // 约定：取的动作同时清空队列（由提供方 remove 实现），所以同一句不会被并进第二步。
        // 取不到 = 没有插话，行为与以前完全一致。
        pendingInterjections: (() -> List<UIMessage>)? = null,
        // 【插话补步 · 2026-10-06 宝定的】宝的话插进来时，这一轮可能正处在"没有工具调用、马上要退出"
        // 的那一步——按老路，队里的话没人接，只能落到 ChatService 的兜底（另起一轮）。
        // 可兜底那轮会被当成"新回合"，而它本该是"当前这轮的第二次请求"：像有工具那样多走一步，
        // 把猫刚写的正文/思考/工具结果连同她的话一起带进下一次请求。
        // 这里只看不取 —— 真取的动作在每步开头那处（continue 回去自然被取走；取走即清空，不会空转）。
        hasPendingInterjections: (() -> Boolean)? = null,
        // 【2026-09-24 召回留痕】把本次门控 / 拆词 / 命中数回传给上层（ChatService 补写到用户消息）
        onRecallDebug: ((String) -> Unit)? = null,
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
        memTrace("1-entry", messages)

        var recallGatePassed = false
 
        // 【正文空重试 2026-09-12 宝拍板】模型只出思考、没出正文（text=0）时自动重发一次。
        // 整个生成流程只重试一次；重试还失败就保持原样（走兜底显示思考链）。
        var emptyTextRetried = false

        // 【2026-09-27 补 · 宝的担心】这一整个回合里有没有调过工具。
        // 调过工具就不重发：工具来回本来就多，最后一轮要是没写完，不该把整个回合重开一遍
        // （那是双倍的钱）。只有"从头到尾没碰过工具"的回合才保留重发——那种是真的一句话都没说成。
        var anyToolCallThisTurn = false

        // 【上下文窗口冻结 · 2026-09-29 宝的方案】上下文窗口的起点，只在这一次 generateText
        // （= 一个完整回合，工具循环全在里面）的开头算一次，循环里每一步都用它。
        // 不冻的话：工具结果是作为单独一条追加进 messages 的，条数一涨、凑满 gs 的倍数，
        // limitContext 的起点就往前跳一组 —— 表现就是"工具调用那个回合上下文被裁"（宝当晚实测）。
        // 回合边界不用判断：这个函数调一次就是一个回合，返回即结束。
        // 起点存在这个容器里（-1 = 还没算），随每次调用传给内芯 generateInternal。
        val ctxStartHolder = intArrayOf(-1)

        for (stepIndex in 0 until maxSteps) {
            // 【看双份 · 2026-10-06】宝报"渲染是好的，请求发出去给的结果是两个"。
            // 每步开头照一眼这一步要发的 USER：条数 + 最后几条的文本尾巴 + id 前 6 位。
            // id 是关键 —— 展开出来的那条是现场新建的（新 id），原来那条带着旧 id，
            // 同文本不同 id = 两份来自两条路；同 id = 同一条被塞了两次。
            run {
                val users = messages.filter { it.role == MessageRole.USER }
                val tail = users.takeLast(4).joinToString(" | ") { m ->
                    m.parts.filterIsInstance<UIMessagePart.Text>()
                        .joinToString("") { it.text }
                        .take(10).replace("\n", "⏎") + "(#${m.id.toString().take(6)})"
                }
                AppLogBuffer.log("Interject", "STEP#$stepIndex users=${users.size} tail=$tail")
            }
            // 【插话搭车 · 2026-09-30 宝的方案】每步开始前取一次排队中的用户消息。
            // 取到就并进这一步的请求：宝插的话跟着猫的下一口气走，不用等整轮跑完另开一轮。
            // 取的动作由提供方清空（remove），所以同一句不会被并进第二步。
            pendingInterjections?.invoke()?.takeIf { it.isNotEmpty() }?.let { extra ->
                // 【插话定位 · 2026-09-30】给"搭车这一刻的最后一个 part"打记号，再补一个空 Text 当隔断。
                // 猫接下来写的正文会被追加进"最后一个 Text part"（见 Message.kt 的流式累加），
                // 不隔断的话插话前后的正文会并成一段，显示层就没法把宝的话夹在中间。
                // 隔断之后：正文A｜（宝的话）｜正文B，三段天然分开，显示层扫到 metadata 里的
                // interject 标记，就知道宝的话该画在这一段后面。
                val interjectIds = extra.map { it.id.toString() }
                val lastAssistantIndex = messages.indexOfLast { it.role == MessageRole.ASSISTANT }
                if (lastAssistantIndex >= 0) {
                    val lastAssistant = messages[lastAssistantIndex]
                    if (lastAssistant.parts.isNotEmpty()) {
                        val parts = lastAssistant.parts.toMutableList()
                        val anchor = parts[parts.lastIndex]
                        anchor.metadata = buildJsonObject {
                            anchor.metadata?.forEach { (key, value) -> put(key, value) }
                            put("interjectAnchor", interjectIds.joinToString(","))
                        }
                        parts.add(UIMessagePart.Text(""))
                        messages = messages.toMutableList().also {
                            it[lastAssistantIndex] = lastAssistant.copy(parts = parts)
                        }
                        AppLogBuffer.log(
                            "Interject",
                            "anchor: marked part[${parts.size - 2}] of assistant#${lastAssistantIndex}, ids=${interjectIds.joinToString(",")}"
                        )
                    }
                }
                // 【搭车的话补标 · 2026-10-04 宝实测】collapseInterjections 第一道门要求
                // "列表里有带 interject 标的 USER"；搭车这条原来是把 extra 原样并进去、没打标，
                // 于是那道门直接关掉，回来的"下半段"认不出该并回去 → 独立成条
                //（宝截图：一次回复被拆成上下两条、中间还插个思考块，很突兀）。
                // 这里在并进请求前把标打上；metadata 不进 API 请求体，只在本地的流式列表里活着，
                // 正好是 collapse 要认的那份。
                val taggedExtra = extra.map { m ->
                    m.copy(parts = m.parts.map { part ->
                        val base = part.metadata
                        val meta = buildJsonObject {
                            base?.forEach { (k, v) -> put(k, v) }
                            put("interject", JsonPrimitive(true))
                        }
                        when (part) {
                            is UIMessagePart.Text -> part.copy(metadata = meta)
                            is UIMessagePart.Image -> part.copy(metadata = meta)
                            else -> part
                        }
                    })
                }
                // 【去重 · 2026-10-06 宝截图为证】请求体里出现过两条一模一样的 user（53/54 相邻）。
                // 她那条插话既在会话消息列表里（sendMessage 存的那份），又被这里 + taggedExtra
                // 加了一遍 —— 同一条 id 出现两次。先按 id 把列表里已有的摘掉，再统一加 tagged。
                val extraIds = extra.map { it.id }.toSet()
                // 【抓鬼 · 2026-10-06】插话在请求里出现两份（点一次也这样）。这里 extra 已经
                // 在手里，不再消费队列。照一眼：列表里有没有"和它文本一样"的 USER，各自的完整 id。
                run {
                    val texts = extra.map { m ->
                        m.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }.trim()
                    }.toSet()
                    val hits = messages.mapIndexedNotNull { idx, m ->
                        if (m.role != MessageRole.USER) null
                        else {
                            val t = m.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }.trim()
                            if (t in texts) "  #$idx id=${m.id} ij=${m.parts.any { pt -> pt.metadata?.containsKey("interject") == true }}" else null
                        }
                    }
                    AppLogBuffer.log(
                        "Interject",
                        "GHOST extra=[${extra.joinToString("; ") { it.id.toString() }}] sameInList=${hits.size}\n" +
                            hits.joinToString("\n")
                    )
                }
                val before = messages.size
                messages = messages.filterNot { it.id in extraIds } + taggedExtra
                AppLogBuffer.log(
                    "Interject",
                    "ride: merged ${extra.size} pending user message(s) into step #$stepIndex (tagged) dedup=${before - messages.size + taggedExtra.size}"
                )
            }
            memTrace("2-step$stepIndex", messages)
            Log.i(TAG, "streamText: start step #$stepIndex (${model.id})")
 
            // 【2026-09-25 · MCP 工具面实时刷新】MCP 那段每步重算：
            // 同一回合内 AI 用 mcp_switch 开了服务器，下一步请求就能看到。
            // 旧的 MCP 工具按名字前缀摘掉，其余（本地/系统/工作区/技能）原样复用；
            // mcpToolsProvider 纯读内存快照，成本只有几十个对象的构造。
            val extraTools = if (mcpToolsProvider != null || pluginToolsProvider != null) {
                tools.filterNot {
                    ToolNaming.isMcpToolName(it.name) || ToolNaming.isPluginToolName(it.name)
                } + (mcpToolsProvider?.invoke() ?: emptyList()) +
                    (pluginToolsProvider?.invoke() ?: emptyList())
            } else {
                tools
            }

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
                extraTools = extraTools,
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
                    onRecallDebug = onRecallDebug,
                    windowFirstIndex = windowFirstIndex,
                    surfacingScroll = surfacingScroll,
                    surfacingLastK = surfacingLastK,
                    onSurfacingAdvance = onSurfacingAdvance,
                    ctxStartHolder = ctxStartHolder,
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
                    //
                    // 【2026-09-27 拆兜底 · 宝拍板】把"有工具"和"没工具"两种场景分开：
                    // 有工具待执行时【不捞】——这一轮模型的顺序是「思考 → 调工具」，正文要等工具
                    // 结果回来之后才写；此刻 text=0 是正常的，硬捞只能捞到思考的尾巴（就是宝嫌
                    // 丑的那种碎碎念）。让它照原样继续跑工具，下一轮正文自然会来；下一轮要是还
                    // 只思考不写、而那会儿工具已经跑完，兜底会正常接住。
                    // 没工具的轮次才是真说完了，该兜就兜，行为不变。
                    val hasPendingTools = lastMsg.parts
                        .filterIsInstance<UIMessagePart.Tool>()
                        .any { !it.isExecuted }
                    if (textLen == 0 && reasoningLen > 0 && !hasPendingTools) {
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
                    // 【2026-09-27 补】再加一道：本回合调过工具就不重发（见 anyToolCallThisTurn）。
                    if (fallbackUsed && !emptyTextRetried && !anyToolCallThisTurn) {
                        emptyTextRetried = true
                        AppLogBuffer.log("GEN_RESULT", "text=0 自动重试一次（原地清空重写，保留同一条消息）")
                        // 【正文空重试 · 2026-10-08 修】原来这里把最后一条整个切掉（slice 掉），
                        // 重发时 handleMessageChunk 看到最后一条是 user → 判定 role 不同 →
                        // 另开一条 assistant（新 id）→ 会话里就多出一条。
                        //（旧行为：按位置对齐把它叠进空那格、显示成 <2/2>；
                        //  改成按 id 对齐后：找不到家 → 显示成两条独立消息。）
                        // 现在改成"原地清空、保留同一条"：最后一条仍是 ASSISTANT，
                        // 重发时 handleMessageChunk 会接着写它 → 同一个 id，库里不会多消息。
                        messages = messages.slice(0 until messages.lastIndex) +
                            messages.last().copy(parts = emptyList())
                        emit(GenerationChunk.Messages(messages))
                        continue
                    }
                    // 【插话补步 · 2026-10-06 宝定的】没有工具调用、本该退出了，但队里还有她的话 →
                    // 别走兜底那条"另起一轮"（那会被当成新回合），就在这一轮里再发一次请求：
                    // 像有工具多走一步那样，把这次的内容（正文/思考/工具结果）连她的话一起带过去。
                    // 只看不取（真取在每步开头那处）；取走即清空，所以不会空转。
                    if (hasPendingInterjections?.invoke() == true) {
                        AppLogBuffer.log(
                            "Interject",
                            "no tool calls but pending interjection -> extra request (step #$stepIndex)"
                        )
                        continue
                    }
                    // no tool calls, break
                    break
                }

                // 【2026-09-27 补】走到这里说明本回合有工具要跑——记下，后面就不许重发了。
                anyToolCallThisTurn = true
 
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
        // 【2026-09-24 召回留痕】门控 / 拆词 / 命中数回传（见 generateText 同名参数）
        onRecallDebug: ((String) -> Unit)? = null,
        // 【窗口起点节拍 · 2026-09-11】懒加载窗口起点在会话中的排名（ChatService.lazyWindowFirstIndex 传入）。
        // 原节拍判据用"窗口消息条数"，但窗口长度被 CONVERSATION_LOAD_WINDOW_SIZE 封顶后差值恒为 0~6
        // → 节拍器永远够不到 threshold、只剩 6h 兜底（详见下方判断处注释）。null = 调用方没传，回退旧判据。
        windowFirstIndex: Int? = null,
        // 【浮现节拍 · 2026-09-30 宝纠正】累计跳量（见 generateText 同名参数）
        surfacingScroll: Long? = null,
        surfacingLastK: Int? = null,
        onSurfacingAdvance: ((Long, Int) -> Unit)? = null,
        // 【上下文窗口冻结 · 2026-09-29】上下文窗口起点容器（-1 = 还没算）。
        // 由外层 generateText 的回合循环持有并传入，一个回合共用同一个起点。
        ctxStartHolder: IntArray? = null,
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
        // 【2026-09-24 召回留痕】本次门控原因 / 拆词结果（供界面小字）
        var recallGateReason: String? = null
        var recallKeywords: List<String>? = null

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
                        // 【2026-09-24 AI 拆词接回】复用门控那个硅基 provider 的 key，零额外配置
                        val keywordExtractor = (judgeProvider as? me.rerere.ai.provider.ProviderSetting.OpenAI)
                            ?.takeIf { it.apiKey.isNotBlank() }
                            ?.let { me.rerere.rikkahub.data.service.QueryKeywordExtractor(apiKey = it.apiKey) }
                        // 【2026-09-24 召回留痕】改用带原因的 judge（needsRecall 壳保留，别处仍在用）
                        val gate = if (recallGate) null else MemoryIntentJudge.judge(queryText, judgeProvider)
                        // 【2026-09-24 召回留痕】门控判"不需要召回"时也回报一句，界面才看得见"为什么没搜"
                        if (gate != null && !gate.needs) {
                            onRecallDebug?.invoke("门控：${gate.reason}（未召回） · 命中 0 条")
                        }
                        if (gate?.needs == true) {
                            recallGateReason = gate.reason
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
                                                    onKeywords = { kws -> if (recallKeywords == null) recallKeywords = kws },
                                                    keywordExtractor = keywordExtractor,
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
                            // 【2026-09-24 召回留痕】无论命中几条都回报（0 条也是信息）
                            onRecallDebug?.invoke(
                                buildString {
                                    append("门控：").append(recallGateReason ?: "已过")
                                    val kws = recallKeywords
                                    append(" · 拆词：")
                                    append(
                                        when {
                                            kws == null -> "没走关键词路"
                                            kws.isEmpty() -> "无（向量已够）"
                                            else -> kws.joinToString(", ")
                                        }
                                    )
                                    append(" · 命中 ").append(allRecalled.size).append(" 条")
                                }
                            )
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
            // 【上下文窗口冻结 · 2026-09-29】起点冻在回合开头。
            // 第一刀按原公式算（(N - cms) - (N % gs)，与 limitContext 对齐）；
            // 之后每一步都用同一个起点往末尾取 —— 这样前缀不动，回合内的工具结果照样进得去。
            val ctxCap = assistant.contextMessageSize
            val ctxGs = assistant.contextGroupSize.coerceAtLeast(1)
            val ctxStart = ctxStartHolder?.get(0)?.takeIf { it >= 0 } ?: (
                if (ctxCap > 0 && messages.size > ctxCap) {
                    (messages.size - ctxCap) - (messages.size % ctxGs)
                } else 0
                ).also { ctxStartHolder?.set(0, it) }
            val ctxMessages = if (ctxStart > 0 && ctxStart < messages.size) {
                messages.drop(ctxStart)
            } else {
                messages
            }
            // 【上下文窗口冻结 · 2026-09-29 验证日志】跑一轮看 start 是否全程不变。
            AppLogBuffer.log(
                "CTXWIN",
                "N=${messages.size} start=$ctxStart ctx=${ctxMessages.size} " +
                    "cms=$ctxCap gs=$ctxGs holder=${ctxStartHolder != null}"
            )
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
                // 【2026-09-28 宝发现 · 橘仔修】换条节拍从"一组"改成"一整轮"。
                // 原写法除 gs(=6)：浮现每 6 条就换一条 → 五次里四次是白换、白碎前缀；
                // 且它的起跳点与请求裁剪差几条，现象就是"上个回合浮现换、下个回合才裁剪"。
                // 改成除"上下文条数"（=窗口滚满一整轮，默认 30）→ 与裁剪同拍。
                val surfacingCycle =
                    if (assistant.contextMessageSize > 0) assistant.contextMessageSize else gs
                // 【2026-09-30 宝的判据】浮现换条应该"搭便车"：卡在缓存本来就要断的那一轮
                // （= 上下文真的换组那一轮，messages.size % gs == 0），才不会额外多花钱。
                // 现在没有留痕，判不出它到底是"准时换"还是"自己跑了"。这里把两组数一起打出来：
                //   tick/scroll = 换条依据（累计滚动条数优先，回退旧算法）
                //   kAligned = 这一轮上下文是否真的换组（缓存本来就要断）
                // tick 变了但 kAligned=false → 就是自己跑出来、白碎一次。
                // 【2026-09-30 宝纠正 · 橘仔改】换条依据 = 请求体起点 k 自己的跳。
                // 旧版用 surfacingScroll（ChatService 在窗口裁剪时累加"裁掉多少条"）——
                // 那个量数的是窗口：而窗口裁剪发生时 windowFirst +gs / k -gs 正好抵消，
                // 请求体一个字没变，浮现却跟着换条 → 白碎一次缓存。宝今晚一句
                // "窗口和上下文用的那个数好像不一样"点破。
                val kNow = ctxStartInMemory
                val lastK = surfacingLastK
                var scrollNow = surfacingScroll ?: 0L
                // 【死锁修复 · 2026-10-02】原判据是 `lastK != null && kNow != lastK`，
                // 而唯一的写回点（onSurfacingAdvance）就在这个 if 里面 —— lastK 初值 null，
                // 于是永远进不去、永远不写，scroll 恒 0、浮现永远同一条（先有鸡还是先有蛋）。
                // 实测：日志里 scroll=0 lastK=-1 从未变过。改成"第一次也记一笔"。
                if (lastK != kNow) {
                    // k 往前跳（= 上下文真换组，请求前缀本来就该断）才累加；
                    // k 变小 = 重开窗口后重算，不累加（这正是 09-29 想治的"重开跳"）。
                    if (lastK != null && kNow > lastK) scrollNow += (kNow - lastK).toLong()
                    onSurfacingAdvance?.invoke(scrollNow, kNow)
                }
                val surfacingTick = scrollNow / surfacingCycle
                AppLogBuffer.log(
                    "Surfacing",
                    "tick=$surfacingTick scroll=$scrollNow lastK=${lastK ?: -1} k=$kNow" +
                        " windowFirst=${windowFirstIndex ?: -1}" +
                        " msgSize=${messages.size} gs=$gsCfg cycle=$surfacingCycle" +
                        " kAligned=${gsCfg > 1 && messages.size % gsCfg == 0}"
                )
                SelfNoteSurfacing.buildMessage(
                    json = selfNotesJson,
                    // 【2026-09-29】换了依据：优先用累计滚动条数（重开不变）；没传时回退旧算法。
                    tick = surfacingTick,
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
                        // 【距上次聊天 · 2026-10-05 宝改的算法】量"她离开多久"，>15 分钟才报。
                        append(sinceLastUserMessageText(messages))
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
        var finalMessages: List<UIMessage>
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

        // 【2026-09-29 宝+橘仔】请求体瘦身：把"一组之外"的历史图片换成 [图片] 占位。
        // 病根：图以 base64 内嵌进请求（一张能顶二十几万字符），既把内存顶爆（OOM 真凶），也把 token 烧穿。
        // 规则（宝 2026-09-29 定）：最近一组（contextGroupSize 条）之内的图原样带上，更早的换占位——
        // 跟窗口裁剪同一把尺子；她同一个回合发的图必然落在最后一组里，所以一次发几张都带得全。
        finalMessages = slimHistoricalImages(finalMessages, assistant.contextGroupSize)

        // 【思考链瘦身 · 2026-10-05 宝提】把"最近 N 条之外"的历史思考链摘掉，别每轮都把它们发给模型。
        // 动机两条：①思考链是请求体里最占地方的两类之一（另一类是工具结果），摘掉省 token；
        // ②长回合里猫会被自己前面绕圈的思考形状带偏，摘掉旧的等于自动把形状洗掉。
        // 规则跟图片瘦身同一把尺子，只是单位是"消息条数"（-1=全带，0=全不带，N=最近 N 条）。
        finalMessages = slimHistoricalReasoning(finalMessages, assistant.reasoningContextDepth)

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
        memTrace("3-send stream=$stream", finalMessages)

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
                    // 【缓存命中留痕 · 2026-10-01】界面上那行"XX K tok（XX K cached）"是算出来的，
                    // 日志环里没有；出问题（例如某轮 cached 只有 70K）时没处可查。
                    // 这里把 API 报的 usage 落到 StreamDone 一并记下，filter "StreamDone" 即可。
                    val u = messages.lastOrNull()?.usage
                    AppLogBuffer.log(
                        "StreamDone",
                        "contentTotal=$streamContentTotal finish=$streamFinishReason" +
                            " prompt=${u?.promptTokens ?: 0} cached=${u?.cachedTokens ?: 0} out=${u?.completionTokens ?: 0}"
                    )
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
 * 【内存追踪 2026-09-28】给"发请求时崩"定位用。
 * 报错都发生在请求那一刻，但不知道是哪一段把堆吃掉的。
 * 在请求链路几个节点各打一次堆占用 + 本轮消息体量（含图片张数与 url 总长，
 * 用来区分图片是 file:// 路径还是内联 base64），崩的那回就能看出是哪段涨的。
 * read_app_logs filter "MemTrace" 可全量回放。
 */
private fun memTrace(stage: String, messages: List<UIMessage>? = null) {
    runCatching {
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / 1048576L
        val maxMb = rt.maxMemory() / 1048576L
        val detail = messages?.let { msgs ->
            var textLen = 0
            var imgCount = 0
            var imgUrlLen = 0
            for (m in msgs) for (p in m.parts) when (p) {
                is UIMessagePart.Text -> textLen += p.text.length
                is UIMessagePart.Reasoning -> textLen += p.reasoning.length
                is UIMessagePart.Image -> { imgCount++; imgUrlLen += p.url.length }
                else -> {}
            }
            " msgs=${msgs.size} text=$textLen img=$imgCount imgUrlLen=$imgUrlLen"
        } ?: ""
        AppLogBuffer.log("MemTrace", "$stage heap=${usedMb}/${maxMb}MB$detail")
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

/**
 * 【距上次聊天 · 2026-10-05 宝改的算法】取代退役的 TimeReminderTransformer 那条 <time_reminder>。
 *
 * 旧那条报的是"现在几点 + 间隔"，位置却在【历史最前面】——窗口一裁，首条就换人，
 * 时间戳跟着换 → 从那儿往后整段重算（PromptDiff 实测：命中率掉到 41%，断点钉死在一处）。
 *
 * 【★ 为什么换成现在这个算法 · 2026-10-05 宝指出】
 * 老写法是"最后一条 USER ↔ 现在"。可每条请求本来就是**她先发消息才触发**的 →
 * 那个差值永远≈0，量出来的只是"这个回合跑了多久"，跟"她多久没说话"毫无关系。
 * 现在改成：**猫最后一条回复 ↔ 她这一条消息**，量的才是"她离开多久"。
 * 超过 15 分钟才报（跟"这场聊了多久"的断点同一条线）；不足 15 分钟返回空串，不加任何东西。
 * 位置仍在末尾注入块里（增量区），会变也不伤前缀缓存。
 */
private fun sinceLastUserMessageText(messages: List<UIMessage>): String {
    var lastAssistantSec: Long? = null
    var lastUserSec: Long? = null
    for (msg in messages) {
        val sec = msgEpochSecond(msg) ?: continue
        when (msg.role) {
            MessageRole.ASSISTANT -> lastAssistantSec = sec
            MessageRole.USER -> lastUserSec = sec
            else -> Unit
        }
    }
    val a = lastAssistantSec ?: return ""
    val u = lastUserSec ?: return ""
    val minutes = ((u - a) / 60L).coerceAtLeast(0)
    if (minutes < 15) return ""
    return when {
        minutes < 60 -> "，距离上次聊天已经过了 $minutes 分钟"
        else -> "，距离上次聊天已经过了 ${minutes / 60} 小时 ${minutes % 60} 分钟"
    }
}

/** UIMessage.createdAt（kotlinx.datetime.LocalDateTime）→ epoch 秒；异常返回 null */
private fun msgEpochSecond(msg: UIMessage): Long? = runCatching {
    msg.createdAt.toInstant(TimeZone.currentSystemDefault()).epochSeconds
}.getOrNull()

/**
 * 【2026-09-29 宝+橘仔】请求体瘦身：把"一组之外"的历史图片换成 [图片] 占位。
 *
 * 病根：图片以 base64 内嵌进请求，一张能顶二十几万字符
 * （PromptDiff 实测：82 条消息 / 9 张内嵌图 / 152 万字符）。
 * 一来把内存顶爆（OOM 真凶），二来每轮都把这些字符重新算一遍钱。
 *
 * 规则（宝 2026-09-29 定的）：**最近一组（contextGroupSize 条）之内的图不换，超过一组的换占位**——
 * 跟窗口裁剪用同一把尺子；她同一个回合发的图一定落在最后一组里，所以一次发几张都带得全。
 */
/**
 * 【思考链瘦身 · 2026-10-05】把"最近 keepRecent 条消息"之外的 Reasoning part 全部摘掉。
 *
 * keepRecent 语义：
 *   -1（默认）= 全带，一个字不摘，行为跟以前完全一样；
 *    0        = 全不带，历史里一条思考链都不发给模型；
 *    N > 0    = 只保留最近 N 条消息里的思考链（按消息条数算，跟 contextGroupSize 同一个量纲）。
 *
 * 为什么要留"最近 N 条"：多步工具调用时，模型要能看见自己上一步为什么决定去查某个东西，
 * 全摘会让它在回合内"失忆"。摘掉的换成一段 [推理] 占位，保持 parts 结构不被破坏。
 */
private fun slimHistoricalReasoning(messages: List<UIMessage>, keepRecent: Int): List<UIMessage> {
    if (messages.isEmpty() || keepRecent < 0) return messages
    val keepFrom = (messages.size - keepRecent).coerceAtLeast(0)
    var changed = false
    val result = ArrayList<UIMessage>(messages.size)
    messages.forEachIndexed { index, msg ->
        if (index >= keepFrom) {
            result.add(msg)
        } else {
            var touched = false
            val newParts: List<UIMessagePart> = msg.parts.map { part ->
                if (part is UIMessagePart.Reasoning) {
                    touched = true
                    UIMessagePart.Text("[推理]")
                } else {
                    part
                }
            }
            if (touched) {
                changed = true
                result.add(msg.copy(parts = newParts))
            } else {
                result.add(msg)
            }
        }
    }
    return if (changed) result else messages
}

private fun slimHistoricalImages(messages: List<UIMessage>, groupSize: Int): List<UIMessage> {
    if (messages.isEmpty()) return messages
    val gs = if (groupSize > 0) groupSize else 6
    val keepFrom = (messages.size - gs).coerceAtLeast(0)
    var changed = false
    val result = ArrayList<UIMessage>(messages.size)
    messages.forEachIndexed { index, msg ->
        if (index >= keepFrom) {
            result.add(msg)
        } else {
            var touched = false
            val newParts: List<UIMessagePart> = msg.parts.map { part ->
                if (part is UIMessagePart.Image) {
                    touched = true
                    UIMessagePart.Text("[图片]")
                } else {
                    part
                }
            }
            if (touched) {
                changed = true
                result.add(msg.copy(parts = newParts))
            } else {
                result.add(msg)
            }
        }
    }
    return if (changed) result else messages
}
