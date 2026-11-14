/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.ui.components.ui.LocalLiquidGlassHost
import me.rerere.rikkahub.ui.components.ui.rememberLiquidGlassHost
import me.rerere.rikkahub.ui.components.ui.liquidGlass
import me.rerere.rikkahub.ui.components.ui.liquidGlassSource
import me.rerere.rikkahub.ui.components.ui.LiquidGlassStyle
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInParent

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.hugeicons.stroke.Voice
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.AppLogBuffer
import me.rerere.rikkahub.data.ai.RequestEditController
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.components.message.TaskCardOverlay
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.VoiceCallService
import me.rerere.rikkahub.ui.components.ai.ChatInput
import me.rerere.rikkahub.ui.components.ai.ScheduleMessageDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.utils.base64Decode
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

@Composable
fun ChatPage(id: Uuid, text: String?, files: List<Uri>, nodeId: Uuid? = null, autoStartVoice: Boolean = false) {
    val vm: ChatVM = koinViewModel(
        parameters = {
            parametersOf(id.toString())
        }
    )
    val filesManager: FilesManager = koinInject()
    val conversationRepo: ConversationRepository = koinInject()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()

    val setting by vm.settings.collectAsStateWithLifecycle()
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    // 【老消息跳转 2026-08-31】目标在懒加载窗口外时，临时加载目标段显示（配合 ChatList 跳转模式）
    var jumpNodes by remember { mutableStateOf<List<MessageNode>?>(null) }
    var jumpTargetIndex by remember { mutableStateOf<Int?>(null) }
    // 【引用跳回 2026-10-10】会话内点被引的小条发起的跳转 —— 跟路由参数 nodeId 走同一段逻辑
    var localJumpNodeId by remember { mutableStateOf<Uuid?>(null) }
    val loadingJob by vm.conversationJob.collectAsStateWithLifecycle()
    val processingStatus by vm.processingStatus.collectAsStateWithLifecycle()
    val currentChatModel by vm.currentChatModel.collectAsStateWithLifecycle()
    val enableWebSearch by vm.enableWebSearch.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()

    val windowAdaptiveInfo = currentWindowDpSize()
    val isBigScreen =
        windowAdaptiveInfo.width > windowAdaptiveInfo.height && windowAdaptiveInfo.width >= 1100.dp

    val inputState = vm.inputState

    // 初始化输入状态（处理传入的 files 和 text 参数）
    LaunchedEffect(files, text) {
        if (files.isNotEmpty()) {
            val localFiles = filesManager.createChatFilesByContents(files)
            val contentTypes = files.mapNotNull { file ->
                filesManager.getFileMimeType(file)
            }
            val parts = buildList {
                localFiles.forEachIndexed { index, file ->
                    val type = contentTypes.getOrNull(index)
                    if (type?.startsWith("image/") == true) {
                        add(UIMessagePart.Image(url = file.toString()))
                    } else if (type?.startsWith("video/") == true) {
                        add(UIMessagePart.Video(url = file.toString()))
                    } else if (type?.startsWith("audio/") == true) {
                        add(UIMessagePart.Audio(url = file.toString()))
                    }
                }
            }
            inputState.messageContent = parts
        }
        text?.base64Decode()?.let { decodedText ->
            if (decodedText.isNotEmpty()) {
                inputState.setMessageText(decodedText)
            }
        }
    }

    val chatListState = rememberLazyListState()
    // 【2026-09-27 修】同一条跳转只处理一次：否则列表条数一变（发新消息）effect 会重跑，
    // 又把视图拽回那条老消息。
    val lastJumpNodeId = remember { mutableStateOf<Uuid?>(null) }
    // 【引用跳回 2026-10-10】两个来源合流：从别的页面跳进来的 nodeId、会话内点小条发的 localJumpNodeId
    val jumpTargetNodeId: Uuid? = nodeId ?: localJumpNodeId
    LaunchedEffect(nodeId, localJumpNodeId, conversation.messageNodes.size) {
        // 【2026-09-27 修·老消息跳转失效】原条件是 `!vm.chatListInitialized`，而 chatListInitialized
        // 挂在 ChatVM 上、VM 按会话 ID 复用 —— 进过一次会话后该标记永久为 true，
        // 于是「从搜索/收藏夹点某条消息跳回同一个会话」永远不执行（日志里连 jumpToNode 三条都不出现）。
        // 改为：带 nodeId（跳转请求）时无条件执行；只有"无 nodeId 的首次滚到底"仍受该标记限制。
        if (conversation.messageNodes.isNotEmpty() && (!vm.chatListInitialized || (jumpTargetNodeId != null && lastJumpNodeId.value != jumpTargetNodeId))) {
            if (jumpTargetNodeId != null) {
                lastJumpNodeId.value = jumpTargetNodeId
                val index = conversation.messageNodes.indexOfFirst { it.id == jumpTargetNodeId }
                if (index >= 0) {
                    // 窗口剪切后，历史消息 index 需映射到窗口内；超出窗口则停在窗口开头
                    val start = (conversation.messageNodes.size - WINDOW_DISPLAY_SIZE).coerceAtLeast(0)
                    chatListState.scrollToItem(if (index >= start) index - start else 0)
                } else {
                    // 【老消息跳转 2026-08-31】目标在懒加载窗口外：从数据库定位并加载目标段临时显示
                    runCatching {
                        val dbIndex = conversationRepo.getMessageNodeIndex(conversation.id.toString(), jumpTargetNodeId)
                        if (dbIndex != null) {
                            val count = conversationRepo.getMessageNodeCount(conversation.id.toString())
                            val segStart = (dbIndex - 30).coerceAtLeast(0)
                            val segEnd = (dbIndex + 30).coerceAtMost(count)
                            val nodes = conversationRepo.getMessageNodesRange(conversation.id.toString(), segStart, segEnd)
                            if (nodes.isNotEmpty()) {
                                jumpNodes = nodes
                                jumpTargetIndex = dbIndex - segStart
                                AppLogBuffer.log("ChatPage", "jumpToNode: 窗口外定位成功 dbIndex=$dbIndex seg=[$segStart,$segEnd) target=${jumpTargetIndex}")
                            } else {
                                AppLogBuffer.log("ChatPage", "jumpToNode: 目标段为空 dbIndex=$dbIndex count=$count")
                            }
                        } else {
                            AppLogBuffer.log("ChatPage", "jumpToNode: 数据库找不到 nodeId=$jumpTargetNodeId")
                        }
                    }.onFailure {
                        AppLogBuffer.log("ChatPage", "jumpToNode: 老消息跳转失败 ${it.message}")
                    }
                }
            } else {
                chatListState.requestScrollToItem(conversation.messageNodes.size.coerceAtMost(WINDOW_DISPLAY_SIZE) + 5)
            }
            vm.chatListInitialized = true
        }
    }

    ChatPageContent(
        inputState = inputState,
        loadingJob = loadingJob,
        processingStatus = processingStatus,
        setting = setting,
        conversation = conversation,
        navController = navController,
        vm = vm,
        chatListState = chatListState,
        enableWebSearch = enableWebSearch,
        currentChatModel = currentChatModel,
        autoStartVoice = autoStartVoice,
        errors = errors,
        onDismissError = { vm.dismissError(it) },
        onClearAllErrors = { vm.clearAllErrors() },
        jumpNodes = jumpNodes,
        jumpTargetIndex = jumpTargetIndex,
        onExitJump = {
            jumpNodes = null
            jumpTargetIndex = null
        },
        // 【引用跳回 2026-10-10】点被引的小条 → 设这一路跳转请求
        onQuotedClick = { localJumpNodeId = it },
    )
}

