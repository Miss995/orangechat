package me.rerere.rikkahub.ui.pages.pawprint

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
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

private fun appendPawprint(context: android.content.Context, text: String) {
    val f = pawprintFile(context)
    f.appendText("${System.currentTimeMillis()}|$text\n")
}

private val fmt = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)

private fun formatTime(t: Long): String = fmt.format(Date(t))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PawprintPage() {
    val navController = LocalNavController.current
    val context = LocalContext.current

    var draft by remember { mutableStateOf("") }
    val items = remember { mutableStateListOf<Pawprint>() }

    LaunchedEffect(Unit) {
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
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                placeholder = { Text("随便写点什么。") },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    enabled = draft.isNotBlank(),
                    onClick = {
                        val text = draft.trim()
                        if (text.isEmpty()) return@TextButton
                        appendPawprint(context, text)
                        items.add(0, Pawprint(System.currentTimeMillis(), text))
                        draft = ""
                    },
                ) {
                    Text("留下")
                }
            }

            Spacer(Modifier.height(8.dp))

            if (items.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "这里还什么都没有。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(items) { p ->
                        Column {
                            Text(
                                text = formatTime(p.time),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(2.dp))
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
