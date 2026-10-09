/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.ui.components.ai.AssistantPicker
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.readBooleanPreference
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * 【重排·第十三刀 2026-10-09】会话面板 —— 从侧边栏搬出来的居中卡片
 *
 * 最上面 = 选择助手；下面 = 当前助手的会话列表。
 * （文件夹条 + 新建按钮留到第二版补。）
 *
 * 入口：主面板「切换聊天」格。
 */
@Composable
fun ChatSessionPanel(
    onDismiss: () -> Unit,
    navController: Navigator,
    vm: ChatVM,
    settings: Settings,
    current: Conversation,
) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val scope = rememberCoroutineScope()
    val repo = koinInject<ConversationRepository>()
    val drawerVm: ChatDrawerVM = koinViewModel(viewModelStoreOwner = activity)

    val conversations = drawerVm.conversations.collectAsLazyPagingItems()
    val conversationListState = rememberLazyListState()
    val conversationJobs by vm.conversationJobs.collectAsStateWithLifecycle(
        initialValue = emptyMap(),
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .heightIn(max = 560.dp)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(24.dp),
                ),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(modifier = Modifier.padding(vertical = 10.dp)) {
                // ① 选择助手
                AssistantPicker(
                    settings = settings,
                    onUpdateSettings = { newSettings ->
                        vm.updateSettings(newSettings)
                        scope.launch {
                            val newId =
                                if (context.readBooleanPreference("create_new_conversation_on_start", true)) {
                                    Uuid.random()
                                } else {
                                    repo.getConversationsOfAssistant(newSettings.assistantId)
                                        .first()
                                        .firstOrNull()
                                        ?.id ?: Uuid.random()
                                }
                            navigateToChatPage(navigator = navController, chatId = newId)
                            onDismiss()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    onClickSetting = {
                        navController.navigate(Screen.AssistantDetail(id = settings.assistantId.toString()))
                    },
                )

                // ② 当前助手的会话列表
                ConversationList(
                    current = current,
                    conversations = conversations,
                    conversationJobs = conversationJobs.keys,
                    listState = conversationListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    onClick = {
                        navigateToChatPage(navController, it.id)
                        onDismiss()
                    },
                    onRegenerateTitle = { vm.generateTitle(it, true) },
                    onDelete = {
                        vm.deleteConversation(it)
                        conversations.refresh()
                        if (it.id == current.id) {
                            navigateToChatPage(navController)
                        }
                    },
                    onPin = { vm.updatePinnedStatus(it) },
                    onMoveToAssistant = { },
                    onMoveToFolder = { },
                    // 【重排·第十三刀 2026-10-09】面板里平铺：去掉日期标题那条底色（斑马线）
                    flatStyle = true,
                )
            }
        }
    }
}
