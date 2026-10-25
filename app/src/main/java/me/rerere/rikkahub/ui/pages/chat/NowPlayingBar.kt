package me.rerere.rikkahub.ui.pages.chat

import android.os.Handler
import android.os.Looper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import me.rerere.rikkahub.ui.pages.miniapp.MiniAppWebViewHost
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 「现在在播」的一条。
 *
 * 为什么要有它（2026-10-25 宝拍板做）：
 * 音乐是 eryu 那个网页放的，audio 对象在 WebView 里——不挂 DOM、不进系统媒体会话。
 * 所以通知栏没有播放器、锁屏没控制、系统压根不知道有音乐在放。
 * 宝原话：这配置很适合演鬼片，手机莫名其妙自己开始响。
 *
 * 做法：定时问 WebView 一句「现在在播什么」（页面里 state / audio 都是顶层声明的，
 * 外面灌进去的 JS 读得到），拿到就画这一条。没在播就不显示。
 */
data class NowPlayingSnap(
    val name: String,
    val artist: String,
    val paused: Boolean,
    val position: Double,
    val duration: Double,
)

internal object NowPlaying {
    /** 当前快照；null = 没在播 / 页面没开着。 */
    var snap by mutableStateOf<NowPlayingSnap?>(null)
        private set

    private val main = Handler(Looper.getMainLooper())

    /** 读一次页面状态。页面没开着就清空。 */
    fun refresh() {
        val wv = MiniAppWebViewHost.webView ?: run { snap = null; return }
        main.post {
            try {
                wv.evaluateJavascript(READ_JS) { raw ->
                    snap = parse(raw)
                }
            } catch (_: Throwable) {
                snap = null
            }
        }
    }

    /** 播放 / 暂停。 */
    fun toggle() {
        call("toggle") { refresh() }
    }

    /** 下一首。 */
    fun next() {
        call("next") { refresh() }
    }

    private fun call(cmd: String, then: () -> Unit) {
        val wv = MiniAppWebViewHost.webView ?: return
        // ⚠️ 别把 if 写进字符串模板里：模板内再嵌双引号会让词法分析器分不清结尾
        //（2026-10-25 构建 #614 报的就是这个：NowPlayingBar.kt:96 Expecting '}'）
        val fn = if (cmd == "toggle") "togglePlay" else "playNext"
        main.post {
            try {
                wv.evaluateJavascript("try{ ($fn()) }catch(e){}") {
                    then()
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun parse(raw: String?): NowPlayingSnap? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return try {
            // evaluateJavascript 把字符串结果再 JSON 编码一层，先脱壳
            val inner = if (raw.startsWith("\"")) {
                (JSONTokener(raw).nextValue() as? String) ?: return null
            } else raw
            val o = JSONObject(inner)
            val song = o.optJSONObject("song") ?: return null
            val name = song.optString("name").ifBlank { return null }
            NowPlayingSnap(
                name = name,
                artist = song.optString("artist"),
                paused = o.optBoolean("paused", true),
                position = o.optDouble("t", 0.0),
                duration = o.optDouble("dur", 0.0),
            )
        } catch (_: Throwable) {
            null
        }
    }

    private val READ_JS = """
        (function(){
          try{
            if(typeof audio === 'undefined' || typeof state === 'undefined') return "null";
            var s = state.song;
            if(!s) return "null";
            return JSON.stringify({
              song: { name: s.name || '', artist: s.artist || '', songId: s.songId || '' },
              paused: !!audio.paused,
              t: Math.round((audio.currentTime || 0) * 10) / 10,
              dur: Math.round((audio.duration || 0) * 10) / 10
            });
          }catch(e){ return "null"; }
        })()
    """.trimIndent()
}

/**
 * 播放条本体。没在播就整个不出现（不占位）。
 */
@Composable
fun NowPlayingBar(modifier: Modifier = Modifier) {
    // 每 1.5 秒问一次页面；页面没开时这里会自己清空。
    LaunchedEffect(Unit) {
        while (true) {
            NowPlaying.refresh()
            delay(1500)
        }
    }

    val snap = NowPlaying.snap

    // ⚠️ 必须包在 Column 里：这个 Compose 版本里能调的只有 ColumnScope/RowScope
    // 那两个 AnimatedVisibility，顶层签名解析不到（2026-10-21 为这个跑过四次构建）。
    Column(modifier = modifier) {
    AnimatedVisibility(
        visible = snap != null,
        enter = expandVertically(),
        exit = shrinkVertically(),
    ) {
        val s = snap ?: return@AnimatedVisibility
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(12.dp)),
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = s.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = buildString {
                                append(s.artist.ifBlank { "未知" })
                                if (s.duration > 0) {
                                    append("  ")
                                    append(fmt(s.position))
                                    append(" / ")
                                    append(fmt(s.duration))
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (s.paused) "▶" else "❚❚",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { NowPlaying.toggle() }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                    Text(
                        text = "▶|",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { NowPlaying.next() }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                val frac = if (s.duration > 0) {
                    (s.position / s.duration).toFloat().coerceIn(0f, 1f)
                } else 0f
                LinearProgressIndicator(
                    progress = { frac },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                )
            }
        }
    }
    }
}

private fun fmt(sec: Double): String {
    if (sec <= 0 || sec.isNaN()) return "0:00"
    val total = sec.toInt()
    return "%d:%02d".format(total / 60, total % 60)
}
