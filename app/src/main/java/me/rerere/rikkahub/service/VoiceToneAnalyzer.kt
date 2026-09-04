/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 *
 * VoiceToneAnalyzer —— 声音情绪分析器（宝 2026-09-04 的"情绪耳朵"）
 * 调硅基流动的 Qwen3-Omni（原生支持音频输入+情绪理解）听一段语音，
 * 输出说话者的语气/语速判断。结果写回语音消息 metadata，
 * 由 VoiceMessageTransformer 拼成「（语气：X，语速：Y）」喂给 DeepSeek。
 */

package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.ai.AppLogBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

object VoiceToneAnalyzer {

    data class ToneResult(val tone: String, val speed: String)

    // 硅基流动 Qwen3-Omni（原生全模态：文本+音频+视频）——宝下午确认的路线 Y 主角
    private const val MODEL = "Qwen/Qwen3-Omni-30B-A3B-Instruct"
    private const val ENDPOINT = "https://api.siliconflow.cn/v1/chat/completions"

    /**
     * 分析一段语音文件（wav），返回语气/语速。任何失败静默返回 null（不打扰用户）。
     * 音频按 OpenAI 兼容 input_audio 格式 base64 传入。
     */
    suspend fun analyze(apiKey: String, audioPath: String): ToneResult? = withContext(Dispatchers.IO) {
        try {
            val path = audioPath.removePrefix("file://")
            val file = File(path)
            if (!file.exists() || file.length() == 0L) {
                AppLogBuffer.log("VoiceToneAnalyzer", "audio file missing: $path")
                return@withContext null
            }
            val b64 = Base64.getEncoder().encodeToString(file.readBytes())

            val prompt = "你是声音心理师。听这段中文语音，判断说话者的语气和语速。只输出一行：语气=XX，语速=X。语气从「平静/开心/紧张/低落/激动/犹豫/疲惫/撒娇/认真/其他」里选最接近的一个词；语速从「快/中/慢」里选。不要输出任何其他内容。"

            // 硅基流动 Qwen3-Omni 音频格式 = audio_url + data URI（官方文档 multimodal 5.3 Audio Understanding）
            // 注意：不是 OpenAI 的 input_audio！格式错了会 400 静默失败（2026-09-04 踩坑）
            val body = buildString {
                append("{\"model\":\"$MODEL\",\"max_tokens\":60,\"messages\":[{\"role\":\"user\",\"content\":[")
                append("{\"type\":\"audio_url\",\"audio_url\":{\"url\":\"data:audio/wav;base64,")
                append(b64)
                append("\"}},")
                append("{\"type\":\"text\",\"text\":")
                append(JsonEscaper.escape(prompt))
                append("}]}]}")
            }

            val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 10_000
                conn.readTimeout = 60_000
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                val respText = if (code in 200..299) {
                    conn.inputStream.bufferedReader().readText()
                } else {
                    val err = conn.errorStream?.bufferedReader()?.readText() ?: ""
                    AppLogBuffer.log("VoiceToneAnalyzer", "Omni http=$code err=$err")
                    return@withContext null
                }

                // 响应里抓 message.content（形如「语气=紧张，语速=快」）
                val content = Regex("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(respText)?.groupValues?.get(1)
                    ?.replace("\\n", "")?.replace("\\\"", "\"") ?: return@withContext null
                val tone = Regex("语气=([^，,、\\s]+)").find(content)?.groupValues?.get(1)
                val speed = Regex("语速=([^，,、\\s]+)").find(content)?.groupValues?.get(1)
                if (tone.isNullOrBlank() || speed.isNullOrBlank()) {
                    AppLogBuffer.log("VoiceToneAnalyzer", "unparseable content: $content")
                    return@withContext null
                }
                AppLogBuffer.log("VoiceToneAnalyzer", "omni ok tone=$tone speed=$speed")
                ToneResult(tone = tone, speed = speed)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            AppLogBuffer.log("VoiceToneAnalyzer", "analyze failed: ${e.message}")
            null
        }
    }

    /** 简单 JSON 字符串转义（只处理 prompt 里的引号/反斜杠/换行） */
    private object JsonEscaper {
        fun escape(s: String): String = buildString {
            append('"')
            for (c in s) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(c)
                }
            }
            append('"')
        }
    }
}
