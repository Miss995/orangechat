/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.MediaStore
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.service.RikkaNotificationListenerService
import me.rerere.rikkahub.plugin.webview.MusicPlayerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private fun getAppName(context: Context, packageName: String): String {
    return try {
        val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(appInfo).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        packageName
    }
}

private fun isNotificationListenerEnabled(context: Context): Boolean {
    val cn = android.content.ComponentName(context, RikkaNotificationListenerService::class.java)
    return try {
        android.provider.Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        )?.contains(cn.flattenToString()) == true
    } catch (_: Exception) {
        false
    }
}

private fun getActiveMediaControllers(context: Context): List<android.media.session.MediaController>? {
    val mediaSessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    val cn = android.content.ComponentName(context, RikkaNotificationListenerService::class.java)
    return try {
        mediaSessionManager.getActiveSessions(cn)
    } catch (e: Exception) {
        null
    }
}

// ==================== eryu（咱家自建的网易云播放器）====================
// 为什么不直接播网易云的地址：那些地址要么要 cookie、要么带签名会过期。
// eryu 跑在咱家服务器上，负责"按 id 把音频缓存下来"，再从 /music/file/ 发出来，
// 那条路免 token、也不怕网易云那边改规则。
private const val ERYU_BASE = "http://47.101.190.165:9090"
private const val ERYU_TOKEN = "123456"
private const val ERYU_TIMEOUT_MS = 12000

/** 简单 GET，失败返回 null，绝不把异常抛出去 */
private fun eryuGet(path: String): String? {
    var conn: HttpURLConnection? = null
    return try {
        conn = (URL(ERYU_BASE + path).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = ERYU_TIMEOUT_MS
            readTimeout = ERYU_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", "okhttp/4.12.0")
            setRequestProperty("X-Auth-Token", ERYU_TOKEN)
        }
        if (conn.responseCode !in 200..299) null
        else conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: Exception) {
        null
    } finally {
        try { conn?.disconnect() } catch (_: Exception) {}
    }
}

