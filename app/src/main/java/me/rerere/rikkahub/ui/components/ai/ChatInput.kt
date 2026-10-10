/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.ai

import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import coil3.compose.AsyncImage
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.dokar.sonner.ToastType
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.Job
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.ui.UIMessagePart
import me.rerere.asr.ASRStatus
import me.rerere.common.android.appTempFolder
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.FullScreen
import me.rerere.hugeicons.stroke.Voice
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.datastore.getQuickMessagesOfAssistant
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.service.VoiceCallService
import me.rerere.rikkahub.ui.components.ui.KeepScreenOn
import me.rerere.rikkahub.ui.components.ui.toComposeColor
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionRecordAudio
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalASRState
import me.rerere.rikkahub.ui.context.LocalCurrentAssistant
import me.rerere.rikkahub.ui.context.LocalCurrentChatModel
import me.rerere.rikkahub.data.datastore.ChatFontFamily
import me.rerere.rikkahub.ui.context.LocalDisplaySettings
import me.rerere.rikkahub.ui.context.LocalProviders
import me.rerere.rikkahub.ui.context.LocalQuickMessages
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.hooks.rememberAssistantState
import me.rerere.rikkahub.utils.SoundEffectPlayer
import org.koin.compose.koinInject
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

enum class ExpandState {
    Collapsed, Files, Panel,
}

