/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Github
import me.rerere.hugeicons.stroke.SecurityCheck
import me.rerere.hugeicons.stroke.SmartPhone01
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.easteregg.EmojiBurstHost
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.plus
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun SettingAboutPage() {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    var updateStatus by remember { mutableStateOf("点击检查更新") }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var dialogText by remember { mutableStateOf("") }
    val emojiOptions = remember {
        listOf(
            "🎉", "✨", "🌟", "💫", "🎊", "🥳", "🎈", "🎆", "🎇", "🧨",
            "🌈", "🧧", "🎁", "🍬", "🍭", "🍉", "🍓", "🍒", "🍍", "🥭",
            "🐱", "🐶", "🦊", "🐼", "🦁", "🐯", "🐵", "🦄",
            "❤️", "🧡", "💛", "💚", "💙", "💜",
            "🇨🇳", "🌏", "🌍", "🌎",
            "🤗", "🤩", "😆", "😺", "😸", "🤡",
            "💡", "🔥", "💥", "🚀", "⭐", "🌙"
        )
    }
    var logoCenterPx by remember { mutableStateOf(Offset.Zero) }

    fun checkForUpdate() {
        updateStatus = "正在检查…"
        scope.launch {
            val (status, detail) = withContext(Dispatchers.IO) {
                runCatching {
                    val settingsStore = org.koin.core.context.GlobalContext.get().get<SettingsStore>()
                    val supabase = settingsStore.settingsFlowRaw.first().systemToolsSetting
                    if (!supabase.supabaseEnabled || supabase.supabaseUrl.isBlank() || supabase.supabaseApiKey.isBlank()) {
                        return@runCatching "未配置 Supabase" to "请在「系统工具」中启用并填写 Supabase 配置后再检查。"
                    }
                    val baseUrl = supabase.supabaseUrl.trimEnd('/')
                    val endpoint = URL("$baseUrl/rest/v1/app_versions?select=version_name,version_code,git_commit,notes,created_at&order=created_at.desc&limit=1")
                    val conn = (endpoint.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("apikey", supabase.supabaseApiKey)
                        setRequestProperty("Authorization", "Bearer ${supabase.supabaseApiKey}")
                        setRequestProperty("Accept", "application/json")
                        connectTimeout = 10000
                        readTimeout = 10000
                    }
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        val err = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                        return@runCatching "检查失败" to "HTTP $code: $err"
                    }
                    val body = conn.inputStream.bufferedReader().readText()
                    val array = JSONArray(body)
                    if (array.length() == 0) {
                        return@runCatching "云端暂无版本记录" to "还没有发布记录。每次构建完成后在 Supabase 的 app_versions 表登记一次即可。"
                    }
                    val obj = array.getJSONObject(0)
                    val latestName = obj.optString("version_name", "?")
                    val latestCode = obj.optLong("version_code", 0L)
                    val currentName = BuildConfig.VERSION_NAME
                    val currentCode = BuildConfig.VERSION_CODE.toLongOrNull() ?: 0L
                    if (latestCode > currentCode) {
                        "发现新版本：$latestName" to "当前版本：$currentName\n最新版本：$latestName\n\n去 GitHub Actions 下载最新 APK 覆盖安装即可（数据保留）。"
                    } else {
                        "已是最新版本" to "当前已是最新版本：$currentName"
                    }
                }.getOrElse { e ->
                    "检查失败" to (e.message ?: "未知错误")
                }
            }
            updateStatus = status
            dialogText = detail
            showUpdateDialog = true
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.about_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        EmojiBurstHost(
            modifier = Modifier.fillMaxSize(),
            emojiOptions = emojiOptions,
            burstCount = 12
        ) { onBurst ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding + PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AsyncImage(
                            model = R.mipmap.ic_launcher,
                            contentDescription = "Logo",
                            modifier = Modifier
                                .clip(CircleShape)
                                .size(150.dp)
                                .onGloballyPositioned { coordinates ->
                                    val position = coordinates.positionInParent()
                                    val size = coordinates.size
                                    logoCenterPx = Offset(
                                        position.x + size.width / 2f,
                                        position.y + size.height / 2f
                                    )
                                }
                                .clickable {
                                    onBurst(logoCenterPx)
                                }
                        )

                        Text(
                            text = "橘瓣",
                            style = MaterialTheme.typography.displaySmall,
                        )
                    }
                }

                item {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        item(
                            modifier = Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = { navController.navigate(Screen.Debug) },
                                interactionSource = remember { MutableInteractionSource() },
                                indication = LocalIndication.current,
                            ),
                            leadingContent = { Icon(HugeIcons.Code, null) },
                            supportingContent = {
                                Text("${BuildConfig.VERSION_NAME} / ${BuildConfig.VERSION_CODE} / ${BuildConfig.GIT_COMMIT} / ${BuildConfig.BUILD_TIME}")
                            },
                            headlineContent = { Text(stringResource(R.string.about_page_version)) },
                        )
                        item(
                            leadingContent = { Icon(HugeIcons.SmartPhone01, null) },
                            supportingContent = {
                                Text("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE} / SDK ${android.os.Build.VERSION.SDK_INT}")
                            },
                            headlineContent = { Text(stringResource(R.string.about_page_system)) },
                        )
                        item(
                            onClick = { checkForUpdate() },
                            leadingContent = { Icon(HugeIcons.Earth, null) },
                            supportingContent = { Text(updateStatus) },
                            headlineContent = { Text("检查更新") },
                        )
                    }
                }

                item {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        item(
                            onClick = { context.openUrl("https://github.com/sue1231513/orangechat") },
                            leadingContent = { Icon(HugeIcons.Earth, null) },
                            supportingContent = {
                                Text("https://github.com/sue1231513/orangechat")
                            },
                            headlineContent = { Text(stringResource(R.string.about_page_website)) },
                        )
                        item(
                            onClick = { context.openUrl("https://github.com/sue1231513/orangechat") },
                            leadingContent = { Icon(HugeIcons.Github, null) },
                            supportingContent = {
                                Text("https://github.com/sue1231513/orangechat")
                            },
                            headlineContent = { Text(stringResource(R.string.about_page_github)) },
                        )
                        item(
                            onClick = { context.openUrl("https://github.com/sue1231513/orangechat/blob/master/LICENSE") },
                            leadingContent = { Icon(HugeIcons.File02, null) },
                            supportingContent = {
                                Text("https://github.com/sue1231513/orangechat/blob/master/LICENSE")
                            },
                            headlineContent = { Text(stringResource(R.string.about_page_license)) },
                        )
                    }
                }

                item {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        item(
                            onClick = { context.openUrl("https://github.com/rikkahub/rikkahub") },
                            leadingContent = { Icon(HugeIcons.Github, null) },
                            supportingContent = {
                                Text(stringResource(R.string.about_page_upstream_desc))
                            },
                            headlineContent = { Text(stringResource(R.string.about_page_upstream_name)) },
                        )
                    }
                }

                item {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        item(
                            onClick = {
                                navController.navigate(
                                    Screen.Legal(
                                        titleRes = R.string.legal_user_agreement_title,
                                        contentRes = R.string.legal_user_agreement_text
                                    )
                                )
                            },
                            leadingContent = { Icon(HugeIcons.File02, null) },
                            supportingContent = { Text(stringResource(R.string.legal_user_agreement_desc)) },
                            headlineContent = { Text(stringResource(R.string.legal_user_agreement_title)) },
                        )
                        item(
                            onClick = {
                                navController.navigate(
                                    Screen.Legal(
                                        titleRes = R.string.legal_privacy_policy_title,
                                        contentRes = R.string.legal_privacy_policy_text
                                    )
                                )
                            },
                            leadingContent = { Icon(HugeIcons.File02, null) },
                            supportingContent = { Text(stringResource(R.string.legal_privacy_policy_desc)) },
                            headlineContent = { Text(stringResource(R.string.legal_privacy_policy_title)) },
                        )
                        item(
                            onClick = {
                                navController.navigate(
                                    Screen.Legal(
                                        titleRes = R.string.legal_disclaimer_title,
                                        contentRes = R.string.legal_disclaimer_text
                                    )
                                )
                            },
                            leadingContent = { Icon(HugeIcons.SecurityCheck, null) },
                            supportingContent = { Text(stringResource(R.string.legal_disclaimer_desc)) },
                            headlineContent = { Text(stringResource(R.string.legal_disclaimer_title)) },
                        )
                        item(
                            onClick = {
                                navController.navigate(
                                    Screen.Legal(
                                        titleRes = R.string.legal_plugin_security_title,
                                        contentRes = R.string.legal_plugin_security_text
                                    )
                                )
                            },
                            leadingContent = { Icon(HugeIcons.SecurityCheck, null) },
                            supportingContent = { Text(stringResource(R.string.legal_plugin_security_desc)) },
                            headlineContent = { Text(stringResource(R.string.legal_plugin_security_title)) },
                        )
                    }
                }

                item {
                    Text(
                        text = stringResource(R.string.about_page_derivation_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                    )
                }
            }
        }
    }

    if (showUpdateDialog) {
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            title = { Text("版本检查") },
            text = { Text(dialogText) },
            confirmButton = {
                TextButton(onClick = { showUpdateDialog = false }) { Text("好的") }
            },
        )
    }
}
