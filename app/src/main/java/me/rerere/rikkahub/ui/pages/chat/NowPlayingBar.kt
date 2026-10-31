package me.rerere.rikkahub.ui.pages.chat

import android.os.Handler
import android.os.Looper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import me.rerere.rikkahub.data.ai.AppLogBuffer
import me.rerere.rikkahub.ui.components.message.AudioPlaybackHub
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

    /**
     * 【波形条认 hub · 2026-10-31】这一条现在是从哪儿来的。
     * true = App 内的音频中枢（音乐卡走的那条），false = eryu 网页里的 audio。
     * 开关要做的事不一样，所以得记住。
     */
    var fromHub by mutableStateOf(false)
        private set

    /** 上次打日志时的样子 —— 只在变化时打，不然 1.5 秒一条会刷爆日志环 */
    private var lastSig = ""

    /**
     * 读一次状态。
     *
     * 【顺序 · 2026-10-31 第二次修】先看 hub，再问网页。
     * 原来反着写：先问网页，问不到才回头。实测小程序关掉之后 WebView 对象还在，
     * 而那句 JS 一直不回调 —— 于是永远卡在第一步，卡片在聊天页播着，这条却一直不出现。
     * 两个源本来就是互斥的（网页播的时候 hub 不会播），先问 hub 最省事也最不容易卡。
     */
    fun refresh() {
        val hubSnap = fromHubNow()
        logState()
        if (hubSnap != null) {
            snap = hubSnap
            return
        }
        val wv = MiniAppWebViewHost.webView ?: run { snap = null; return }
        main.post {
            try {
                wv.evaluateJavascript(READ_JS) { raw ->
                    val fromPage = parse(raw)
                    fromHub = false
                    snap = fromPage
                }
            } catch (_: Throwable) {
                snap = null
            }
        }
    }

    /** 【排查日志 · 2026-10-31】只在状态变化时打一行，别刷屏。 */
    private fun logState() {
        val u = AudioPlaybackHub.currentUrl
        val sig = buildString {
            append("hub=").append(if (u.isNullOrBlank()) "null" else "url(${u.length})")
            append(" playing=").append(AudioPlaybackHub.isPlaying)
            append(" dur=").append(AudioPlaybackHub.durationMs)
            append(" pos=").append(AudioPlaybackHub.positionMs())
            append(" title=").append(AudioPlaybackHub.currentTitle)
            append(" fromHub=").append(fromHub)
            append(" snap=").append(snap?.name)
            append(" wv=").append(MiniAppWebViewHost.webView != null)
        }
        if (sig != lastSig) {
            lastSig = sig
            AppLogBuffer.log("NowPlaying", sig)
        }
    }

    /**
     * 网页没在播时看这边：音乐卡片点播走的是 App 内的播放器，
     * 以前它压根不出现在这条上（宝：卡片能放了，上面那条不冒头）。
     */
    private fun fromHubNow(): NowPlayingSnap? {
        val url = AudioPlaybackHub.currentUrl
        if (url.isNullOrBlank()) {
            fromHub = false
            return null
        }
        val dur = AudioPlaybackHub.durationMs
        val pos = AudioPlaybackHub.positionMs()
        // 播到结尾就收起来 —— 跟网页那边同一条规矩
        if (dur > 0 && pos >= dur - 400) {
            fromHub = false
            return null
        }
        fromHub = true
        return NowPlayingSnap(
            name = AudioPlaybackHub.currentTitle?.takeIf { it.isNotBlank() } ?: "音乐",
            artist = "",
            paused = !AudioPlaybackHub.isPlaying,
            position = pos / 1000.0,
            duration = dur / 1000.0,
        )
    }

    /** 播放 / 暂停。谁在播就管谁。 */
    fun toggle() {
        if (fromHub) {
            AudioPlaybackHub.toggleCurrent()
            return
        }
        call("toggle") { refresh() }
    }

    /** 【点进度条跳过去 · 2026-10-31】只有 hub 能跳（网页那边没做这功能）。 */
    fun seek(sec: Double) {
        if (fromHub) AudioPlaybackHub.seekTo((sec * 1000).toInt())
    }

    /** 下一首。hub 只有一首，没有下一首。 */
    fun next() {
        if (fromHub) return
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
            // 播到结尾就收起来：播放条的意义是「现在在播什么」，播完了留着只会让人以为还在放。
            // 手动暂停不算（暂停时 currentTime 还没到末尾，ended 是 false）。
            if (o.optBoolean("ended", false)) return null
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
              ended: !!audio.ended || (!!audio.paused && audio.duration > 0 && audio.currentTime >= audio.duration - 0.4),
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
/** 【体检用】只记第一次，避免每帧刷日志 */
private val nowPlayingBarComposeTick = java.util.concurrent.atomic.AtomicInteger(0)

@Composable
fun NowPlayingBar(modifier: Modifier = Modifier) {
    // 【体检 · 2026-10-31】只打第一次，不刷屏。用来分清三种情况：
    //   有 COMPOSE 有 LOOP  = 都正常，问题在 refresh 内部
    //   有 COMPOSE 没 LOOP  = LaunchedEffect 没启动
    //   两个都没有           = 这个 Composable 压根没进组合
    if (nowPlayingBarComposeTick.incrementAndGet() == 1) {
        AppLogBuffer.log("NowPlaying", "COMPOSE entered")
    }
    // 每 1.5 秒问一次页面；页面没开时这里会自己清空。
    LaunchedEffect(Unit) {
        AppLogBuffer.log("NowPlaying", "LOOP start")
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
                // 【点进度条跳 · 2026-10-31】线本身 2dp 太细点不着，
                // 外面套 14dp 的可点区域，按点到的比例换算成秒交给中枢。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .pointerInput(s.duration, NowPlaying.fromHub) {
                            detectTapGestures { off ->
                                if (s.duration > 0 && NowPlaying.fromHub) {
                                    val ratio = (off.x / size.width.toFloat()).coerceIn(0f, 1f)
                                    NowPlaying.seek(ratio * s.duration)
                                }
                            }
                        },
                    contentAlignment = Alignment.CenterStart,
                ) {
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
}

private fun fmt(sec: Double): String {
    if (sec <= 0 || sec.isNaN()) return "0:00"
    val total = sec.toInt()
    return "%d:%02d".format(total / 60, total % 60)
}
