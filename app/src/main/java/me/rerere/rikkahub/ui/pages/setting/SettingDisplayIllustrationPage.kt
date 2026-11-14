/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.setting

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import coil3.compose.AsyncImage
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Canvas
import me.rerere.rikkahub.ui.components.ui.drawBubbleImage
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingDisplayIllustrationPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }
    val context = LocalContext.current

    fun updateDisplaySetting(setting: DisplaySetting) {
        displaySetting = setting
        vm.updateSettings(settings.copy(displaySetting = setting))
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val bgDir = remember { File(context.filesDir, "input_backgrounds").apply { mkdirs() } }
    val drawerBgDir = remember { File(context.filesDir, "drawer_backgrounds").apply { mkdirs() } }

    // Input background picker launcher
    val bgPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val destFile = File(bgDir, "input_bg_${System.currentTimeMillis()}.png")
            context.contentResolver.openInputStream(it)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            updateDisplaySetting(displaySetting.copy(inputBackgroundPath = destFile.absolutePath))
        }
    }

    // Drawer background picker launcher
    val drawerBgPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val destFile = File(drawerBgDir, "drawer_bg_${System.currentTimeMillis()}.png")
            context.contentResolver.openInputStream(it)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            updateDisplaySetting(displaySetting.copy(drawerBackgroundPath = destFile.absolutePath))
        }
    }

    // Avatar Frame launchers
    val frameDir = remember { File(context.filesDir, "avatar_frames").apply { mkdirs() } }
    val userFramePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val destFile = File(frameDir, "user_frame_${System.currentTimeMillis()}.png")
            context.contentResolver.openInputStream(it)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            updateDisplaySetting(displaySetting.copy(userAvatarFramePath = destFile.absolutePath))
        }
    }
    val aiFramePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val destFile = File(frameDir, "ai_frame_${System.currentTimeMillis()}.png")
            context.contentResolver.openInputStream(it)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            updateDisplaySetting(displaySetting.copy(aiAvatarFramePath = destFile.absolutePath))
        }
    }

    // Bubble background picker launcher
    val bubbleBgDir = remember { File(context.filesDir, "bubble_backgrounds").apply { mkdirs() } }
    val userBubbleBgPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val destFile = File(bubbleBgDir, "user_bubble_${System.currentTimeMillis()}.png")
            context.contentResolver.openInputStream(it)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            updateDisplaySetting(displaySetting.copy(userBubbleImagePath = destFile.absolutePath))
        }
    }
    val aiBubbleBgPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val destFile = File(bubbleBgDir, "ai_bubble_${System.currentTimeMillis()}.png")
            context.contentResolver.openInputStream(it)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            updateDisplaySetting(displaySetting.copy(assistantBubbleImagePath = destFile.absolutePath))
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("插图素材") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Input Background
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("输入框背景") },
                ) {
                    item(
                        headlineContent = { Text("自定义输入框背景图") },
                        supportingContent = {
                            Text(
                                if (displaySetting.inputBackgroundPath.isNotBlank() && File(displaySetting.inputBackgroundPath).exists())
                                    "当前背景: ${File(displaySetting.inputBackgroundPath).name}"
                                else "选择一张图片作为输入框区域背景"
                            )
                        },
                        trailingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (displaySetting.inputBackgroundPath.isNotBlank()) {
                                    TextButton(onClick = {
                                        File(displaySetting.inputBackgroundPath).delete()
                                        updateDisplaySetting(displaySetting.copy(inputBackgroundPath = ""))
                                    }) { Text("清除") }
                                }
                                TextButton(onClick = {
                                    bgPickerLauncher.launch(arrayOf("image/*"))
                                }) { Text("选择图片") }
                            }
                        },
                    )
                }
            }

            // Drawer Background
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("侧边栏背景") },
                ) {
                    item(
                        headlineContent = { Text("自定义侧边栏背景图") },
                        supportingContent = {
                            Text(
                                if (displaySetting.drawerBackgroundPath.isNotBlank() && File(displaySetting.drawerBackgroundPath).exists())
                                    "当前背景: ${File(displaySetting.drawerBackgroundPath).name}"
                                else "选择一张图片作为侧边栏背景"
                            )
                        },
                        trailingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (displaySetting.drawerBackgroundPath.isNotBlank()) {
                                    TextButton(onClick = {
                                        File(displaySetting.drawerBackgroundPath).delete()
                                        updateDisplaySetting(displaySetting.copy(drawerBackgroundPath = ""))
                                    }) { Text("清除") }
                                }
                                TextButton(onClick = {
                                    drawerBgPickerLauncher.launch(arrayOf("image/*"))
                                }) { Text("选择图片") }
                            }
                        },
                    )
                }
            }

            // 气泡背景图 & 圆角
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("气泡背景") },
                ) {
                    item(
                        headlineContent = { Text("用户气泡背景图") },
                        supportingContent = {
                            Text(
                                if (displaySetting.userBubbleImagePath.isNotBlank() && File(displaySetting.userBubbleImagePath).exists())
                                    "当前背景: ${File(displaySetting.userBubbleImagePath).name}"
                                else "选择一张图片作为用户气泡背景"
                            )
                        },
                        trailingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (displaySetting.userBubbleImagePath.isNotBlank()) {
                                    TextButton(onClick = {
                                        File(displaySetting.userBubbleImagePath).delete()
                                        updateDisplaySetting(displaySetting.copy(userBubbleImagePath = ""))
                                    }) { Text("清除") }
                                }
                                TextButton(onClick = {
                                    userBubbleBgPicker.launch(arrayOf("image/*"))
                                }) { Text("选择图片") }
                            }
                        },
                    )
                    item(
                        headlineContent = { Text("AI气泡背景图") },
                        supportingContent = {
                            Text(
                                if (displaySetting.assistantBubbleImagePath.isNotBlank() && File(displaySetting.assistantBubbleImagePath).exists())
                                    "当前背景: ${File(displaySetting.assistantBubbleImagePath).name}"
                                else "选择一张图片作为AI气泡背景"
                            )
                        },
                        trailingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (displaySetting.assistantBubbleImagePath.isNotBlank()) {
                                    TextButton(onClick = {
                                        File(displaySetting.assistantBubbleImagePath).delete()
                                        updateDisplaySetting(displaySetting.copy(assistantBubbleImagePath = ""))
                                    }) { Text("清除") }
                                }
                                TextButton(onClick = {
                                    aiBubbleBgPicker.launch(arrayOf("image/*"))
                                }) { Text("选择图片") }
                            }
                        },
                    )
                    item(
                        headlineContent = { Text("气泡圆角(0=直角)") },
                        supportingContent = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Slider(
                                    value = displaySetting.bubbleCornerRadius,
                                    onValueChange = {
                                        updateDisplaySetting(displaySetting.copy(bubbleCornerRadius = it))
                                    },
                                    valueRange = 0f..32f,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(text = "${displaySetting.bubbleCornerRadius.toInt()}")
                            }
                        }
                    )
                    item(
                        headlineContent = { Text("平铺模式") },
                        supportingContent = { Text("开 = 中间那块重复贴（形状不变形）；关 = 拉伸（会变形）") },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.bubbleTiled,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(bubbleTiled = it))
                                }
                            )
                        }
                    )
                    item(
                        headlineContent = { Text("四边裁剪") },
                        supportingContent = {
                            Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(text = "左", modifier = Modifier.width(28.dp))
                                        Slider(
                                            value = displaySetting.bubbleCropL,
                                            onValueChange = {
                                                updateDisplaySetting(displaySetting.copy(bubbleCropL = it))
                                            },
                                            valueRange = 0f..0.45f,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(text = "${(displaySetting.bubbleCropL * 100).toInt()}%")
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(text = "上", modifier = Modifier.width(28.dp))
                                        Slider(
                                            value = displaySetting.bubbleCropT,
                                            onValueChange = {
                                                updateDisplaySetting(displaySetting.copy(bubbleCropT = it))
                                            },
                                            valueRange = 0f..0.45f,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(text = "${(displaySetting.bubbleCropT * 100).toInt()}%")
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(text = "右", modifier = Modifier.width(28.dp))
                                        Slider(
                                            value = displaySetting.bubbleCropR,
                                            onValueChange = {
                                                updateDisplaySetting(displaySetting.copy(bubbleCropR = it))
                                            },
                                            valueRange = 0f..0.45f,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(text = "${(displaySetting.bubbleCropR * 100).toInt()}%")
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(text = "下", modifier = Modifier.width(28.dp))
                                        Slider(
                                            value = displaySetting.bubbleCropB,
                                            onValueChange = {
                                                updateDisplaySetting(displaySetting.copy(bubbleCropB = it))
                                            },
                                            valueRange = 0f..0.45f,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(text = "${(displaySetting.bubbleCropB * 100).toInt()}%")
                                    }
                                Text("手画的图边上常留白，先裁掉再切")
                            }
                        }
                    )
                    item(
                        headlineContent = { Text("横向可重复区") },
                        supportingContent = {
                            Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(text = "起", modifier = Modifier.width(28.dp))
                                        Slider(
                                            value = displaySetting.bubbleRepeatX1,
                                            onValueChange = {
                                                updateDisplaySetting(displaySetting.copy(bubbleRepeatX1 = it))
                                            },
                                            valueRange = 0f..1f,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(text = "${(displaySetting.bubbleRepeatX1 * 100).toInt()}%")
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(text = "终", modifier = Modifier.width(28.dp))
                                        Slider(
                                            value = displaySetting.bubbleRepeatX2,
                                            onValueChange = {
                                                updateDisplaySetting(displaySetting.copy(bubbleRepeatX2 = it))
                                            },
                                            valueRange = 0f..1f,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(text = "${(displaySetting.bubbleRepeatX2 * 100).toInt()}%")
                                    }
                                Text("两条线之间那段会被重复铺开；贴到一起就不铺")
                            }
                        }
                    )
                    item(
                        headlineContent = { Text("纵向可重复区") },
                        supportingContent = {
                            Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(text = "起", modifier = Modifier.width(28.dp))
                                        Slider(
                                            value = displaySetting.bubbleRepeatY1,
                                            onValueChange = {
                                                updateDisplaySetting(displaySetting.copy(bubbleRepeatY1 = it))
                                            },
                                            valueRange = 0f..1f,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(text = "${(displaySetting.bubbleRepeatY1 * 100).toInt()}%")
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(text = "终", modifier = Modifier.width(28.dp))
                                        Slider(
                                            value = displaySetting.bubbleRepeatY2,
                                            onValueChange = {
                                                updateDisplaySetting(displaySetting.copy(bubbleRepeatY2 = it))
                                            },
                                            valueRange = 0f..1f,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(text = "${(displaySetting.bubbleRepeatY2 * 100).toInt()}%")
                                    }
                                Text("耳朵那种想整块锁死不动的，把这一区压窄")
                            }
                        }
                    )
                    item(
                        headlineContent = { Text("左右内边距") },
                        supportingContent = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Slider(
                                    value = if (displaySetting.bubblePaddingH < 0f) 8f else displaySetting.bubblePaddingH,
                                    onValueChange = {
                                        updateDisplaySetting(displaySetting.copy(bubblePaddingH = it))
                                    },
                                    valueRange = 0f..40f,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(text = "${(if (displaySetting.bubblePaddingH < 0f) 8f else displaySetting.bubblePaddingH).toInt()}dp")
                            }
                        }
                    )
                    item(
                        headlineContent = { Text("气泡出血(图能画到气泡外)") },
                        supportingContent = {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Slider(
                                        value = displaySetting.bubbleBleed,
                                        onValueChange = {
                                            updateDisplaySetting(displaySetting.copy(bubbleBleed = it))
                                        },
                                        valueRange = 0f..40f,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(text = "${displaySetting.bubbleBleed.toInt()}dp")
                                }
                                Text(
                                    if (displaySetting.bubbleBleed > 0f)
                                        "图往外多画这么多，伸出去的部分不会被裁 —— 耳朵就用这个"
                                    else "0 = 图只画在气泡里"
                                )
                            }
                        }
                    )
                    item(
                        headlineContent = { Text("预览") },
                        supportingContent = {
                            val path = displaySetting.userBubbleImagePath
                                .ifBlank { displaySetting.assistantBubbleImagePath }
                            // ⚠️ remember 必须无条件调用，不能塞进 if 里
                            val bmp = remember(path) {
                                if (path.isNotBlank() && File(path).exists()) {
                                    runCatching {
                                        android.graphics.BitmapFactory.decodeFile(path)?.asImageBitmap()
                                    }.getOrNull()
                                } else null
                            }
                            val previewBleedPx = with(androidx.compose.ui.platform.LocalDensity.current) {
                                displaySetting.bubbleBleed.dp.toPx()
                            }
                            if (bmp != null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        // 预览区高度也按出血放大，伸出来的部分才有地方露
                                        .height(96.dp + displaySetting.bubbleBleed.dp * 2)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.CenterEnd)
                                            .fillMaxWidth(0.72f)
                                            .height(64.dp)
                                    ) {
                                        Canvas(modifier = Modifier.matchParentSize()) {
                                            drawBubbleImage(
                                                bmp = bmp,
                                                cropLeft = displaySetting.bubbleCropL,
                                                cropTop = displaySetting.bubbleCropT,
                                                cropRight = displaySetting.bubbleCropR,
                                                cropBottom = displaySetting.bubbleCropB,
                                                repeatXStart = displaySetting.bubbleRepeatX1,
                                                repeatXEnd = displaySetting.bubbleRepeatX2,
                                                repeatYStart = displaySetting.bubbleRepeatY1,
                                                repeatYEnd = displaySetting.bubbleRepeatY2,
                                                tiled = displaySetting.bubbleTiled,
                                                expandPx = previewBleedPx,
                                            )
                                        }
                                        Text(
                                            text = "示例：今天也在一起",
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                        )
                                    }
                                }
                            } else {
                                Text("先选一张气泡背景图，这里就会出预览")
                            }
                        }
                    )
                    item(
                        headlineContent = { Text("背景图叠加主题色遮罩") },
                        supportingContent = { Text("关=纯图片, 开=图片+主题色遮罩") },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.bubbleImageOverlayEnabled,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(bubbleImageOverlayEnabled = it))
                                }
                            )
                        },
                    )
                }
            }

            // Avatar Frame (QQ-style decoration)
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("头像挂件") },
                ) {
                    // ===== 用户头像挂件 =====
                    item(
                        headlineContent = { Text("用户头像挂件") },
                        supportingContent = {
                            if (displaySetting.userAvatarFramePath.isBlank() || !File(displaySetting.userAvatarFramePath).exists()) {
                                Text("选择一张图片作为头像装饰框")
                            }
                        },
                        trailingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (displaySetting.userAvatarFramePath.isNotBlank()) {
                                    TextButton(onClick = {
                                        File(displaySetting.userAvatarFramePath).delete()
                                        updateDisplaySetting(displaySetting.copy(userAvatarFramePath = ""))
                                    }) { Text("清除") }
                                }
                                TextButton(onClick = { userFramePicker.launch(arrayOf("image/*")) }) { Text("选择") }
                            }
                        },
                    )
                    if (displaySetting.userAvatarFramePath.isNotBlank() && File(displaySetting.userAvatarFramePath).exists()) {
                        run {
                            // 实时预览：圆形头像 + 挂件叠加
                            item(
                                headlineContent = { Text("预览") },
                                supportingContent = {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(160.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        // 参考圆形头像
                                        Box(
                                            modifier = Modifier
                                                .padding(0.dp)
                                                .size(80.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                        )
                                        // 挂件叠加层
                                        Box(
                                            modifier = Modifier
                                                .offset(
                                                    x = displaySetting.userAvatarFrameOffsetX.dp,
                                                    y = displaySetting.userAvatarFrameOffsetY.dp
                                                )
                                                .size((80 * displaySetting.userAvatarFrameScale).dp)
                                        ) {
                                            AsyncImage(
                                                model = File(displaySetting.userAvatarFramePath),
                                                contentDescription = "用户头像挂件",
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Fit,
                                            )
                                        }
                                    }
                                },
                            )
                            // 偏移 X
                            item(
                                headlineContent = { Text("偏移 X") },
                                supportingContent = {
                                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Slider(
                                            value = displaySetting.userAvatarFrameOffsetX,
                                            onValueChange = { updateDisplaySetting(displaySetting.copy(userAvatarFrameOffsetX = it)) },
                                            valueRange = -100f..100f,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text("${displaySetting.userAvatarFrameOffsetX.toInt()}")
                                    }
                                },
                            )
                            // 偏移 Y
                            item(
                                headlineContent = { Text("偏移 Y") },
                                supportingContent = {
                                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Slider(
                                            value = displaySetting.userAvatarFrameOffsetY,
                                            onValueChange = { updateDisplaySetting(displaySetting.copy(userAvatarFrameOffsetY = it)) },
                                            valueRange = -100f..100f,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text("${displaySetting.userAvatarFrameOffsetY.toInt()}")
                                    }
                                },
                            )
                            // 缩放
                            item(
                                headlineContent = { Text("缩放") },
                                supportingContent = {
                                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Slider(
                                            value = displaySetting.userAvatarFrameScale,
                                            onValueChange = { updateDisplaySetting(displaySetting.copy(userAvatarFrameScale = it)) },
                                            valueRange = 0.5f..2f,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text("${(displaySetting.userAvatarFrameScale * 100).toInt()}%")
                                    }
                                },
                            )
                        }
                    }

                    // ===== AI头像挂件 =====
                    item(
                        headlineContent = { Text("AI头像挂件") },
                        supportingContent = {
                            if (displaySetting.aiAvatarFramePath.isBlank() || !File(displaySetting.aiAvatarFramePath).exists()) {
                                Text("选择一张图片作为AI头像装饰框")
                            }
                        },
                        trailingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (displaySetting.aiAvatarFramePath.isNotBlank()) {
                                    TextButton(onClick = {
                                        File(displaySetting.aiAvatarFramePath).delete()
                                        updateDisplaySetting(displaySetting.copy(aiAvatarFramePath = ""))
                                    }) { Text("清除") }
                                }
                                TextButton(onClick = { aiFramePicker.launch(arrayOf("image/*")) }) { Text("选择") }
                            }
                        },
                    )
                    if (displaySetting.aiAvatarFramePath.isNotBlank() && File(displaySetting.aiAvatarFramePath).exists()) {
                        run {
                            // 实时预览：圆形头像 + 挂件叠加
                            item(
                                headlineContent = { Text("预览") },
                                supportingContent = {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(160.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        // 参考圆形头像
                                        Box(
                                            modifier = Modifier
                                                .padding(0.dp)
                                                .size(80.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                        )
                                        // 挂件叠加层
                                        Box(
                                            modifier = Modifier
                                                .offset(
                                                    x = displaySetting.aiAvatarFrameOffsetX.dp,
                                                    y = displaySetting.aiAvatarFrameOffsetY.dp
                                                )
                                                .size((80 * displaySetting.aiAvatarFrameScale).dp)
                                        ) {
                                            AsyncImage(
                                                model = File(displaySetting.aiAvatarFramePath),
                                                contentDescription = "AI头像挂件",
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Fit,
                                            )
                                        }
                                    }
                                },
                            )
                            // 偏移 X
                            item(
                                headlineContent = { Text("偏移 X") },
                                supportingContent = {
                                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Slider(
                                            value = displaySetting.aiAvatarFrameOffsetX,
                                            onValueChange = { updateDisplaySetting(displaySetting.copy(aiAvatarFrameOffsetX = it)) },
                                            valueRange = -100f..100f,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text("${displaySetting.aiAvatarFrameOffsetX.toInt()}")
                                    }
                                },
                            )
                            // 偏移 Y
                            item(
                                headlineContent = { Text("偏移 Y") },
                                supportingContent = {
                                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Slider(
                                            value = displaySetting.aiAvatarFrameOffsetY,
                                            onValueChange = { updateDisplaySetting(displaySetting.copy(aiAvatarFrameOffsetY = it)) },
                                            valueRange = -100f..100f,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text("${displaySetting.aiAvatarFrameOffsetY.toInt()}")
                                    }
                                },
                            )
                            // 缩放
                            item(
                                headlineContent = { Text("缩放") },
                                supportingContent = {
                                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Slider(
                                            value = displaySetting.aiAvatarFrameScale,
                                            onValueChange = { updateDisplaySetting(displaySetting.copy(aiAvatarFrameScale = it)) },
                                            valueRange = 0.5f..2f,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text("${(displaySetting.aiAvatarFrameScale * 100).toInt()}%")
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}