@Composable
fun ChatInput(
    state: ChatInputState,
    loading: Boolean,
    conversation: Conversation,
    settings: Settings,
    mcpManager: McpManager,
    hazeState: HazeState,
    enableSearch: Boolean,
    onToggleSearch: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onUpdateChatModel: (Model) -> Unit,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateSearchService: (Int) -> Unit,
    onCompressContext: (additionalPrompt: String, targetTokens: Int, keepRecentMessages: Int) -> Job,
    onCancelClick: () -> Unit,
    onSendClick: () -> Unit,
    onLongSendClick: () -> Unit,
    onScheduleClick: () -> Unit = {},
    onVoiceMessage: ((url: String, duration: Long, transcript: String) -> Unit)? = null,
    autoStartVoice: Boolean = false,
    // 【重排·第六刀 2026-10-08】顶栏那三个挪进面板，需要从 ChatPage 拿到入口
    requestEditMode: Boolean = false,
    onToggleRequestEdit: () -> Unit = {},
    onVoiceCall: () -> Unit = {},
    previewMode: Boolean = false,
    onTogglePreview: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenAssistant: () -> Unit = {},
    // 【重排·第九刀 2026-10-08】「切换聊天」要弹助手选择面板，需要它
    onUpdateSettings: (Settings) -> Unit = {},
    // 【重排·第十三刀 2026-10-09】「切换聊天」改成开「会话面板」
    onOpenSessionPanel: () -> Unit = {},
    // 【重排·第十刀 2026-10-09】「遗拾旧事」三个入口（都跳现成页面）
    onOpenSearch: () -> Unit = {},
    onOpenFavorites: () -> Unit = {},
    onOpenChatHistory: () -> Unit = {},
    // 【重排·第十二刀 2026-10-09】「小工具」三个入口（翻译 / 图像生成 / 小应用）
    onOpenTranslator: () -> Unit = {},
    onOpenImageGen: () -> Unit = {},
    onOpenMiniApps: () -> Unit = {},
    // 【引用一句 2026-10-10】选中提示条上点「引用这句」
    onUsePickedQuote: (() -> Unit)? = null,
) {
    val toaster = LocalToaster.current
    val assistant = settings.getCurrentAssistant()
    val hazeTintColor = MaterialTheme.colorScheme.surfaceContainerLow

    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    fun sendMessage() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        // 【插话 2026-09-25 宝的需求】输入框里有字时，即使猫在忙也按「发送」走：
        // 宝能在猫干活的时候把话塞进来。空着才是「停止」。
        if (loading && state.isEmpty()) onCancelClick() else onSendClick()
    }

    fun sendMessageWithoutAnswer() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        if (loading && state.isEmpty()) onCancelClick() else onLongSendClick()
    }

    var expand by remember { mutableStateOf(ExpandState.Collapsed) }
    var showInjectionSheet by remember { mutableStateOf(false) }
    var showCompressDialog by remember { mutableStateOf(false) }
    fun dismissExpand() {
        expand = ExpandState.Collapsed
        showInjectionSheet = false
        showCompressDialog = false
    }

    fun expandToggle(type: ExpandState) {
        if (expand == type) {
            dismissExpand()
        } else {
            expand = type
        }
    }

    val context = LocalContext.current
    val filesManager: FilesManager = koinInject()
    val asr = LocalASRState.current
    val asrState by asr.state.collectAsState()
    val voiceCallActiveId by VoiceCallService.activeConversationId.collectAsStateWithLifecycle()
    val isVoiceCallActive = voiceCallActiveId != null
    val hapticFeedback = LocalHapticFeedback.current
    val soundEffectPlayer: SoundEffectPlayer = koinInject()
    LaunchedEffect(Unit) {
        soundEffectPlayer.preload(R.raw.asr_start, R.raw.asr_stop)
    }
    val asrPermission = rememberPermissionState(PermissionRecordAudio)
    PermissionManager(permissionState = asrPermission)
    var asrBaseText by remember { mutableStateOf("") }
    var voiceMessageMode by remember { mutableStateOf(false) }
    // 【重排·第五刀 2026-10-08】输入模式：false=文字（输入框） true=语音（录音条）
    // 粘性的：手动切过去就一直保持，不会自己弹回来（宝定的）
    var voiceInputMode by remember { mutableStateOf(false) }
    // 【重排·第七刀 2026-10-08】「当前会话」子面板：升起在主面板上方（不是替换，两层并存）
    var showSessionPanel by remember { mutableStateOf(false) }
    // 【重排·第九刀 2026-10-08】「切换聊天」弹的助手选择面板
    var showAssistantPicker by remember { mutableStateOf(false) }
    // 【重排·第十刀 2026-10-09】「遗拾旧事」子面板：搜索聊天 / 收藏夹 / 聊天历史
    var showLegacyPanel by remember { mutableStateOf(false) }
    // 【重排·第十二刀 2026-10-09】「小工具」子面板：翻译 / 图像生成 / 小应用
    var showToolPanel by remember { mutableStateOf(false) }

    // Auto-start voice recording when entering from voice call notification
    LaunchedEffect(autoStartVoice) {
        if (autoStartVoice && asrState.status == ASRStatus.Idle && asrState.isAvailable) {
            if (asrPermission.allRequiredPermissionsGranted) {
                voiceMessageMode = true
                asr.start { }
            } else {
                asrPermission.requestPermissions()
            }
        }
    }

    LaunchedEffect(asrState.status) {
        when (asrState.status) {
            ASRStatus.Listening -> {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                soundEffectPlayer.play(R.raw.asr_start)
            }

            ASRStatus.Stopping -> {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
                soundEffectPlayer.play(R.raw.asr_stop)
            }

            else -> {}
        }
    }
    LaunchedEffect(asrState.errorMessage) {
        asrState.errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
            toaster.show(message = message, type = ToastType.Error)
            voiceMessageMode = false
        }
    }

    // Handle voice message completion
    LaunchedEffect(asrState.audioFilePath, voiceMessageMode) {
        if (voiceMessageMode && asrState.audioFilePath != null && asrState.status == ASRStatus.Idle) {
            onVoiceMessage?.invoke(
                asrState.audioFilePath!!,
                asrState.durationMs,
                asrState.transcript
            )
            voiceMessageMode = false
        }
    }

    // Camera launcher
    var cameraOutputUri by remember { mutableStateOf<Uri?>(null) }
    var cameraOutputFile by remember { mutableStateOf<File?>(null) }
    val (_, launchCameraCrop) = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            state.addImages(filesManager.createChatFilesByContents(listOf(croppedUri)))
            dismissExpand()
        },
        onCleanup = {
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        }
    )
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captureSuccessful ->
        if (captureSuccessful && cameraOutputUri != null) {
            if (settings.displaySetting.skipCropImage) {
                state.addImages(filesManager.createChatFilesByContents(listOf(cameraOutputUri!!)))
                cameraOutputFile?.delete()
                cameraOutputFile = null
                cameraOutputUri = null
                dismissExpand()
            } else {
                launchCameraCrop(cameraOutputUri!!)
            }
        } else {
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        }
    }
    val onLaunchCamera: () -> Unit = {
        cameraOutputFile = context.cacheDir.resolve("camera_${Uuid.random()}.jpg")
        cameraOutputUri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", cameraOutputFile!!
        )
        cameraLauncher.launch(cameraOutputUri!!)
    }

    // Image picker launcher
    var preCropTempFile by remember { mutableStateOf<File?>(null) }
    val (_, launchImageCrop) = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            state.addImages(filesManager.createChatFilesByContents(listOf(croppedUri)))
            dismissExpand()
        },
        onCleanup = {
            preCropTempFile?.delete()
            preCropTempFile = null
        }
    )
    val imagePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                Log.d("ImagePickButton", "Selected URIs: $selectedUris")
                if (settings.displaySetting.skipCropImage) {
                    state.addImages(filesManager.createChatFilesByContents(selectedUris))
                    dismissExpand()
                } else {
                    if (selectedUris.size == 1) {
                        val tempFile = File(context.appTempFolder, "pick_temp_${System.currentTimeMillis()}.jpg")
                        runCatching {
                            context.contentResolver.openInputStream(selectedUris.first())?.use { input ->
                                tempFile.outputStream().use { output -> input.copyTo(output) }
                            }
                            preCropTempFile = tempFile
                            launchImageCrop(tempFile.toUri())
                        }.onFailure {
                            Log.e("ImagePickButton", "Failed to copy image to temp, falling back", it)
                            launchImageCrop(selectedUris.first())
                        }
                    } else {
                        state.addImages(filesManager.createChatFilesByContents(selectedUris))
                        dismissExpand()
                    }
                }
            } else {
                Log.d("ImagePickButton", "No images selected")
            }
        }

    // Video picker launcher
    val videoPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                state.addVideos(filesManager.createChatFilesByContents(selectedUris))
                dismissExpand()
            }
        }

    // Audio picker launcher
    val audioPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                state.addAudios(filesManager.createChatFilesByContents(selectedUris))
                dismissExpand()
            }
        }

    // File picker launcher
    val filePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                val allowedMimeTypes = setOf(
                    "text/plain", "text/html", "text/css", "text/javascript", "text/csv", "text/xml",
                    "application/json", "application/javascript", "application/pdf",
                    "application/msword",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.ms-excel",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.ms-powerpoint",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "application/epub+zip",
                    "application/zip", "application/x-zip-compressed"
                )

                val allDocuments = mutableListOf<UIMessagePart.Document>()
                val allImageUris = mutableListOf<Uri>()

                uris.forEach { uri ->
                    val fileName = filesManager.getFileNameFromUri(uri) ?: "file"
                    val mime = filesManager.getFileMimeType(uri) ?: "text/plain"
                    val isZip = mime == "application/zip" || mime == "application/x-zip-compressed" ||
                        fileName.endsWith(".zip", ignoreCase = true)

                    if (isZip) {
                        // Auto-extract ZIP and add internal files
                        val extracted = filesManager.extractZipToChatFiles(uri, fileName)
                        allDocuments.addAll(extracted.documents)
                        allImageUris.addAll(extracted.images)
                    } else {
                        val isAllowed = allowedMimeTypes.contains(mime) || mime.startsWith("text/") ||
                            mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
                            mime == "application/pdf" ||
                            fileName.endsWith(".txt", ignoreCase = true) ||
                            fileName.endsWith(".md", ignoreCase = true) ||
                            fileName.endsWith(".csv", ignoreCase = true) ||
                            fileName.endsWith(".json", ignoreCase = true) ||
                            fileName.endsWith(".js", ignoreCase = true) ||
                            fileName.endsWith(".jsx", ignoreCase = true) ||
                            fileName.endsWith(".mjs", ignoreCase = true) ||
                            fileName.endsWith(".cjs", ignoreCase = true) ||
                            fileName.endsWith(".html", ignoreCase = true) ||
                            fileName.endsWith(".css", ignoreCase = true) ||
                            fileName.endsWith(".vue", ignoreCase = true) ||
                            fileName.endsWith(".svelte", ignoreCase = true) ||
                            fileName.endsWith(".xml", ignoreCase = true) ||
                            fileName.endsWith(".py", ignoreCase = true) ||
                            fileName.endsWith(".rb", ignoreCase = true) ||
                            fileName.endsWith(".lua", ignoreCase = true) ||
                            fileName.endsWith(".sql", ignoreCase = true) ||
                            fileName.endsWith(".java", ignoreCase = true) ||
                            fileName.endsWith(".kt", ignoreCase = true) ||
                            fileName.endsWith(".ts", ignoreCase = true) ||
                            fileName.endsWith(".tsx", ignoreCase = true) ||
                            fileName.endsWith(".dart", ignoreCase = true) ||
                            fileName.endsWith(".php", ignoreCase = true) ||
                            fileName.endsWith(".swift", ignoreCase = true) ||
                            fileName.endsWith(".go", ignoreCase = true) ||
                            fileName.endsWith(".bat", ignoreCase = true) ||
                            fileName.endsWith(".cmd", ignoreCase = true) ||
                            fileName.endsWith(".ps1", ignoreCase = true) ||
                            fileName.endsWith(".psm1", ignoreCase = true) ||
                            fileName.endsWith(".sh", ignoreCase = true) ||
                            fileName.endsWith(".bash", ignoreCase = true) ||
                            fileName.endsWith(".zsh", ignoreCase = true) ||
                            fileName.endsWith(".fish", ignoreCase = true) ||
                            fileName.endsWith(".c", ignoreCase = true) ||
                            fileName.endsWith(".h", ignoreCase = true) ||
                            fileName.endsWith(".cpp", ignoreCase = true) ||
                            fileName.endsWith(".cc", ignoreCase = true) ||
                            fileName.endsWith(".cxx", ignoreCase = true) ||
                            fileName.endsWith(".hpp", ignoreCase = true) ||
                            fileName.endsWith(".hh", ignoreCase = true) ||
                            fileName.endsWith(".hxx", ignoreCase = true) ||
                            fileName.endsWith(".rs", ignoreCase = true) ||
                            fileName.endsWith(".cs", ignoreCase = true) ||
                            fileName.endsWith(".markdown", ignoreCase = true) ||
                            fileName.endsWith(".mdx", ignoreCase = true) ||
                            fileName.endsWith(".toml", ignoreCase = true) ||
                            fileName.endsWith(".ini", ignoreCase = true) ||
                            fileName.endsWith(".env", ignoreCase = true) ||
                            fileName.endsWith(".gradle", ignoreCase = true) ||
                            fileName.endsWith(".kts", ignoreCase = true) ||
                            fileName.endsWith(".properties", ignoreCase = true) ||
                            fileName.endsWith(".proto", ignoreCase = true) ||
                            fileName.endsWith(".graphql", ignoreCase = true) ||
                            fileName.endsWith(".gql", ignoreCase = true) ||
                            fileName.endsWith(".yml", ignoreCase = true) ||
                            fileName.endsWith(".yaml", ignoreCase = true)
                        if (isAllowed) {
                            val localUri = filesManager.createChatFilesByContents(listOf(uri))[0]
                            allDocuments.add(UIMessagePart.Document(url = localUri.toString(), fileName = fileName, mime = mime))
                        } else {
                            toaster.show(
                                context.getString(R.string.chat_input_unsupported_file_type, fileName),
                                type = ToastType.Error
                            )
                        }
                    }
                }
                if (allDocuments.isNotEmpty()) {
                    state.addFiles(allDocuments)
                }
                if (allImageUris.isNotEmpty()) {
                    state.addImages(allImageUris)
                }
                if (allDocuments.isNotEmpty() || allImageUris.isNotEmpty()) {
                    dismissExpand()
                }
            }
        }

    // Collapse when ime is visible
    val imeVisile = WindowInsets.isImeVisible
    LaunchedEffect(imeVisile, showInjectionSheet, showCompressDialog) {
        if (imeVisile && !showInjectionSheet && !showCompressDialog) {
            dismissExpand()
        }
    }

    // Load input background image
    // 2026-09-17：改用 Coil。原来 BitmapFactory.decodeFile 是全尺寸解码，
    // 一张 2000×2000 的图展开就是 16MB，且不和消息图片共享缓存。
    val inputBgPath = settings.displaySetting.inputBackgroundPath
    val inputBgAvailable = remember(inputBgPath) {
        inputBgPath.isNotBlank() && File(inputBgPath).exists()
    }

    Surface(
        color = Color.Transparent,
    ) {
        Column(
            modifier = modifier
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 【重排·第三刀 2026-10-08】新面板：点 + 从输入框「上方」弹出的小面板
            // 内容先占位（宝：先看形态，之后再定哪几个常用的挪进来）
            AnimatedVisibility(
                visible = expand == ExpandState.Panel,
                enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut(),
            ) {
                // 【重排·第七刀 2026-10-08】面板套娃：点「当前会话」在它上面升起一层
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (showSessionPanel) {
                        // 【重排·第十八刀补2 2026-10-09】外壳圆角跟着高度走（20dp 配三四十 dp 的壳=胶囊）
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp)),
                            shape = RoundedCornerShape(12.dp),
                            tonalElevation = 0.dp,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                PanelCell(
                                    label = if (requestEditMode) "编辑·开" else "编辑",
                                    modifier = Modifier.weight(1f),
                                    compact = true,
                                ) { onToggleRequestEdit() }
                                PanelCell(
                                    label = "通话",
                                    modifier = Modifier.weight(1f),
                                    compact = true,
                                ) { onVoiceCall() }
                                PanelCell(
                                    label = if (previewMode) "预览·开" else "预览",
                                    modifier = Modifier.weight(1f),
                                    compact = true,
                                ) { onTogglePreview() }
                                PanelCell(
                                    label = "定时",
                                    modifier = Modifier.weight(1f),
                                    compact = true,
                                ) {
                                    onScheduleClick()
                                    expand = ExpandState.Collapsed
                                }
                            }
                        }
                    }

                    // 【重排·第十刀 2026-10-09】「遗拾旧事」子面板：三个入口都跳现成页面
                    if (showLegacyPanel) {
                        // 【重排·第十八刀补2 2026-10-09】外壳圆角跟着高度走（20dp 配三四十 dp 的壳=胶囊）
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp)),
                            shape = RoundedCornerShape(12.dp),
                            tonalElevation = 0.dp,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                PanelCell(
                                    label = "搜索聊天",
                                    modifier = Modifier.weight(1f),
                                    compact = true,
                                ) {
                                    onOpenSearch()
                                    expand = ExpandState.Collapsed
                                }
                                PanelCell(
                                    label = "收藏夹",
                                    modifier = Modifier.weight(1f),
                                    compact = true,
                                ) {
                                    onOpenFavorites()
                                    expand = ExpandState.Collapsed
                                }
                                PanelCell(
                                    label = "聊天历史",
                                    modifier = Modifier.weight(1f),
                                    compact = true,
                                ) {
                                    onOpenChatHistory()
                                    expand = ExpandState.Collapsed
                                }
                            }
                        }
                    }

                    // 【重排·第十二刀 2026-10-09】「小工具」子面板：翻译 / 图像生成 / 小应用
                    if (showToolPanel) {
                        // 【重排·第十八刀补2 2026-10-09】外壳圆角跟着高度走（20dp 配三四十 dp 的壳=胶囊）
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp)),
                            shape = RoundedCornerShape(12.dp),
                            tonalElevation = 0.dp,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                PanelCell(
                                    label = "翻译",
                                    modifier = Modifier.weight(1f),
                                    compact = true,
                                ) {
                                    onOpenTranslator()
                                    expand = ExpandState.Collapsed
                                }
                                PanelCell(
                                    label = "图像生成",
                                    modifier = Modifier.weight(1f),
                                    compact = true,
                                ) {
                                    onOpenImageGen()
                                    expand = ExpandState.Collapsed
                                }
                                PanelCell(
                                    label = "小应用",
                                    modifier = Modifier.weight(1f),
                                    compact = true,
                                ) {
                                    onOpenMiniApps()
                                    expand = ExpandState.Collapsed
                                }
                            }
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp)),
                        shape = RoundedCornerShape(20.dp),
                        tonalElevation = 0.dp,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 10.dp)
                                // 【重排·第十刀 2026-10-09】格子能横着滑（宝选的 b：以后再加格不用重排）
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PanelCell(label = "语音", modifier = Modifier.width(72.dp)) {
                                voiceInputMode = true
                                expand = ExpandState.Collapsed
                            }
                            PanelCell(label = "设置", modifier = Modifier.width(72.dp)) {
                                onOpenSettings()
                                expand = ExpandState.Collapsed
                            }
                            PanelCell(label = "切换聊天", modifier = Modifier.width(72.dp)) {
                                onOpenSessionPanel()
                                expand = ExpandState.Collapsed
                            }
                            PanelCell(
                                label = if (showSessionPanel) "当前会话·开" else "当前会话",
                                modifier = Modifier.width(72.dp),
                            ) { showSessionPanel = !showSessionPanel }
                            PanelCell(
                                label = if (showLegacyPanel) "遗拾旧事·开" else "遗拾旧事",
                                modifier = Modifier.width(72.dp),
                            ) { showLegacyPanel = !showLegacyPanel }
                            PanelCell(
                                label = if (showToolPanel) "小工具·开" else "小工具",
                                modifier = Modifier.width(72.dp),
                            ) { showToolPanel = !showToolPanel }
                        }
                    }
                }
            }

            // Input area with optional background image
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    // 【第二刀 2026-10-08】在输入框整块上滑 -> 拉起附件区（跟点 + 同一个动作）
                    // 用 Initial pass 只观察、不消费，免得抢掉输入框自己的手势
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val down = awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = PointerEventPass.Initial
                                )
                                // 【按需让位 · 2026-10-10 宝的方案】输入框内容超过它自己的可视高度
                                //（5 行 —— 见下面 TextFieldLineLimits.MultiLine(maxHeightInLines = 5)）
                                // → 它自己有得滚，这一轮让给它，等抬手再重来；否则外层照常接（拉附件）。
                                // 上一版无条件让（Final pass）错在哪：TextField 只要碰到指针就标 consumed
                                //（它得先防着"这可能是拖选 / 放光标"），外层永远在第一步就退出 →
                                // 不管有没有字，怎么滑都拉不出附件。
                                val __inputText = state.textContent.text.toString()
                                val __inputCanScroll =
                                    __inputText.length > 90 || __inputText.count { ch -> ch == '\n' } >= 4
                                if (__inputCanScroll) {
                                    waitForUpOrCancellation(pass = PointerEventPass.Initial)
                                    continue
                                }
                                val startY = down.position.y
                                var fired = false
                                var alive = true
                                while (alive) {
                                    // 【观察位 · 2026-10-10 最终版】回到 Initial：外层只"看"不消费。
                                    // 走到这里说明输入框没得滚（上面那道 __inputCanScroll 闸已经放行），
                                    // 所以放心观察 —— 滑够 48f 就拉 / 收附件。
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val ch = event.changes.firstOrNull { it.id == down.id }
                                    if (ch == null || !ch.pressed) {
                                        alive = false
                                    } else if (!fired && (startY - ch.position.y) > 48f) {
                                        fired = true
                                        if (expand != ExpandState.Files) {
                                            expand = ExpandState.Files
                                        }
                                    } else if (!fired && (ch.position.y - startY) > 48f) {
                                        // 下滑 -> 收回（宝 2026-10-08 提的：只出得去、回不来）
                                        fired = true
                                        if (expand != ExpandState.Collapsed) {
                                            expand = ExpandState.Collapsed
                                        }
                                    }
                                }
                            }
                        }
                    }
                    .clip(MaterialTheme.shapes.largeIncreased)
                    .then(
                        if (settings.displaySetting.enableBlurEffect) Modifier.hazeEffect(
                            state = hazeState,
                            style = HazeMaterials.ultraThin(containerColor = hazeTintColor)
                        )
                        else Modifier
                    ),
                shape = MaterialTheme.shapes.largeIncreased,
                tonalElevation = 0.dp,
                // When background image is set, make surface transparent so image is visible
                color = if (inputBgAvailable) Color.Transparent
                    else if (settings.displaySetting.enableBlurEffect) Color.Transparent
                    else settings.displaySetting.inputFieldColor?.let { it.toComposeColor() } ?: hazeTintColor,
            ) {
                // Use Box so background image can match parent size
                Box {
                    // Background image inside input area (matches content size exactly)
                    if (inputBgAvailable) {
                        AsyncImage(
                            model = File(inputBgPath),
                            contentDescription = null,
                            modifier = Modifier
                                .matchParentSize()
                                .clip(MaterialTheme.shapes.largeIncreased),
                            contentScale = ContentScale.Crop,
                            alpha = 1f,
                        )
                    }
                    Column(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (state.messageContent.isNotEmpty()) {
                            MediaFileInputRow(state = state)
                        }

                        // 【重排·第四刀 2026-10-08】+ 和发送键挪到输入框左右两侧
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            ActionIconButton(
                                onClick = {
                                    expandToggle(ExpandState.Panel)
                                }) {
                                Icon(
                                    imageVector = if (expand == ExpandState.Panel) HugeIcons.Cancel01 else HugeIcons.Add01,
                                    contentDescription = stringResource(R.string.more_options)
                                )
                            }

                            Box(modifier = Modifier.weight(1f)) {
                                if (voiceInputMode) {
                                    // 【重排·第五刀 2026-10-08】语音模式：这根条子占输入框的位子
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(24.dp))
                                            .clickable {
                                                when (asrState.status) {
                                                    ASRStatus.Listening -> asr.stop()
                                                    ASRStatus.Idle, ASRStatus.Error -> {
                                                        if (!asrPermission.allRequiredPermissionsGranted) {
                                                            asrPermission.requestPermissions()
                                                        } else {
                                                            voiceMessageMode = true
                                                            asr.start { }
                                                        }
                                                    }
                                                    ASRStatus.Connecting, ASRStatus.Stopping -> {}
                                                }
                                            },
                                        shape = RoundedCornerShape(24.dp),
                                        color = if (asrState.isRecording) MaterialTheme.colorScheme.errorContainer
                                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 10.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                        ) {
                                            Text(
                                                text = if (asrState.isRecording) "点击结束并发送" else "点击开始说话",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = if (asrState.isRecording) MaterialTheme.colorScheme.onErrorContainer
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                } else {
                                    TextInputRow(
                                        state = state,
                                        onSendMessage = { sendMessage() }
                                    )
                                }
                            }

                            // 【重排·第五刀 2026-10-08】语音模式下，这个位子换成「切回文字」
                            if (voiceInputMode) {
                                ActionIconButton(
                                    onClick = {
                                        if (asrState.isRecording) asr.stop()
                                        voiceInputMode = false
                                    }
                                ) {
                                    Icon(
                                        imageVector = HugeIcons.Cancel01,
                                        contentDescription = "切回文字输入",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            } else {
                            AnimatedVisibility(
                                visible = !asrState.isRecording,
                                enter = fadeIn() + scaleIn(),
                                exit = fadeOut() + scaleOut(),
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .combinedClickable(
                                            enabled = loading || !state.isEmpty(),
                                            onClick = {
                                                dismissExpand()
                                                sendMessage()
                                            }, onLongClick = {
                                                dismissExpand()
                                                sendMessageWithoutAnswer()
                                            }
                                        )
                                ) {
                                    // 【插话 2026-09-25 宝的需求】输入框里有字时，按钮保持「发送」的样子，
                                    // 哪怕猫正在忙。这样一眼就能看出「现在按下去是插话，不是打断」。
                                    val showStop = loading && state.isEmpty()
                                    val containerColor = when {
                                        showStop -> MaterialTheme.colorScheme.errorContainer
                                        state.isEmpty() -> MaterialTheme.colorScheme.surfaceContainerHigh
                                        else -> MaterialTheme.colorScheme.primary
                                    }
                                    val contentColor = when {
                                        showStop -> MaterialTheme.colorScheme.onErrorContainer
                                        state.isEmpty() -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                        else -> MaterialTheme.colorScheme.onPrimary
                                    }
                                    Surface(
                                        modifier = Modifier.fillMaxSize(),
                                        shape = CircleShape,
                                        color = containerColor,
                                        content = {})
                                    if (showStop) {
                                        KeepScreenOn()
                                        Icon(
                                            imageVector = HugeIcons.Cancel01,
                                            contentDescription = stringResource(R.string.stop),
                                            tint = contentColor,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    } else {
                                        Icon(
                                            imageVector = HugeIcons.ArrowUp02,
                                            contentDescription = stringResource(R.string.send),
                                            tint = contentColor,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                            }
                        }

                    }

                    // 【重排·第九刀 2026-10-08】助手选择面板（复用现成的 AssistantPickerSheet）
                    if (showAssistantPicker) {
                        val assistantState = rememberAssistantState(settings, onUpdateSettings)
                        AssistantPickerSheet(
                            settings = settings,
                            currentAssistant = assistant,
                            onAssistantSelected = { picked ->
                                showAssistantPicker = false
                                assistantState.setSelectAssistant(picked)
                            },
                            onDismiss = {
                                showAssistantPicker = false
                            }
                        )
                    }
                }
            }

            // Expanded content
            Box(
                modifier = Modifier
                    .animateContentSize()
                    .fillMaxWidth()
            ) {
                BackHandler(
                    enabled = expand != ExpandState.Collapsed,
                ) {
                    dismissExpand()
                }
                if (expand == ExpandState.Files) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .then(
                                if (settings.displaySetting.enableBlurEffect) Modifier.hazeEffect(
                                    state = hazeState,
                                    style = HazeMaterials.ultraThin()
                                )
                                else Modifier
                            ),
                        shape = RoundedCornerShape(20.dp),
                        tonalElevation = 0.dp,
                        color = if (settings.displaySetting.enableBlurEffect) Color.Transparent else hazeTintColor,
                    ) {
                        FilesPicker(
                            conversation = conversation,
                            state = state,
                            assistant = assistant,
                            mcpManager = mcpManager,
                            onCompressContext = onCompressContext,
                            onUpdateAssistant = onUpdateAssistant,
                            showInjectionSheet = showInjectionSheet,
                            onShowInjectionSheetChange = { showInjectionSheet = it },
                            showCompressDialog = showCompressDialog,
                            onShowCompressDialogChange = { showCompressDialog = it },
                            onDismiss = { dismissExpand() },
                            onTakePic = onLaunchCamera,
                            onPickImage = { imagePickerLauncher.launch("image/*") },
                            onPickVideo = { videoPickerLauncher.launch("video/*") },
                            onPickAudio = { audioPickerLauncher.launch("audio/*") },
                            onPickFile = { filePickerLauncher.launch(arrayOf("*/*")) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionIconButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(30.dp),
        shape = CircleShape,
        tonalElevation = 0.dp,
        color = Color.Transparent,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

@Composable
private fun TextInputRow(
    state: ChatInputState,
    onSendMessage: () -> Unit,
) {
    val displaySettings = LocalDisplaySettings.current
    val filesManager: FilesManager = koinInject()
    val assistant = LocalCurrentAssistant.current
    val allQuickMessages = LocalQuickMessages.current
    val quickMessages = remember(allQuickMessages, assistant.quickMessageIds) {
        allQuickMessages.filter { it.id in assistant.quickMessageIds }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (state.isEditing()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = stringResource(R.string.editing))
                    Spacer(Modifier.weight(1f))
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = stringResource(R.string.cancel_edit),
                        modifier = Modifier.clickable { state.clearInput() }
                    )
                }
            }
        }

        // 【消息引用 2026-09-22】引用条：显示被引消息的摘要，右侧一个叉取消。
        // 形状/配色跟上面那条"编辑中"保持一致，宝一眼能认。
        if (state.quotedMessageId != null) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "引用：" + state.quotedPreview.ifBlank { "（一条消息）" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = "取消引用",
                        modifier = Modifier.clickable {
                            state.quotedMessageId = null
                            state.quotedPreview = ""
                            state.quotedText = null
                        }
                    )
                }
            }
        }

        // 【引用一句 2026-10-10】选中一段文字 → 冒一条提示，点了才真引用。
        // 为什么不直接在系统工具条上加一项：Compose 1.12 上 LocalTextToolbar 的覆盖不生效
        //（Google issue 447192728 / 184950231），详见 ChatMessage.kt 里 SelectableWithQuote 的注释。
        // 所以系统工具条原样不动，猫在旁边自己冒一条。
        state.pickedText?.let { picked ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "选中了 " + picked.length + " 个字：" + picked.replace("\n", " "),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    if (onUsePickedQuote != null) {
                        TextButton(onClick = onUsePickedQuote) {
                            Text("引用这句", style = MaterialTheme.typography.labelMedium)
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = "取消",
                        modifier = Modifier.clickable {
                            state.pickedText = null
                            state.pickedMessageId = null
                        }
                    )
                }
            }
        }

        var isFocused by remember { mutableStateOf(false) }
        var isFullScreen by remember { mutableStateOf(false) }
        val receiveContentListener = remember(
            displaySettings.pasteLongTextAsFile, displaySettings.pasteLongTextThreshold
        ) {
            ReceiveContentListener { transferableContent ->
                when {
                    transferableContent.hasMediaType(MediaType.Image) -> {
                        transferableContent.consume { item ->
                            val uri = item.uri
                            if (uri != null) {
                                state.addImages(
                                    filesManager.createChatFilesByContents(
                                        listOf(uri)
                                    )
                                )
                            }
                            uri != null
                        }
                    }

                    displaySettings.pasteLongTextAsFile && transferableContent.hasMediaType(MediaType.Text) -> {
                        transferableContent.consume { item ->
                            val text = item.text?.toString()
                            if (text != null && text.length > displaySettings.pasteLongTextThreshold) {
                                val document = filesManager.createChatTextFile(text)
                                state.addFiles(listOf(document))
                                true
                            } else {
                                false
                            }
                        }
                    }

                    else -> transferableContent
                }
            }
        }
        TextField(
            state = state.textContent,
            textStyle = LocalTextStyle.current.copy(
                fontFamily = when (displaySettings.chatFontFamily) {
                    ChatFontFamily.DEFAULT -> FontFamily.Default
                    ChatFontFamily.SERIF -> FontFamily.Serif
                    ChatFontFamily.MONOSPACE -> FontFamily.Monospace
                    ChatFontFamily.CUSTOM -> {
                        val fontPath = displaySettings.customFontPath
                        if (fontPath.isNotBlank() && java.io.File(fontPath).exists()) {
                            FontFamily(Font(java.io.File(fontPath)))
                        } else {
                            FontFamily.Default
                        }
                    }
                }
            ),
            modifier = Modifier
                .fillMaxWidth()
                .contentReceiver(receiveContentListener)
                .onFocusChanged {
                    isFocused = it.isFocused
                },
            shape = MaterialTheme.shapes.largeIncreased,
            placeholder = {
                Text(stringResource(R.string.chat_input_placeholder))
            },
            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5),
            keyboardOptions = KeyboardOptions(
                imeAction = if (displaySettings.sendOnEnter) ImeAction.Send else ImeAction.Default
            ),
            onKeyboardAction = {
                if (displaySettings.sendOnEnter && !state.isEmpty()) {
                    onSendMessage()
                }
            },
            colors = TextFieldDefaults.colors().copy(
                unfocusedIndicatorColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
            ),
            trailingIcon = {
                if (isFocused) {
                    IconButton(
                        onClick = {
                            isFullScreen = !isFullScreen
                        }) {
                        Icon(HugeIcons.FullScreen, null)
                    }
                }
            },
            leadingIcon = if (quickMessages.isNotEmpty()) {
                {
                    QuickMessageButton(quickMessages = quickMessages, state = state)
                }
            } else null,
        )
        if (isFullScreen) {
            FullScreenEditor(state = state) {
                isFullScreen = false
            }
        }
    }
}

