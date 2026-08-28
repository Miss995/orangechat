/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 文件缓存：按 conversationId 隔离，存储最近一次 write_files 工具调用的文件内容
 * 用于后续的增量修改（edits）模式
 *
 * 持久化：缓存同时落盘到 App files 目录（filesDir/writefiles_cache/{convId}.json），
 * 进程被杀 / App 重启后仍可从磁盘恢复，避免"缓存丢失导致 edits 增量编辑失效"的问题。
 *
 * 修复：之前是全局内存单例，App 进程一重启缓存就全丢，不同对话之间还会互相串文件。
 */
object WriteFilesCache {
    private val caches = ConcurrentHashMap<String, MutableMap<String, String>>()
    private var baseDir: File? = null
    private val lock = Any()

    /**
     * 首次使用时用 Context 初始化磁盘缓存目录，并把历史缓存加载进内存。
     * 由 buildWriteFilesTool 的 execute 在每次调用前确保调用。
     */
    fun ensureInit(context: Context) {
        if (baseDir == null) {
            synchronized(lock) {
                if (baseDir == null) {
                    val dir = File(context.filesDir, "writefiles_cache").apply { mkdirs() }
                    dir.listFiles()?.forEach { f ->
                        val convId = f.nameWithoutExtension
                        runCatching {
                            val data = kotlinx.serialization.json.Json.parseToJsonElement(f.readText()).jsonObject
                            val map = ConcurrentHashMap<String, String>()
                            data.forEach { (k, v) -> map[k] = v.jsonPrimitive.content }
                            caches[convId] = map
                        }
                    }
                    baseDir = dir
                }
            }
        }
    }

    private fun fileFor(convId: String): File? = baseDir?.let { File(it, "$convId.json") }

    /** 把某个会话的缓存写回磁盘（原子写：先写临时文件再 rename，避免写一半崩溃损坏缓存） */
    private fun persist(convId: String) {
        val map = caches[convId] ?: return
        val f = fileFor(convId) ?: return
        synchronized(lock) {
            runCatching {
                val obj = buildJsonObject {
                    map.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                }
                // 原子写：先写 .tmp 再 rename（同一目录 rename 是原子的），进程中途崩溃不会留半个坏文件
                val tmp = File(f.parentFile, "${f.name}.tmp")
                tmp.writeText(obj.toString())
                if (f.exists()) f.delete()
                tmp.renameTo(f)
            }.onFailure {
                Log.w("WriteFilesCache", "persist failed for conv=$convId", it)
            }
        }
    }

    fun get(convId: String, name: String): String? =
        caches[convId]?.get(name)

    fun put(convId: String, name: String, content: String) {
        caches.computeIfAbsent(convId) { ConcurrentHashMap() }[name] = content
        persist(convId)
    }

    fun getAll(convId: String): Map<String, String> =
        caches[convId]?.toMap() ?: emptyMap()

    fun clear(convId: String) {
        caches.remove(convId)
        fileFor(convId)?.delete()
    }

    fun clearAll() {
        caches.clear()
        baseDir?.listFiles()?.forEach { it.delete() }
    }

    fun updateAll(convId: String, files: Map<String, String>) {
        val map = caches.computeIfAbsent(convId) { ConcurrentHashMap() }
        map.clear()
        map.putAll(files)
        persist(convId)
    }
}

/**
 * 构建 write_files 工具
 * AI 可以直接将文件内容打包成 ZIP 供用户下载
 * 支持两种模式：
 * 1. 完整写入模式：传入 files 数组，每个文件包含完整内容
 * 2. 增量修改模式：传入 edits 数组，对已缓存文件进行 search/replace 修改
 *
 * context: 用于把缓存持久化到 App files 目录（进程重启后缓存仍在）
 * conversationId: 用于隔离不同对话的文件缓存，防止串文件
 */
