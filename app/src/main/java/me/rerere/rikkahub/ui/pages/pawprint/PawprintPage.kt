package me.rerere.rikkahub.ui.pages.pawprint

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.ui.context.LocalNavController
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val FILE_NAME = "pawprint.txt"

private data class Pawprint(
    val time: Long,
    val text: String,
)

private fun pawprintFile(context: android.content.Context): File =
    File(context.filesDir, FILE_NAME)

private fun loadPawprints(context: android.content.Context): List<Pawprint> {
    val f = pawprintFile(context)
    if (!f.exists()) return emptyList()
    return f.readLines()
        .mapNotNull { line ->
            val idx = line.indexOf('|')
            if (idx <= 0) null
            else {
                val t = line.substring(0, idx).toLongOrNull() ?: return@mapNotNull null
                Pawprint(t, line.substring(idx + 1))
            }
        }
        .sortedByDescending { it.time }
}

private val fmt = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)

private fun formatTime(t: Long): String = fmt.format(Date(t))

/**
 * 爪印 · 2026-11-01
 *
 * 猫自己待着的地方。这里只有"看"——写是猫用 pawprint 工具写的，
 * 不给宝留输入框：那地方是猫的角落，不是让宝替猫记事的地方。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PawprintPage() {
    val navController = LocalNavController.current
    val context = LocalContext.current

    val items = remember { mutableStateListOf<Pawprint>() }
    var refreshTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshTick) {
        items.clear()
        items.addAll(loadPawprints(context))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("爪印") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(HugeIcons.Cancel01, contentDescription = "关闭")
                    }
                },
                actions = {
                    TextButton(onClick = { refreshTick++ }) {
                        Text("刷新")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            if (items.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "还没有爪印。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(items) { p ->
                        Column {
                            Text(
                                text = formatTime(p.time),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = p.text,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}