@Composable
private fun QuickMessageButton(
    quickMessages: List<QuickMessage>,
    state: ChatInputState,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(
        onClick = {
            expanded = !expanded
        }) {
        Icon(HugeIcons.Zap, null)
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .widthIn(min = 200.dp)
                .width(IntrinsicSize.Min)
        ) {
            quickMessages.forEach { quickMessage ->
                Surface(
                    onClick = {
                        state.appendText(quickMessage.content)
                        expanded = false
                    },
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Text(
                            text = quickMessage.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = quickMessage.content,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FullScreenEditor(
    state: ChatInputState, onDone: () -> Unit
) {
    BasicAlertDialog(
        onDismissRequest = {
            onDone()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false, decorFitsSystemWindows = false
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding(),
            verticalArrangement = Arrangement.Bottom
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 800.dp),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(8.dp)
                        .fillMaxSize(),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row {
                        TextButton(
                            onClick = {
                                onDone()
                            }) {
                            Text(stringResource(R.string.chat_page_save))
                        }
                    }
                    TextField(
                        state = state.textContent,
                        modifier = Modifier
                            .padding(bottom = 2.dp)
                            .fillMaxSize(),
                        shape = RoundedCornerShape(32.dp),
                        placeholder = {
                            Text(stringResource(R.string.chat_input_placeholder))
                        },
                        colors = TextFieldDefaults.colors().copy(
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                    )
                }
            }
        }
    }
}

// 【重排·第六刀 2026-10-08】面板里的一格（抽出来，免得 8 格写 8 遍）
@Composable
private fun PanelCell(
    label: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        // 【重排·第十八刀 2026-10-09】不用 Surface(onClick=...)：它自带 48dp 最小触摸区，
        // 格子明明只有一行字，却被撑出大片空白（宝："能开一家蜜雪冰城"）。
        // 改用 modifier.clickable，高度就由内容自己说了算（compact 才真的生效）。
        // 【第十八刀补 2026-10-09】圆角跟着高度走：格子矮了圆角不缩，就成了"扁胶囊"。
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(if (compact) 8.dp else 12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = if (compact) 4.dp else 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