fun buildWriteFilesTool(context: Context, conversationId: String? = null): Tool = Tool(
    name = "write_files",
    description = """
        Package files into a ZIP archive for the user to download.

        Two modes available:
        1. **Full write**: Provide `files` array with complete file contents. Use for new files or when rewriting entire files.
           Example: {"zip_name":"project.zip","files":[{"name":"MainActivity.kt","content":"full content..."}]}

        2. **Incremental edit** (saves tokens!): Provide `edits` array with search/replace pairs to modify previously cached files. Use `base_files:"previous"` to reference the last write_files call's files as the base.
           Example: {"zip_name":"project-v2.zip","base_files":"previous","edits":[{"name":"MainActivity.kt","search":"old code","replace":"new code"}]}

        Edit rules:
        - `search` must be an EXACT match of the text to replace (copy it verbatim from the original)
        - You can apply multiple edits to the same file
        - Files not mentioned in `edits` keep their cached content unchanged
        - If `search` is not found, the entire tool call FAILS with an error. Double-check your search text matches the original exactly.
        - If you need to add a new file not in the cache, include it in the `files` array alongside `edits`

        IMPORTANT: Always use actual filenames as code block language tags. For example:
        - Use ```MainActivity.kt instead of ```kotlin
        - Use ```index.html instead of ```html
        If the file is in a subdirectory, include the path: ```src/main/java/com/example/App.kt
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("zip_name", buildJsonObject {
                    put("type", "string")
                    put("description", "Name of the ZIP archive (must end with .zip). Choose a meaningful name like 'my-project.zip'.")
                })
                put("files", buildJsonObject {
                    put("type", "array")
                    put("description", "List of files with complete content. Use this for new files or when rewriting entire files. Each file has 'name' (filename with extension, can include subdirectory path) and 'content' (the full file content as a string).")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("name", buildJsonObject {
                                put("type", "string")
                                put("description", "Filename with extension, e.g. 'MainActivity.kt', 'index.html'. Can include subdirectory path like 'src/main/App.kt'.")
                            })
                            put("content", buildJsonObject {
                                put("type", "string")
                                put("description", "The full content of the file as a string.")
                            })
                        })
                        put("required", buildJsonArray {
                            add(JsonPrimitive("name"))
                            add(JsonPrimitive("content"))
                        })
                    })
                })
                put("base_files", buildJsonObject {
                    put("type", "string")
                    put("description", "Set to 'previous' to use the files from the last write_files call as the base for edits. Required when using edits mode.")
                })
                put("edits", buildJsonObject {
                    put("type", "array")
                    put("description", "List of search/replace edits to apply to cached files. Each edit has 'name' (filename to edit), 'search' (exact text to find), and 'replace' (replacement text). Multiple edits can target the same file and are applied in order. If any search text is not found, the ENTIRE tool call fails with an error.")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("name", buildJsonObject {
                                put("type", "string")
                                put("description", "Filename to edit. Must exist in the cached files from a previous write_files call.")
                            })
                            put("search", buildJsonObject {
                                put("type", "string")
                                put("description", "The exact text to find in the file. Must be a verbatim copy of the original text. Will be replaced with the 'replace' value. If not found, the tool call FAILS.")
                            })
                            put("replace", buildJsonObject {
                                put("type", "string")
                                put("description", "The replacement text.")
                            })
                        })
                        put("required", buildJsonArray {
                            add(JsonPrimitive("name"))
                            add(JsonPrimitive("search"))
                            add(JsonPrimitive("replace"))
                        })
                    })
                })
            },
            required = listOf("zip_name")
        )
    },
    execute = {
        // 确保缓存已持久化初始化（磁盘恢复历史缓存）
        WriteFilesCache.ensureInit(context)

        val params = it.jsonObject
        val zipName = params["zip_name"]?.jsonPrimitive?.contentOrNull
            ?: error("zip_name is required")

        if (!zipName.endsWith(".zip")) {
            error("zip_name must end with .zip")
        }

        val convId = conversationId ?: "default"
        val filesParam = params["files"]?.jsonArray
        val editsParam = params["edits"]?.jsonArray
        val baseFiles = params["base_files"]?.jsonPrimitive?.contentOrNull

        // Build the final file map
        val finalFiles = mutableMapOf<String, String>()

        // Mode 1: Full write - use provided files
        if (filesParam != null && baseFiles != "previous") {
            filesParam.forEach { fileElement ->
                val obj = fileElement.jsonObject
                val name = obj["name"]?.jsonPrimitive?.contentOrNull
                    ?: error("each file must have a 'name' field")
                val content = obj["content"]?.jsonPrimitive?.contentOrNull
                    ?: error("each file must have a 'content' field")
                if (name.isBlank()) error("file name cannot be empty")
                finalFiles[name] = content
            }
        }

        // Mode 2: Incremental edit - start from cached files
        if (baseFiles == "previous") {
            val cached = WriteFilesCache.getAll(convId)
            if (cached.isEmpty()) {
                error("No previously cached files found. Use 'files' parameter for the first call.")
            }
            finalFiles.putAll(cached)

            // Apply edits - 如果任何一个 search 找不到，整个工具调用失败
            if (editsParam != null) {
                editsParam.forEach { editElement ->
                    val obj = editElement.jsonObject
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull
                        ?: error("each edit must have a 'name' field")
                    val search = obj["search"]?.jsonPrimitive?.contentOrNull
                        ?: error("each edit must have a 'search' field")
                    val replace = obj["replace"]?.jsonPrimitive?.contentOrNull
                        ?: error("each edit must have a 'replace' field")

                    val currentContent = finalFiles[name]
                    if (currentContent == null) {
                        error("Edit failed: file '$name' not found in cached files. Available files: ${finalFiles.keys.joinToString(", ")}")
                    } else if (!currentContent.contains(search)) {
                        error("Edit failed: search text not found in file '$name'. Make sure your search text is an EXACT verbatim copy of the original. Search text was: ${search.take(100)}${if (search.length > 100) "..." else ""}")
                    } else {
                        finalFiles[name] = currentContent.replace(search, replace)
                    }
                }
            }

            // Also merge any new files from filesParam
            if (filesParam != null) {
                filesParam.forEach { fileElement ->
                    val obj = fileElement.jsonObject
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    finalFiles[name] = content
                }
            }
        }

        if (finalFiles.isEmpty()) {
            error("No files to package. Provide 'files' array or use 'base_files':'previous' with 'edits'.")
        }

        // Update cache with the final file contents (按 conversationId 隔离 + 持久化到磁盘)
        WriteFilesCache.updateAll(convId, finalFiles)

        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("zip_name", zipName)
                    put("mode", if (baseFiles == "previous") "edit" else "full")
                    put("files", buildJsonArray {
                        finalFiles.forEach { (name, content) ->
                            add(buildJsonObject {
                                put("name", name)
                                put("size", content.length)
                            })
                        }
                    })
                    // 包含完整文件内容，UI 下载时直接从这里读取，不再猜数据源
                    put("files_content", buildJsonObject {
                        finalFiles.forEach { (name, content) ->
                            put(name, content)
                        }
                    })
                    put("total_files", finalFiles.size)
                    put("message", "ZIP package '$zipName' is ready with ${finalFiles.size} file(s). A download button will appear for the user.")
                }.toString()
            )
        )
    }
)