@Composable
private fun ChatPageContent(
    inputState: ChatInputState,
    loadingJob: Job?,
    processingStatus: String? = null,
    setting: Settings,
    conversation: Conversation,
    navController: Navigator,
    vm: ChatVM,
    chatListState: LazyListState,
    enableWebSearch: Boolean,
    currentChatModel: Model?,
    autoStartVoice: Boolean = false,
    errors: List<ChatError>,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
    jumpNodes: List<MessageNode>? = null,
    jumpTargetIndex: Int? = null,
    onExitJump: () -> Unit = {},
    // 【引用跳回 2026-10-10】点被引的小条 → 跳回那条消息（参数 = 目标节点 id）
    onQuotedClick: (Uuid) -> Unit = {},
) {
    // 【插话不落库 · 2026-10-02】排队中的插话（插话没进会话，列表靠它画"排队中"折叠条）
    val pendingInterjections by vm.pendingInterjections.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var previewMode by rememberSaveable { mutableStateOf(false) }
    // 【重排·第十三刀 2026-10-09】会话面板（从侧边栏搬出来的那块）
    // 【液态玻璃 · 2026-11-06】背景录制器（顶栏那条测试条已撤，liquidHost 不再需要）
    // 【气泡玻璃 · 2026-11-07 宝指的】内容录制层里含所有气泡，气泡采自己那块就采到自己 → 无限重影。
    // 气泡其实只要背景，所以单独建一个"只罩背景"的 host：静态壁纸，永远采不到气泡。
    val bgGlassHost = rememberLiquidGlassHost()
    // 【两份 host · 2026-11-09 21:14 宝点破："你是不是忘记区分输入框和气泡的玻璃了"】
    //   ① bgGlassHost   —— 只录背景图。气泡（BubbleSurface）读这个，跟 11-07 以来一样。
    //   ② pageGlassHost —— 录"背景 + 气泡"。输入框读这个，才透得出气泡。
    // 之前只有一个 host、又把整个 Scaffold 录进去 → 气泡玻璃读到了"包含自己"的图，
    // 自己喂自己，整块糊成一团。
    val pageGlassHost = rememberLiquidGlassHost()
    // 【搬底栏 · 2026-11-09】输入框要从 Scaffold 底栏搬到录制层外面，
    // 底栏留一条同高的空位顶住列表留白。高度由输入框自己量出来回填。
    var inputAreaHeight by remember { mutableStateOf(0.dp) }
    val cpDensity = androidx.compose.ui.platform.LocalDensity.current
    var showSessionPanel by remember { mutableStateOf(false) }
    // 【重排·第六刀 2026-10-08】语音通话入口：顶栏和输入区面板共用一份
    val onVoiceCallAction: () -> Unit = {
        val activeId = VoiceCallService.activeConversationId.value
        when {
            activeId == null -> navController.navigate(
                Screen.VoiceCall(conversation.id.toString())
            )
            activeId == conversation.id.toString() -> navController.navigate(
                Screen.VoiceCall(conversation.id.toString())
            )
            else -> {
                toaster.show("当前有通话进行中，请先挂断", type = ToastType.Warning)
            }
        }
    }
    // 定时发送对话框（2026-09-17 宝提的小玩法）
    var showScheduleDialog by remember { mutableStateOf(false) }
    val hazeState = rememberHazeState()

    TTSAutoPlay(vm = vm, setting = setting, conversation = conversation)

    // 请求编辑模式：监听待编辑请求，非空时弹出编辑界面
    val pendingEdit by RequestEditController.pending.collectAsStateWithLifecycle()
    if (pendingEdit != null) {
        RequestEditDialog(
            data = pendingEdit!!,
            onConfirm = { RequestEditController.submit(it) },
            onCancel = { RequestEditController.submit(null) },
        )
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) {
        // 【水波背景 · 2026-11-14 宝："后面都变成全黑了"】
        // ⚠️ 水绝不能进下面那两层 liquidGlassSource：WebView 是硬件层，
        //    GraphicsLayer 录不到它 —— 录出来是空的，drawLayer 再把铺的那层主题色
        //    整个画上去，就成了全黑。所以水放在录制层【外面】、页面最底下。
        if (setting.displaySetting.chatWaterBackground) {
            WaterBackground(modifier = Modifier.fillMaxSize())
        }

        // 【液态玻璃 · 2026-11-07】录制层：背景图 + 内容一起录。
        // ⚠️ 玻璃条绝不能进这一层 —— 它读的那张图正是这一层每帧录的，会自己喂自己卡死。
        Box(modifier = Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalLiquidGlassHost provides bgGlassHost) {
        // 【2026-11-07 宝】原来这里还有一层 liquidGlassSource(liquidHost) 录整页，只服务那条测试条。
        // 测试条撤了，这层就不再录 —— 省掉每 300ms 一次整页截图。Box 留着，嵌套不动。
        Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (setting.displaySetting.chatWaterBackground) Modifier
                      else Modifier.liquidGlassSource(pageGlassHost, MaterialTheme.colorScheme.background))
        ) {
            // 【气泡的图单独录一份 · 2026-11-09 21:23 宝："气泡的玻璃消失了"】
            // 猫把 source 换成 pageGlassHost 时，bgGlassHost 就一张图都没收到了 →
            // 气泡那边 host.background 是空的，liquidGlass 直接原样返回，玻璃就没了。
            // 所以背景图这一层再套一层 source，同时喂给气泡那个 host。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (setting.displaySetting.chatWaterBackground) Modifier
                          else Modifier.liquidGlassSource(bgGlassHost, MaterialTheme.colorScheme.background))
            ) {
                AssistantBackground(setting = setting)
            }
        Scaffold(
            bottomBar = {
                // 【搬底栏 · 2026-11-09 21:03 宝："老实巴交，干净一点"】
                // 输入框搬到录制层外面去了（玻璃不能录自己，会自己喂自己），
                // 这里留一条跟它一样高的空位 —— ChatList 拿 innerPadding 的底部当留白，
                // 没了这条，气泡会掉到屏幕最底下。
                Spacer(modifier = Modifier.height(inputAreaHeight))
            },
            containerColor = Color.Transparent,
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
            ) {
            ChatList(
                innerPadding = innerPadding,
                conversation = conversation,
                state = chatListState,
                loading = loadingJob != null,
                processingStatus = processingStatus,
                previewMode = previewMode,
                settings = setting,
                hazeState = hazeState,
                errors = errors,
                pendingInterjections = pendingInterjections[conversation.id].orEmpty(),
                onDismissError = onDismissError,
                onClearAllErrors = onClearAllErrors,
                onRegenerate = {
                    vm.regenerateAtMessage(it)
                },
                onEdit = {
                    inputState.editingMessage = it.id
                    inputState.setContents(it.parts)
                },
                // 【消息引用 2026-09-22】长按选了"引用"→ 挂到输入框上方那条引用条
                onQuote = {
                    inputState.quotedMessageId = it.id
                    // 【引用一句 2026-10-10】这次引的是整条，把上一次可能留下的"那一句"清掉
                    inputState.quotedText = null
                    inputState.quotedPreview = it.parts
                        .filterIsInstance<UIMessagePart.Text>()
                        .joinToString(" ") { p -> p.text }
                        .trim()
                        .take(120)
                },
                // 【引用一句 2026-10-10】选中一段文字 → 先存进 inputState，输入框上方冒一条提示；
                // 点了那条上的「引用这句」才真引用（见下面 ChatInput 的 onUsePickedQuote）。
                onQuoteText = { msg, text ->
                    if (text.isBlank()) {
                        inputState.pickedText = null
                        inputState.pickedMessageId = null
                    } else {
                        inputState.pickedMessageId = msg.id
                        inputState.pickedText = text
                    }
                },
                onForkMessage = {
                    scope.launch {
                        val fork = vm.forkMessage(message = it)
                        navigateToChatPage(navController, chatId = fork.id)
                    }
                },
                onDelete = {
                    if (loadingJob != null) {
                        vm.showDeleteBlockedWhileGeneratingError()
                    } else {
                        vm.deleteMessage(it)
                    }
                },
                onUpdateMessage = { newNode ->
                    vm.updateConversation(
                        conversation.copy(
                            messageNodes = conversation.messageNodes.map { node ->
                                if (node.id == newNode.id) {
                                    newNode
                                } else {
                                    node
                                }
                            }
                        ))
                    vm.saveConversationAsync()
                },
                onClickSuggestion = { suggestion ->
                    inputState.editingMessage = null
                    inputState.setMessageText(suggestion)
                },
                onTranslate = { message, locale ->
                    vm.translateMessage(message, locale)
                },
                onClearTranslation = { message ->
                    vm.clearTranslationField(message.id)
                },
                onJumpToMessage = { index ->
                    previewMode = false
                    scope.launch {
                        // 窗口剪切后，历史消息 index 需要映射到窗口内的位置；超出窗口则滚到窗口底部
                        val start = (conversation.messageNodes.size - WINDOW_DISPLAY_SIZE).coerceAtLeast(0)
                        val target = if (index >= start) index - start else 0
                        chatListState.animateScrollToItem(target)
                    }
                },
                jumpNodes = jumpNodes,
                jumpTargetIndex = jumpTargetIndex,
                onExitJump = onExitJump,
                onQuotedClick = onQuotedClick,
                onToolApproval = { toolCallId, approved, reason ->
                    vm.handleToolApproval(toolCallId, approved, reason)
                },
                onToolAnswer = { toolCallId, answer ->
                    vm.handleToolAnswer(toolCallId, answer)
                },
                onToggleFavorite = { node ->
                    vm.toggleMessageFavorite(node)
                },
                onConversationSystemPromptChange = { newPrompt ->
                    vm.updateConversation(conversation.copy(customSystemPrompt = newPrompt))
                    vm.saveConversationAsync()
                },
            )
            }
                // 【顶部波形条 · 2026-10-31】正在播放的那条。一直在组合里，自己轮询、自己收。
                NowPlayingBar(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp)
                        .padding(top = 8.dp)
                )
                // 【小任务卡 · 2026-10-06】任务写完后从右边滑进来的卡片，挂顶部
                TaskCardOverlay(
                    conversation = conversation,
                    loading = loadingJob != null,
                    onTaskDone = { name, byTimeout -> vm.appendTaskReceipt(name, byTimeout) },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = innerPadding.calculateTopPadding() + 8.dp),
                )

                // 【浮层·网页小窗 2026-10-25】右边一个小圆钮，点开就是网页窗（骨架版，还没接 WebView）
                var showFloatWindow by remember { mutableStateOf(false) }
                FloatingBrowserWindow(
                    visible = showFloatWindow,
                    onClose = { showFloatWindow = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = innerPadding.calculateTopPadding() + 8.dp),
                )
                if (!showFloatWindow) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = innerPadding.calculateTopPadding() + 8.dp, end = 10.dp)
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { showFloatWindow = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("▣", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        }   // ← 录制层收尾（背景 + 气泡，不含输入框）
        // 【输入框搬出录制层 · 2026-11-09 21:03】
        // 玻璃读的那张图就是上面录的，它自己绝不能进那一层 —— 会自己喂自己卡死
        // （2026-11-07 那条测试条就是因为住在外面才没出事）。
        androidx.compose.runtime.CompositionLocalProvider(
            me.rerere.rikkahub.ui.components.ui.LocalLiquidGlassHost provides pageGlassHost
        ) {   // 输入框走第二份 host：采"背景+气泡"，而外面的气泡仍用 bgGlassHost
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onGloballyPositioned {
                    inputAreaHeight = with(cpDensity) { it.size.height.toDp() }
                },
        ) {
                ChatInput(
                    // 【玻璃输入框 · 2026-11-08】宝要的：套一层液态玻璃，打印机那套不动（glassMode 默认 false）
                    glassMode = true,
                    state = inputState,
                    loading = loadingJob != null,
                    settings = setting,
                    conversation = conversation,
                    mcpManager = vm.mcpManager,
                    hazeState = hazeState,
                    autoStartVoice = autoStartVoice,
                    // 【重排·第六刀 2026-10-08】顶栏那三个的入口，面板里也要用
                    requestEditMode = setting.requestEditMode,
                    onToggleRequestEdit = {
                        vm.updateSettings(setting.copy(requestEditMode = !setting.requestEditMode))
                    },
                    onVoiceCall = onVoiceCallAction,
                    previewMode = previewMode,
                    onTogglePreview = {
                        previewMode = !previewMode
                    },
                    onOpenSettings = {
                        navController.navigate(Screen.Setting)
                    },
                    onOpenAssistant = {
                        navController.navigate(Screen.Assistant)
                    },
                    // 【重排·第十刀 2026-10-09】遗拾旧事：三个入口都跳现成页面
                    onOpenSearch = {
                        navController.navigate(Screen.MessageSearch)
                    },
                    onOpenFavorites = {
                        navController.navigate(Screen.Favorite)
                    },
                    onOpenChatHistory = {
                        navController.navigate(Screen.History)
                    },
                    onOpenPawprint = {
                        navController.navigate(Screen.Pawprint)
                    },
                    // 【2026-11-02 宝】当前会话子面板：主动消息 / 微信 Bot / QQ Bot
                    onOpenProactive = {
                        navController.navigate(Screen.SettingProactiveMessage)
                    },
                    onOpenWeixinBot = {
                        navController.navigate(Screen.SettingWeixinBot)
                    },
                    onOpenQqBot = {
                        navController.navigate(Screen.SettingQqBot)
                    },
                    // 【2026-11-03 宝】工作流：也挪进「当前会话」子面板
                    onOpenWorkflows = {
                        navController.navigate(Screen.Workflows)
                    },
                    // 【2026-11-02 宝】小工具子面板：插件 / Web 服务器 / 健康数据
                    onOpenPlugins = {
                        navController.navigate(Screen.SettingPlugins)
                    },
                    onOpenWebServer = {
                        navController.navigate(Screen.SettingWeb)
                    },
                    onOpenHealth = {
                        navController.navigate(Screen.Health)
                    },
                    // 【重排·第十二刀 2026-10-09】小工具：翻译 / 图像生成 / 小应用
                    onOpenTranslator = {
                        navController.navigate(Screen.Translator)
                    },
                    onOpenImageGen = {
                        navController.navigate(Screen.ImageGen)
                    },
                    onOpenMiniApps = {
                        // 点小程序按钮直接进浏览器（不再先过一层 Mini Apps 列表页）。
                        // 有旧页面就直接回去；没有（WebView 已被关掉）就给一个起始搜索页。
                        navController.navigate(
                            Screen.MiniApp(
                                url = "https://www.bing.com",
                                title = null,
                            )
                        )
                    },
                    // 【引用一句 2026-10-10】选中提示条上点「引用这句」→ 这时候才真的挂进引用条
                    onUsePickedQuote = {
                        val text = inputState.pickedText
                        val mid = inputState.pickedMessageId
                        if (text != null && mid != null) {
                            inputState.quotedMessageId = mid
                            inputState.quotedText = text
                            inputState.quotedPreview = text.replace("\n", " ").trim().take(120)
                        }
                        inputState.pickedText = null
                        inputState.pickedMessageId = null
                    },
                    onUpdateSettings = {
                        vm.updateSettings(it)
                    },
                    // 【重排·第十三刀 2026-10-09】「切换聊天」改成开会话面板
                    onOpenSessionPanel = {
                        showSessionPanel = true
                    },
                    onCancelClick = {
                        vm.stopGeneration()
                    },
                    enableSearch = enableWebSearch,
                    onToggleSearch = {
                        vm.updateSettings(setting.copy(enableWebSearch = !enableWebSearch))
                    },
                    onSendClick = {
                        if (currentChatModel == null) {
                            toaster.show("请先选择模型", type = ToastType.Error)
                            return@ChatInput
                        }
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = inputState.getContents(),
                                messageId = inputState.editingMessage!!,
                            )
                        } else {
                            vm.handleMessageSend(
                                content = inputState.getContents(),
                                // 【消息引用 2026-09-22】带上正在引用的那条（没引用就是 null）
                                quotedMessageId = inputState.quotedMessageId,
                                // 【引用一句 2026-10-10】只引用了其中一句时，那句原文
                                quotedText = inputState.quotedText,
                                // 【插话补充 · 2026-10-13】按了那条开关 = 补上文；没按 = null（新话题）
                                interjectKind = if (inputState.interjectSupplement) "supplement" else null,
                            )
                            scope.launch {
                                chatListState.requestScrollToItem(conversation.messageNodes.size.coerceAtMost(WINDOW_DISPLAY_SIZE) + 5)
                            }
                        }
                        inputState.clearInput()
                    },
                    onVoiceMessage = { url, duration, transcript ->
                        if (currentChatModel == null) {
                            toaster.show("请先选择模型", type = ToastType.Error)
                            return@ChatInput
                        }
                        vm.handleMessageSend(
                            listOf(
                                UIMessagePart.VoiceMessage(
                                    url = url,
                                    duration = duration,
                                    transcript = transcript,
                                )
                            ),
                            // 语音条只上屏不触发 AI（宝 2026-09-04 方案）：一条语音时间有限，
                            // 可以连发多条攒着（下一条还能纠正上一条），发文字消息时自动一起带走触发
                            answer = false,
                        )
                        scope.launch {
                            chatListState.requestScrollToItem(conversation.messageNodes.size.coerceAtMost(WINDOW_DISPLAY_SIZE) + 5)
                        }
                    },
                    onLongSendClick = {
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = inputState.getContents(),
                                messageId = inputState.editingMessage!!,
                            )
                        } else {
                            vm.handleMessageSend(
                                content = inputState.getContents(),
                                answer = false,
                                quotedMessageId = inputState.quotedMessageId,
                                quotedText = inputState.quotedText,
                            )
                            scope.launch {
                                chatListState.requestScrollToItem(conversation.messageNodes.size.coerceAtMost(WINDOW_DISPLAY_SIZE) + 5)
                            }
                        }
                        inputState.clearInput()
                    },
                    onScheduleClick = {
                        showScheduleDialog = true
                    },
                    onUpdateChatModel = {
                        vm.setChatModel(assistant = setting.getCurrentAssistant(), model = it)
                    },
                    onUpdateAssistant = {
                        vm.updateSettings(
                            setting.copy(
                                assistants = setting.assistants.map { assistant ->
                                    if (assistant.id == it.id) {
                                        it
                                    } else {
                                        assistant
                                    }
                                }
                            )
                        )
                    },
                    onUpdateSearchService = { index ->
                        vm.updateSettings(
                            setting.copy(
                                searchServiceSelected = index
                            )
                        )
                    },
                    onCompressContext = { additionalPrompt, targetTokens, keepRecentMessages ->
                        vm.handleCompressContext(additionalPrompt, targetTokens, keepRecentMessages)
                    },
                )
        }
        }
        }
        }

            // 【2026-11-07 宝】顶部那条测试玻璃条已撤：占 56dp，聊天记录看着太窄。
            // 气泡玻璃已经跑通，它任务完成了。（想再要顶栏玻璃，从这里恢复即可。）
        }
    }

    // 定时发送：让宝预约一条消息，到点自动发出去（2026-09-17 宝提的）
    if (showScheduleDialog) {
        ScheduleMessageDialog(
            conversationId = conversation.id.toString(),
            initialText = inputState.getContents()
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString("") { it.text },
            onDismiss = { showScheduleDialog = false },
            onScheduled = { inputState.clearInput() },
        )
    }

    // 【重排·第十三刀 2026-10-09】会话面板：最上面选助手，下面是当前助手的会话列表
    if (showSessionPanel) {
        ChatSessionPanel(
            onDismiss = { showSessionPanel = false },
            navController = navController,
            vm = vm,
            settings = setting,
            current = conversation,
        )
    }
}

