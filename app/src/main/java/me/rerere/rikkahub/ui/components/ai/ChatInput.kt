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
import androidx.compose.animation.AnimatedVisibility as TopAnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
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
import androidx.compose.ui.platform.LocalDensity
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
import kotlin.math.floor
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
        if (loading && state.isEmpty()) {
            onCancelClick()
        } else {
            onSendClick()
        }
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
    // 【状态纸的收纸动画 · 2026-10-20 宝："收回去的时候也做个动画，感觉他好像突然飞走了一样"】
    // 纸收回去要缩完再撤弹窗，所以这里多留 260ms（弹窗条件用它撑着）。
    var statusHolding by remember { mutableStateOf(false) }
    LaunchedEffect(me.rerere.rikkahub.data.ai.PrinterStatus.active) {
        if (me.rerere.rikkahub.data.ai.PrinterStatus.active) {
            statusHolding = true
        } else {
            delay(220)
            statusHolding = false
        }
    }
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

    // 【别用 Surface · 2026-10-19】Material 的 Surface 不管 shape 是什么，
    // 都会把内容裁到自己的边界里。纸票往上画、出了这个容器的顶边 —— 就被它削掉了。
    // 【纸的定位基准 · 2026-10-20】纸贴着 Box 底边，要往上让到机身的顶边 ——
    // 这两个坐标各量一次，差值就是「该挪多少」。
    // 必须声明在 Box 外面：Box 的参数里就要用它，声明在里面够不着。
    var rootBoxRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var surfaceRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    // 这里本来只是想要个透明背景，Box 就够，而且不裁。
    // 【量底边 · 2026-10-20】纸要靠 align 贴在这个 Box 的底边，所以得知道它到底在哪。
    Box(
        modifier = Modifier.onGloballyPositioned { rootBoxRect = it.boundsInWindow() },
        contentAlignment = Alignment.TopCenter,
    ) {
        // 【纸票的状态判据 · 2026-10-19 提到最外层 · 2026-10-20 搬到这里】
        // 纸要从机身那个 Column 里搬出来、挂到 Box 上，那判据得先在 Box 层算好。
        // 它只依赖 state / loading，两个都是函数参数，放这儿拿得到。
        val paperKind = when {
            state.isEditing() -> "edit"
            state.pickedText != null -> "picked"
            state.quotedMessageId != null -> "quote"
            // 【等状态纸一起 · 2026-10-20 宝："让他晚一点出现，和那个状态纸一起出现"】
            // 只用 loading 的话，猫一开工它就出现 —— 那会儿锚点还没量好、位置没落定，
            // 看着就是在半空悬一下才接到输入框上。statusHolding 是状态纸那道门，两个同开同关。
            loading && statusHolding -> "interject"
            else -> null
        }
        // 【纸的滑入淡入 · 2026-10-20 跟着纸一起搬到外层】
        // 不用 AnimatedVisibility：它在 Column 作用域里有重载，
        // 编译器会优先挑那个扩展版本，Kotlin 直接报"隐式接收者"。
        // 自己用 alpha + translationY 做，效果一样，还不受作用域限制。
        val paperAnim by animateFloatAsState(
            targetValue = if (paperKind != null) 1f else 0f,
            animationSpec = tween(durationMillis = 240, easing = LinearOutSlowInEasing),
            label = "paperAnim",
        )
        // 【弹层的锚点 · 2026-10-19】Popup 的位置相对父级，先把这块的实际尺寸记下来
        var paperAnchorBox by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
        // 【纸票跟着页面走 · 2026-10-19】Popup 是独立的一层窗口，不归聊天页管 ——
        // 页面切走（比如去设置）时没人通知它，就留在原地飘着。
        // 用 Activity 的 resume 状态当判据：不在前台就把纸票关掉。
        val chatLifecycleOwner = LocalLifecycleOwner.current
        var pageActive by remember {
            mutableStateOf(chatLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        DisposableEffect(chatLifecycleOwner) {
            val observer = LifecycleEventObserver { _, _ ->
                pageActive = chatLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            }
            chatLifecycleOwner.lifecycle.addObserver(observer)
            onDispose { chatLifecycleOwner.lifecycle.removeObserver(observer) }
        }
        Column(
            modifier = modifier
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Input area with optional background image
            // 【机身外面套一层不裁剪的容器 · 2026-10-19】
            // 机身 Surface 带圆角，圆角会把里面的东西裁到形状里 —— 纸在它肚子里
            // 怎么改都会被裁（这就是"报0高能治顶、治不了少一半"的原因）。
            // 所以纸挂到外面来，跟机身并排。
            // 【外层从 Box 改成 Column · 2026-10-19】附件要放在机身「下面」当兄弟节点 ——
            // Box 是叠着的（会盖在机身上），Column 是排着的（把整块往上顶）。
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.onGloballyPositioned { paperAnchorBox = it.boundsInWindow() }
            ) {
            // 【机身抖一下 · 2026-10-20 宝："附件吸入、还有抖一下"】
            // 只认"附件从有变没"那一刻 —— 也就是发送。别的时候一律不抖。
            var hadAttachments by remember { mutableStateOf(false) }
            val machineShake = remember { Animatable(0f) }
            LaunchedEffect(state.messageContent.isNotEmpty()) {
                val has = state.messageContent.isNotEmpty()
                if (hadAttachments && !has) {
                    machineShake.snapTo(0f)
                    machineShake.animateTo(1f, tween(durationMillis = 280, easing = LinearEasing))
                    machineShake.snapTo(0f)
                }
                hadAttachments = has
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    // 【抖 · 2026-10-20】原型 machShake 是"往下 1.6px 再回来"，
                    // 一个 sin 半波就够了：0→1→0。
                    .graphicsLayer {
                        translationY = kotlin.math.sin(machineShake.value * kotlin.math.PI).toFloat() *
                            3f.dp.toPx()
                    }
                    .onGloballyPositioned {
                        surfaceOriginInWindow = it.localToWindow(Offset.Zero)
                        surfaceRect = it.boundsInWindow()
                    }
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
                            // 【缝要压在纸上 · 2026-10-19】纸票在 Column 里、画在出纸口之后，
                            // 顺序反了 —— 纸一出来就把缝盖住。给缝更高的 zIndex，
                            // 才能"纸从缝里冒出来、缝压着纸的下沿"。
                            .zIndex(2f)
                            // 【打印机外观 2026-10-18 宝定的】26 -> 14：出纸口别比纸窄太多，
                            // 免得看着像"纸进去一圈、出来还缩了"
                            .padding(horizontal = 14.dp)
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
                        // ===== 【出纸票 · 2026-10-18 宝定的】=====
                        // 选中/插话/引用/编辑 原来挤在 LCD 屏幕里，被屏框和机身一圈圈挤窄，
                        // 看着就是"纸进去一圈、出来还缩了"。现在四条统一做成一张从出纸口
                        // 冒出来的纸票：暖白、上沿直角（贴着那道缝）、下沿收一点点。
                        // 一次只露一张（宝：输入框不能无限叠加），按要紧程度排优先级。
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
                                        .clip(RoundedCornerShape(7.dp))
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
                                    // 【打印机外观 2026-10-18】宝给的定法：三层，从外到内是
                                    //   ① 一圈黑（把键从机身里切出来）
                                    //   ② 一圈「键色自己的暗版」（这才是阴影 —— 黑色在深机身里根本看不见，
                                    //      橘键的影子必须是深橘）
                                    //   ③ 中间纯亮色（键面全亮，不带任何渐变/暗部）
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color(0xFF0C0908))
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(1.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(lerp(containerColor, Color.Black, 0.62f))
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .padding(1.5.dp)
                                                    .clip(RoundedCornerShape(5.dp))
                                                    .background(containerColor)
                                            )
                                        }
                                    }
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
                                        // 【不做"纸的厚度"了 · 2026-10-19 宝选的 ②】
                                        // 这块现在的意思是「纸从机器里出来、一路往下」，
                                        // 纸边单独收一道边反而把纸截断了 —— 干脆到底都是同一个白。
                                        1.00f to Color(0xFFF4F2EC),
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

                // ===== 【出纸票 · 挂在机身外面】=====
                            // 【出纸动效 · 2026-10-18 宝定的】纸不是"啪"一下出现的：
                            // 高度从 0 长到自然高度（顶边贴着缝不动、底边往下推），240ms 缓出；
                            // 收起 150ms 快一点。用 tween 不用 spring —— spring 会带回弹，
                            // 那就又成"弹到界面上"了。
                            // 【纸票 · 2026-10-19 定稿】
                            // 前面试过两版都不对：
                            //   ① height(0.dp) —— 给子元素"最高 0"的约束，内容直接被压没；
                            //   ② layout 报 0 高 + 往上溢出 —— 往上长的方向是消息列表，列表压在
                            //      它上面（zIndex 只在同一层管用），纸的上半截被列表盖住了。
                            // 现在改成：在机身里正常占位，用滑入+淡入做动画。
                            // 代价是弹纸时机身会长一点，但因为是滑进去的，看着像"纸被吸出来"。
                            // 【附件 · 顶输入框 · 2026-10-19 宝定的第三版】
                            // 前两版都不对：
                            //   ① 塞机身肚子里 —— 机身被拉长；
                            //   ② 做成 Popup —— 不占布局、没有体积，键盘直接从它身上穿过去，还盖住机身底边。
                            // 现在放机身「下面」，跟机身并排当一个兄弟节点：它自己占一行，
                            // 把整块往上顶。机身一个像素都不长，键盘来了也会让路。
                            // 【这道门去掉了 · 2026-10-20 宝："完全没看出来动画在哪里"】
                            // 原来 messageContent 一空，整个组件就不画了 —— 数据清了、组件也没了，
                            // 附件吸入那 0.44 秒压根没机会播。
                            // 现在交给组件自己处理空：它内部留着"上一次的样子"，把纸画完、飞走，飞完再清。
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp)
                            ) {
                                PaperAttachmentStack(state = state)
                            }
                // 【重排·第三刀 2026-10-08】新面板：点 + 从输入框「上方」弹出的小面板
                // 内容先占位（宝：先看形态，之后再定哪几个常用的挪进来）
                AnimatedVisibility(
                    visible = expand == ExpandState.Panel,
                    enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
                    exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut(),
                ) {
                    // 【重排·第七刀 2026-10-08】面板套娃：点「当前会话」在它上面升起一层
                    // 【合成一整张纸 · 2026-10-21 宝选方案一：只用一道细实线分栏】
                    // 原来是 8dp，两块纸之间塌出一段空。缩到 1dp —— 纸底透出来正好是一条线。
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        if (showSessionPanel) {
                            // 【重排·第十八刀补2 2026-10-09】外壳圆角跟着高度走（20dp 配三四十 dp 的壳=胶囊）
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 7.dp)
                                    .clip(RoundedCornerShape(0.dp)),
                                shape = RoundedCornerShape(0.dp),
                                tonalElevation = 0.dp,
                                color = Color(0xFFF4F2EC),
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
                                        showDivider = false,
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
                                    .padding(horizontal = 7.dp)
                                    .clip(RoundedCornerShape(0.dp)),
                                shape = RoundedCornerShape(0.dp),
                                tonalElevation = 0.dp,
                                color = Color(0xFFF4F2EC),
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
                                        showDivider = false,
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
                                    .padding(horizontal = 7.dp)
                                    .clip(RoundedCornerShape(0.dp)),
                                shape = RoundedCornerShape(0.dp),
                                tonalElevation = 0.dp,
                                color = Color(0xFFF4F2EC),
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
                                        showDivider = false,
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
                                .padding(horizontal = 7.dp)
                                // 【抬一层 · 2026-10-20 宝：输入框上面放了状态纸和纸票之后，这排点不了了】
                                // 不猜是谁压的，直接把它抬到最上面 —— 不管打印机或纸怎么长上来，它都点得到。
                                .zIndex(3f)
                                .clip(RoundedCornerShape(0.dp)),
                            shape = RoundedCornerShape(0.dp),
                            tonalElevation = 0.dp,
                            color = Color(0xFFF4F2EC),
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
                                    showDivider = false,
                                    modifier = Modifier.width(72.dp),
                                ) { showToolPanel = !showToolPanel }
                            }
                        }
                    }
                }

                            // 【放宽 · 2026-10-20】原来非要 paperKind != null 才开这个弹窗，
                            // 结果普通发送（没引用没插话）时状态纸压根不显示。现在状态纸也能单独撑起它。
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
                    // 【附件面板换成纸 · 2026-10-21 宝半夜的"坏建议"，接受】
                    // 上面是打印纸、下面是白卡片，两套东西拼在一起。这里让它也变成一张纸：
                    // 直角（纸是裁出来的，不做圆角）、纸底（跟上面那张同色 #F8F6F1）。
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 【比纸边窄 · 2026-10-21 宝："附件面板的宽度会大于纸边的宽度，要稍微往回缩一点"】
                            // 上面那张纸是屏幕减 44（外面 8 + 自己 14），面板原来是屏幕减 16，宽出 28。
                            .padding(horizontal = 14.dp)
                            .clip(RoundedCornerShape(0.dp))
                            .then(
                                if (settings.displaySetting.enableBlurEffect) Modifier.hazeEffect(
                                    state = hazeState,
                                    style = HazeMaterials.ultraThin()
                                )
                                else Modifier
                            ),
                        shape = RoundedCornerShape(0.dp),
                        tonalElevation = 0.dp,
                        color = Color(0xFFF8F6F1),
                        contentColor = Color(0xFF3A342E),
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
        if (paperAnchorBox != null && pageActive &&
            (paperKind != null || statusHolding)
        ) {
            // 【非空取样 · 2026-10-20】paperAnchorBox 是可变 state，Kotlin 不会做非空推断，
            // 先取出来存成局部 val，下面一律用它。
            val anchorBox = paperAnchorBox!!
            // 【改成普通布局节点 · 2026-10-20】
            // Popup 的定位要压在「当前多高」上，可高度得等内容量完才知道 ——
            // 每一帧都在拿上一帧的高度补账，看着就是纸往下压一下。
            // 现在底边由 align 钉死、再往上让一个输入区的高度：
            // 纸怎么长都只往上顶，底边不动。
            Box(
                // 【纸自己这层也要居中 · 2026-10-20】外面那个 Box 管不到里面 ——
                // 纸的 Column 有固定宽度（跟着机身），这层默认从左边排，看着就偏左。
                contentAlignment = Alignment.TopCenter,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .offset {
                        // 【贴机身顶边 · 2026-10-20】纸的底边现在在 Box 底边，
                        // 往上让「Box 底 → 机身顶」这段距离，底边就正好压在机身顶边上。
                        val boxBottom = rootBoxRect?.bottom ?: 0f
                        val surfTop = surfaceRect?.top ?: boxBottom
                        IntOffset(0, -(boxBottom - surfTop).toInt())
                    }
            ) {
        Column(
            // 【纸封顶 · 2026-10-20 宝的截图："后面那坨紫色，简直是完美覆盖"】
            // 纸是从缝里往上长的，长高了就一路顶到输入框上方那排面板上，把人家的点击全挡住。
            // 面板在 Column 里、纸在根 Box 里 —— 不在同一层，zIndex 管不着。
            // 所以从高度上封：最多长这么高，多出来的在纸自己里面滑。
            // 【一张纸 · 2026-10-20 宝："为啥不能拼成同一张纸啊"】
            // 真正的做法不是把两张纸的圆角凑齐，是让它们都坐在同一层纸底上：
            // 这里铺一次底色（纸边留白 14dp），下面两张纸背景全透明、不带阴影。
            modifier = Modifier
                .heightIn(max = 96.dp)
                .width(with(LocalDensity.current) { anchorBox.width.toDp() })
                .padding(horizontal = 14.dp)
                .clip(RoundedCornerShape(3.dp))
                // 【跟附件纸同色 · 2026-10-20 宝："要跟下面那个纸边保持同一个颜色"】
                // 附件纸面是 #F8F6F1（它顶上那一小段 #F4F2EC 是为接住机身的暗纸边）。
                // 原来这里是 #EDE9DF，比它暗一档、还偏黄，两张纸贴一起就看得出是两张。
                .background(Color(0xFFF8F6F1))
                // 【不用 animateContentSize · 2026-10-20 宝："怎么改都在第二张跳出来的时候往下陷一点"】
                // 那个 API 是"内容先按最终高度排好，外框再动画追上去"——纸变高的那一瞬，
                // 内容已经先漏出一截，看着就是往下陷。高度动画得由纸自己长（内容被裁在里面）。
        ) {
        // ★ 状态纸 · 2026-10-20 宝换的方向：纸不假装变成气泡，
        // 它报"机器现在在干嘛"——门控/召回/拼请求/正在回复。
        // 全是真的，就不用接了，也不会穿帮。
        if (paperKind != null) {
            val picked = state.pickedText ?: ""
            Surface(
                // 【合页 · 2026-10-20 宝："两个合不上"】纸票换成在上之后，圆角没跟着换，
                // 两张都朝中间顶，中间就合不上。现在它只圆上面两个角；单独一张时四角都圆。
                shape = RoundedCornerShape(0.dp),
                color = Color.Transparent,
                contentColor = Color(0xFF2B2723),
                shadowElevation = 0.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    // 【只缩纸票自己 · 2026-10-20】原来这个动画套在整列上，
                    // 把状态纸也一起从底部长出来（所以它出现得慢、还分层）。
                    .graphicsLayer {
                        // 【不弹 · 2026-10-20 宝："太 q 弹了"】原来用 scaleY 从 0 长到 1，
                        // 那是把整张纸压扁再弹开 —— 字跟着变形，所以看着"啵"一下。
                        // 改成淡入 + 从缝里往上冒几像素，纸不变形。
                        alpha = paperAnim
                        translationY = (1f - paperAnim) * 6.dp.toPx()
                    },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when (paperKind) {
                        "edit" -> {
                            Text(
                                text = stringResource(R.string.editing),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = HugeIcons.Cancel01,
                                contentDescription = stringResource(R.string.cancel_edit),
                                modifier = Modifier.clickable { state.clearInput() },
                            )
                        }
                        "picked" -> {
                            Text(
                                text = "选中了 " + picked.length + " 个字：" +
                                    picked.replace("\n", " "),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(6.dp))
                            if (onUsePickedQuote != null) {
                                Text(
                                    text = "引用这句",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color(0xFF7A4B14),
                                    modifier = Modifier
                                        .clickable { onUsePickedQuote() }
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            Icon(
                                imageVector = HugeIcons.Cancel01,
                                contentDescription = "取消",
                                modifier = Modifier.clickable {
                                    state.pickedText = null
                                    state.pickedMessageId = null
                                },
                            )
                        }
                        "quote" -> {
                            Text(
                                text = "引用：" + state.quotedPreview.ifBlank { "（一条消息）" },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                imageVector = HugeIcons.Cancel01,
                                contentDescription = "取消引用",
                                modifier = Modifier.clickable {
                                    state.quotedMessageId = null
                                    state.quotedPreview = ""
                                    state.quotedText = null
                                },
                            )
                        }
                        else -> {
                            Text(
                                text = if (state.interjectSupplement)
                                    "这是补充上文那条 · 再按取消"
                                else "这句是补充上文那条？（按一下）",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        state.interjectSupplement = !state.interjectSupplement
                                    },
                            )
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = me.rerere.rikkahub.data.ai.PrinterStatus.active,
            // 【自己长 · 2026-10-20】高度动画必须由纸自己长（expandVertically 会把内容裁在里面），
            // 不能让外层 animateContentSize 代劳 —— 那个会让内容先漏出去一截。
            // 【一次性到位 · 2026-10-20】错位的根不在"哪一帧"，在"每一帧"：
            // Popup 每帧都用上一帧的尺寸算位置，而 expandVertically 让高度一直在变
            // → 整段动画里它一直在错。去掉高度动画：高度瞬间到位，只错一次跳变，
            // 同时淡入，那一下基本看不见。
            enter = fadeIn(animationSpec = tween(durationMillis = 170)),
            // 退出也一次性收掉：高度逐帧缩同样会一直在错
            exit = fadeOut(animationSpec = tween(durationMillis = 140)),
        ) {
            val psteps = me.rerere.rikkahub.data.ai.PrinterStatus.steps
            Surface(
                shape = RoundedCornerShape(0.dp),
                color = Color.Transparent,
                contentColor = Color(0xFF2B2723),
                shadowElevation = 0.dp,
                modifier = Modifier
                    .fillMaxWidth(),
            ) {
                // 【内部滑动 · 2026-10-20 宝要的】不挤出去，纸固定三行高，
                // 多的在里面滑 —— 记录全留着，想翻哪步花了多久就往上拨。
                // 手势不打架：Popup 是独立窗口，手指落在纸面上就归它。
                val stScroll = rememberScrollState()
                // 【高度按行高算，不写死 · 2026-10-20 宝："不是刚好两行，是两行加第三行的一底"】
                // 写死 44dp 是把行高当成 16dp 估的，实际 labelSmall 每行比它矮，
                // 两行排完还剩一点，第三行就露了个头。现在直接读字体的行高来算。
                // 【钉死行高 · 2026-10-20 宝："还是两行多一点第三行的边"】
                // 上一版读的是 labelSmall 的行高，但这两行字用的是 Monospace ——
                // 字体都不一样，行高当然对不上。而且 Android 的文字默认带上下内边距，
                // 那部分不算在 lineHeight 里，所以两行永远多出一个尾巴。
                // 现在两边一起钉死：行高 15sp + 关掉内边距，高度就按这两个数算，不会再飘。
                val pstepTextStyle = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 15.sp,
                    // 【再补一刀 · 2026-10-20】光关字体内边距不够 —— Compose 还会把行高
                    // 按基线在上下分配，每行实际比 lineHeight 高一点点。两行就是多出来的那一截，
                    // 正好够露出第三行的边。Trim.Both = 把行高上下多余的空隙全切掉，
                    // 每行真的等于 15sp，高度就能钉准。
                    // ⚠️ lineHeightStyle 是 TextStyle 的，不是 PlatformTextStyle 的（编译踩过）。
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.Both,
                    ),
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                )
                val pstepLineH = with(LocalDensity.current) { 15.sp.toDp() }
                val pstepMaxH = pstepLineH * 2 + 12.dp
                LaunchedEffect(psteps.size) {
                    stScroll.animateScrollTo(stScroll.maxValue)
                }
                Column(
                    modifier = Modifier
                        // 【封顶两行 · 2026-10-20 宝："一行就一行，两行就两行，三行还是两行"】
                // 原来写死 44dp（正好两行），只印一行时也占两行，多出半行就挤得难受。
                // 现在是「长到两行为止」：一行占一行，两行占两行，再多就封顶在里面滑。
                // 纸已经是普通节点、底边钉死，长高只往上顶，不会再弹。
                .heightIn(max = pstepMaxH)
                        // 【padding 往上挪 · 2026-10-20 宝的直觉："会不会是边边的空太多了"】
                        // 原来 padding 在 verticalScroll 之后（内层）= 它是滚动内容的一部分，
                        // 那 6dp 会跟着内容一起滚，把"两行"的可见空间吃掉一块 —— 于是第三行的边
                        // 从底下那 6dp 的位置里漏出来。挪到滚动区外面，两行就是干干净净两行。
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .verticalScroll(stScroll)
                ) {
                    psteps.forEach { pstep ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = pstep.label,
                                style = pstepTextStyle,
                                color = Color(0xFF4A443C),
                                modifier = Modifier.weight(1f),
                            )
                            if (pstep.elapsedMs > 0) {
                                Text(
                                    text = String.format("%.1fs", pstep.elapsedMs / 1000f),
                                    style = pstepTextStyle,
                                    color = Color(0xFF8A8070),
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                text = if (pstep.done) "✓" else "…",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = if (pstep.done) Color(0xFF6B7A4B) else Color(0xFFB08A3E),
                            )
                        }
                    }
                }
            }
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
    // 【打印机外观 2026-10-18】跟发送键同一套三层（宝定的）：
    //   ① 最外一圈黑 —— 把键从机身里切出来
    //   ② 一圈「机身色自己的暗版」—— 阴影必须是自己颜色的暗色，黑在深机身里看不见
    //   ③ 中间亮面 —— 键面全亮，不带渐变
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(7.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // 底下三层（只铺背景，不吃点击 —— 点击在外层那个 Box 上）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0C0908))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(1.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF221D19))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(1.5.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0xFF3A342F))
                )
            }
        }
        content()
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
    showDivider: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        // 【重排·第十八刀 2026-10-09】不用 Surface(onClick=...)：它自带 48dp 最小触摸区，
        // 格子明明只有一行字，却被撑出大片空白（宝："能开一家蜜雪冰城"）。
        // 改用 modifier.clickable，高度就由内容自己说了算（compact 才真的生效）。
        // 【第十八刀补 2026-10-09】圆角跟着高度走：格子矮了圆角不缩，就成了"扁胶囊"。
        modifier = modifier
            .clickable(onClick = onClick)
            // 【细实线分栏 · 2026-10-21 宝选方案一】
            // 上一版是满纸虚线框，看着像填空表。改成格子之间只画一条竖线，
            // 上下各留 22% 不画到底 —— 像纸上印的分栏，不像表格格。
            .drawBehind {
                if (showDivider) {
                    val w = 1.dp.toPx()
                    drawLine(
                        color = Color(0xFFD3CCBF),
                        start = Offset(size.width - w / 2f, size.height * 0.22f),
                        end = Offset(size.width - w / 2f, size.height * 0.78f),
                        strokeWidth = w,
                    )
                }
            },
        shape = RoundedCornerShape(0.dp),
        color = Color.Transparent,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = if (compact) 3.dp else 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF3A342F),
            )
        }
    }
}
