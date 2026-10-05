/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.service

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.ai.AppLogBuffer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.mcp.McpTool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.rikkahub.data.service.MemoryBankService
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.tools.SystemTools
import me.rerere.rikkahub.data.ai.tools.ToolNaming
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.data.ai.tools.createWorkspaceTools
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.plugin.loader.PluginLoader
import me.rerere.rikkahub.plugin.provider.PluginToolProvider
import me.rerere.asr.ASRProviderSetting
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.ImageDescriber
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.VideoNarrationTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.QuotedMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.VoiceMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.WorkspaceReminderTransformer
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.datastore.getSelectedASRProvider
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.utils.applyPlaceholders
import me.rerere.rikkahub.utils.sendNotification
import me.rerere.rikkahub.utils.cancelNotification
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "ChatService"

/** 浮现节拍账本（SharedPreferences 名）· 2026-09-29 */
private const val SURFACING_PREFS = "surfacing_tick"

/**
 * 【懒加载窗口 2026-08-25】打开对话时只加载最近 N 条消息节点到内存。
 * 长对话（几千条）不再全量加载，流式更新/重组只碰窗口内的少量节点 → 大窗口不卡。
 * 显示只取最后 200 条（ChatList.WINDOW_DISPLAY_SIZE），这里多留余量给 AI 上下文取用。
 */
internal const val CONVERSATION_LOAD_WINDOW_SIZE = 300  // internal（2026-09-12）：GenerationHandler 的注入刷新节拍要用它预判"本回合会不会裁组"

/**
 * 【缓存对齐 2026-08-26】窗口裁剪组大小兜底值：正常取对话关联 assistant 的 contextGroupSize
 * （设置里"多少条一组"，与 limitContext 组对齐同步）；读不到时才用这个兜底。
 */
private const val DEFAULT_WINDOW_GROUP_SIZE = 4

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
)

enum class ChatErrorSolution {
    CheckTitleModelSettings,
}

