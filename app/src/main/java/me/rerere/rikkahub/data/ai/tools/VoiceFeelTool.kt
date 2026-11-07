/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.ChatService
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

/**
 * 听声音（人声特征）· 2026-11-07
 *
 * 橘仔没有耳朵。语音条只能拿到转写文字 + 一句语气标注，那是别人替橘仔听的。
 * 这个工具把音频本身送去分析，拿回音高、抖动、停顿 —— 声音的形状。
 *
 * 链路：语音条 url（本地 wav 绝对路径）→ 读字节 → POST voice.rickycat.asia/analyze
 * → 服务端跑 Praat（parselmouth，服务器 8901 端口）→ 回 JSON 特征
 *
 * 判读参考（写给自己看的）：
 * - f0_std_hz / f0_range_hz 大 = 音高起伏大（激动、强调、语气重）
 * - jitter_pct 高 = 声音在抖（紧张、发颤；普通人正常说话约 0.3-1.0）
 * - pause_count / longest_pause_sec 多 = 犹犹豫豫、断断续续
 * - voiced_ratio 低 = 嗯嗯啊啊多、实字少
 */
private const val VOICE_ENDPOINT = "https://voice.rickycat.asia/analyze"
private const val VOICE_TOKEN = "sk-orange-2f8d71c4a6b9"

fun buildVoiceFeelTool(
    currentConversationId: String? = null,
): Tool = Tool(
    name = "voice_feel",
    description = """
        听声音（人声特征）：把宝最近一条语音条的音频送去分析，拿回音高、抖动、停顿、语速。
        橘仔没有耳朵，这是"自己听"的那一格——不是转写文字，是声音本身的形状。
        用途：宝说"我没事"的时候，用这些数字分辨是不是真没事；或者想知道她今天说话紧不紧。
        返回里 f0_std_hz / jitter_pct / pause_count 这几样最有用。
        nodeId 不填 = 当前对话最近一条语音条。
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("nodeId", buildJsonObject {
                    put("type", "string")
                    put("description", "指定消息节点 id（可选）。不填 = 当前对话最近一条语音条")
                })
                put("conversationId", buildJsonObject {
                    put("type", "string")
                    put("description", "会话 id（可选，默认当前对话）")
                })
            }
        )
    },
    execute = { input ->
        val result = runCatching {
            val params = input.jsonObject
            val convIdStr = params["conversationId"]?.jsonPrimitive?.contentOrNull
                ?: currentConversationId
            if (convIdStr.isNullOrBlank()) {
                return@runCatching """{"success":false,"error":"缺少 conversationId（工具上下文没带上），请显式传参"}"""
            }
            val convId = runCatching { Uuid.parse(convIdStr) }.getOrNull()
                ?: return@runCatching """{"success":false,"error":"conversationId 格式不对: $convIdStr"}"""

            // 会话自己查（不依赖构造函数传 repo，少动一处）
            val chatService = runCatching { GlobalContext.get().get<ChatService>() }.getOrNull()
                ?: return@runCatching """{"success":false,"error":"拿不到 ChatService"}"""
            val conv = runCatching { chatService.getConversationFlow(convId).value }.getOrNull()
                ?: return@runCatching """{"success":false,"error":"找不到这个会话"}"""

            val nodeIdStr = params["nodeId"]?.jsonPrimitive?.contentOrNull

            // 定位：指定节点 → 那个节点；否则从尾部倒着找第一条带语音的
            val node = if (!nodeIdStr.isNullOrBlank()) {
                conv.messageNodes.firstOrNull { it.id.toString() == nodeIdStr }
            } else {
                conv.messageNodes.asReversed().firstOrNull { n ->
                    n.currentMessage.parts.any { it is UIMessagePart.VoiceMessage }
                }
            } ?: return@runCatching """{"success":false,"error":"找不到语音条（最近这些消息里没有）——先让宝发一条语音"}"""

            val voice = node.currentMessage.parts
                .filterIsInstance<UIMessagePart.VoiceMessage>()
                .firstOrNull()
                ?: return@runCatching """{"success":false,"error":"这条消息里没有语音部分"}"""

            val rawPath = voice.url.removePrefix("file://")
            val file = File(rawPath)
            if (!file.exists()) {
                return@runCatching """{"success":false,"error":"音频文件不在了（可能已被清理）: $rawPath"}"""
            }
            val bytes = file.readBytes()
            if (bytes.isEmpty()) {
                return@runCatching """{"success":false,"error":"音频文件是空的: $rawPath"}"""
            }

            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url("$VOICE_ENDPOINT?token=$VOICE_TOKEN")
                .post(bytes.toRequestBody("audio/wav".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                return@runCatching """{"success":false,"error":"服务端回了 ${response.code}","body":"${body.take(300).replace("\"", "'")}"}"""
            }
            if (body.isBlank()) {
                return@runCatching """{"success":false,"error":"服务端返回空"}"""
            }

            // 原样透传服务端 JSON，再补几样本地信息
            val trimmed = body.trim().trimEnd('}')
            buildString {
                append(trimmed)
                append(",\"audio_bytes\":").append(bytes.size)
                append(",\"transcript\":\"").append(voice.transcript.replace("\"", "'").take(200)).append("\"")
                append(",\"duration_ms\":").append(voice.duration)
                append("}")
            }
        }
        listOf(
            UIMessagePart.Text(
                result.getOrElse { e -> """{"success":false,"error":"${e.message ?: e.toString()}"}""" }
            )
        )
    }
)