@Composable
private fun TopBar(
    settings: Settings,
    conversation: Conversation,
    previewMode: Boolean,
    requestEditMode: Boolean,
    onToggleRequestEdit: () -> Unit,
    onClickMenu: () -> Unit,
    onNewChat: () -> Unit,
    onUpdateTitle: (String) -> Unit,
    onVoiceCall: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val titleState = useEditState<String> {
        onUpdateTitle(it)
    }

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        title = {
            val editTitleWarning = stringResource(R.string.chat_page_edit_title_warning)
            Surface(
                onClick = {
                    if (conversation.messageNodes.isNotEmpty()) {
                        titleState.open(conversation.title)
                    } else {
                        toaster.show(editTitleWarning, type = ToastType.Warning)
                    }
                },
                color = Color.Transparent,
            ) {
                Column {
                    val assistant = settings.getCurrentAssistant()
                    val model = settings.getCurrentChatModel()
                    val provider = model?.findProvider(providers = settings.providers, checkOverwrite = false)
                    Text(
                        text = conversation.title.ifBlank { stringResource(R.string.chat_page_new_chat) },
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (model != null && provider != null) {
                        Text(
                            text = "${assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }} / ${model.displayName} (${provider.name})",
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            color = LocalContentColor.current.copy(0.65f),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 8.sp,
                            )
                        )
                    }
                }
            }
        },
        actions = {
            // 【重排·第八刀 2026-10-08】请求编辑 / 语音通话 / 预览模式 三个撤出顶栏（进了 ＋ 面板）
            IconButton(
                onClick = {
                    onNewChat()
                }
            ) {
                Icon(HugeIcons.MessageAdd01, "New Message")
            }
        },
    )
    titleState.EditStateContent { title, onUpdate ->
        AlertDialog(
            onDismissRequest = {
                titleState.dismiss()
            },
            title = {
                Text(stringResource(R.string.chat_page_edit_title))
            },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        titleState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        titleState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }
}
