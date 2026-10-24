package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import me.rerere.rikkahub.data.sticker.StickerStore
import java.io.File

/**
 * 【表情包面板 · 2026-10-24】
 *
 * 长按输入框左边那个「＋」弹出来。点一张就当成图片附件塞进输入框。
 * 长按一张 → 问一句要不要删掉（存歪了得有地方收拾）。
 * 图从 filesDir/stickers 读 —— 就是拖拽 / 分享存进来的那个目录。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun StickerSheet(
    onDismiss: () -> Unit,
    onPick: (File) -> Unit,
) {
    val context = LocalContext.current
    var files by remember { mutableStateOf(StickerStore.list(context)) }
    var pendingDelete by remember { mutableStateOf<File?>(null) }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删掉这张？") },
            text = {
                // 有描述就给描述（人更认得出是哪张），没有就退回文件名
                Text(StickerStore.readNote(target) ?: target.name)
            },
            confirmButton = {
                TextButton(onClick = {
                    StickerStore.delete(target)
                    files = StickerStore.list(context)
                    pendingDelete = null
                }) { Text("删掉") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("算了") }
            },
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        if (files.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "还没有表情包\n分屏把图拖进聊天页，松手就存进来了",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(380.dp),
            ) {
                items(files, key = { it.absolutePath }) { file ->
                    AsyncImage(
                        model = file,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .combinedClickable(
                                onClick = { onPick(file) },
                                onLongClick = { pendingDelete = file },
                            ),
                    )
                }
            }
        }
    }
}
