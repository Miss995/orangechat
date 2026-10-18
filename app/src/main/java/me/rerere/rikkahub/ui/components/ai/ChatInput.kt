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
import androidx.compose.foundation.ScrollState
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.MutableState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
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

// 【打印机外观 2026-10-18】机身形状：上圆下直（塑料件注塑，上缘是圆角；下缘接纸，是直的）
private val PrinterBodyShape = RoundedCornerShape(
    topStart = 14.dp, topEnd = 14.dp, bottomStart = 0.dp, bottomEnd = 0.dp
)

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
        // 【插话 2026-09-25 宝的需求】输入框里有内容时，即使猫在忙也按「发送」走：
        // 宝能在猫干活的时候把话塞进来。空着才是「停止」。
        // （2026-10-13：这里的「有内容」含附件 —— 一张图没配字也算。）
        if (loading && state.isEmpty()) onCancelClick() else onSendClick()
    }

    fun sendMessageWithoutAnswer() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        if (loading && state.isEmpty()) onCancelClick() else onLongSendClick()
    }

    var expand by remember { mutableStateOf(ExpandState.Collapsed) }
    // 【手势打架 · 2026-10-17】输入框自己的滚动状态（外面建好，传给 TextInputRow 里那个 TextField 共享同一实例）。
    // 老判据是数字数（>90 或换行>=4）猜的：53~90 那段明明该滚了，却判成"没得滚"，
    // 于是外层接走手势，滑输入框内部就把附件区拉了出来。
    // 现在直接问它「还能不能滚」—— 不分字号、不分中英文、不分框宽，文字真排不下才为真。
    val inputScrollState = rememberScrollState()
    // 【分区 · 2026-10-17 宝的方案】输入框在屏幕上的矩形 + 整块面板的屏幕原点。
    // 手势落下来先算落在哪边：框里 = 全归输入框（滚不动也不拉面板）；框外空白 = 才拉/收附件。
    // 空间判断比"滚到边界再传"稳 —— 落点一开始就知道，不用等手指动到哪儿才发现到边界。
    // 用 MutableState 对象传（不是 by 委托 —— 委托没法跨函数共享，TextField 在 TextInputRow 里）
    val fieldRectState = remember { mutableStateOf(Rect.Zero) }
    var surfaceOriginInWindow by remember { mutableStateOf(Offset.Zero) }
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
                    .onGloballyPositioned { surfaceOriginInWindow = it.localToWindow(Offset.Zero) }
                    // 【第二刀 2026-10-08】在输入框整块上滑 -> 拉起附件区（跟点 + 同一个动作）
                    // 用 Initial pass 只观察、不消费，免得抢掉输入框自己的手势
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val down = awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = PointerEventPass.Initial
                                )
                                // 【分区判断 · 2026-10-17 宝的方案】手指落在输入框里 -> 这一下全归输入框，
                                // 哪怕没内容可滚也不拉面板；落在框外的空白（边距、按钮那片）-> 才归外层。
                                if (fieldRectState.value.contains(surfaceOriginInWindow + down.position)) {
                                    while (true) {
                                        val e = awaitPointerEvent(PointerEventPass.Initial)
                                        val c = e.changes.firstOrNull { it.id == down.id }
                                        if (c == null || !c.pressed) break
                                    }
                                    continue
                                }
                                var startY = down.position.y
                                var fired = false
                                var alive = true
                                while (alive) {
                                    // 【边界才让位 · 2026-10-17 宝提的】外层始终只"看"不消费（Initial pass），
                                    // 每一帧按方向问输入框一句：你这个方向还能不能滚？
                                    //   还能滚 → 这一轮让给它，并把起算点挪到当前位置
                                    //            （不挪的话，滚到底那一下会被前面攒的距离一次性算成"滑够了"，面板立刻弹出来）
                                    //   滚到头（或压根没内容可滚）→ 才把这一下交给外层：上滑拉附件、下滑收附件。
                                    // 老版本是进门先查一次"能不能滚"，能滚就整段让位 ——
                                    // 结果文字一多，附件面板就彻底打不开了（除非清空或点 +），这才换成分帧判方向。
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val ch = event.changes.firstOrNull { it.id == down.id }
                                    if (ch == null || !ch.pressed) {
                                        alive = false
                                    } else {
                                        val dy = startY - ch.position.y   // 正 = 手指往上滑
                                        val scrollableThisWay =
                                            if (dy > 0) inputScrollState.canScrollForward
                                            else inputScrollState.canScrollBackward
                                        if (scrollableThisWay) {
                                            startY = ch.position.y
                                        } else if (!fired && (dy > 48f || dy < -48f)) {
                                            fired = true
                                            if (dy > 0) {
                                                if (expand != ExpandState.Files) {
                                                    expand = ExpandState.Files
                                                }
                                            } else {
                                                // 下滑 -> 收回（宝 2026-10-08 提的：只出得去、回不来）
                                                if (expand != ExpandState.Collapsed) {
                                                    expand = ExpandState.Collapsed
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // 【打印机外观 2026-10-18】裁切只在开模糊时需要；
                    // 平时不裁 —— 否则底下那道纸边永远伸不出机身圆角
                    .then(
                        if (settings.displaySetting.enableBlurEffect) Modifier
                            .clip(PrinterBodyShape)
                            .hazeEffect(
                                state = hazeState,
                                style = HazeMaterials.ultraThin(containerColor = hazeTintColor)
                            )
                        else Modifier
                    )
                    // 【打印机外观 2026-10-18】纸要长出机身一截：
                    // 机身里的纸边到底边就停了，这里在机身外面再补一段同样质感的纸，
                    // 两截接在一起 = 纸从机器底下露出来（不是被机身压住）
                    .drawWithContent {
                        drawContent()
                        val h = 9.dp.toPx()
                        val insetX = 8.dp.toPx()
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFFD5D2CA),   // 接住机身内纸边的底端
                                    Color(0xFFDFDCD4),
                                    Color(0xFFC7C4BC),
                                ),
                                startY = size.height,
                                endY = size.height + h,
                            ),
                            topLeft = Offset(insetX, size.height),
                            size = Size(size.width - insetX * 2, h),
                        )
                    },
                shape = PrinterBodyShape,
                tonalElevation = 0.dp,
                // 【打印机外观 2026-10-18】机身底色一律透明：渐变画在下面那层 Box 上
                // （Surface 的 color 只能纯色，画不了渐变）
                color = Color.Transparent,
            ) {
                // Use Box so background image can match parent size
                Box(
                    modifier = if (inputBgAvailable) Modifier
                        else Modifier.background(
                            // 【打印机外观】机身：深灰塑料外壳
                            Brush.verticalGradient(
                                0f to Color(0xFF3D3936),
                                0.34f to Color(0xFF312D2A),
                                0.72f to Color(0xFF282421),
                                1f to Color(0xFF221F1C),
                            )
                        )
                ) {
                    // 【打印机外观】出纸口：机身顶上那道缝，纸就是从这里出来的
                    // 2026-10-18 做厚度：上沿一道塑料切口的高光，中间落下去变黑，
                    // 下沿再返一点亮 —— 三层才像一条缝，一条渐变永远像贴纸
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 26.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(bottomStart = 2.dp, bottomEnd = 2.dp))
                            .background(
                                Brush.verticalGradient(
                                    0.00f to Color(0xFF1E1A18),   // 上沿：只是切口的一点毛边，别亮
                                    0.30f to Color(0xFF050404),   // 掉进去
                                    0.78f to Color(0xFF010101),   // 里面最黑
                                    1.00f to Color(0xFF171412),   // 下沿：一点点返光
                                )
                            )
                    )
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
                        // 【打印机外观 2026-10-18】水平 8dp 不放在这里了 ——
                        // 接缝要铺到机身左右边缘，内边距挪给顶盖和输入行各自承担。
                        // 底边也不留了：纸要一直排到机身底部，把那块黑盖掉
                        modifier = Modifier.padding(top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        // ===== 【打印机外观 2026-10-18】顶盖：两颗 LED + 散热孔 + 机身型号 =====
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, end = 12.dp, top = 5.dp, bottom = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            // 左边那颗：猫在干活时亮绿
                            // 2026-10-18 加体积：亮的时候外面一圈光晕、灯珠自己带球面渐变；
                            // 灭的时候是凹进去的（上暗下亮），不是一个贴上去的圆片
                            Box(
                                modifier = Modifier
                                    .width(13.dp).height(13.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (loading) {
                                    Box(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .clip(CircleShape)
                                            .background(
                                                Brush.radialGradient(
                                                    listOf(Color(0x997DDC8A), Color(0x007DDC8A))
                                                )
                                            )
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .width(7.dp).height(7.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (loading) {
                                                Brush.verticalGradient(
                                                    listOf(Color(0xFFC4F5CC), Color(0xFF4FAE60))
                                                )
                                            } else {
                                                Brush.verticalGradient(
                                                    listOf(Color(0xFF262C29), Color(0xFF4D5950))
                                                )
                                            }
                                        )
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .width(13.dp).height(13.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(7.dp).height(7.dp)
                                        .clip(CircleShape)
                                        .background(
                                            Brush.verticalGradient(
                                                listOf(Color(0xFF262C29), Color(0xFF4D5950))
                                            )
                                        )
                                )
                            }
                            // 散热孔
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color(0x4D000000)),
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                repeat(16) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f).fillMaxHeight()
                                            .background(Color(0x44000000))
                                    )
                                }
                            }
                            Text(
                                text = "CAT-PRINTER",
                                fontSize = 8.sp,
                                letterSpacing = 1.5.sp,
                                color = Color(0x38FFFFFF),
                            )
                        }

                        if (state.messageContent.isNotEmpty()) {
                            MediaFileInputRow(state = state)
                        }

                        // 【重排·第四刀 2026-10-08】+ 和发送键挪到输入框左右两侧
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
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

                            // 【打印机外观】屏框 + LCD：输入框像嵌在机身里的屏
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(Color(0xFF1A1715), Color(0xFF141210))
                                        )
                                    )
                                    .padding(5.dp)
                            ) {
                                // 【打印机外观】LCD 底色（外面那 5dp 露出的是屏框）
                                // 【打印机外观】LCD 底色，圆角 6dp —— 屏是嵌在框里的，不能是直角
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            Brush.verticalGradient(
                                                listOf(
                                                    Color(0xFF0D1A14),
                                                    Color(0xFF122019),
                                                    Color(0xFF0A1210),
                                                )
                                            )
                                        )
                                )
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
                                        loading = loading,
                                        onSendMessage = { sendMessage() },
                                        onUsePickedQuote = onUsePickedQuote,
                                        inputScrollState = inputScrollState,
                                        fieldRectState = fieldRectState
                                    )
                                }
                                // 【打印机外观】扫描线：细横纹，像真屏
                                Canvas(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .clip(RoundedCornerShape(6.dp))
                                ) {
                                    val step = 3.dp.toPx()
                                    var y = step
                                    while (y < size.height) {
                                        drawLine(
                                            color = Color(0x12FFFFFF),
                                            start = Offset(0f, y),
                                            end = Offset(size.width, y),
                                            strokeWidth = 1f,
                                        )
                                        y += step
                                    }
                                }
                                // 【打印机外观】斜向反光
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            Brush.linearGradient(
                                                colors = listOf(
                                                    Color(0x2EFFFFFF),
                                                    Color(0x0AFFFFFF),
                                                    Color.Transparent,
                                                ),
                                                start = Offset(0f, 0f),
                                                end = Offset(420f, 760f),
                                            )
                                        )
                                )
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
                                    // 【插话 2026-09-25 宝的需求】输入框里有内容时，按钮保持「发送」的样子，
                                    // 哪怕猫正在忙。这样一眼就能看出「现在按下去是插话，不是打断」。
                                    // （2026-10-13：含附件 —— 一张图没配字也算。）
                                    val showStop = loading && state.isEmpty()
                                    val containerColor = when {
                                        // 【打印机外观】橙色打印键
                                        showStop -> Color(0xFFB4402C)
                                        state.isEmpty() -> Color(0xFF3A342F)
                                        else -> Color(0xFFE8934A)
                                    }
                                    val contentColor = when {
                                        showStop -> Color(0xFFFFFFFF)
                                        state.isEmpty() -> Color(0x66FFFFFF)
                                        else -> Color(0xFF1A1310)
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

                        // ===== 【打印机外观 2026-10-18】底座 =====
                        // 【打印机外观】接缝和纸包在一起 —— 单独开一个 spacing=0 的 Column，
                        // 否则外层 Column 的 spacedBy(8.dp) 会在它俩中间撑出一道缝
                        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        // 底部接缝：机器和纸之间那道深色台
                        // 【打印机外观 2026-10-18】接缝要铺到机身左右边缘 —— 它所在的 Column
                        // 有 8dp 水平内边距，所以这里放宽 16dp、左移 8dp，把它顶回两侧
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                // 【打印机外观 2026-10-18】屏框底边到接缝上边太挤，
                                // 补 8dp 上边距（加 column 的 2dp 间距 ≈ 10dp），纸跟着一起往下
                                .padding(top = 8.dp)
                                .height(9.dp)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color(0xFF090807), Color(0xFF1C1917), Color(0xFF2B2724))
                                    )
                                )
                        )
                        // 机器底下压着的一沓纸：只露出一道纸边（左右内缩 8dp，躲开机身圆角）
                        // 【打印机外观 2026-10-18】不能再在机身里 offset 了 ——
                        // 一挪，接缝和纸之间就空出一条机身黑，看着像个莫名其妙的凹槽
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp)
                                .height(14.dp)
                                .background(
                                    // 【打印机外观】纸纹：很浅的暖白，几乎不带黄
                                    // （原来的米黄叠上投影会发土，看着像旧报纸）
                                    Brush.verticalGradient(
                                        0.00f to Color(0xFFEDEAE3),
                                        0.20f to Color(0xFFF7F5F0),
                                        0.42f to Color(0xFFFCFBF7),
                                        0.62f to Color(0xFFF4F2EC),
                                        0.75f to Color(0xFFE9E6DF),
                                        1.00f to Color(0xFFD5D2CA),
                                    )
                                )
                        ) {
                            // 【打印机外观】机器的投影：横向铺满纸边，高度只占 5dp，
                            // 底边留一点不透明 —— 这样它才"压"在纸上，不是悬在半空
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .background(
                                        Brush.verticalGradient(
                                            0.00f to Color(0x70000000),
                                            0.60f to Color(0x38000000),
                                            1.00f to Color(0x14000000),
                                        )
                                    )
                            )
                            // 压痕：阴影和纸的交界，纸在这里折起来
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 5.dp)
                                    .height(1.dp)
                                    .background(Color(0x40FFFFFF))
                            )
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
    // 【插话补充 · 2026-10-13】只有猫在干活（= 这条算插话）时才显示那条开关
    loading: Boolean,
    onSendMessage: () -> Unit,
    // 【引用一句 2026-10-10】选中提示条上点「引用这句」（要传下来：这个函数有自己的参数列表）
    onUsePickedQuote: (() -> Unit)? = null,
    // 【手势打架 · 2026-10-17】外面建好的滚动状态，传给 TextField 用同一个实例 ——
    // 这样外面那道手势闸读到的「能不能滚」就是输入框真实的滚动状态。
    inputScrollState: ScrollState = rememberScrollState(),
    // 【分区 · 2026-10-17】TextField 把自己在屏幕上的矩形写进来，外面手势闸读它判落点。
    fieldRectState: MutableState<Rect> = remember { mutableStateOf(Rect.Zero) },
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

        // 【插话补充 · 2026-10-13 宝定的】猫在干活时，宝发的话常常是"补她自己上一条"，
        // 可这个形状是"打断"，猫容易当成一句新话题去接（宝的观察：猫回得快、她回得慢，
        // 那句补充物理上落在猫回复之后，逻辑上却接她自己上一句）。
        // 按一下 = 标成"补充"，发送时写进消息；不按 = 新话题，跟以前一样。
        if (loading) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (state.interjectSupplement) MaterialTheme.colorScheme.tertiaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.clickable {
                    state.interjectSupplement = !state.interjectSupplement
                },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (state.interjectSupplement) "这是补充上文那条 · 再按取消"
                        else "这句是补充上文那条？（按一下）",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
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
                // 【打印机外观 2026-10-18】LCD 上的绿光字
                color = Color(0xFF7FFFA8),
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
                .onGloballyPositioned { fieldRectState.value = it.boundsInWindow() }
                .contentReceiver(receiveContentListener)
                .onFocusChanged {
                    isFocused = it.isFocused
                },
            shape = MaterialTheme.shapes.largeIncreased,
            placeholder = {
                // 【打印机外观 2026-10-18】占位字淡一点，像屏上没点亮的像素
                Text(
                    text = stringResource(R.string.chat_input_placeholder),
                    color = Color(0xFF7FFFA8).copy(alpha = 0.35f),
                )
            },
            // 【打印机外观】屏是固定大小的：最多两行，再多就在屏里滚
            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 2),
            // 【手势打架 · 2026-10-17】用外面传进来的实例，外面那道手势闸读的就是它。
            scrollState = inputScrollState,
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
                // 【打印机外观 2026-10-18】LCD：暗绿屏底
                focusedContainerColor = Color(0xFF0D1712),
                unfocusedContainerColor = Color(0xFF0D1712),
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
