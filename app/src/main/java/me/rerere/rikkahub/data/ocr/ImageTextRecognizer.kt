/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 【图片文字引用 · 2026-10-12】
 *
 * 把一张图里的文字读出来。
 * 用的是 ML Kit 的中文识别 —— 离线跑、不联网、不花钱。
 *
 * 返回按行切好的文字（空列表 = 图里没字，或者读失败）。
 */
object ImageTextRecognizer {

    /** 懒加载：ML Kit 的 client 只要建一次，重复建会浪费资源。 */
    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /**
     * @param url 图片地址，一般是 file:// 或 content://（聊天里的图都是这种）。
     * @return 识别出来的每一行文字；读不出来就返回空列表。
     */
    suspend fun recognize(context: Context, url: String): List<String> {
        if (url.isBlank()) return emptyList()
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return emptyList()
        // InputImage.fromFilePath 支持 file:// 和 content://。
        // 有些图是 data:base64 内嵌的，这里读不了 —— 那种直接返回空，不抛异常。
        val image = runCatching { InputImage.fromFilePath(context, uri) }.getOrNull()
            ?: return emptyList()

        return suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val lines = mutableListOf<String>()
                    visionText.textBlocks.forEach { block ->
                        block.lines.forEach { line ->
                            val t = line.text.trim()
                            if (t.isNotEmpty()) lines.add(t)
                        }
                    }
                    if (cont.isActive) cont.resume(lines)
                }
                .addOnFailureListener { e ->
                    if (cont.isActive) cont.resumeWithException(e)
                }
        }
    }
}