fun createMusicTool(context: Context): Tool = Tool(
    name = "control_music",
    description = "Control music playback on the device. " +
        "Can get currently playing media info, and control playback (play, pause, next, previous, seek). " +
        "Can also search and play music by sending an intent to installed music apps. " +
        "Can also play an audio URL directly inside the app. " +
        "Use action 'stop_url' to stop the app's own player. " +
        "Requires notification listener permission to be enabled.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("action") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Action to perform: 'get_now_playing' (get current playing info), 'play', 'pause', 'next', 'previous', 'seek' (seek to position), 'play_search' (search and play music)"))
                    put("enum", buildJsonArray {
                        add(JsonPrimitive("get_now_playing"))
                        add(JsonPrimitive("play"))
                        add(JsonPrimitive("pause"))
                        add(JsonPrimitive("next"))
                        add(JsonPrimitive("previous"))
                        add(JsonPrimitive("seek"))
                        add(JsonPrimitive("play_search"))
                        add(JsonPrimitive("play_url"))
                        add(JsonPrimitive("stop_url"))
                        add(JsonPrimitive("play_song"))
                    })
                }
                putJsonObject("position_ms") {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("Position in milliseconds to seek to. Required for 'seek' action."))
                }
                putJsonObject("query") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Search query for 'play_search' / 'play_song' action. e.g. song name or artist"))
                }
                putJsonObject("artist") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Artist name for 'play_search' action (optional)"))
                }
                putJsonObject("title") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Song title for 'play_search' action (optional)"))
                }
                putJsonObject("url") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Audio URL for 'play_url' action. Must start with http:// or https://."))
                }
            },
            required = listOf("action")
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val action = params["action"]?.jsonPrimitive?.contentOrNull ?: ""

        try {
            // Check notification listener permission
            if (!isNotificationListenerEnabled(context)) {
                return@Tool listOf(UIMessagePart.Text(
                    buildJsonObject {
                        put("success", false)
                        put("error", JsonPrimitive("Notification listener permission is not enabled. Please enable it in system settings for this app."))
                    }.toString()
                ))
            }

            val controllers = getActiveMediaControllers(context)

            when (action) {
                "get_now_playing" -> {
                    if (controllers.isNullOrEmpty()) {
                        return@Tool listOf(UIMessagePart.Text(
                            buildJsonObject {
                                put("success", true)
                                put("message", JsonPrimitive("No active media sessions found. No music is currently playing."))
                            }.toString()
                        ))
                    }

                    val activeController = controllers.firstOrNull {
                        it.playbackState?.state == PlaybackState.STATE_PLAYING ||
                        it.playbackState?.state == PlaybackState.STATE_PAUSED
                    } ?: controllers.firstOrNull()

                    if (activeController == null) {
                        return@Tool listOf(UIMessagePart.Text(
                            buildJsonObject {
                                put("success", true)
                                put("message", JsonPrimitive("No active media sessions found."))
                            }.toString()
                        ))
                    }

                    val metadata = activeController.metadata
                    val playbackState = activeController.playbackState
                    val isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING
                    val positionMs = playbackState?.position?.toLong() ?: -1L

                    val result = buildJsonObject {
                        put("success", true)
                        put("action", JsonPrimitive("get_now_playing"))
                        put("title", JsonPrimitive(metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown"))
                        put("artist", JsonPrimitive(metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Unknown"))
                        put("album", JsonPrimitive(metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""))
                        put("is_playing", isPlaying)
                        put("position_ms", positionMs)
                        put("duration_ms", metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: -1L)
                        put("app_name", JsonPrimitive(getAppName(context, activeController.packageName)))
                        put("package_name", JsonPrimitive(activeController.packageName))
                    }
                    listOf(UIMessagePart.Text(result.toString()))
                }

                "play", "pause", "next", "previous", "seek" -> {
                    if (controllers.isNullOrEmpty()) {
                        return@Tool listOf(UIMessagePart.Text(
                            buildJsonObject {
                                put("success", false)
                                put("error", JsonPrimitive("No active media sessions found. Please play music first."))
                            }.toString()
                        ))
                    }

                    val controller = controllers.firstOrNull {
                        it.playbackState != null
                    } ?: controllers.firstOrNull()

                    if (controller == null) {
                        return@Tool listOf(UIMessagePart.Text(
                            buildJsonObject {
                                put("success", false)
                                put("error", JsonPrimitive("No active media controller found"))
                            }.toString()
                        ))
                    }

                    val transportControls = controller.transportControls
                    when (action) {
                        "play" -> transportControls.play()
                        "pause" -> transportControls.pause()
                        "next" -> transportControls.skipToNext()
                        "previous" -> transportControls.skipToPrevious()
                        "seek" -> {
                            val positionMs = params["position_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                            if (positionMs == null) {
                                return@Tool listOf(UIMessagePart.Text(
                                    buildJsonObject {
                                        put("success", false)
                                        put("error", JsonPrimitive("Missing required parameter 'position_ms' for seek action"))
                                    }.toString()
                                ))
                            }
                            transportControls.seekTo(positionMs)
                        }
                    }

                    listOf(UIMessagePart.Text(
                        buildJsonObject {
                            put("success", true)
                            put("action", JsonPrimitive(action))
                            put("app_name", JsonPrimitive(getAppName(context, controller.packageName)))
                            put("message", JsonPrimitive("Successfully sent $action command to ${getAppName(context, controller.packageName)}"))
                        }.toString()
                    ))
                }

                "play_song" -> {
                    val query = params["query"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (query.isBlank()) {
                        return@Tool listOf(UIMessagePart.Text(
                            buildJsonObject {
                                put("success", false)
                                put("error", JsonPrimitive("Missing required parameter 'query' for play_song"))
                            }.toString()
                        ))
                    }

                    // 网络活整段挪到 IO 线程（execute 是 suspend，可以直接切）
                    val outcome: Pair<List<String>?, String> = withContext(Dispatchers.IO) {
                        try {
                            val searchBody = eryuGet("/music/search?q=" + URLEncoder.encode(query, "UTF-8"))
                            if (searchBody == null) {
                                Pair(null, "搜索请求失败（eryu 没响应）")
                            } else {
                                val songs = Json.parseToJsonElement(searchBody).jsonObject["songs"]?.jsonArray
                                val first = songs?.firstOrNull()?.jsonObject
                                if (first == null) {
                                    Pair(null, "没搜到「" + query + "」")
                                } else {
                                    val id = first["id"]?.jsonPrimitive?.contentOrNull ?: ""
                                    val name = first["name"]?.jsonPrimitive?.contentOrNull ?: query
                                    val artist = first["artist"]?.jsonPrimitive?.contentOrNull ?: ""
                                    val urlBody = eryuGet("/music/url?id=" + URLEncoder.encode(id, "UTF-8"))
                                    val path = urlBody?.let {
                                        Json.parseToJsonElement(it).jsonObject["url"]?.jsonPrimitive?.contentOrNull
                                    }
                                    if (path == null) {
                                        Pair(null, "eryu 没给出地址（id=" + id + "）")
                                    } else {
                                        val full = if (path.startsWith("http")) path else ERYU_BASE + path
                                        Pair(listOf(full, name, artist), "")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Pair(null, e.javaClass.simpleName + ": " + (e.message ?: ""))
                        }
                    }

                    val info = outcome.first
                    if (info == null) {
                        return@Tool listOf(UIMessagePart.Text(
                            buildJsonObject {
                                put("success", false)
                                put("action", JsonPrimitive("play_song"))
                                put("query", JsonPrimitive(query))
                                put("error", JsonPrimitive(outcome.second))
                            }.toString()
                        ))
                    }

                    val fullUrl = info[0]
                    val songName = info[1]
                    val songArtist = info[2]
                    MusicPlayerService.play(context, fullUrl, songName, songArtist)

                    listOf(UIMessagePart.Text(
                        buildJsonObject {
                            put("success", true)
                            put("action", JsonPrimitive("play_song"))
                            put("query", JsonPrimitive(query))
                            put("name", JsonPrimitive(songName))
                            put("artist", JsonPrimitive(songArtist))
                            put("url", JsonPrimitive(fullUrl))
                            put("message", JsonPrimitive("Playing " + songName + " - " + songArtist + " in the app's own player."))
                        }.toString()
                    ))
                }

                "stop_url" -> {
                    // 停掉 App 自己的播放器。这个走 MusicPlayerService，不碰第三方音乐 App。
                    MusicPlayerService.stop(context)
                    listOf(UIMessagePart.Text(
                        buildJsonObject {
                            put("success", true)
                            put("action", JsonPrimitive("stop_url"))
                            put("message", JsonPrimitive("Stopped the app's own player."))
                        }.toString()
                    ))
                }

                "play_url" -> {
                    val url = params["url"]?.jsonPrimitive?.contentOrNull ?: ""
                    val title = params["title"]?.jsonPrimitive?.contentOrNull ?: ""
                    val artist = params["artist"]?.jsonPrimitive?.contentOrNull ?: ""

                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        return@Tool listOf(UIMessagePart.Text(
                            buildJsonObject {
                                put("success", false)
                                put("error", JsonPrimitive("'url' must start with http:// or https://. For local files use the plugin bridge instead."))
                            }.toString()
                        ))
                    }

                    // 交给 App 自己的前台播放服务（ExoPlayer），不依赖任何第三方音乐 App。
                    MusicPlayerService.play(context, url, title.ifBlank { "音频" }, artist)

                    listOf(UIMessagePart.Text(
                        buildJsonObject {
                            put("success", true)
                            put("action", JsonPrimitive("play_url"))
                            put("url", JsonPrimitive(url))
                            put("title", JsonPrimitive(title))
                            put("artist", JsonPrimitive(artist))
                            put("message", JsonPrimitive("Started playing in the app's own player."))
                        }.toString()
                    ))
                }

                "play_search" -> {
                    val query = params["query"]?.jsonPrimitive?.contentOrNull ?: ""
                    val artist = params["artist"]?.jsonPrimitive?.contentOrNull ?: ""
                    val title = params["title"]?.jsonPrimitive?.contentOrNull ?: ""

                    if (query.isBlank() && artist.isBlank() && title.isBlank()) {
                        return@Tool listOf(UIMessagePart.Text(
                            buildJsonObject {
                                put("success", false)
                                put("error", JsonPrimitive("At least one of 'query', 'artist', or 'title' must be provided for play_search"))
                            }.toString()
                        ))
                    }

                    val searchIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                        putExtra(MediaStore.EXTRA_MEDIA_FOCUS, if (artist.isNotBlank() || title.isNotBlank()) MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE else MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
                        putExtra(SearchManager.QUERY, query)
                        if (artist.isNotBlank()) {
                            putExtra(MediaStore.EXTRA_MEDIA_ARTIST, artist)
                        }
                        if (title.isNotBlank()) {
                            putExtra(MediaStore.EXTRA_MEDIA_TITLE, title)
                        }
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }

                    val activities = context.packageManager.queryIntentActivities(searchIntent, 0)
                    if (activities.isNullOrEmpty()) {
                        return@Tool listOf(UIMessagePart.Text(
                            buildJsonObject {
                                put("success", false)
                                put("error", JsonPrimitive("No music app found that supports search and play"))
                            }.toString()
                        ))
                    }

                    context.startActivity(searchIntent)

                    listOf(UIMessagePart.Text(
                        buildJsonObject {
                            put("success", true)
                            put("action", JsonPrimitive("play_search"))
                            put("query", JsonPrimitive(query))
                            put("artist", JsonPrimitive(artist))
                            put("title", JsonPrimitive(title))
                            put("message", JsonPrimitive("Sent play search request: ${if (query.isNotBlank()) query else "${artist} - ${title}"}"))
                        }.toString()
                    ))
                }

                else -> {
                    listOf(UIMessagePart.Text(
                        buildJsonObject {
                            put("success", false)
                            put("error", JsonPrimitive("Unknown action: $action. Supported actions: get_now_playing, play, pause, next, previous, seek, play_search"))
                        }.toString()
                    ))
                }
            }
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", false)
                    put("error", JsonPrimitive(e.message ?: "Unknown error"))
                }.toString()
            ))
        }
    }
)