private val inputTransformers by lazy {
    listOf(
        // 【退役 · 2026-10-04 宝定的】TimeReminderTransformer 不再挂在这条链上。
        // 它给"列表里第一条 USER"无条件插一条带"当前时间"的 <time_reminder>——窗口一裁首条就换人，
        // 那条时间戳跟着换，而它坐在列表最前面 → 从那儿往后整段缓存全废
        // （PromptDiff 实测：命中率掉到 41%，断点钉死在一处）。
        // 现在改成：在末尾那条"系统消息注入"里报一句"距上次聊天 xx 分钟"（见 GenerationHandler）。
        // 类本身和它的单测都留着不动，只是不再进这条链。
        PromptInjectionTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
        VideoNarrationTransformer,
        VoiceMessageTransformer,
    )
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )
}

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val generationHandler: GenerationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val localTools: LocalTools,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val pluginToolProvider: PluginToolProvider,
    private val pluginLoader: PluginLoader,
    private val workspaceRepository: WorkspaceRepository,
    private val memoryBankService: MemoryBankService,
    private val folderRepository: FolderRepository,
) {
    // workspace 系统提示注入 (依赖 workspaceRepository, 故在类内构造)
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)

    // 统一会话管理
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val _sessionsVersion = MutableStateFlow(0L)

    /**
     * 懒加载窗口起点：conversationId -> 窗口第一条 node 在数据库中的 nodeIndex。
     * 打开对话时只加载最近 CONVERSATION_LOAD_WINDOW_SIZE 条，nodeIndex < 该值的窗口外历史
     * 在保存时会被合并回完整对话，避免部分加载的会话覆盖/删除老消息。
     */
    private val lazyWindowFirstIndex = ConcurrentHashMap<Uuid, Int>()

    // 错误状态
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        if (error is CancellationException) return
        _errors.update {
            it + ChatError(title = title, error = error, conversationId = conversationId, solution = solution)
        }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }

    // 生成完成流
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    // 【插话下半场 2026-09-29】哪些会话还有一句插话等着被接上。
    // 用 set 去重：宝连着插好几句话，只接一次。
    // 【插话搭车 · 2026-09-30 宝的方案】宝插的话：按会话排队，等猫这一回合下一步请求时并进去
    // （合并点在"每一步开头"，那时 tool 结果已经挂在末尾，所以不会插进 tool_calls 和 tool 之间）。
    // 取即清空：提供方用 remove，同一句不会被并进第二步。
    private val pendingInterjections =
        java.util.concurrent.ConcurrentHashMap<Uuid, MutableList<UIMessage>>()

    // 【插话搭车记账 · 2026-10-01】这一回合里真的被搭上车的插话消息 id。
    // 提供方取队列时顺手写进来；收尾合并时读它。
    // 为什么要这本账：搭车那一刻打的 metadata 记号只存在于"请求副本"里，
    // 落库的会话上没有，合并按 metadata 去找永远找不到人（宝实测：折叠条一刷新就散、
    // 日志里从来没有 merged）。账在本侧记，落库会话也认。
    private val rodeInterjectIds =
        java.util.concurrent.ConcurrentHashMap<Uuid, MutableList<String>>()

    // 【插话不落库 · 2026-10-02 宝定的根治】收尾时要挂进猫那条的插话（入队即记，挂完清）。
    // 跟 pendingInterjections 的区别：那本是"搭车队列"（被取走就清，用来判断要不要接力），
    // 这本是"收尾要挂的东西"——不管搭没搭上车都要挂，接力那条也记在这里。
    private val interjectedMessages =
        java.util.concurrent.ConcurrentHashMap<Uuid, MutableList<UIMessage>>()

    // 【数标诊断节流 · 2026-10-05】排"一次插话显示两个折叠条"用，5 秒最多一行，免得把日志环刷爆。
    @Volatile
    private var lastInterjectDupLogAt = 0L
    /** 【条数对账降频 · 2026-10-05】流式每秒约 10 个 chunk，5 秒最多报一行条数不符。 */
    private var lastCountMismatchLogAt = 0L

    // 【collapse 日志节流 · 2026-10-05】collapse 每秒被调十次、每次都吐一行，500 条的环五十秒就被冲干净，
    // 收尾那几行 merge-* 留痕根本活不到排查的时候。改成每 30 次（约 3 秒）一行，给它们留出活路。
    private var collapseLogTick = 0

    // 【插话不落库 · 2026-10-02】排队中的插话，给界面画"排队中"折叠条用。
    // 插话不再进会话（内存/库都不进），界面就没有"独立那条"可读，改读这个流。
    val pendingInterjectionFlow = kotlinx.coroutines.flow.MutableStateFlow<Map<Uuid, List<UIMessage>>>(emptyMap())

    private fun syncPendingInterjectionFlow() {
        pendingInterjectionFlow.value = pendingInterjections.entries.associate { (k, v) -> k to v.toList() }
    }

    /** sendMessage 里"插入消息"那一步的产物（原来用 Triple，插话那条不进会话后多带一个字段）。 */
    private data class InsertResult(
        val assistant: Assistant,
        val processedContent: List<UIMessagePart>,
        val insertedMessage: UIMessage,
        val conversation: Conversation,
    )

    // 前台状态管理
    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> _isForeground.value = true
            Lifecycle.Event.ON_STOP -> _isForeground.value = false
            else -> {}
        }
    }

    // 标记 lifecycleObserver 是否已真正 addObserver 成功。
    // 用于规避 init 的 post 任务还没执行就被 cleanup() 的竞态:
    // 若 addObserver 是异步派发到主线程, cleanup 可能在它之前执行 removeObserver,
    // 此时 observer 实际没挂上(后续 ON_START/ON_STOP 回调丢失, 前后台状态失效)。
    private val observerAdded = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    // 真正执行 addObserver 的 Runnable, 保存引用供 cleanup 精确取消 pending 的 post,
    // 避免用 removeCallbacksAndMessages(null) 误删同 handler 上其它任务。
    private val addObserverRunnable = Runnable {
        try {
            ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
            observerAdded.set(true)
            Log.i(TAG, "Lifecycle observer added")
        } catch (e: Exception) {
            // 例如 ProcessLifecycleOwner 尚未就绪等异常边界, 记日志不崩
            Log.e(TAG, "Failed to add lifecycle observer", e)
        }
    }

    init {
        // 添加生命周期观察者。ProcessLifecycleOwner.get().lifecycle.addObserver
        // 强制要求主线程调用。正常情况下 ChatService 由 RikkaHubApp.onCreate 的
        // 预热调用在主线程构造, 这里直接执行即可。这里加线程判断 + 派发, 是给
        // "万一未来又出现一个在后台线程首次访问 ChatService 的新入口"兜底:
        // 不让它直接崩, 而是把 addObserver 派发到主线程异步执行。
        if (Looper.myLooper() == Looper.getMainLooper()) {
            addObserverRunnable.run()
        } else {
            Log.w(TAG, "ChatService constructed off main thread; dispatching addObserver to main thread")
            mainHandler.post(addObserverRunnable)
        }
    }

    fun cleanup() = runCatching {
        // 同样在主线程操作 observer, 与 addObserver 的执行线程保持一致,
        // 规避"post 中的 add 还没执行, 这里先 remove"导致 observer 实际没挂上的竞态。
        val removeObserverRunnable = Runnable {
            try {
                if (observerAdded.get()) {
                    ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
                    observerAdded.set(false)
                    Log.i(TAG, "Lifecycle observer removed")
                } else {
                    // observer 还没成功 add, 说明 init 的 post 任务可能尚未执行;
                    // 精确取消 pending 的 add, 避免它在 cleanup 之后才跑导致 observer 残留挂载。
                    // 注: Handler.removeCallbacks 返回 void, 无法据返回值判断是否真有任务被取消,
                    // 这里只是尽力取消, 取消不到也无害(任务里会因 observerAdded 仍为 false 而照常 add)。
                    mainHandler.removeCallbacks(addObserverRunnable)
                    Log.i(TAG, "Cancelled pending lifecycle observer add (cleanup before add)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove lifecycle observer", e)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            removeObserverRunnable.run()
        } else {
            mainHandler.post(removeObserverRunnable)
            Unit
        }
        sessions.values.forEach { it.cleanup() }
        sessions.clear()
    }

    // ---- Session 管理 ----

    internal fun getOrCreateSession(conversationId: Uuid): ConversationSession {
        return sessions.computeIfAbsent(conversationId) { id ->
            val settings = settingsStore.settingsFlow.value
            ConversationSession(
                id = id,
                initial = Conversation.ofId(
                    id = id,
                    assistantId = settings.getCurrentAssistant().id
                ),
                scope = appScope,
                onIdle = { removeSession(it) }
            ).also {
                _sessionsVersion.value++
                Log.i(TAG, "createSession: $id (total: ${sessions.size + 1})")
            }
        }
    }

    private fun removeSession(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        if (session.isInUse) {
            Log.d(TAG, "removeSession: skipped $conversationId (still in use)")
            return
        }
        if (sessions.remove(conversationId, session)) {
            session.cleanup()
            _sessionsVersion.value++
            Log.i(TAG, "removeSession: $conversationId (remaining: ${sessions.size})")
        }
    }

    // ---- 引用管理 ----

    fun addConversationReference(conversationId: Uuid) {
        getOrCreateSession(conversationId).acquire()
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessions[conversationId]?.release()
    }

    private fun launchWithConversationReference(
        conversationId: Uuid,
        block: suspend () -> Unit
    ): Job = appScope.launch {
        addConversationReference(conversationId)
        try {
            block()
        } finally {
            removeConversationReference(conversationId)
        }
    }

    // ---- 对话状态访问 ----

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        return getOrCreateSession(conversationId).state
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> {
        val session = sessions[conversationId] ?: return flowOf(null)
        return session.generationJob
    }

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> {
        val session = sessions[conversationId] ?: return MutableStateFlow(null)
        return session.processingStatus
    }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return _sessionsVersion.flatMapLatest {
            val currentSessions = sessions.values.toList()
            if (currentSessions.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(currentSessions.map { s ->
                    s.generationJob.map { job -> s.id to job }
                }) { pairs ->
                    pairs.filter { it.second != null }.toMap()
                }
            }
        }
    }

    // ---- 初始化对话 ----

    suspend fun initializeConversation(conversationId: Uuid) {
        getOrCreateSession(conversationId) // 确保 session 存在
        // 总是从数据库重新加载最新数据，确保能显示主动消息等新内容
        // 【懒加载窗口】只加载最近 CONVERSATION_LOAD_WINDOW_SIZE 条，长对话打开不再全量加载
        val conversation = conversationRepo.getConversationById(conversationId, CONVERSATION_LOAD_WINDOW_SIZE)
        if (conversation != null) {
            // 记录懒加载窗口边界：窗口第一条 node 在数据库中的 nodeIndex（保存时合并窗口外历史用）
            val totalCount = conversationRepo.getMessageNodeCount(conversationId.toString())
            lazyWindowFirstIndex[conversationId] = (totalCount - conversation.messageNodes.size).coerceAtLeast(0)
            // 【2026-08-27 15:06 恢复——5cadb1de 误删导致所有窗口空白！】
            // 注意：这里的 updateConversation 是 ChatService 内部方法（只更新内存态 session.state.value，
            // 不落库、不重写 nodeIndex），它负责把数据库加载的对话写回 session 内存，是 UI 显示的唯一来源！
            // 之前误删（以为是"裸落库压平 nodeIndex"）→ session 永远是空对话 → 所有窗口变空白新对话。
            // 窗口倒退的真凶是 ConversationRepository.updateConversation 的窗口版重写 nodeIndex（已在
            // 5cadb1de 里改为保持原值），跟这一行无关——这行必须保留！
            updateConversation(conversationId, conversation)
            // 只有当前选中助手与该对话的助手不一致时才写 DataStore，
            // 避免每次打开/切换对话都无条件写入 SELECT_ASSISTANT 触发 settingsFlow 全量重组
            val currentSettings = settingsStore.settingsFlow.value
            if (currentSettings.assistantId != conversation.assistantId) {
                settingsStore.updateAssistant(conversation.assistantId)
            }
        } else {
            // 新建对话, 并添加预设消息
            val currentSettings = settingsStore.settingsFlowRaw.first()
            val assistant = currentSettings.getCurrentAssistant()
            val newConversation = Conversation.ofId(
                id = conversationId,
                assistantId = assistant.id,
                newConversation = true
            ).updateCurrentMessages(assistant.presetMessages)
            updateConversation(conversationId, newConversation)
        }
    }

    // ---- 发送消息 ----

    fun sendMessage(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true, quotedMessageId: Uuid? = null) {
        if (content.isEmptyInputMessage()) return
        val tSend = System.currentTimeMillis()

        val session = getOrCreateSession(conversationId)
        // 【插话 2026-09-25 宝的需求】猫正在忙的时候，宝还能把话塞进来。
        // 这种情况不取消当前这一轮（让它自然跑完），消息照样存下来，
        // 不在这个函数里触发新的生成，免得两轮打架；等当前那轮跑完再自动接上。
        //
        // 【插话下半场 2026-09-29】两个补充：
        //   ① 这条 job 不注册进 session（见函数末尾）——ConversationSession.setJob()
        //      第一句就是 _generationJob.value?.cancel()，是"强制抢占"语义
        //      （原设计用于"用户主动发消息，打断旧生成"）。插话场景下它会把正在跑的
        //      那轮掐掉（日志实证：被插话那轮 contentTotal 少一截、finish=unknown），
        //      所以插话时不走 setJob。
        //   ② 当前那轮跑完后自动接上：起一个接力协程等 busyJob 结束，再跑一次生成。
        val busyJob = session.getJob()
        val isInterjection = busyJob?.isActive == true
        if (!isInterjection) {
            busyJob?.cancel()
        }

        val job = appScope.launch {
            try {
                val settings = settingsStore.settingsFlow.first()
                AppLogBuffer.log(TAG, "sendMessage: dispatch took=${System.currentTimeMillis() - tSend}ms")

                // 用户发送消息时重置主动消息计时器（异步执行，不阻塞发消息主流程）
                try {
                    val proactiveSetting = settings.proactiveMessageSetting
                    if (proactiveSetting.enabled) {
                        me.rerere.rikkahub.data.service.ProactiveMessageService.resetTimer(context, proactiveSetting)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("ChatService", "Failed to reset proactive timer", e)
                }

                // 读取最新状态 -> 追加用户消息 -> 落库，整体加锁。
                // 防止跟同一时刻可能在跑的标题生成/建议生成/语音通话挂断反馈互相覆盖对方刚写入的消息。
                val insert = withContext(Dispatchers.IO) {
                    session.saveMutex.withLock {
                        val t0 = System.currentTimeMillis()
                        val latestConversation = session.state.value
                        val assistant = settings.getAssistantById(latestConversation.assistantId)
                            ?: settings.getCurrentAssistant()
                        val processedContent = preprocessUserInputParts(content, assistant)

                        val insertedMessage = UIMessage(
                            role = MessageRole.USER,
                            parts = processedContent,
                            // 【消息引用 2026-09-22】这条在回复哪一条（宝长按消息选的"引用"）
                            quotedMessageId = quotedMessageId,
                        )
                        // 【抓鬼 · 2026-10-06】一次点击被处理了几次，看这里。
                        // 两行同样的文本 = 客户端/入口把同一条送了两遍。
                        run {
                            val txt = processedContent.filterIsInstance<UIMessagePart.Text>()
                                .joinToString("") { it.text }.trim()
                            AppLogBuffer.log(
                                TAG,
                                "[Make] id=${insertedMessage.id} interject=$isInterjection " +
                                    "len=${txt.length} text=${txt.take(24)}"
                            )
                        }
                        // 【插话不落库 · 2026-10-02 宝定的根治】插话不进会话（内存和库都不进）。
                        // 它本来就是"挂在猫那条回复里的一段话"，存储层就不该有独立的一条。
                        // 老路子：先造一条独立的 → 收尾合并 → 再删掉 → 显示层再靠锚点过滤；
                        // 四层补丁谁漏谁冒（宝实测：重复显示、列表比节点树多一条 → 2/2 分支）。
                        val newConversation = if (isInterjection) latestConversation else latestConversation.copy(
                            messageNodes = latestConversation.messageNodes + insertedMessage.toMessageNode(),
                        )
                        if (!isInterjection) {
                            // 【先显示再落库 2026-08-28】用户消息先进内存态 → UI 立刻显示（秒显）；
                            // 落库（窗口 diff 读全量比较）可能慢（几百 ms~几秒），放后台感知不到。
                            // saveConversation 内部最后会再 updateConversation 一次（裁剪成窗口态），最终状态一致。
                            updateConversation(conversationId, newConversation)
                            saveConversation(conversationId, newConversation)
                        }
                        AppLogBuffer.log(TAG, "sendMessage: in-lock insert+save took=${System.currentTimeMillis() - t0}ms (size=${newConversation.messageNodes.size}) interject=$isInterjection")
                        // 【插话吞正文调查 · 2026-10-05】插话进来的那一刻，把当前那条猫消息的 parts
                        // 抄一份。三处守门（条数对账 / 走错格 STRAY / 合回后的 tail）都没报警，
                        // 而宝眼睛看见"插话之后猫的正文不见了" —— 丢的地方在守门射程外，得抓这一刻。
                        if (isInterjection) {
                            val head = newConversation.messageNodes.lastOrNull()?.messages?.lastOrNull()
                            val desc = head?.parts?.joinToString("") { pt ->
                                when (pt) {
                                    is UIMessagePart.Text -> "T${pt.text.length}"
                                    is UIMessagePart.Reasoning -> "R${pt.reasoning.length}"
                                    is UIMessagePart.Tool -> "Tool"
                                    else -> "?"
                                }
                            } ?: "-"
                            val headText = head?.parts
                                ?.filterIsInstance<UIMessagePart.Text>()
                                ?.joinToString("") { it.text }
                                ?.take(40) ?: "-"
                            AppLogBuffer.log(
                                TAG,
                                "[Interject] AT-SEND nodes=${newConversation.messageNodes.size} role=${head?.role} parts=$desc headText=$headText"
                            )
                        }
                        InsertResult(assistant, processedContent, insertedMessage, newConversation)
                    }
                }
                val assistant = insert.assistant
                val processedContent = insert.processedContent
                val insertedMessage = insert.insertedMessage
                val newConversation = insert.conversation
                AppLogBuffer.log(TAG, "sendMessage: lock released at ${System.currentTimeMillis() - tSend}ms")

                // 【语音情绪分析 V1 2026-09-04·宝的"情绪耳朵"】语音条上屏后后台调 Qwen3-Omni 听音频，
                // 出语气/语速 → 写回该消息 VoiceMessage 的 metadata → VoiceMessageTransformer 拼
                // 「（语气：X，语速：Y）」尾巴喂 DeepSeek。fire-and-forget：不阻塞发送/生成主流程；
                // 分析完成时若消息已被后续触发带走（无 tone），尾巴留空退回纯转述——不破坏缓存前缀。
                runCatching {
                    // 【插话不落库 · 2026-10-02】插话不进会话，这里的 last 会是猫那条 → 插话时跳过语音情绪分析
                    val addedMessage = if (isInterjection) null else newConversation.messageNodes.lastOrNull()?.messages?.firstOrNull()
                    val voicePart = addedMessage?.parts?.filterIsInstance<UIMessagePart.VoiceMessage>()?.firstOrNull()
                    val asrProvider = settings.getSelectedASRProvider()
                    if (addedMessage != null && voicePart != null && voicePart.transcript.isNotBlank()
                        && asrProvider is ASRProviderSetting.SiliconFlow && asrProvider.apiKey.isNotBlank()
                    ) {
                        val targetId = addedMessage.id
                        val audioPath = voicePart.url
                        val apiKey = asrProvider.apiKey
                        appScope.launch {
                            val toneResult = VoiceToneAnalyzer.analyze(apiKey, audioPath)
                            if (toneResult != null) {
                                session.saveMutex.withLock {
                                    val cur = session.state.value
                                    val updated = cur.copy(
                                        messageNodes = cur.messageNodes.map { node ->
                                            if (node.messages.any { it.id == targetId }) {
                                                node.copy(messages = node.messages.map { msg ->
                                                    if (msg.id == targetId) {
                                                        msg.copy(parts = msg.parts.map { p ->
                                                            if (p is UIMessagePart.VoiceMessage && p.transcript.isNotBlank()) {
                                                                p.copy(
                                                                    metadata = JsonObject(
                                                                        mapOf(
                                                                            "tone" to JsonPrimitive(toneResult.tone),
                                                                            "speed" to JsonPrimitive(toneResult.speed),
                                                                        )
                                                                    )
                                                                )
                                                            } else p
                                                        })
                                                    } else msg
                                                })
                                            } else node
                                        }
                                    )
                                    updateConversation(conversationId, updated)
                                    AppLogBuffer.log(
                                        TAG,
                                        "voiceTone: analyzed msg=$targetId tone=${toneResult.tone}/${toneResult.speed}",
                                    )
                                }
                            }
                        }
                    }
                }.onFailure { e ->
                    Log.w(TAG, "voiceTone: dispatch failed", e)
                }

                // 触发 message_sent 事件钩子
                // 关键: 这里用 appScope.launch 提交独立协程, 而不是直接 await callEvent。
                // 原因: callEvent 内部对订阅插件的 handler 在单线程 pluginDispatcher 上串行执行,
                // supabase_memory 等插件会同步 fetch 网络请求 (最长 15s 超时)。若直接 await,
                // 用户点发送后会卡在这里直到所有插件 handler 跑完才继续走 sendMessage 后续逻辑。
                // 改为 fire-and-forget 提交到 AppScope (SupervisorJob) 上, 不挂在当前 sendMessage
                // 的 job 下 —— 这样用户连续发消息触发 session.getJob()?.cancel() 取消上一条消息 job 时,
                // 不会把这次插件同步也连累取消掉 (Supabase 记录保持完整)。
                runCatching {
                    val eventData = JsonObject(
                        mapOf(
                            "assistant_id" to JsonPrimitive(assistant.id.toString()),
                            "conversation_id" to JsonPrimitive(conversationId.toString()),
                            "message" to JsonPrimitive(processedContent.mapNotNull { part ->
                                if (part is UIMessagePart.Text) part.text else null
                            }.joinToString("\n")),
                            "role" to JsonPrimitive("user"),
                            "timestamp" to JsonPrimitive(System.currentTimeMillis())
                        )
                    )
                    appScope.launch {
                        try {
                            pluginLoader.callEvent("message_sent", eventData)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to trigger message_sent event", e)
                        }
                    }
                }.onFailure { e ->
                    Log.w(TAG, "Failed to trigger message_sent event", e)
                }

                // 保存用户消息到外置记忆库（fire-and-forget，不阻塞后续生成流程）
                try {
                    val settingsRaw = settingsStore.settingsFlowRaw.first()
                    val externalMemoryConfigs = settingsRaw.externalMemories.filter {
                        it.enabled && it.id in assistant.externalMemoryIds && it.autoSaveMessages
                    }
                    if (externalMemoryConfigs.isNotEmpty()) {
                        val textParts = processedContent.mapNotNull { part ->
                            when (part) {
                                is UIMessagePart.Text -> part.text
                                is UIMessagePart.VoiceMessage ->
                                    if (part.transcript.isNotBlank()) part.transcript else "[语音消息]"
                                else -> null
                            }
                        }.joinToString("\n")
                        // 真图带文字脸（2026-09-08 宝拍板）：file:// 真图由视觉模型转述内容拼进落库文本——
                        // 云端图片记录不再"哑巴"，归档总结/记忆召回能"看见"图；表情包/网络图是 Text markdown
                        // 本来就落库原文，不在这里处理。转述失败给 [图片] 兜底（至少留个存在标记，比完全丢强）。
                        val imageParts = processedContent.filterIsInstance<UIMessagePart.Image>()
                        externalMemoryConfigs.forEach { config ->
                            appScope.launch {
                                runCatching {
                                    val service = me.rerere.rikkahub.data.service.ExternalMemoryService(config)
                                    var messageText = textParts
                                    if (imageParts.isNotEmpty()) {
                                        val descriptions = mutableListOf<String>()
                                        for (img in imageParts) {
                                            val desc = ImageDescriber.describe(img.url)
                                            descriptions.add(if (desc.isNullOrBlank()) "[图片]" else "[图片] $desc")
                                        }
                                        if (messageText.isNotBlank()) messageText += "\n"
                                        messageText += descriptions.joinToString("\n")
                                    }
                                    service.saveMessage(
                                        assistantId = assistant.id.toString(),
                                        conversationId = conversationId.toString(),
                                        role = "user",
                                        content = messageText,
                                    )
                                }.onFailure {
                                    Log.w(TAG, "Failed to save user message to external memory ${config.name}", it)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to save user message to external memory", e)
                }

                // 斜杠命令直执行（宝 2026-09-01：/开头 = 客户端直执行 MCP 工具，不经过 AI；宝要自己玩桌游/潮汐岛）
                val slashText = processedContent.mapNotNull { part ->
                    if (part is UIMessagePart.Text) part.text else null
                }.joinToString(" ").trim()
                if (slashText.startsWith("/") && slashText.length > 1) {
                    val handled = handleSlashCommandDirect(conversationId, slashText)
                    if (handled) {
                        AppLogBuffer.log(TAG, "sendMessage: slash command handled directly, skip generation: $slashText")
                        return@launch
                    }
                    // 未匹配到工具 → 继续走 AI 生成（AI 兜底解释支持的命令）
                }

                // 开始补全
                AppLogBuffer.log(TAG, "sendMessage: about to generate at ${System.currentTimeMillis() - tSend}ms")
                // 【插话 2026-09-25】插话时不启动新一轮：当前那轮还在跑，等它自然收尾。
                if (answer && !isInterjection) {
                    handleMessageComplete(conversationId)
                } else if (isInterjection && answer) {
                    // 【插话搭车 · 2026-09-30 宝的方案】排进队列，等猫这一回合下一步请求时自然带上
                    // （合并点在 GenerationHandler 每步开头）。入队放在 job 内部做：
                    // insertedMessage 是上面 withContext 的返回值，只有这个作用域够得着。
                    // 【插话不落库 · 2026-10-02】入队的就是那条消息本身（它没进会话，不用再去 last() 里捞）
                    pendingInterjections.computeIfAbsent(conversationId) {
                        java.util.Collections.synchronizedList(mutableListOf<UIMessage>())
                    }.add(insertedMessage)
                    // 【收尾要挂的账 · 2026-10-02】不管搭不搭得上车都得挂，所以入队这刻就记一笔
                    //（接力那条也在这本账里，于是它也会被合并）
                    interjectedMessages.computeIfAbsent(conversationId) {
                        java.util.Collections.synchronizedList(mutableListOf<UIMessage>())
                    }.add(insertedMessage)
                    syncPendingInterjectionFlow()
                    AppLogBuffer.log(
                        TAG,
                        "interject: queued for ride conv=$conversationId " +
                            "id=${insertedMessage.id.toString().take(8)} " +
                            "len=" + insertedMessage.parts.filterIsInstance<UIMessagePart.Text>()
                                .sumOf { it.text.length } +
                            " size=${newConversation.messageNodes.size}"
                    )
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e(TAG, "sendMessage failed, conversationId=$conversationId", e)
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
        if (!isInterjection) {
            session.setJob(job)
        } else if (answer) {
            // 【插话下半场 2026-09-29】不注册这条 job（免得 setJob 的强制抢占掐掉正在跑的那轮）。
            // 【插话搭车 · 2026-09-30 宝的方案】入队那一步在 job 内部完成（那里才够得着 newConversation）；
            // 这里只挂兜底：等当前那轮跑完再看队列——还在，说明这一轮没搭上车，才补开一轮。
            val previousJob = busyJob
            appScope.launch {
                runCatching { previousJob?.join() }
                // 让当前那轮的收尾（落库等）写完再接手
                kotlinx.coroutines.delay(300)
                val leftover = pendingInterjections.remove(conversationId)
                syncPendingInterjectionFlow()
                if (!leftover.isNullOrEmpty()) {
                    // 队列还在 = 这一轮没有第二次请求，宝那句没人接 → 兜底起一轮
                    AppLogBuffer.log(TAG, "interject: no ride happened, relay generation conv=$conversationId")
                    // 【接力修 · 2026-10-04 宝实测】把拿出来的插话放回队列，再起接力那一轮。
                    // 原来只用不还，带出两个毛病：
                    //   ① 接力那轮的请求里没有宝的话（模型压根看不到她说了什么）
                    //   ② 取走时才记的"搭车账"（rodeInterjectIds）是空的 → 收尾找不到锚点，
                    //      插话被兜底规则扔到最后一条末尾
                    // 放回去之后，接力这轮会像平常一样把它取走并进请求、顺手记账——一次修好两个。
                    pendingInterjections.computeIfAbsent(conversationId) {
                        java.util.Collections.synchronizedList(mutableListOf<UIMessage>())
                    }.addAll(leftover!!)
                    syncPendingInterjectionFlow()
                    runCatching { handleMessageComplete(conversationId) }
                } else {
                    AppLogBuffer.log(TAG, "interject: rode the turn, no relay needed conv=$conversationId")
                }
            }
        }
    }

    // ---- 添加主动消息 ----

    fun addProactiveMessage(conversationId: Uuid, aiMessage: UIMessage) {
        launchWithConversationReference(conversationId) {
            try {
                appendProactiveAiMessageUnderLock(conversationId, aiMessage)
            } catch (e: Exception) {
                Log.e(TAG, "addProactiveMessage failed, conversationId=$conversationId", e)
            }
        }
    }

    /**
     * 在 saveMutex 保护下，把 AI 主动生成的消息追加到对话并落库。
     * 与 sendMessage/regenerate 等共享同一把锁，避免 read-modify-write 竞态导致消息被覆盖。
     */
    private suspend fun appendProactiveAiMessageUnderLock(
        conversationId: Uuid,
        aiMessage: UIMessage,
    ) {
        val session = getOrCreateSession(conversationId)

        // 等待当前正在进行的生成任务（如果有）先完全结束，再追加这条主动消息。
        // 原因：主生成流程会按 index 位置往它自己的消息节点写入流式增量内容
        // (Conversation.updateCurrentMessages 是按位置对齐的，不认节点归属)。
        // 如果不等待，这里基于当下状态追加的新节点会占据下一个 index 位置，
        // 导致主生成流程后续到达的 chunk 被错误地合并进这条主动消息节点里，
        // 表现为消息分支 <2/2> 错乱、内容被覆盖。
        session.getJob()?.let { job ->
            if (job.isActive) {
                Log.i(TAG, "appendProactiveAiMessageUnderLock: waiting for ongoing generation to finish, conversationId=$conversationId")
                job.join()
            }
        }

        session.saveMutex.withLock {
            // 优先从数据库读取完整对话，避免 session 被 idle 清除后用空对话覆盖数据库已有数据
            val currentConversation = conversationRepo.getConversationById(conversationId)
                ?: session.state.value
            val updated = currentConversation.copy(
                messageNodes = currentConversation.messageNodes + aiMessage.toMessageNode(),
                updateAt = java.time.Instant.now()
            )
            // 2026-08-27：去掉裸 updateConversation——saveConversation 内部会以窗口版统一保存，
            // 裸调（null）会走全量 diff，传入全量虽不删但会把 nodeIndex 重写，存在错位风险
            saveConversation(conversationId, updated)
        }
    }

    // ---- 斜杠命令直执行（宝 2026-09-01：/开头 = 客户端直执行 MCP 工具，不经过 AI）----

    /**
     * 斜杠命令直执行：/工具名 参数... 或 /服务器名 工具名 参数...
     * 直接在客户端调 MCP 工具（不走 AI 生成链路），结果作为 AI 消息追加到对话。
     * @return true = 已处理（结果已追加）；false = 未匹配到工具（调用方继续走 AI 兜底）
     */
    private suspend fun handleSlashCommandDirect(conversationId: Uuid, slashText: String): Boolean {
        return try {
            val settings = settingsStore.settingsFlow.first()
            val assistant = settings.getCurrentAssistant()
            val allMcpTools = mcpManager.getAllAvailableTools() // List<Pair<Uuid, McpTool>>
            if (allMcpTools.isEmpty()) return false

            // 解析命令
            val trimmed = slashText.substring(1).trim()
            val tokens = trimmed.split(Regex("\\s+"))
            if (tokens.isEmpty() || tokens[0].isEmpty()) return false

            // 特殊命令：/mcp 或 /工具 列出所有可用 MCP 工具
            if (tokens[0] == "mcp" || tokens[0] == "工具") {
                val sb = StringBuilder("可用 MCP 工具（服务器名 + 工具名）：\n")
                val servers = settings.mcpServers.filter { it.commonOptions.enable && it.id in assistant.mcpServers }
                servers.forEach { server ->
                    sb.appendLine("【${server.commonOptions.name}】")
                    allMcpTools.filter { it.first == server.id }.forEach { (_, tool) ->
                        sb.appendLine("  /${server.commonOptions.name} ${tool.name}")
                    }
                }
                appendSlashResult(conversationId, slashText, sb.toString())
                return true
            }

            // 尝试匹配服务器名（第一个 token 是服务器名？）
            val servers = settings.mcpServers.filter { it.commonOptions.enable && it.id in assistant.mcpServers }
            val serverByName = servers.firstOrNull { it.commonOptions.name == tokens[0] }
            val toolName: String
            val argText: String
            val serverFilter: Uuid?
            if (serverByName != null) {
                serverFilter = serverByName.id
                if (tokens.size < 2) {
                    // 只写了服务器名，没写工具名 → 列出该服务器工具
                    val sb = StringBuilder("【${serverByName.commonOptions.name}】可用工具：\n")
                    allMcpTools.filter { it.first == serverByName.id }.forEach { (_, tool) ->
                        sb.appendLine("  /${serverByName.commonOptions.name} ${tool.name}")
                    }
                    appendSlashResult(conversationId, slashText, sb.toString())
                    return true
                }
                toolName = tokens[1]
                argText = tokens.drop(2).joinToString(" ")
            } else {
                serverFilter = null
                toolName = tokens[0]
                argText = tokens.drop(1).joinToString(" ")
            }

            // 匹配工具
            val candidates = allMcpTools.filter { (sid, tool) ->
                tool.name == toolName && (serverFilter == null || sid == serverFilter)
            }
            if (candidates.isEmpty()) return false // 未匹配，走 AI 兜底

            // 同名工具多个候选（不同服务器）→ 提示用服务器名区分
            if (candidates.size > 1 && serverFilter == null) {
                val serverNames = candidates.mapNotNull { (sid, _) ->
                    servers.firstOrNull { it.id == sid }?.commonOptions?.name
                }.distinct()
                appendSlashResult(
                    conversationId, slashText,
                    "「$toolName」在多个服务器都有，请指定服务器：\n" +
                        serverNames.joinToString("\n") { "  /$it $toolName ..." }
                )
                return true
            }

            val (serverId, tool) = candidates.first()

            // 解析参数
            val args = parseSlashCommandArgs(tool, argText)

            // 调用工具
            val result = mcpManager.callTool(serverId, tool.name, args)
            val resultText = result.mapNotNull { part ->
                when (part) {
                    is UIMessagePart.Text -> part.text
                    is UIMessagePart.Image -> "[图片] ${part.url}"
                    else -> part.toString()
                }
            }.joinToString("\n").ifBlank { "(无输出)" }

            appendSlashResult(conversationId, slashText, resultText)
            true
        } catch (e: Exception) {
            Log.w(TAG, "handleSlashCommandDirect failed", e)
            appendSlashResult(conversationId, slashText, "❌ 执行失败：${e.message ?: e.javaClass.simpleName}")
            true
        }
    }

    /** 解析斜杠命令参数：JSON 优先，否则单参数塞进唯一属性，否则空参数 */
    private fun parseSlashCommandArgs(tool: McpTool, argText: String): JsonObject {
        val text = argText.trim()
        if (text.isEmpty()) return JsonObject(emptyMap())
        // JSON 参数（{...}）
        if (text.startsWith("{")) {
            return runCatching { Json.parseToJsonElement(text).jsonObject }
                .getOrElse { JsonObject(emptyMap()) }
        }
        // 单参数工具（潮汐岛 command 风格）：塞进唯一属性
        val schema = tool.inputSchema as? InputSchema.Obj
        if (schema != null && schema.properties.size == 1) {
            val key = schema.properties.keys.first()
            return buildJsonObject { put(key, text) }
        }
        // 其他：空参数（工具自己会提示缺什么）
        return JsonObject(emptyMap())
    }

    /** 把斜杠命令结果作为 AI 消息追加到对话（不加锁等待——当前就是 sendMessage 的 job） */
    private suspend fun appendSlashResult(conversationId: Uuid, cmd: String, resultText: String) {
        val session = getOrCreateSession(conversationId)
        session.saveMutex.withLock {
            val currentConversation = conversationRepo.getConversationById(conversationId)
                ?: session.state.value
            val updated = currentConversation.copy(
                messageNodes = currentConversation.messageNodes + UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text("⚡ $cmd\n\n$resultText")),
                ).toMessageNode(),
                updateAt = java.time.Instant.now()
            )
            saveConversation(conversationId, updated)
        }
    }

    // ---- 语音通话被拒接通知 ----

    /**
     * AI 主动发起的语音通话被用户拒接时, 另起一轮轻量文本生成,
     * 把"电话被挂断"作为新的一条独立 assistant 消息追加进对话.
     * 不走工具调用循环 / 插件事件钩子 / 外置记忆库 / 标题建议生成,
     * 参考 generateTitle / generateSuggestion 的轻量调用方式.
     */
    fun notifyVoiceCallDeclined(conversationId: Uuid) {
        appScope.launch(Dispatchers.IO) {
            try {
                val session = getOrCreateSession(conversationId)

                // 等待当前正在进行的主生成任务（如果有）先完全结束，再读取历史、生成反馈。
                // 1) 保证读到的对话历史是完整、最新的，不会读到 AI 还没说完那半句话的旧状态；
                // 2) 保证后面 addProactiveMessage 追加反馈时不会跟还在跑的流式生成发生位置错位。
                session.getJob()?.let { job ->
                    if (job.isActive) {
                        Log.i(TAG, "notifyVoiceCallDeclined: waiting for ongoing generation to finish, conversationId=$conversationId")
                        job.join()
                    }
                }

                val settings = settingsStore.settingsFlow.first()
                // 优先从数据库取完整对话, 避免 session 被 idle 清除后用空对话覆盖
                val currentConversation = conversationRepo.getConversationById(conversationId)
                    ?: getConversationFlow(conversationId).value
                val assistant = settings.getAssistantById(currentConversation.assistantId)
                    ?: settings.getCurrentAssistant()
                val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
                if (model == null) {
                    Log.e(TAG, "notifyVoiceCallDeclined: no model found, conversationId=$conversationId")
                    return@launch
                }
                val provider = model.findProvider(settings.providers)
                if (provider == null) {
                    Log.e(TAG, "notifyVoiceCallDeclined: no provider found, conversationId=$conversationId, modelId=${model.id}")
                    return@launch
                }
                val providerHandler = providerManager.getProviderByType(provider)

                val historyMessages = currentConversation.currentMessages.let {
                    if (assistant.contextMessageSize > 0) it.takeLast(assistant.contextMessageSize) else it
                }

                // 记录生成开始前的消息节点数量，作为"生成期间是否有新消息插入"的判断基准
                val nodeCountBeforeGeneration = currentConversation.messageNodes.size

                val eventDescription = "[系统事件] 你刚刚主动发起的语音通话邀请被用户直接挂断了（拒接，未接听）。请用你自己的语气自然地回应这件事，简短即可，不要提及\"系统事件\"\"工具\"等技术词汇，不要用引号或标注包裹，就当作正常聊天说一句话。"

                val messages = buildList {
                    if (assistant.systemPrompt.isNotEmpty()) {
                        add(UIMessage(role = MessageRole.SYSTEM, parts = listOf(UIMessagePart.Text(assistant.systemPrompt))))
                    }
                    addAll(historyMessages)
                    add(UIMessage.user(eventDescription))
                }

                val result = providerHandler.generateText(
                    providerSetting = provider,
                    messages = messages,
                    params = TextGenerationParams(
                        model = model,
                        temperature = assistant.temperature ?: 0.8f,
                        topP = assistant.topP,
                        maxTokens = assistant.maxTokens,
                        reasoningLevel = ReasoningLevel.OFF,
                        customHeaders = buildList {
                            addAll(assistant.customHeaders)
                            addAll(model.customHeaders)
                        },
                        customBody = buildList {
                            addAll(assistant.customBodies)
                            addAll(model.customBodies)
                        },
                    ),
                )

                val replyText = result.choices[0].message?.toText()?.trim().orEmpty()
                if (replyText.isBlank()) {
                    Log.w(TAG, "notifyVoiceCallDeclined: empty reply, conversationId=$conversationId")
                    return@launch
                }

                // 生成耗时期间，用户可能已经发了新消息 —— 这种情况下这句"被挂断了"的抱怨已经不合语境，直接放弃写入
                val latestConversation = conversationRepo.getConversationById(conversationId)
                    ?: getConversationFlow(conversationId).value
                val newNodesSinceStart = if (latestConversation.messageNodes.size > nodeCountBeforeGeneration) {
                    latestConversation.messageNodes.subList(nodeCountBeforeGeneration, latestConversation.messageNodes.size)
                } else {
                    emptyList()
                }
                val userSentNewMessage = newNodesSinceStart.any { node ->
                    node.messages.any { it.role == MessageRole.USER }
                }
                if (userSentNewMessage) {
                    Log.i(TAG, "notifyVoiceCallDeclined: user already sent a new message during generation, skip. conversationId=$conversationId")
                    return@launch
                }

                val aiMessage = UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text(replyText))
                )
                addProactiveMessage(conversationId, aiMessage)

                if (!isForeground.value) {
                    val senderName = if (assistant.useAssistantAvatar) {
                        assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
                    } else {
                        model.displayName
                    }
                    sendGenerationDoneNotification(conversationId, senderName)
                }
            } catch (e: Exception) {
                Log.e(TAG, "notifyVoiceCallDeclined failed, conversationId=$conversationId", e)
            }
        }
    }

    private fun preprocessUserInputParts(parts: List<UIMessagePart>, assistant: Assistant): List<UIMessagePart> {
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    part.copy(
                        text = part.text.replaceRegexes(
                            assistant = assistant,
                            scope = AssistantAffectScope.USER,
                            visual = false
                        )
                    )
                }

                else -> part
            }
        }
    }

    // ---- 重新生成消息 ----

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()

        val job = appScope.launch {
            try {
                if (message.role == MessageRole.USER) {
                    // 如果是用户消息，则截止到当前消息
                    session.saveMutex.withLock {
                        val conversation = session.state.value
                        val node = conversation.getMessageNodeByMessage(message)
                        val indexAt = conversation.messageNodes.indexOf(node)
                        val newConversation = conversation.copy(
                            messageNodes = conversation.messageNodes.subList(0, indexAt + 1)
                        )
                        saveConversation(conversationId, newConversation)
                    }
                    handleMessageComplete(conversationId)
                } else {
                    if (regenerateAssistantMsg) {
                        val conversation = session.state.value
                        val node = conversation.getMessageNodeByMessage(message)
                        val nodeIndex = conversation.messageNodes.indexOf(node)
                        handleMessageComplete(conversationId, messageRange = 0..<nodeIndex)
                    } else {
                        session.saveMutex.withLock {
                            saveConversation(conversationId, session.state.value)
                        }
                    }
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                Log.e(TAG, "regenerateAtMessage failed, conversationId=$conversationId", e)
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            }
        }

        session.setJob(job)
    }

    // ---- 处理工具调用审批 ----

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
    ) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()

        val job = appScope.launch {
            try {
                val newApprovalState = when {
                    answer != null -> ToolApprovalState.Answered(answer)
                    approved -> ToolApprovalState.Approved
                    else -> ToolApprovalState.Denied(reason)
                }

                val updatedNodes = session.saveMutex.withLock {
                    val conversation = session.state.value
                    val updatedNodes = conversation.messageNodes.map { node ->
                        node.copy(
                            messages = node.messages.map { msg ->
                                msg.copy(
                                    parts = msg.parts.map { part ->
                                        when {
                                            part is UIMessagePart.Tool && part.toolCallId == toolCallId -> {
                                                part.copy(approvalState = newApprovalState)
                                            }

                                            else -> part
                                        }
                                    }
                                )
                            }
                        )
                    }
                    val updatedConversation = conversation.copy(messageNodes = updatedNodes)
                    saveConversation(conversationId, updatedConversation)
                    updatedNodes
                }

                // Check if there are still pending tools
                val hasPendingTools = updatedNodes.any { node ->
                    node.currentMessage.parts.any { part ->
                        part is UIMessagePart.Tool && part.isPending
                    }
                }

                // Only continue generation when all pending tools are handled
                if (!hasPendingTools) {
                    handleMessageComplete(conversationId)
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                Log.e(TAG, "handleToolApproval failed, conversationId=$conversationId, toolCallId=$toolCallId", e)
                addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            }
        }

        session.setJob(job)
    }

    // ---- 处理消息补全 ----

    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null
    ) {
        val settings = settingsStore.settingsFlow.first()
        val initialConversation = getConversationFlow(conversationId).value
        val assistant = settings.getAssistantById(initialConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId) ?: return

        val senderName = if (assistant.useAssistantAvatar) {
            assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        } else {
            model.displayName
        }

        // session 需要在 runCatching 外声明，以便 .onSuccess 中也能访问 saveMutex
        val session = getOrCreateSession(conversationId)

        runCatching {

            // reset suggestions
            updateConversation(conversationId, initialConversation.copy(chatSuggestions = emptyList()))

            // memory tool
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                if (settings.enableWebSearch || mcpManager.getAllAvailableTools().isNotEmpty()) {
                    addError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable)
                    )
                }
            }

            // check invalid messages
            checkInvalidMessages(conversationId)
            val conversation = getConversationFlow(conversationId).value

            // start generating
            generationHandler.generateText(
                settings = settings,
                model = model,
                processingStatus = session.processingStatus,
                messages = conversation.currentMessages.let {
                    if (messageRange != null) {
                        it.subList(messageRange.start, messageRange.endInclusive + 1)
                    } else {
                        it
                    }
                }.let { msgs ->
                    // 【消息引用 2026-09-22】被引原文由 QuotedMessageTransformer 拼（只改请求，不落库）
                    // 【插话还原 · 2026-10-01】合并后宝那句挂在猫的回复里（part 带 interject 标），
                    // 发之前拆回来：猫的正文A ／ 宝的话（独立 user 消息）／ 猫的正文B。
                    // 不拆的话模型会以为那句是它自己说的。
                    expandInterjections(msgs)
                },
                assistant = assistant,
                conversationSystemPrompt = conversation.customSystemPrompt,
                workspaceCwd = conversation.workspaceCwd,
                // 【窗口起点节拍 · 2026-09-11】把懒加载窗口起点传给最近事件节拍器：
                // 用"窗口往前滚了多少条"当判据，替代被封顶的"窗口条数差值"（详见 GenerationHandler 注释）
                windowFirstIndex = lazyWindowFirstIndex[conversationId],
                // 【浮现节拍 · 2026-09-30 宝纠正】读的是"请求体起点 k 往前跳的累计量"。
                // 旧版读的是窗口裁剪累计（数错了量：窗口裁剪时请求体一个字没变，浮现白碎）。
                // 写回交给 GenerationHandler 的 onSurfacingAdvance 回调 —— 只有那边算得出 k。
                surfacingScroll = context
                    .getSharedPreferences(SURFACING_PREFS, Application.MODE_PRIVATE)
                    .getLong("surfacing_scroll_k_$conversationId", 0L),
                surfacingLastK = context
                    .getSharedPreferences(SURFACING_PREFS, Application.MODE_PRIVATE)
                    .getLong("surfacing_lastk_$conversationId", -1L)
                    .takeIf { it >= 0 }?.toInt(),
                onSurfacingAdvance = { newScroll, newK ->
                    context.getSharedPreferences(SURFACING_PREFS, Application.MODE_PRIVATE)
                        .edit()
                        .putLong("surfacing_scroll_k_$conversationId", newScroll)
                        .putLong("surfacing_lastk_$conversationId", newK.toLong())
                        .apply()
                },
                // 【插话搭车 · 2026-09-30】取即清空：这一回合每步请求前看一眼排队中的插话，
                // 有就并进去（宝的话跟着猫的下一口气走），取完队列空 = 兜底接力不会再补一轮。
                pendingInterjections = {
                    val taken = pendingInterjections.remove(conversationId) ?: emptyList()
                    // 【搭车记账 · 2026-10-01】取走的同时记一笔：这些 id 真的被并进去了。
                    // 收尾合并时不靠 metadata 猜，直接读这本账。
                    if (taken.isNotEmpty()) {
                        rodeInterjectIds.computeIfAbsent(conversationId) {
                            java.util.Collections.synchronizedList(mutableListOf<String>())
                        }.addAll(taken.map { it.id.toString() })
                    }
                    // 【插话不落库 · 2026-10-02】队列被取走＝这些已搭上车，界面上的"排队中"该撤了
                    //（接下来由猫那条消息里的折叠条接管）
                    syncPendingInterjectionFlow()
                    taken
                },
                memories = if (assistant.useGlobalMemory) {
                    memoryRepository.getGlobalMemories()
                } else {
                    memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
                },
                inputTransformers = buildList {
                    addAll(inputTransformers)
                    add(templateTransformer)
                    add(workspaceReminderTransformer)
                    // 【消息引用 2026-09-22】把被引原文拼进请求（挂在最后一条用户消息前，不落库）
                    add(QuotedMessageTransformer)
                },
                outputTransformers = outputTransformers,
                tools = buildList {
                    if (settings.enableWebSearch) {
                        addAll(createSearchTools(settings))
                    }
addAll(localTools.getTools(assistant.localTools, me.rerere.rikkahub.data.ai.tools.ToolInvocationContext(
    callerAssistantId = assistant.id.toString(),
    callerConversationId = conversationId.toString(),
)))
                    // System tools (location, notifications, calendar, alarm, camera)
                    val systemToolsOptions = settings.systemToolsSetting.getEnabledOptions().toMutableSet()
                    // 如果存在启用的外置记忆库，始终启用 supabase_query 工具
                    if (settings.externalMemories.any { it.enabled }) {
                        systemToolsOptions.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.SupabaseQuery)
                    }
                    if (systemToolsOptions.isNotEmpty()) {
                        val systemTools = SystemTools(context, settings)
                        addAll(systemTools.getTools(systemToolsOptions, conversation.currentMessages, filesManager))
                    }
                    addAll(createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), conversation.workspaceCwd))
                    if (assistant.enabledSkills.isNotEmpty()) {
                        addAll(
                            createSkillTools(
                                enabledSkills = assistant.enabledSkills,
                                allSkills = skillManager.listSkills(),
                                skillManager = skillManager,
                            )
                        )
                    }
                    mcpManager.getAllAvailableTools().forEach { (serverId, tool) ->
                        add(
                            Tool(
                                name = ToolNaming.buildMcpToolName(serverId, tool.name),
                                description = tool.description ?: "",
                                parameters = { tool.inputSchema },
                                needsApproval = tool.needsApproval,
                                execute = {
                                    mcpManager.callTool(serverId, tool.name, it.jsonObject)
                                },
                            )
                        )
                    }
                    // MCP 开关 (2026-09-19 宝拍板): 让 AI 自己启停 MCP 服务器, 不用人工进设置手动勾.
                    add(
                        me.rerere.rikkahub.data.ai.tools.createMcpSwitchTool(
                            listServers = {
                                // 实时读（2026-09-19 修）：settings 是本回合开始时的快照，
                                // 同一回合内开关别的服务器后它不会变，会读到旧状态。
                                val live = settingsStore.settingsFlow.first()
                                val liveAssistant = live.assistants.firstOrNull { it.id == assistant.id } ?: assistant
                                live.mcpServers.map { server ->
                                    me.rerere.rikkahub.data.ai.tools.McpServerInfo(
                                        id = server.id,
                                        displayName = server.commonOptions.name.ifBlank { "未命名服务器" },
                                        enabled = server.id in liveAssistant.mcpServers,
                                        globalEnabled = server.commonOptions.enable,
                                        toolCount = server.commonOptions.tools.size,
                                    )
                                }
                            },
                            onSetEnabled = { newSet ->
                                settingsStore.updateAssistantMcpServers(assistant.id, newSet)
                                val on = settings.mcpServers.count { it.id in newSet }
                                "已保存。这个助手现在开着 $on 个 MCP 服务器。"
                            },
                        )
                    )
                    // Plugin tools
                    addAll(pluginToolProvider.getTools())
                    // 插件开关 (2026-09-28 宝拍板): 让 AI 自己启停插件。
                    // 跟 mcp_switch 一样常驻、故意不挂 LocalToolOption（门锁在里面）。
                    // 插件没有助手级设置，启停就是全局的。
                    // ⚠️ 2026-09-28 深夜补：这条曾经只挂在 ToolSurfaceBuilder（那条路只服务小应用/工作流），
                    // 聊天这边看不见 —— 现在补上 ChatService 这一份。
                    add(
                        me.rerere.rikkahub.data.ai.tools.createPluginSwitchTool(
                            listPlugins = {
                                val pm = org.koin.java.KoinJavaComponent
                                    .getKoin().get<me.rerere.rikkahub.plugin.manager.PluginManager>()
                                pm.awaitInitialization()
                                pm.plugins.value.map { p ->
                                    me.rerere.rikkahub.data.ai.tools.PluginEntry(
                                        id = p.manifest.id,
                                        name = p.manifest.name,
                                        enabled = p.isEnabled,
                                        toolCount = p.manifest.tools.size,
                                    )
                                }
                            },
                            onSetEnabled = { id, enabled ->
                                val pm = org.koin.java.KoinJavaComponent
                                    .getKoin().get<me.rerere.rikkahub.plugin.manager.PluginManager>()
                                pm.togglePlugin(id, enabled)
                                val on = pm.plugins.value.count { it.isEnabled }
                                "已保存。现在开着 $on 个插件。"
                            },
                        )
                    )
                    // 激进模式开关 (2026-09-28 宝拍板): 让 AI 自己开一段、用完关掉。
                    // 它是常驻前台服务，所以 onSetEnabled 里要真的去 start/stop；
                    // 跟"主动消息"互斥这一点跟设置页保持一致。
                    add(
                        me.rerere.rikkahub.data.ai.tools.createAggressiveModeTool(
                            currentState = {
                                settingsStore.settingsFlow.first().proactiveMessageSetting.aggressiveModeEnabled
                            },
                            onSetEnabled = { enabled ->
                                try {
                                    settingsStore.update { s ->
                                        val pms = s.proactiveMessageSetting
                                        s.copy(
                                            proactiveMessageSetting = if (enabled) {
                                                pms.copy(aggressiveModeEnabled = true, enabled = false)
                                            } else {
                                                pms.copy(aggressiveModeEnabled = false)
                                            }
                                        )
                                    }
                                    if (enabled) {
                                        me.rerere.rikkahub.data.service.ProactiveMessageService.cancel(context)
                                        val intent = android.content.Intent(
                                            context,
                                            me.rerere.rikkahub.data.service.DeviceEventAiTriggerService::class.java,
                                        )
                                        context.startForegroundService(intent)
                                        "已开启，常驻服务已起。"
                                    } else {
                                        me.rerere.rikkahub.data.service.DeviceEventAiTriggerService.stop(context)
                                        "已关闭，服务已停。"
                                    }
                                } catch (e: Exception) {
                                    "设置改过了，但服务操作失败：${e.message ?: e.javaClass.simpleName}"
                                }
                            },
                        )
                    )
                    // 激进模式数值 (2026-10-03 橘仔自己提、宝拍板"你想做就做"):
                    // 开关归上面那个工具，这个管"开着的时候那几个数"。
                    // 只改数值、不碰服务起停——不然每调一次防抖都要重启常驻服务，
                    // 既没必要，也会把正在等的那个计时打断。
                    add(
                        me.rerere.rikkahub.data.ai.tools.createAggressiveSettingsTool(
                            currentValues = {
                                val pm = settingsStore.settingsFlow.first().proactiveMessageSetting
                                me.rerere.rikkahub.data.ai.tools.AggressiveSettingsSnapshot(
                                    enabled = pm.aggressiveModeEnabled,
                                    minIntervalSeconds = pm.aggressiveMinIntervalSeconds,
                                    debounceSeconds = pm.aggressiveDebounceSeconds,
                                    dwellMinutes = pm.aggressiveDwellMinutes,
                                )
                            },
                            onUpdate = { minInterval, debounce, dwell ->
                                settingsStore.update { s ->
                                    val pm = s.proactiveMessageSetting
                                    s.copy(
                                        proactiveMessageSetting = pm.copy(
                                            aggressiveMinIntervalSeconds = minInterval ?: pm.aggressiveMinIntervalSeconds,
                                            aggressiveDebounceSeconds = debounce ?: pm.aggressiveDebounceSeconds,
                                            aggressiveDwellMinutes = dwell ?: pm.aggressiveDwellMinutes,
                                        )
                                    )
                                }
                                val parts = buildList {
                                    minInterval?.let { add("最小间隔 ${it}秒") }
                                    debounce?.let { add("防抖 ${it}秒") }
                                    dwell?.let { add(if (it == 0) "停留触发已关" else "停留 ${it}分钟") }
                                }
                                if (parts.isEmpty()) "" else "已保存：" + parts.joinToString("、") + "。下一次触发开始生效。"
                            },
                        )
                    )
                },
                // 【2026-09-25 · MCP 工具面实时刷新】只算 MCP 那一段，供每一步重算用。
                // 上面 buildList 里那份 MCP 是"发送那一刻"的快照（第一轮照旧用它），
                // 从第二轮起 GenerationHandler 会用这里的实时结果替掉它，于是同一回合内
                // 用 mcp_switch 开关服务器后，下一步请求立刻生效，不用等下一回合。
                mcpToolsProvider = {
                    mcpManager.getAllAvailableTools().map { (serverId, tool) ->
                        Tool(
                            name = ToolNaming.buildMcpToolName(serverId, tool.name),
                            description = tool.description ?: "",
                            parameters = { tool.inputSchema },
                            needsApproval = tool.needsApproval,
                            execute = {
                                mcpManager.callTool(serverId, tool.name, it.jsonObject)
                            },
                        )
                    }
                },
                // 【2026-09-29 · 插件工具面实时刷新】跟 mcpToolsProvider 同一套：
                // 同一回合内用 plugin_switch 开关插件后，下一步请求立刻生效。
                pluginToolsProvider = {
                    pluginToolProvider.getTools()
                },
                pluginPromptInjections = pluginToolProvider.getPluginPromptInjections(),
                conversationId = conversationId.toString(),
                // 【2026-09-24 召回留痕】把门控 / 拆词 / 命中数写回这条用户消息，
                // 界面会在消息下面画一行小字（宝要的"看得见"）。
                // updateConversationState 只改内存态，紧随其后的 saveConversation 会一并落库。
                onRecallDebug = { debug ->
                    updateConversationState(conversationId) { conv ->
                        val nodes = conv.messageNodes
                        val lastUserIdx = nodes.indexOfLast { n ->
                            n.messages.any { m -> m.role == MessageRole.USER }
                        }
                        if (lastUserIdx < 0) conv
                        else conv.copy(
                            messageNodes = nodes.mapIndexed { i, n ->
                                if (i != lastUserIdx) n
                                else n.copy(
                                    messages = n.messages.map { m ->
                                        if (m.role == MessageRole.USER) m.copy(recallDebug = debug) else m
                                    }
                                )
                            }
                        )
                    }
                },
            ).onCompletion {
                // 取消 Live Update 通知
                cancelLiveUpdateNotification(conversationId)

                // 可能被取消了，或者意外结束，兜底更新
                val updatedConversation = getConversationFlow(conversationId).value.copy(
                    messageNodes = getConversationFlow(conversationId).value.messageNodes.map { node ->
                        node.copy(messages = node.messages.map { it.finishReasoning() })
                    },
                    updateAt = Instant.now()
                )
                updateConversation(conversationId, updatedConversation)

                // Show notification if app is not in foreground
                if (!isForeground.value && settings.displaySetting.enableNotificationOnMessageGeneration) {
                    sendGenerationDoneNotification(conversationId, senderName)
                }
            }.collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        val __t0 = System.currentTimeMillis()
                        val currentConversationForChunk = getConversationFlow(conversationId).value
                        val __tA = System.currentTimeMillis()
                        // 【插话合回 · 2026-10-01】回来的这份是"发出去那一版"，宝插的话被拆成了
                        // 独立 user 消息（三截），而会话里那条早就合并过（一截）。先合回去再塞，
                        // 否则按位置更新会多出一条、还会把前半顶掉（宝实测：一句显示两次 + 跳过）。
                        val incoming = collapseInterjections(chunk.messages)
                        val __tB = System.currentTimeMillis()
                        // 【2026-09-24 召回留痕】传进来的 chunk.messages 取自"开始生成那一刻"的快照，
                        // 那时门控/召回还没跑完，上面没有 recallDebug；而 updateCurrentMessages 是按 id
                        // 整条替换的，会把界面上的小字抹掉。这里先把旧消息上的小字补回来再刷。
                        val debugSource = currentConversationForChunk.currentMessages
                            .lastOrNull { it.recallDebug != null }
                        val patchedMessages = if (debugSource == null) {
                            incoming
                        } else {
                            val idx = incoming.indexOfFirst { it.id == debugSource.id }
                            if (idx < 0) incoming
                            else incoming.toMutableList().also { list ->
                                list[idx] = list[idx].copy(recallDebug = debugSource.recallDebug)
                            }
                        }
                        val __tC = System.currentTimeMillis()
                        // 【条数对账 · 2026-10-05】插话错位调查（宝报"第二次请求之后，4 点之前的历史全被标了 2/2"）。
                        // updateCurrentMessages 是"按位置对齐"的：两边条数一旦对不上，
                        // 后面的格全部串位、每格都塞一份副本 → 整段历史长出 <2/2>。
                        // 这里只报"合回后的条数 vs 会话格数"，差多少一眼看出错位的起点。
                        if (patchedMessages.size != currentConversationForChunk.messageNodes.size) {
                            val nowCnt = System.currentTimeMillis()
                            if (nowCnt - lastCountMismatchLogAt > 5_000) {
                                lastCountMismatchLogAt = nowCnt
                                AppLogBuffer.log(
                                    TAG,
                                    "[Interject] COUNT incoming=${patchedMessages.size} " +
                                        "nodes=${currentConversationForChunk.messageNodes.size} " +
                                        "diff=${patchedMessages.size - currentConversationForChunk.messageNodes.size} " +
                                        "inTail=" + patchedMessages.takeLast(4).joinToString(",") {
                                            "${it.role.name.take(1)}${it.id.toString().take(6)}"
                                        } + " nodeTail=" + currentConversationForChunk.messageNodes.takeLast(4).joinToString(",") {
                                            "${it.currentMessage.role.name.take(1)}${it.currentMessage.id.toString().take(6)}"
                                        }
                                )
                            }
                        }
                        val updatedConversation = currentConversationForChunk
                            .updateCurrentMessages(patchedMessages)
                        updateConversation(conversationId, updatedConversation)
                        val __tD = System.currentTimeMillis()

                        // 如果应用不在前台，发送 Live Update 通知
                        if (!isForeground.value && settings.displaySetting.enableNotificationOnMessageGeneration && settings.displaySetting.enableLiveUpdateNotification) {
                            sendLiveUpdateNotification(conversationId, chunk.messages, senderName)
                        }
                        val __tE = System.currentTimeMillis()
                        // 【卡顿定位 · 2026-10-02】流式每个 chunk 都要走这一整段。总耗 >50ms
                        // 就在主线程上排长队（每秒约 10 个 chunk）。只在慢的时候打一行，
                        // 拆开看是"取会话/合插话/补小字/更新/通知"哪一段吃掉了时间。
                        run {
                            val __total = __tE - __t0
                            if (__total > 50) {
                                AppLogBuffer.log(
                                    TAG,
                                    "CHUNKTIME total=${__total}ms getConv=${__tA - __t0} collapse=${__tB - __tA} " +
                                        "patch=${__tC - __tB} update=${__tD - __tC} notify=${__tE - __tD} n=${chunk.messages.size}"
                                )
                            }
                        }
                    }
                }
            }
        }.onFailure {
            // 取消 Live Update 通知
            cancelLiveUpdateNotification(conversationId)

            it.printStackTrace()
            addError(it, conversationId, title = context.getString(R.string.error_title_generation))
            Logging.log(TAG, "handleMessageComplete: $it")
            Logging.log(TAG, it.stackTraceToString())
        }.onSuccess {
            AppLogBuffer.log(TAG, "[Interject] onSuccess entered conv=${conversationId.toString().take(8)}")
            val finalConversation = session.saveMutex.withLock {
                var latest = getConversationFlow(conversationId).value
                // 【插话合并 · 2026-10-01】落库前先把宝插话的那条并进猫的回复里。
                // 不并的话界面上是"两条并排"，皮（折叠条）贴在两条的骨架上，怎么调都像异物。
                // 【搭车记账 · 2026-10-01】除了 metadata 锚点，再带上"这一回合真被搭过车的 id"：
                // 锚点只活在请求副本里，落库会话上不认它，所以合并要靠这本账。
                // 【插话不落库 · 2026-10-02】合并改成读"入队那刻记的账"——不再靠锚点从会话里找人。
                // 插话压根没进会话，所以这里只要拿到那几条消息本体，直接挂到猫那条上。
                val pendingToMerge = interjectedMessages.remove(conversationId).orEmpty()
                latest = mergeInterjectionsIntoAssistant(latest, pendingToMerge)
                // 【回写内存 · 2026-10-01 宝实测第二轮】只合并落库不够：界面读的是内存态
                // （session.state.value），收尾不写回它，下一条回复一来列表重建，宝那句
                // 又会从折叠条变回独立消息（宝原话："只有在你那条消息发出来之后才会跳回去"）。
                // 这个 internal 的 updateConversation 只写内存、不落库、不重写 nodeIndex。
                updateConversation(conversationId, latest)
                saveConversation(conversationId, latest)
                latest
            }
            // 【插话丢条排查 2026-09-30】收尾时报一次条数和最后一条的角色：
            // 配合 interject 日志，判断宝插的那条有没有在这轮收尾时被盖掉。
            AppLogBuffer.log(
                TAG,
                "interject: generation done size=${finalConversation.messageNodes.size}" +
                    " last=${finalConversation.currentMessages.lastOrNull()?.role}"
            )

            // 自动唤起网易云音乐：扫描刚完成的 assistant 文本中的 orpheus:// scheme
            try {
                val lastAssistantMessage = finalConversation.currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
                val messageText = lastAssistantMessage?.toText() ?: ""
                launchNeteaseCloudMusic(messageText)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to launch NetEase Cloud Music", e)
            }

            // 检测并执行 [JUMP] 标记 - 正常聊天中的切屏（AI总是可以跳转，不需要开关）
            try {
                val lastAssistantMessage = finalConversation.currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
                val rawText = lastAssistantMessage?.parts?.filterIsInstance<UIMessagePart.Text>()
                    ?.joinToString("\n") { it.text } ?: ""
                if (rawText.contains("[JUMP]", ignoreCase = true)) {
                    // 从展示给用户的消息文本中移除 [JUMP] 标记
                    val cleanedText = rawText.replace("\\[JUMP]".toRegex(RegexOption.IGNORE_CASE), "").trim()
                    if (lastAssistantMessage != null && cleanedText != rawText) {
                        val cleanedMessage = lastAssistantMessage.copy(
                            parts = lastAssistantMessage.parts.map { part ->
                                if (part is UIMessagePart.Text) {
                                    UIMessagePart.Text(part.text.replace("\\[JUMP]".toRegex(RegexOption.IGNORE_CASE), "").trim())
                                } else {
                                    part
                                }
                            }
                        )
                        // 更新对话状态并持久化
                        val cleanedConversation = finalConversation.copy(
                            messageNodes = finalConversation.messageNodes.map { node ->
                                node.copy(
                                    messages = node.messages.map { msg ->
                                        if (msg.id == cleanedMessage.id) cleanedMessage else msg
                                    }
                                )
                            }
                        )
                        updateConversation(conversationId, cleanedConversation)
                        saveConversation(conversationId, cleanedConversation)
                    }
                    // 拉起 RouteActivity 切屏（正常聊天中不受时间阈值限制）
                    val jumpIntent = Intent(context, RouteActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                        )
                        putExtra("conversationId", conversationId.toString())
                    }
                    context.startActivity(jumpIntent)
                    Log.d(TAG, "[JUMP] detected in normal chat, force jump to conversation $conversationId")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to handle [JUMP] in normal chat", e)
            }

            // 触发 message_received 事件钩子
            // 同 message_sent: 用 appScope.launch 提交独立协程, 不阻塞 handleMessageComplete
            // 后续的标题生成/建议生成等流程, 也不随上一条消息的 job 取消而中断。
            runCatching {
                val lastAssistantMessage = finalConversation.currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
                val eventData = JsonObject(
                    mapOf(
                        "assistant_id" to JsonPrimitive(assistant.id.toString()),
                        "conversation_id" to JsonPrimitive(conversationId.toString()),
                        "message" to JsonPrimitive(lastAssistantMessage?.toText() ?: ""),
                        "role" to JsonPrimitive("assistant"),
                        "timestamp" to JsonPrimitive(System.currentTimeMillis())
                    )
                )
                appScope.launch {
                    try {
                        pluginLoader.callEvent("message_received", eventData)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to trigger message_received event", e)
                    }
                }
            }.onFailure { e ->
                Log.w(TAG, "Failed to trigger message_received event", e)
            }

            launchWithConversationReference(conversationId) {
                generateTitle(conversationId, finalConversation)
            }
            launchWithConversationReference(conversationId) {
                generateSuggestion(conversationId, finalConversation)
            }

            // 保存 AI 回复到外置记忆库
            try {
                val externalMemoryConfigs = settings.externalMemories.filter {
                    it.enabled && it.id in assistant.externalMemoryIds && it.autoSaveMessages
                }
                if (externalMemoryConfigs.isNotEmpty()) {
                    val lastAssistantMessage = finalConversation.currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
                    val messageText = lastAssistantMessage?.toText() ?: ""
                    if (messageText.isNotBlank()) {
                        kotlinx.coroutines.coroutineScope {
                            externalMemoryConfigs.forEach { config ->
                                launch {
                                    runCatching {
                                        val service = me.rerere.rikkahub.data.service.ExternalMemoryService(config)
                                        service.saveMessage(
                                            assistantId = assistant.id.toString(),
                                            conversationId = conversationId.toString(),
                                            role = "assistant",
                                            content = messageText,
                                        )
                                    }.onFailure {
                                        Log.w(TAG, "Failed to save assistant message to external memory ${config.name}", it)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save assistant message to external memory", e)
            }
        }
    }

    // 【2026-09-16】private → internal：主动消息路径（ProactiveMessageService）也要挂这套工作区工具
    internal suspend fun createWorkspaceToolsIfReady(workspaceId: String?, cwd: String? = null): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(workspaceId, workspaceRepository, cwd)
    }

    // ---- 检查无效消息 ----

    private fun checkInvalidMessages(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        var messagesNodes = conversation.messageNodes

        // 移除无效 tool (未执行的 Tool)
        messagesNodes = messagesNodes.mapIndexed { _, node ->
            // Check for Tool type with non-executed tools
            val hasPendingTools = node.currentMessage.getTools().any { !it.isExecuted }

            if (hasPendingTools) {
                // Keep messages that are ready to resume, such as approved/denied/answered tools.
                val hasResumableTool = node.currentMessage.getTools().any {
                    !it.isExecuted && it.approvalState.canResumeToolExecution()
                }
                if (hasResumableTool) {
                    return@mapIndexed node
                }

                // If all tools are executed, it's valid
                val allToolsExecuted = node.currentMessage.getTools().all { it.isExecuted }
                if (allToolsExecuted && node.currentMessage.getTools().isNotEmpty()) {
                    return@mapIndexed node
                }

                // Remove messages that still have unresolved tool approvals.
                return@mapIndexed node.copy(
                    messages = node.messages.filter { it.id != node.currentMessage.id },
                    selectIndex = node.selectIndex - 1
                )
            }
            node
        }

        // 更新index
        messagesNodes = messagesNodes.map { node ->
            if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) {
                node.copy(selectIndex = 0)
            } else {
                node
            }
        }

        // 移除无效消息
        messagesNodes = messagesNodes.filter { it.messages.isNotEmpty() }

        updateConversation(conversationId, conversation.copy(messageNodes = messagesNodes))
    }

    private fun cancelToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}"""
                )
            ),
            approvalState = ToolApprovalState.Denied("Generation cancelled by user")
        )
    }

    // ---- 生成标题 ----

    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false
    ) {
        val shouldGenerate = when {
            force -> true
            conversation.title.isBlank() -> true
            else -> false
        }
        if (!shouldGenerate) return

        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.titleModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        prompt = settings.titlePrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(4).joinToString("\n\n") { it.summaryAsText() })
                    ),
                ),
                params = TextGenerationParams(
                    model = model,
                    reasoningLevel = ReasoningLevel.OFF,
                ),
            )

            val session = getOrCreateSession(conversationId)
            session.saveMutex.withLock {
                // 生成完，conversation可能不是最新了，因此需要重新获取
                conversationRepo.getConversationById(conversation.id)?.let {
                    saveConversation(
                        conversationId,
                        it.copy(title = result.choices[0].message?.toText()?.trim() ?: "")
                    )
                }
            }
        }.onFailure {
            it.printStackTrace()
            Log.e(TAG, "generateTitle failed, conversationId=$conversationId", it)
            addError(
                error = it,
                conversationId = conversationId,
                title = context.getString(R.string.error_title_generate_title),
                solution = ChatErrorSolution.CheckTitleModelSettings,
            )
        }
    }

    // ---- 生成建议 ----

    suspend fun generateSuggestion(conversationId: Uuid, conversation: Conversation) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.suggestionModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return

            val session = getOrCreateSession(conversationId)
            session.saveMutex.withLock {
                updateConversation(
                    conversationId,
                    session.state.value.copy(chatSuggestions = emptyList())
                )
            }

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        settings.suggestionPrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(8).joinToString("\n\n") { it.summaryAsText() }),
                    )
                ),
                params = TextGenerationParams(
                    model = model,
                    reasoningLevel = ReasoningLevel.OFF,
                ),
            )
            val suggestions =
                result.choices[0].message?.toText()?.split("\n")?.map { it.trim() }
                    ?.filter { it.isNotBlank() } ?: emptyList()

            session.saveMutex.withLock {
                val latestConversation = conversationRepo.getConversationById(conversationId)
                    ?: session.state.value
                saveConversation(
                    conversationId,
                    latestConversation.copy(
                        chatSuggestions = suggestions.take(10)
                    )
                )
            }
        }.onFailure {
            it.printStackTrace()
            Log.e(TAG, "generateSuggestion failed, conversationId=$conversationId", it)
        }
    }

    // ---- 压缩对话历史 ----

    suspend fun compressConversation(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int = 32
    ): Result<Unit> = runCatching {
        val settings = settingsStore.settingsFlow.first()
        val model = settings.findModelById(settings.compressModelId)
            ?: settings.getCurrentChatModel()
            ?: throw IllegalStateException("No model available for compression")
        val provider = model.findProvider(settings.providers)
            ?: throw IllegalStateException("Provider not found")

        val providerHandler = providerManager.getProviderByType(provider)

        // 【懒加载窗口】压缩需要完整历史：先全量加载，避免只压窗口内 300 条
        val fullConversation = conversationRepo.getConversationById(conversationId) ?: conversation
        // 压缩是重建对话：清除窗口保护，保存时按完整对话覆盖
        lazyWindowFirstIndex.remove(conversationId)

        val maxMessagesPerChunk = 256
        val allMessages = fullConversation.currentMessages

        // Split messages into those to compress and those to keep
        val messagesToCompress: List<UIMessage>
        val messagesToKeep: List<UIMessage>

        if (keepRecentMessages > 0 && allMessages.size > keepRecentMessages) {
            messagesToCompress = allMessages.dropLast(keepRecentMessages)
            messagesToKeep = allMessages.takeLast(keepRecentMessages)
        } else if (keepRecentMessages > 0) {
            // Not enough messages to compress while keeping recent ones
            throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
        } else {
            messagesToCompress = allMessages
            messagesToKeep = emptyList()
        }

        fun splitMessages(messages: List<UIMessage>): List<List<UIMessage>> {
            if (messages.size <= maxMessagesPerChunk) return listOf(messages)
            val mid = messages.size / 2
            val left = splitMessages(messages.subList(0, mid))
            val right = splitMessages(messages.subList(mid, messages.size))
            return left + right
        }

        suspend fun compressMessages(messages: List<UIMessage>): String {
            val contentToCompress = messages.joinToString("\n\n") { it.summaryAsText() }
            val prompt = settings.compressPrompt.applyPlaceholders(
                "content" to contentToCompress,
                "target_tokens" to targetTokens.toString(),
                "additional_context" to if (additionalPrompt.isNotBlank()) {
                    "Additional instructions from user: $additionalPrompt"
                } else "",
                "locale" to Locale.getDefault().displayName
            )

            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = TextGenerationParams(
                    model = model,
                ),
            )

            return result.choices[0].message?.toText()?.trim()
                ?: throw IllegalStateException("Failed to generate compressed summary")
        }

        val compressedSummaries = coroutineScope {
            splitMessages(messagesToCompress)
                .map { chunk -> async { compressMessages(chunk) } }
                .awaitAll()
        }

        // Create new conversation with compressed history as multiple user messages + kept messages
        val newMessageNodes = buildList {
            compressedSummaries.forEach { summary ->
                add(UIMessage.user(summary).toMessageNode())
            }
            addAll(messagesToKeep.map { it.toMessageNode() })
        }
        val newConversation = fullConversation.copy(
            messageNodes = newMessageNodes,
            chatSuggestions = emptyList(),
        )

        saveConversation(conversationId, newConversation)
    }

    // ---- 通知 ----

    private fun sendGenerationDoneNotification(conversationId: Uuid, senderName: String) {
        // 先取消 Live Update 通知
        cancelLiveUpdateNotification(conversationId)

        val conversation = getConversationFlow(conversationId).value
        context.sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = 1
        ) {
            title = senderName
            content = conversation.currentMessages.lastOrNull()?.toText()?.take(50)?.trim() ?: ""
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = getPendingIntent(context, conversationId)
        }
    }

    private fun getLiveUpdateNotificationId(conversationId: Uuid): Int {
        return conversationId.hashCode() + 10000
    }

    private fun sendLiveUpdateNotification(
        conversationId: Uuid,
        messages: List<UIMessage>,
        senderName: String
    ) {
        val lastMessage = messages.lastOrNull() ?: return
        val parts = lastMessage.parts

        // 确定当前状态
        val (chipText, statusText, contentText) = determineNotificationContent(parts)

        context.sendNotification(
            channelId = CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
            notificationId = getLiveUpdateNotificationId(conversationId)
        ) {
            title = senderName
            content = contentText
            subText = statusText
            ongoing = true
            onlyAlertOnce = true
            category = NotificationCompat.CATEGORY_PROGRESS
            useBigTextStyle = true
            contentIntent = getPendingIntent(context, conversationId)
            requestPromotedOngoing = true
            shortCriticalText = chipText
        }
    }

    private fun determineNotificationContent(parts: List<UIMessagePart>): Triple<String, String, String> {
        // 检查最近的 part 来确定状态
        val lastReasoning = parts.filterIsInstance<UIMessagePart.Reasoning>().lastOrNull()
        val lastTool = parts.filterIsInstance<UIMessagePart.Tool>().lastOrNull()
        val lastText = parts.filterIsInstance<UIMessagePart.Text>().lastOrNull()

        return when {
            // 正在执行工具
            lastTool != null && !lastTool.isExecuted -> {
                val toolName = ToolNaming.toDisplayName(lastTool.toolName)
                Triple(
                    context.getString(R.string.notification_live_update_chip_tool),
                    context.getString(R.string.notification_live_update_tool, toolName),
                    lastTool.input.take(100)
                )
            }
            // 正在思考（Reasoning 未结束）
            lastReasoning != null && lastReasoning.finishedAt == null -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_thinking),
                    context.getString(R.string.notification_live_update_thinking),
                    lastReasoning.reasoning.takeLast(200)
                )
            }
            // 正在写回复
            lastText != null -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_writing),
                    context.getString(R.string.notification_live_update_writing),
                    lastText.text.takeLast(200)
                )
            }
            // 默认状态
            else -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_writing),
                    context.getString(R.string.notification_live_update_title),
                    ""
                )
            }
        }
    }

    private fun cancelLiveUpdateNotification(conversationId: Uuid) {
        context.cancelNotification(getLiveUpdateNotificationId(conversationId))
    }

    private fun getPendingIntent(context: Context, conversationId: Uuid): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", conversationId.toString())
        }
        return PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    // ---- 对话状态更新 ----

    internal fun updateConversation(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        // 只更新内存态 session.state.value，不落库、不重写 nodeIndex（落库走 ConversationRepository.updateConversation）
        val session = getOrCreateSession(conversationId)
        checkFilesDelete(conversation, session.state.value)
        session.state.value = conversation
    }

    /**
     * 老消息跳转（2026-08-30）：目标消息不在当前懒加载窗口内时，把窗口移动到目标附近。
     * 查 nodeIndex → 加载目标段（前后各 150 条）→ 替换 session 内存态 + 更新窗口边界（保存保护按新窗口算）。
     * 返回目标消息在加载段内的 index（供 UI 滚动）；找不到返回 null。
     */
    suspend fun jumpToNode(conversationId: Uuid, nodeId: Uuid): Int? {
        val nodeIndex = conversationRepo.getNodeIndexById(conversationId.toString(), nodeId.toString()) ?: return null
        val segment = conversationRepo.loadMessageNodesWindow(conversationId.toString(), nodeIndex)
        if (segment.isEmpty()) return null
        val session = getOrCreateSession(conversationId)
        val base = session.state.value
        val startIndex = (nodeIndex - 150).coerceAtLeast(0)
        updateConversation(conversationId, base.copy(messageNodes = segment))
        lazyWindowFirstIndex[conversationId] = startIndex
        Log.i(TAG, "jumpToNode: nodeId=$nodeId nodeIndex=$nodeIndex windowStart=$startIndex segment=${segment.size}")
        return nodeIndex - startIndex
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        val current = getConversationFlow(conversationId).value
        updateConversation(conversationId, update(current))
    }

    /**
     * 移动会话到文件夹（folderId 为 null 表示移出到未归类）。
     *
     * 若该会话当前有活跃 session（正在查看或后台生成），先同步内存态再落库：
     * 否则仅改数据库 folder_id，而内存里那份 Conversation 仍是旧 folderId，
     * 后续任意 saveConversation(id, state.value) 会用整对象把 folder_id 覆盖回旧值，导致移动丢失。
     * 先改内存可确保这段窗口内的整对象保存也带上新 folderId。
     */
    suspend fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        if (sessions.containsKey(conversationId)) {
            updateConversationState(conversationId) { it.copy(folderId = folderId) }
        }
        conversationRepo.updateConversationFolderId(conversationId, folderId)
    }

    /**
     * 文件夹内是否存在正在生成回复的会话。
     * 仅活跃 session 可能在生成；内存态 folderId 为权威（移动会先同步内存态）。
     */
    fun hasGeneratingConversationInFolder(folderId: Uuid): Boolean {
        return sessions.values.any { it.isGenerating && it.state.value.folderId == folderId }
    }

    /**
     * 删除文件夹（folder_id 归属会被清空，会话本身保留）。
     *
     * 先把内存中归属该文件夹的活跃 session folderId 置空，再删库：
     * 否则 clearFolder 只改了数据库，而活跃 session 内存态仍指向该文件夹，
     * 后续整对象保存会写回一个已被删除的 folder_id，导致会话在列表中悬空。
     */
    suspend fun deleteFolder(folderId: Uuid) {
        sessions.values
            .filter { it.state.value.folderId == folderId }
            .forEach { updateConversationState(it.id) { c -> c.copy(folderId = null) } }
        folderRepository.deleteFolder(folderId)
    }

    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        val newFiles = newConversation.files
        val oldFiles = oldConversation.files
        val deletedFiles = oldFiles.filter { file ->
            newFiles.none { it == file }
        }
        if (deletedFiles.isNotEmpty()) {
            filesManager.deleteChatFiles(deletedFiles)
            Log.w(TAG, "checkFilesDelete: $deletedFiles")
        }
    }

    /**
     * 【插话合并 · 2026-10-01】把宝插话的那条并进前一条猫的回复里。
     *
     * 判据跟显示层（ChatList）一致：她这条的 createdAt 早于前一条 assistant 的 finishedAt
     * ＝ 她是在猫还没写完的时候发的。搭车和兜底接力都是这个特征。
     *
     * 合并方式：她那句的 parts 挂到猫那条末尾，每个 part 打上 interject 标；
     * 独立那条从列表里删掉。发请求时会按标拆回三条（见 GenerationHandler）。
     *
     * 纯数据层，不碰 UI；从后往前扫一遍，只在收尾调一次。
     */
    private fun mergeInterjectionsIntoAssistant(
        conversation: Conversation,
        pending: List<UIMessage> = emptyList(),
    ): Conversation {
        // 【插话挂载 · 2026-10-02 宝定的根治】插话不再进会话（见 sendMessage 的 isInterjection 分支），
        // 所以不用再"从会话里找那条独立的 USER 消息 → 挂上来 → removeAt 删掉它"。
        // 这里只做一件事：把本回合记下的那几条（搭车的、接力的都在内）挂到最后一条猫的消息上。
        if (pending.isEmpty()) {
            AppLogBuffer.log(TAG, "[Interject] merge-skip: pending empty")
            return conversation
        }
        // 【两份溯源 · 2026-10-05】宝实测"发一个显示两个"。回看：入队只一次、collapse 那侧也有
        // DUP 留痕但从未触发。所以两份要么是"挂载时挂了两次"，要么是"进来时就是两个 part"。
        // 这行把源头交出来：入队几条、每条几个 part、每个 part 的类型和长度。
        AppLogBuffer.log(
            TAG,
            "[Interject] merge-in: pending=${pending.size} " +
                pending.joinToString("|") { p ->
                    p.id.toString().take(8) + ":parts=" + p.parts.size + "(" +
                        p.parts.joinToString(",") { part ->
                            if (part is UIMessagePart.Text) "T${part.text.length}" else "X"
                        } + ")"
                }
        )
        // 【兜底清理 · 2026-10-02】流式刷新偶尔会把她那句（请求里被拆成独立 USER 的那条）
        // 写回会话，于是"挂载"之外还多出一条独立的。按 id 认人，先把它从会话里摘掉。
        // （旧版是靠 removeAt 删；这版从源头不造，所以这里只兜"漏网写回"这一种。）
        val pendingIds = pending.map { it.id.toString() }.toSet()
        val nodes = conversation.messageNodes.filterNot { node ->
            val m = node.currentMessage
            // 【认标不认 id · 2026-10-02】expandInterjections 拆出来那条 USER 的 id 是现场生成的，
            // 跟账上的对不上 → 只认 id 会漏。带插话标的就是它，两个判据取并集。
            m.role == MessageRole.USER &&
                (m.id.toString() in pendingIds || m.parts.any { isInterjectMarked(it) })
        }
        val lastAssistantIndex = nodes.indexOfLast { it.currentMessage.role == MessageRole.ASSISTANT }
        if (lastAssistantIndex < 0) return conversation

        // 【按锚点分发 · 2026-10-04 宝实测】原来固定挂到"会话里最后一条猫消息"（targetIndex），
        // 锚点却打在"她插话那一刻猫写到的那条"上——两者常常不是同一条，于是锚点找不到，
        // 退化成"追加末尾"（宝截图实证：她插在 A 之后，话挂到了后面那条 B 的尾巴上）。
        // 现在逐条找它的锚点落在哪条猫消息上，各挂各的；找不到锚点的（接力，猫写完才插）
        // 才归最后一条末尾——那本来就是它的正确位置。
        fun anchorOfIn(msg: UIMessage, pid: String): Int =
            msg.parts.indexOfFirst { part ->
                // 【拆 key · 2026-10-05】位置锚点改用 interjectAnchor；兼容旧数据（id 串曾写在 interject 里）。
                val m = part.metadata
                val v = m?.get("interjectAnchor") ?: m?.get("interject")
                v is JsonPrimitive && v.content != "true" &&
                    v.content.split(",").any { it.trim() == pid }
            }

        // nodeIndex -> [(锚点在那条 parts 里的位置, 要插进去的 parts)]
        val plan = LinkedHashMap<Int, MutableList<Pair<Int, List<UIMessagePart>>>>()
        val orphans = mutableListOf<UIMessagePart>()

        pending.forEach { p ->
            val pid = p.id.toString()
            val parts = p.parts.map { tagAsInterjectPart(it) }
            var targetNi = -1
            var anchorAt = -1
            nodes.forEachIndexed { ni, node ->
                if (targetNi >= 0) return@forEachIndexed
                if (node.currentMessage.role != MessageRole.ASSISTANT) return@forEachIndexed
                val ai = anchorOfIn(node.currentMessage, pid)
                if (ai >= 0) {
                    targetNi = ni
                    anchorAt = ai
                }
            }
            if (targetNi >= 0) {
                plan.getOrPut(targetNi) { mutableListOf() }.add(anchorAt to parts)
            } else {
                orphans.addAll(parts)
            }
        }
        if (orphans.isNotEmpty()) {
            plan.getOrPut(lastAssistantIndex) { mutableListOf() }.add(Int.MAX_VALUE to orphans)
        }
        AppLogBuffer.log(
            TAG,
            "[Interject] merge: pending=${pending.size} " +
                "ids=" + pending.joinToString(",") { it.id.toString().take(8) } +
                " nodes=${nodes.size} last=$lastAssistantIndex " +
                "plan=" + plan.entries.joinToString(",") { (k, v) -> "#$k×${v.sumOf { it.second.size }}" }
        )

        val result = nodes.toMutableList()
        plan.forEach { (ni, slots) ->
            val node = result[ni]
            val msg = node.currentMessage
            // 顺手抹掉搭车那刻留的 id 锚点（旧记号，不抹会多画一遍）
            val cleaned = msg.parts.map { stripInterjectAnchor(it) }
            // 流式那半步可能已经合过（collapseInterjections）——那种只清理，不再挂一遍
            val alreadyMerged = msg.parts.any { isInterjectMarked(it) }
            // 【两份溯源 · 2026-10-05】收尾这一刻，那条猫消息上原本有几个标、几个 part。
            // marksInMsg>0 但不该有 → 说明流式那侧已经写过一次（两份的来源就在这）。
            AppLogBuffer.log(
                TAG,
                "[Interject] merge-check: ni=$ni alreadyMerged=$alreadyMerged " +
                    "marksInMsg=${msg.parts.count { isInterjectMarked(it) }} partsInMsg=${msg.parts.size}"
            )
            val out = if (alreadyMerged) {
                cleaned
            } else {
                val buf = mutableListOf<UIMessagePart>()
                cleaned.forEachIndexed { i, part ->
                    buf.add(part)
                    slots.filter { it.first == i }.forEach { buf.addAll(it.second) }
                }
                // 锚点越界（那一格找不到了）或本来就是"接力" → 追加末尾，别丢
                slots.filter { it.first >= cleaned.size }.forEach { buf.addAll(it.second) }
                buf
            }
            val newMessages = node.messages.toMutableList().also { list ->
                val idx = node.selectIndex.coerceIn(0, list.size - 1)
                list[idx] = msg.copy(parts = out)
            }
            result[ni] = node.copy(messages = newMessages)
        }
        // 【数标 · 2026-10-04 宝实测】她在界面上看到"同一条猫消息下面画了两个折叠条"。
        // 日志已证：数据层只入队一次、只合并一次。所以要么是这里挂了两次（parts 里真有两个带标的），
        // 要么是渲染层画了两遍。把这几个数打出来定案：
        //   marks = 每处被改的节点里，带 interject 标的部分有**几个**。
        //   出现 =2 → ①（数据层真的两个）；全是 =1 → ②（问题在渲染层）。
        val marks = plan.keys.joinToString(",") { ni ->
            "#$ni=" + result.getOrNull(ni)?.currentMessage?.parts?.count { isInterjectMarked(it) }
        }
        AppLogBuffer.log(
            TAG,
            "interject: merged ${pending.size} into assistant（分 ${plan.size} 处）marks=$marks"
        )
        return conversation.copy(messageNodes = result)
    }

    /** 【插话标 · 2026-10-04】把她的 part 打上 boolean 标（界面认它就是折叠条）。 */
    private fun tagAsInterjectPart(part: UIMessagePart): UIMessagePart {
        val oldMeta = part.metadata
        val newMeta = if (oldMeta == null) {
            JsonObject(mapOf("interject" to JsonPrimitive(true)))
        } else {
            JsonObject(oldMeta + ("interject" to JsonPrimitive(true)))
        }
        return when (part) {
            is UIMessagePart.Text -> part.copy(metadata = newMeta)
            is UIMessagePart.Image -> part.copy(metadata = newMeta)
            else -> part
        }
    }

    /** 【旧锚点清理 · 2026-10-01 保留】抹掉非 boolean 的 interject 锚点（会多画一遍）。 */
    private fun stripInterjectAnchor(part: UIMessagePart): UIMessagePart {
        val meta = part.metadata ?: return part
        // 【拆 key · 2026-10-05】位置锚点搬到 interjectAnchor；兼容旧数据（id 串曾写在 interject 里），两种都抹。
        val v = meta["interject"]
        val isOldIdStr = v is JsonPrimitive && v.content != "true"
        if (!meta.containsKey("interjectAnchor") && !isOldIdStr) return part
        val filtered = JsonObject(meta.filterKeys { key -> key != "interjectAnchor" && !(key == "interject" && isOldIdStr) })
        return when (part) {
            is UIMessagePart.Text -> part.copy(metadata = filtered)
            is UIMessagePart.Image -> part.copy(metadata = filtered)
            else -> part
        }
    }

    /**
     * 【插话还原 · 2026-10-01】合并后，宝插话的那句挂在猫的回复里（part 带 metadata{"interject": true}）。
     * 发给模型前把它拎出来，切成：猫的正文A ／ 宝的话（独立 user 消息）／ 猫的正文B。
     * 不拆的话模型会以为那句是它自己说的。
     *
     * 一条都没有记号时原样返回（绝大多数历史走这条，零开销）。
     */
    private fun expandInterjections(messages: List<UIMessage>): List<UIMessage> {
        if (messages.none { msg -> msg.parts.any { isInterjectMarked(it) } }) return messages
        val out = mutableListOf<UIMessage>()
        for (msg in messages) {
            if (msg.role != MessageRole.ASSISTANT) {
                out.add(msg)
                continue
            }
            var buffer = mutableListOf<UIMessagePart>()
            var cut = false
            for (part in msg.parts) {
                if (isInterjectMarked(part)) {
                    // 【插话可逆 · 2026-10-01】切出来的两截不能顶着同一个 id：
                    // 刷新时 updateCurrentMessages 按 id 找，后半会把前半顶掉（宝实测"跳过"）。
                    // 前半沿用原 id（合并后会话里就是它），后半必须换新 id。
                    if (buffer.isNotEmpty()) {
                        out.add(msg.copy(parts = buffer))
                        buffer = mutableListOf()
                        cut = true
                    }
                    // 只认文字；正文里不带标记（模型看不见这个记号），但记号要跟着上路，
                    // 好让流式回来时 collapseInterjections 认得出这句是宝插的。
                    val text = (part as? UIMessagePart.Text)?.text.orEmpty()
                    // 【看双份 · 2026-10-06】宝报"请求里那句给她的话有两份"。
                    // 展开这一步是"一条带标 part → 一条独立 USER"的唯一出口，先在这儿照一眼：
                    // 吐出来的文本前 20 字 + 长度 + 这条猫消息里有几个带标 part。
                    AppLogBuffer.log(
                        TAG,
                        "[Interject] EXPAND text=${text.take(20)}@${text.length} marks=${msg.parts.count { isInterjectMarked(it) }} parts=${msg.parts.size}"
                    )
                    // 【抓鬼3 · 2026-10-06】这条带标 part 是从哪儿来的：母消息的 id / 角色 /
                    // 这条母消息身上一共带几个标 / 每个标的内容。用来回答"展开出来那条
                    // 和她刚插的那条，是不是同一个源头"。
                    AppLogBuffer.log(
                        TAG,
                        "[Interject] EXPAND-ORIGIN fromId=${msg.id.toString().take(8)} fromRole=${msg.role} " +
                            "markCount=${msg.parts.count { isInterjectMarked(it) }} " +
                            "markTexts=" + msg.parts.filter { isInterjectMarked(it) }
                            .joinToString("|") { (it as? UIMessagePart.Text)?.text?.take(8).orEmpty() }
                    )
                    if (text.isNotBlank()) {
                        out.add(
                            UIMessage(
                                role = MessageRole.USER,
                                parts = listOf(
                                    UIMessagePart.Text(
                                        text = text,
                                        metadata = JsonObject(mapOf("interject" to JsonPrimitive(true)))
                                    )
                                )
                            )
                        )
                    }
                } else {
                    buffer.add(part)
                }
            }
            if (buffer.isNotEmpty()) {
                // 【合不回去的真凶 · 2026-10-02】这是切出来的后半截（带着新 id）。原来靠"紧跟在插话后面"
                // 这个位置关系合回去，但那个条件常不成立（返回列表里往往没有她那条 USER），
                // 于是它独立成条——宝看到的"猫的上半句和下半句分开、两个号"就是它。
                // 这里给后半截自己盖个尾标，回来不靠邻居也能认出来。
                val tailParts = if (cut) {
                    val ti = buffer.indexOfFirst { it is UIMessagePart.Text }
                    if (ti >= 0) {
                        buffer.mapIndexed { i, p -> if (i == ti) markTextPart(p, "interjectTail", "true") else p }
                    } else buffer
                } else buffer
                out.add(msg.copy(parts = tailParts))
            }
        }
        return out
    }

    /**
     * 【插话合回 · 2026-10-01】流式回传的 messages 是"发出去那一版"（宝的话被拆成了独立 user 消息），
     * 而会话里那条早就合并过了。直接按位置塞回会话会错位——宝实测：同一句多出一条、正主被顶掉。
     * 所以先合回原样：带 interject 记号的 user 消息挂回前一条 assistant，容器沿用前一条的 id。
     * 一条记号都没有时原样返回（绝大多数请求走这条，零开销）。
     */
    private fun collapseInterjections(messages: List<UIMessage>): List<UIMessage> {
        if (messages.none { msg -> msg.role == MessageRole.USER && msg.parts.any { isInterjectMarked(it) } }) {
            return messages
        }
        // 【数标诊断 · 2026-10-05】宝实测"一次插话显示两个折叠条"。
        // 先看这一轮里带标 user 有几条——>1 就是重复的来源。5 秒最多报一次。
        val markedUsers = messages.count { it.role == MessageRole.USER && it.parts.any { isInterjectMarked(it) } }
        if (markedUsers > 1) {
            val nowDup = System.currentTimeMillis()
            if (nowDup - lastInterjectDupLogAt > 5_000) {
                lastInterjectDupLogAt = nowDup
                AppLogBuffer.log(TAG, "[Interject] DUP markedUsers=$markedUsers in=${messages.size}")
            }
        }
        val out = mutableListOf<UIMessage>()
        // 【合回补全 · 2026-10-02】上一版只合了"宝那句"，把切出来的**后半截**漏在外面当独立一条。
        // 后果（STRAY 日志实录）：会话里那条是 1 条、回来却是 2 条 → 列表比节点树多 1 →
        // updateCurrentMessages 按位置对齐时整体错位一格 → 每条消息都长出 <2/2> 分支。
        // 每插一次话欠 1 条，攒起来就是"整段历史全有版本"。所以后半截也得并回去。
        var justMerged = false
        var mergedCount = 0
        for (msg in messages) {
            val isInterjectUser = msg.role == MessageRole.USER && msg.parts.any { isInterjectMarked(it) }
            val last = out.lastOrNull()
            val isInterjectTail = msg.role == MessageRole.ASSISTANT && hasTextMark(msg, "interjectTail")
            if (isInterjectUser && last != null && last.role == MessageRole.ASSISTANT) {
                // 【数标诊断 · 2026-10-05】合之前那条猫消息里已有几个标——>0 说明"会话里已有 + 流式又来一条"，
                // 合完就成了两个折叠条。5 秒最多报一次。
                val hadMarks = last.parts.count { isInterjectMarked(it) }
                if (hadMarks > 0) {
                    val nowDup2 = System.currentTimeMillis()
                    if (nowDup2 - lastInterjectDupLogAt > 5_000) {
                        lastInterjectDupLogAt = nowDup2
                        // 【看内容 · 2026-10-05 宝要】光知道"有几个标"不够，得看那几个标里装的是什么：
                        // 是同一份被算了两遍，还是两份不同的东西叠着。
                        val peek = { ps: List<UIMessagePart> ->
                            ps.filter { isInterjectMarked(it) }.joinToString("|") { pt ->
                                val t = (pt as? UIMessagePart.Text)?.text.orEmpty()
                                t.take(10) + "@" + t.length
                            }
                        }
                        AppLogBuffer.log(
                            TAG,
                            "[Interject] DUP merge had=$hadMarks old=[${peek(last.parts)}] new=[${peek(msg.parts)}]"
                        )
                    }
                }
                out[out.lastIndex] = last.copy(parts = concatPartsReplacingInterject(last.parts, msg.parts))
                mergedCount++
                justMerged = true
            } else if (isInterjectTail && last != null && last.role == MessageRole.ASSISTANT) {
                // 【认尾标 · 2026-10-02】切出来的后半截自带记号：只要它跟在一条 assistant 后面就并回去。
                // 不依赖"返回值里有没有她那条 USER"——实测那个条件常不成立，于是后半独立成条
                //（宝看到的"猫的上半句和下半句分开、两个号"）。
                out[out.lastIndex] = last.copy(parts = concatPartsReplacingInterject(last.parts, msg.parts))
                justMerged = true
            } else if (justMerged && msg.role == MessageRole.ASSISTANT && last != null && last.role == MessageRole.ASSISTANT) {
                // 紧跟在插话后面的那半截正文：并回同一条，别让它独立成条
                out[out.lastIndex] = last.copy(parts = concatPartsReplacingInterject(last.parts, msg.parts))
                justMerged = false
            } else {
                // 【漏网兜底 · 2026-10-05 宝实测】她的话没跟紧在猫消息后面（连着两条她的、或列表开头就是它）
                // → 原来原样留下，它就作为独立消息被渲染层**再画一个折叠条**
                //（宝看到的：数据层 marks 只有一个，界面上却有两个）。
                // 现在往回找最近的一条猫消息合上去；实在没有才留原地。两条路都留痕，方便下次复查。
                if (isInterjectUser) {
                    val backIdx = out.indexOfLast { it.role == MessageRole.ASSISTANT }
                    if (backIdx >= 0) {
                        out[backIdx] = out[backIdx].copy(parts = concatPartsReplacingInterject(out[backIdx].parts, msg.parts))
                        AppLogBuffer.log(TAG, "[Interject] collapse: stray user merged back to #$backIdx")
                    } else {
                        out.add(msg)
                        AppLogBuffer.log(TAG, "[Interject] collapse: stray user kept (no assistant before it)")
                    }
                    mergedCount++
                } else {
                    out.add(msg)
                }
                justMerged = false
            }
        }
        // 【日志降频 · 2026-10-02】原来每合并一条就打一行：流式每秒推 10 次、一次 8 条
        // → 每秒 80 行，把 500 条日志环刷爆（别的日志全被挤出去，排查时满屏都是它）。
        // 改成每次调用只汇总一行。行为一字未改。
        if (mergedCount > 0) {
            collapseLogTick++
            if (collapseLogTick % 30 == 0) {
                // 【合并后看 part · 2026-10-05】宝报"带工具的大块已经弹出来，插话一进去就没了"。
                // 光看条数不够——这里把合完之后那条猫消息的 part 类型也打出来，
                // 一眼看出 Tool 是被摘掉了、还是压根没合进来。
                val mergedTail = out.lastOrNull { it.role == MessageRole.ASSISTANT }
                AppLogBuffer.log(
                    TAG,
                    "[Interject] collapse: merged=$mergedCount in=${messages.size} out=${out.size} tick=$collapseLogTick " +
                        "tail=" + (mergedTail?.parts?.joinToString("") { it::class.simpleName?.take(4) ?: "?" } ?: "-")
                )
            }
        }
        return out
    }

    /** part 上有没有"这句是宝插进来的"记号。 */
    private fun isInterjectMarked(part: UIMessagePart): Boolean {
        // 【判据放宽 · 2026-10-05】原来只认 content=="true"。但搭车那侧（GenerationHandler）
        // 写进去的是一串 id（"aaa,bbb"），于是"流式合回来的那份"根本不被认——
        // merge-check 里 marksInMsg 永远 0，摘不掉旧份，两份就叠在一起。
        // 改成"有这个 key 就算"：两条路写的都是这个 key，判据统一。
        // 【拆 key · 2026-10-05】位置锚点已搬到 interjectAnchor，这个 key 从此只表示"这句是她的话"。
        // 收窄回只认 true——宽判据会把带锚点的工具/正文误当内容拎走（宝实测两种都撞到）。
        val v = part.metadata?.get("interject")
        return v is JsonPrimitive && v.content == "true"
    }

    /**
     * 【合回时给旧份让位 · 2026-10-05 宝实测的双份】
     * 数据层收尾时已经把插话挂进会话那条猫消息了；接力重跑时流式那份又会合回来，
     * 直接相加 = 同一个插话挂两份（宝看到的"一个折叠条、里面两遍"）。
     * 规则：新来的这份带标，就先把旧的同类摘掉再放。新来的没标（比如只是尾截正文），原样相加。
     */
    private fun concatPartsReplacingInterject(
        oldParts: List<UIMessagePart>,
        newParts: List<UIMessagePart>
    ): List<UIMessagePart> {
        return if (newParts.any { isInterjectMarked(it) }) {
            oldParts.filterNot { isInterjectMarked(it) } + newParts
        } else {
            oldParts + newParts
        }
    }

    /** 给文字 part 盖一个记号（切出来的后半截靠它被认出来）。 */
    private fun markTextPart(part: UIMessagePart, key: String, value: String): UIMessagePart {
        if (part !is UIMessagePart.Text) return part
        val base = part.metadata
        val map: MutableMap<String, JsonElement> =
            if (base != null) base.toMutableMap() else mutableMapOf()
        map[key] = JsonPrimitive(value)
        return part.copy(metadata = JsonObject(map))
    }

    /** 这条消息里有没有某个记号（只看文字 part）。 */
    private fun hasTextMark(msg: UIMessage, key: String): Boolean =
        msg.parts.any { it is UIMessagePart.Text && it.metadata?.get(key) != null }

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        val exists = conversationRepo.existsConversationById(conversation.id)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
            return // 新会话且为空时不保存
        }

        // 【防误删护栏 2026-08-27 修复】窗口版路径下，如果传入节点数远小于窗口大小（内存态异常/空对话，
        // 例如 session 未初始化就被保存），限量加载数据库窗口内容合并（数据库节点为准 + 追加传入的新节点），
        // 再走窗口版保存——防止窗口版 diff 把窗口外历史当成"消失"删掉。
        // 修复点①：getConversationById 带 loadLimit(300)，不再全量加载——大对话不再 OOM
        // 修复点②：合并结果继续走下方正常保存流程（不递归）——db 历史 <150 条时不再无限递归栈溢出
        val __saveT0 = System.currentTimeMillis()
        var toSave = conversation
        val guardWindowFirstIndex = lazyWindowFirstIndex[conversationId]
        if (exists && guardWindowFirstIndex != null && toSave.messageNodes.size < CONVERSATION_LOAD_WINDOW_SIZE / 2) {
            val dbConversation = conversationRepo.getConversationById(conversationId, CONVERSATION_LOAD_WINDOW_SIZE)
            if (dbConversation != null) {
                val dbIds = dbConversation.messageNodes.map { it.id }.toSet()
                val newNodes = toSave.messageNodes.filter { it.id !in dbIds }
                toSave = if (newNodes.isEmpty()) {
                    dbConversation.copy(
                        title = toSave.title.ifBlank { dbConversation.title },
                        chatSuggestions = toSave.chatSuggestions,
                    )
                } else {
                    dbConversation.copy(
                        messageNodes = dbConversation.messageNodes + newNodes,
                        title = toSave.title.ifBlank { dbConversation.title },
                        chatSuggestions = toSave.chatSuggestions,
                        updateAt = toSave.updateAt,
                    )
                }
            }
        }

        // 【缓存对齐 2026-08-26】窗口裁剪组大小 = 对话关联 assistant 的 contextGroupSize（设置里"多少条一组"），
        // 与 limitContext 组对齐保持同步；读不到/异常回退默认 4
        val windowGroupSize = runCatching {
            val allAssistants = settingsStore.settingsFlow.first().assistants
            val matched = allAssistants.firstOrNull { it.id == toSave.assistantId }
            // 【2026-09-28 排查】宝设置的是 6，但 PromptDiff 显示每轮按 4 条推，怀疑这里没读到。
            // 打完这行日志，跑一轮就能看见实际用的是几、有没有匹配上助手。
            AppLogBuffer.log(
                TAG,
                "WINDOW_GS target=${toSave.assistantId} matched=${matched?.id} gs=${matched?.contextGroupSize} " +
                    "nodes=${toSave.messageNodes.size} all=${allAssistants.joinToString("|") { "${it.id}=${it.contextGroupSize}" }}"
            )
            matched?.contextGroupSize
                ?: DEFAULT_WINDOW_GROUP_SIZE
        }.getOrDefault(DEFAULT_WINDOW_GROUP_SIZE).coerceAtLeast(1)

        // 【懒加载窗口】窗口版保存：只写窗口内变化，窗口外历史受保护（不读全量、不删除）。
        // 判定：存在窗口边界 且 传入条数 < 窗口外+窗口大小 → 窗口版（nodeIndex 从 firstIndex 偏移，不删窗口外）
        val windowFirstIndex = lazyWindowFirstIndex[conversationId]
        // 修复①（2026-09-01 晚）：原来 < windowFirstIndex + WINDOW + groupSize 会把「全量传入」
        // （appendSlashResult/appendProactiveAiMessageUnderLock 从数据库读全量再 saveConversation，
        // 5601 < 5300+300+4 也成立）误判成窗口态 → 窗口裁剪 overflow 巨大 → lazyWindowFirstIndex
        // 爆炸式推高（5300→10600→15900…，宝玩一次桌游窗口保护逻辑就错乱一次）。
        // 收紧为「传入条数 ≤ 窗口+一组」才视为窗口态；全量传入走全量分支（firstIndex=总条数-窗口大小，正确）。
        // 修复②（2026-09-01 22:11 宝实测「第307条卡死」）：收紧后正常发消息也会误伤——
        // 内存态窗口浮动上限=窗口+一组（300+groupSize），再追加 1 条新消息就超过「窗口+一组」上限
        // → 被误判成全量传入 → 走全量保存分支（windowFirstIndex=null）→ updateConversation
        // 把窗口外几千条历史全部判为删除 + FTS 重建成千条（withTransaction 同步执行）→ 落库卡死，
        // 宝只能刷新重开（groupSize=6 时 307 条恰好超限，宝实测每局都卡）。
        // 放宽为「窗口+两组」：正常发消息最大=窗口上限+1 条新消息=301+groupSize ≤ 300+2*groupSize
        // 恒成立（groupSize≥1），真全量传入（几千条）仍远超窗口+两组 → 全量分支不受影响。
        val isWindowState = windowFirstIndex != null &&
            toSave.messageNodes.size <= CONVERSATION_LOAD_WINDOW_SIZE +
                windowGroupSize * 2
        val effectiveFirstIndex = if (isWindowState) windowFirstIndex else null

        // 【裁剪时机 · 2026-09-29 宝的方案】只在"最后一条是用户消息"时裁，让裁剪永远落在
        // 回合边界上（回合里那几条绝不被碰）。兜底：条数顶到落库判定的余量线（312）时无论如何
        // 裁一次，防止回合内工具狂潮把窗口顶爆。门槛放这么高是因为 307 太低：工具回合涨到 307
        // 时兜底会替主判据做决定，又变成"回合内裁"（宝当晚实测发现）。
        val lastNodeIsUser = toSave.messageNodes.lastOrNull()
            ?.messages?.lastOrNull()?.role == MessageRole.USER
        val trimAllowed = lastNodeIsUser ||
            toSave.messageNodes.size >= CONVERSATION_LOAD_WINDOW_SIZE + windowGroupSize * 2

        val updatedConversation = toSave.copy()
        if (!exists) {
            conversationRepo.insertConversation(updatedConversation)
        } else {
            conversationRepo.updateConversation(updatedConversation, effectiveFirstIndex)
        }

        // 更新懒加载窗口边界
        if (isWindowState) {
            // 窗口版：内存窗口前移了 dropped 条（takeLast 丢弃的），窗口起点跟着前移
            // 【缓存对齐 2026-08-26】攒一组裁一组：overflow≤groupSize 不裁（窗口 300~300+groupSize 浮动），
            // overflow>groupSize 只裁 groupSize 的倍数条 → 配合 limitContext 总量对齐，裁剪前后起点同一条消息；
            // groupSize≤1 = 按条裁剪（旧行为）
            val overflow = (toSave.messageNodes.size - CONVERSATION_LOAD_WINDOW_SIZE).coerceAtLeast(0)
            val dropped = if (!trimAllowed) {
                0
            } else if (windowGroupSize <= 1) {
                overflow
            } else if (overflow > windowGroupSize) {
                overflow - (overflow % windowGroupSize)
            } else {
                0
            }
            lazyWindowFirstIndex[conversationId] = (windowFirstIndex ?: 0) + dropped
            // 【浮现节拍 · 2026-09-29】累计滚动条数：只增不减。
            // 窗口条数在 300~306 浮动，重开时 lazyWindowFirstIndex 会重算、值跳，
            // 浮现不能跟着跳，所以另存一本只增不减的账。
            if (dropped > 0) {
                AppLogBuffer.log(
                    TAG,
                    "TRIM dropped=$dropped size=${toSave.messageNodes.size} " +
                        "lastIsUser=$lastNodeIsUser allowed=$trimAllowed gs=$windowGroupSize"
                )
                // 【2026-09-30 宝纠正】这里不再给浮现记账了。
                // 这个位置能拿到的是"窗口裁掉多少条"，而浮现该对齐的是请求体起点 k 的跳。
                // 窗口裁剪时 windowFirst +dropped、k -dropped 正好抵消，请求体一个字没变，
                // 记账在这儿等于让浮现跟着一个假节拍走（详见 GenerationHandler 浮现段注释）。
            }
        } else {
            // 全量版/新对话：窗口起点 = 总条数 - 窗口大小
            lazyWindowFirstIndex[conversationId] =
                (updatedConversation.messageNodes.size - CONVERSATION_LOAD_WINDOW_SIZE).coerceAtLeast(0)
        }

        val __took = System.currentTimeMillis() - __saveT0
        if (__took > 300) {
            AppLogBuffer.log(
                TAG,
                "saveConv SLOW ${__took}ms size=${toSave.messageNodes.size} win=$isWindowState " +
                    "eff=${effectiveFirstIndex ?: -1} busy=${sessions[conversationId]?.getJob()?.isActive == true}"
            )
        }

        // 内存态保持窗口轻量：无论调用方传的是窗口版还是全量，都只保留最近 N 条，
        // 后续流式更新/重组只碰窗口内节点 → 长对话不再每次更新都全量遍历
        // 【缓存对齐 2026-08-26】攒一组裁一组：只有 overflow>groupSize 才裁，且裁 groupSize 的倍数条（窗口 300~300+groupSize 浮动）
        val windowState = if (!trimAllowed) {
            // 【裁剪时机 · 2026-09-29】回合中不裁内存态：只追加
            toSave
        } else if (windowGroupSize <= 1) {
            if (toSave.messageNodes.size > CONVERSATION_LOAD_WINDOW_SIZE) {
                toSave.copy(messageNodes = toSave.messageNodes.takeLast(CONVERSATION_LOAD_WINDOW_SIZE))
            } else {
                toSave
            }
        } else if (toSave.messageNodes.size > CONVERSATION_LOAD_WINDOW_SIZE + windowGroupSize) {
            val overflow = toSave.messageNodes.size - CONVERSATION_LOAD_WINDOW_SIZE
            toSave.copy(messageNodes = toSave.messageNodes.takeLast(CONVERSATION_LOAD_WINDOW_SIZE + (overflow % windowGroupSize)))
        } else {
            toSave
        }
        updateConversation(conversationId, windowState)
    }

    // ---- 翻译消息 ----

    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale
    ) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()

                val messageText = message.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()

                if (messageText.isBlank()) return@launch

                // Set loading state for translation
                val loadingText = context.getString(R.string.translating)
                updateTranslationField(conversationId, message.id, loadingText)

                generationHandler.translateText(
                    settings = settings,
                    sourceText = messageText,
                    targetLanguage = targetLanguage
                ) { translatedText ->
                    // Update translation field in real-time
                    updateTranslationField(conversationId, message.id, translatedText)
                }.collect { /* Final translation already handled in onStreamUpdate */ }

                // Save the conversation after translation is complete
                saveConversation(conversationId, getConversationFlow(conversationId).value)
            } catch (e: Exception) {
                // Clear translation field on error
                clearTranslationField(conversationId, message.id)
                addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
            }
        }
    }

    private fun updateTranslationField(
        conversationId: Uuid,
        messageId: Uuid,
        translationText: String
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = translationText)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // ---- 消息操作 ----

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>
    ) {
        if (parts.isEmptyInputMessage()) return

        val currentConversation = getConversationFlow(conversationId).value
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(currentConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val processedParts = preprocessUserInputParts(parts, assistant)
        var edited = false

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (!node.messages.any { it.id == messageId }) {
                return@map node
            }
            edited = true

            node.copy(
                messages = node.messages + UIMessage(
                    role = node.role,
                    parts = processedParts,
                ),
                selectIndex = node.messages.size
            )
        }

        if (!edited) return

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            throw NotFoundException("Message not found")
        }

        val copiedNodes = currentConversation.messageNodes
            .subList(0, targetNodeIndex + 1)
            .map { node ->
                node.copy(
                    id = Uuid.random(),
                    messages = node.messages.map { message ->
                        message.copy(
                            parts = message.parts.map { part ->
                                part.copyWithForkedFileUrl()
                            }
                        )
                    }
                )
            }

        val forkConversation = Conversation(
            id = Uuid.random(),
            assistantId = currentConversation.assistantId,
            messageNodes = copiedNodes,
            customSystemPrompt = currentConversation.customSystemPrompt,
        )

        saveConversation(forkConversation.id, forkConversation)
        return forkConversation
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNode = currentConversation.messageNodes.firstOrNull { it.id == nodeId }
            ?: throw NotFoundException("Message node not found")

        if (selectIndex !in targetNode.messages.indices) {
            throw BadRequestException("Invalid selectIndex")
        }

        if (targetNode.selectIndex == selectIndex) {
            return
        }

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.id == nodeId) {
                node.copy(selectIndex = selectIndex)
            } else {
                node
            }
        }

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedConversation = buildConversationAfterMessageDelete(currentConversation, messageId)

        if (updatedConversation == null) {
            if (failIfMissing) {
                throw NotFoundException("Message not found")
            }
            return
        }

        saveConversation(conversationId, updatedConversation)
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

    private fun buildConversationAfterMessageDelete(
        conversation: Conversation,
        messageId: Uuid,
    ): Conversation? {
        val targetNodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            return null
        }

        val updatedNodes = conversation.messageNodes.mapIndexedNotNull { index, node ->
            if (index != targetNodeIndex) {
                return@mapIndexedNotNull node
            }

            val nextMessages = node.messages.filterNot { it.id == messageId }
            if (nextMessages.isEmpty()) {
                return@mapIndexedNotNull null
            }

            val nextSelectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex)
            node.copy(
                messages = nextMessages,
                selectIndex = nextSelectIndex,
            )
        }

        return conversation.copy(messageNodes = updatedNodes)
    }

    private fun UIMessagePart.copyWithForkedFileUrl(): UIMessagePart {
        fun copyLocalFileIfNeeded(url: String): String {
            if (!url.startsWith("file:")) return url
            val copied = filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull()
            return copied?.toString() ?: url
        }

        return when (this) {
            is UIMessagePart.Image -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Document -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Video -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Audio -> copy(url = copyLocalFileIfNeeded(url))
            else -> this
        }
    }

    fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = null)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // 停止当前会话生成任务（不清理会话缓存）
    suspend fun stopGeneration(conversationId: Uuid) {
        val job = sessions[conversationId]?.getJob() ?: return
        job.cancel()
        runCatching { job.join() }

        val currentConversation = getConversationFlow(conversationId).value
        val lastNode = currentConversation.messageNodes.lastOrNull() ?: return
        val lastMessage = lastNode.currentMessage
        val updatedMessage = lastMessage.finishPendingTools(::cancelToolByUser)
        if (updatedMessage == lastMessage) {
            return
        }

        val updatedConversation = currentConversation.copy(
            messageNodes = currentConversation.messageNodes.dropLast(1) + lastNode.copy(
                messages = lastNode.messages.map { message ->
                    if (message.id == lastMessage.id) updatedMessage else message
                }
            )
        )
        saveConversation(conversationId, updatedConversation)
    }

    /**
     * 扫描 AI 回复文本中的网易云音乐链接并自动唤起播放指定歌曲。
     *
     * 网易云音乐 scheme 解析规则（外部调用实测）：
     * - `orpheus://song/{id}`        仅跳转歌曲页，不自动播放（停留在原队列）
     * - `orpheus://song/{id}/?autoplay=1`  跳转并自动播放该歌曲（推荐）
     * - `https://music.163.com/song?id={id}` 在部分 ROM（如华为）会被浏览器劫持，不可靠
     *
     * 因此统一使用带 autoplay=1 的 orpheus scheme，并强制用网易云包名打开。
     */
    private fun launchNeteaseCloudMusic(text: String) {
        if (text.isBlank()) return
        val songId = extractNeteaseSongId(text) ?: return
        val uri = "orpheus://song/$songId/?autoplay=1"
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, uri.toUri()).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage("com.netease.cloudmusic")
            }
            context.startActivity(intent)
            Logging.log(TAG, "Launched NetEase Cloud Music, songId=$songId, uri=$uri")
        }.onFailure { e ->
            // 兜底 1：去掉包名限制（极少数定制 ROM 带 package 会被拦截）
            runCatching {
                val fallback = Intent(Intent.ACTION_VIEW, uri.toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(fallback)
                Logging.log(TAG, "Launched NetEase Cloud Music (no package), songId=$songId")
            }.onFailure {
                // 兜底 2：orpheuswidget:// scheme（部分版本只认这个）
                runCatching {
                    val widgetUri = "orpheuswidget://song/$songId"
                    val widgetIntent = Intent(Intent.ACTION_VIEW, widgetUri.toUri())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(widgetIntent)
                    Logging.log(TAG, "Launched NetEase Cloud Music (widget scheme), songId=$songId")
                }.onFailure {
                    Log.w(TAG, "NetEase Cloud Music not available, songId=$songId", e)
                }
            }
        }
    }

    /**
     * 从文本中提取网易云歌曲 ID。支持两种 AI 可能输出的格式：
     * - orpheus://song/{id}
     * - music.163.com/song?id={id} 或 music.163.com/?songid={id}
     */
    private fun extractNeteaseSongId(text: String): String? {
        // orpheus://song/{id}
        Regex("orpheus://song/(\\d+)").find(text)?.let {
            return it.groupValues[1]
        }
        // music.163.com/song?id={id}
        Regex("music\\.163\\.com/song\\?.*?(?:^|[^0-9])(\\d{4,})").find(text)?.let {
            return it.groupValues[1]
        }
        Regex("music\\.163\\.com/song\\?id=(\\d+)").find(text)?.let {
            return it.groupValues[1]
        }
        return null
    }
}