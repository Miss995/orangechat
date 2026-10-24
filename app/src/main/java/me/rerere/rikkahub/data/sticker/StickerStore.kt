package me.rerere.rikkahub.data.sticker

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

/**
 * 【表情包仓库 · 2026-10-24】
 *
 * 就是 filesDir/stickers 这一个目录，不建数据库 —— 文件本身就是记录。
 *
 * 类型判断靠读文件头，不看 MIME：有些 App 会把 gif 标成 image/jpeg，
 * 光信 MIME 会把动图存成静态的（存是存下了，一显示就不动了）。
 */
object StickerStore {
    private const val DIR_NAME = "stickers"

    fun dir(context: Context): File {
        val d = File(context.filesDir, DIR_NAME)
        if (!d.exists()) d.mkdirs()
        return d
    }

    /** 新的排前面（按修改时间倒序）；`xxx.txt` 是描述 sidecar，不算表情包 */
    fun list(context: Context): List<File> =
        dir(context).listFiles()
            ?.filter { it.isFile && it.length() > 0 && !it.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /**
     * 描述 sidecar：跟图同名的 `xxx.gif.txt`，一行文本。
     *
     * 存进来的时候让视觉模型看一眼写在这儿；以后发出去就直接复用这份，
     * 不再多看一次（一张表情包一辈子只被看一次）。
     */
    fun readNote(file: File): String? = runCatching {
        val f = File(file.parentFile, file.name + ".txt")
        if (f.exists()) f.readText().trim().ifBlank { null } else null
    }.getOrNull()

    fun saveNote(file: File, text: String) {
        runCatching { File(file.parentFile, file.name + ".txt").writeText(text.trim()) }
    }

    /** 存一张进来；成功返回绝对路径，失败返回 null */
    fun saveFromUri(context: Context, uri: Uri): String? {
        val name = queryDisplayName(context, uri)

        // 先读前 12 个字节判类型
        val header = ByteArray(12)
        var read = 0
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                read = input.read(header)
            }
        }.onFailure { return null }
        if (read <= 0) return null

        val ext = guessExt(header, read, name)
        val target = File(dir(context), UUID.randomUUID().toString() + "." + ext)

        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }.onFailure {
            runCatching { target.delete() }
            return null
        }

        return if (target.length() > 0) target.absolutePath else null
    }

    fun delete(file: File): Boolean = runCatching { file.delete() }.getOrDefault(false)

    private fun guessExt(header: ByteArray, read: Int, name: String?): String {
        val b = header
        if (read >= 6 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte()) {
            return "gif"
        }
        if (read >= 12 &&
            b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() &&
            b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte() &&
            b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() &&
            b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte()
        ) {
            return "webp"
        }
        if (read >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() &&
            b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()
        ) {
            return "png"
        }
        if (read >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()) {
            return "jpg"
        }
        val lower = name?.lowercase().orEmpty()
        return when {
            lower.endsWith(".gif") -> "gif"
            lower.endsWith(".webp") -> "webp"
            lower.endsWith(".png") -> "png"
            else -> "jpg"
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }
}
