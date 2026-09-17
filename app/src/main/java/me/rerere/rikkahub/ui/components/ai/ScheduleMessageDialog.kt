/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import me.rerere.hugeicons.HugeIcons
import me.rerere.rikkahub.data.service.ScheduledMessage
import me.rerere.rikkahub.data.service.ScheduledMessageScheduler
import me.rerere.rikkahub.data.service.ScheduledMessageStore
import java.util.UUID
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * 定时发送对话框（2026-09-17 宝提）
 *
 * 写一条消息 + 选个时间 → 到点自动发出去，跟宝亲手发的一样。
 * 下面是已经排好的清单，可以点 × 取消。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleMessageDialog(
    conversationId: String,
    initialText: String,
    onDismiss: () -> Unit,
    onScheduled: () -> Unit,
) {
    val context = LocalContext.current
    val zone = remember { TimeZone.currentSystemDefault() }

    var text by remember { mutableStateOf(initialText) }
    var pending by remember { mutableStateOf(ScheduledMessageStore.getAll(context)) }

    // 默认排在「一小时后」
    val defaultTrigger = remember {
        Clock.System.now().plus(kotlin.time.Duration.Companion.hours(1))
            .toLocalDateTime(zone)
    }
    var triggerAt by remember { mutableStateOf(defaultTrigger) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    fun refresh() {
        pending = ScheduledMessageStore.getAll(context)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("定时发送") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("内容") },
                    minLines = 2,
                    maxLines = 5,
                )

                // 时间：日期 + 时刻
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { showDatePicker = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            "${triggerAt.monthNumber}月${triggerAt.dayOfMonth}日",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    OutlinedButton(
                        onClick = { showTimePicker = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            "%02d:%02d".format(triggerAt.hour, triggerAt.minute),
                            maxLines = 1,
                        )
                    }
                }

                // 快捷选项
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val now = Clock.System.now()
                    listOf(
                        "+5分钟" to kotlin.time.Duration.Companion.minutes(5),
                        "+1小时" to kotlin.time.Duration.Companion.hours(1),
                        "+3小时" to kotlin.time.Duration.Companion.hours(3),
                    ).forEach { (label, delta) ->
                        TextButton(onClick = {
                            triggerAt = now.plus(delta).toLocalDateTime(zone)
                        }) { Text(label) }
                    }
                }

                if (pending.isNotEmpty()) {
                    HorizontalDivider()
                    Text("已排好的（${pending.size}）", style = MaterialTheme.typography.labelLarge)
                    pending.forEach { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                val t = Instant.fromEpochMilliseconds(item.triggerAt)
                                    .toLocalDateTime(zone)
                                Text(
                                    "%02d/%02d %02d:%02d".format(
                                        t.monthNumber, t.dayOfMonth, t.hour, t.minute
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Text(
                                    item.content,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = {
                                ScheduledMessageScheduler.cancel(context, item.id)
                                refresh()
                            }) {
                                Icon(HugeIcons.Cancel01, contentDescription = "取消这条")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = {
                    val trigger = triggerAt.toInstant(zone).toEpochMilliseconds()
                    if (trigger <= Clock.System.now().toEpochMilliseconds()) {
                        // 时间已经过去了，就当现在发（不然闹钟会立刻炸，行为一样但更直观）
                    }
                    ScheduledMessageScheduler.schedule(
                        context = context,
                        message = ScheduledMessage(
                            id = UUID.randomUUID().toString(),
                            conversationId = conversationId,
                            content = text.trim(),
                            triggerAt = trigger,
                        )
                    )
                    onScheduled()
                    onDismiss()
                }
            ) { Text("排上") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = triggerAt.toInstant(zone).toEpochMilliseconds()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        // DatePicker 返回的是「UTC 当天 0 点」，按 UTC 取日期才不会被时区带偏一天
                        val picked = Instant.fromEpochMilliseconds(millis)
                            .toLocalDateTime(TimeZone.UTC)
                        triggerAt = LocalDateTime(
                            picked.year, picked.monthNumber, picked.dayOfMonth,
                            triggerAt.hour, triggerAt.minute,
                        )
                    }
                    showDatePicker = false
                }) { Text("好") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("算了") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(
            initialHour = triggerAt.hour,
            initialMinute = triggerAt.minute,
            is24Hour = true,
        )
        Dialog(onDismissRequest = { showTimePicker = false }) {
            androidx.compose.material3.Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    TimePicker(state = timePickerState)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { showTimePicker = false }) { Text("算了") }
                        TextButton(onClick = {
                            triggerAt = LocalDateTime(
                                triggerAt.year, triggerAt.monthNumber, triggerAt.dayOfMonth,
                                timePickerState.hour, timePickerState.minute,
                            )
                            showTimePicker = false
                        }) { Text("好") }
                    }
                }
            }
        }
    }
}
