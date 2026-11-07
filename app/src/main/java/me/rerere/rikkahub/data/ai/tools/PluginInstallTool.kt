/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import android.os.Build
import android.os.Environment
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.plugin.manager.PluginManager
import java.io.File

/**
 * 插件装卸 · 2026-11-07
 *
 * 插件一直得宝手动装（选 zip / 选文件夹）。这里给猫一双手：
 * 直接往 /storage/emulated/0/Orangechat/plugins/<id>/ 写文件，写完叫 PluginManager 重扫。
 *
 * 三档动作：
 *   install —— 写文件 + 重扫（默认，不传 action 时走这条，兼容第一版）
 *   delete  —— 走 PluginManager.deletePlugin（卸内存 + 删目录 + 清记录 + 重扫）
 *   list    —— 看一眼目录里现在有哪些，别瞎猜
 *
 * 目录跟 PluginScanner.PLUGINS_DIR 保持一致（"Orangechat/plugins"），别各写一份。
 * 装完默认就是启用状态（扫描时 isEnabled = true），要关用 plugin_switch。
 */

private const val PLUGINS_ROOT_DIR = "Orangechat/plugins"
private const val MANIFEST_FILE_NAME = "manifest.json"

fun buildPluginInstallTool(
    getPluginManager: suspend () -> PluginManager,
): Tool = Tool(
    name = "plugin_install",
    description = """
        插件的装、删、看。目录是 Orangechat/plugins/<插件id>/。

        什么时候用：
        - 猫自己写了一个插件，想装上试试（action=install）
        - 插件写坏了/不要了，删掉（action=delete）
        - 想确认现在到底装了哪些，或者删之前看看叫什么（action=list）

        install：files 每一条是 {path, content}，path 相对插件目录（"manifest.json"、"index.js"），
        必须包含 manifest.json，否则扫不到。
        delete：整目录删掉，同时清掉启用状态和分组记录。
        list：不需要 id，列出目录里现在有哪些文件夹。

        ⚠️ 需要「所有文件访问权限」（设置 → 应用 → 橘瓣 → 权限）。没有的话工具会直说，别反复重试。
        ⚠️ install 完是"已扫描 + 默认启用"；要让它彻底不跑，用 plugin_switch 关。
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "install（默认）/ delete / list")
                })
                put("id", buildJsonObject {
                    put("type", "string")
                    put("description", "插件 id（反向域名风格，如 com.orangecat.demo）。install / delete 必填。")
                })
                put("files", buildJsonObject {
                    put("type", "array")
                    put("description", "install 用：文件列表，每项 {path, content}；必须含 manifest.json")
                })
            },
            required = listOf("action")
        )
    },
    execute = { input ->
        val params = input.jsonObject
        // 不传 action 就当 install —— 第一版就是这个语义，别让老习惯失效
        val action = params["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase().orEmpty()
            .ifEmpty { "install" }
        val pluginId = params["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

        fun rootDir(): File {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                error(
                    "没有「所有文件访问权限」，进不去 Orangechat/plugins。" +
                        "去 系统设置 → 应用 → 橘瓣 → 权限 → 文件和媒体 → 允许管理所有文件，再试。"
                )
            }
            return File(Environment.getExternalStorageDirectory(), PLUGINS_ROOT_DIR)
        }

        val json = runCatching {
            when (action) {
                "install" -> {
                    if (pluginId.isEmpty()) error("install 需要 id")
                    if (pluginId.contains('/') || pluginId.contains('\\') || pluginId.contains("..")) {
                        error("id 不能带路径分隔符")
                    }
                    val filesArray = params["files"] as? JsonArray ?: error("install 需要 files（数组）")
                    if (filesArray.isEmpty()) error("files 不能为空")

                    val root = rootDir()
                    if (!root.exists()) root.mkdirs()
                    if (!root.canWrite()) error("Orangechat/plugins 目录写不进去（权限或只读）")

                    val dir = File(root, pluginId)
                    if (!dir.exists()) dir.mkdirs()
                    val basePath = dir.canonicalPath

                    val written = mutableListOf<String>()
                    filesArray.forEach { element ->
                        val obj = element.jsonObject
                        val path = obj["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                        val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: ""
                        if (path.isEmpty()) error("files 里有一条缺 path")

                        val target = File(dir, path)
                        if (!target.canonicalPath.startsWith(basePath + File.separator)) {
                            error("路径越界，拒绝写入：$path")
                        }
                        target.parentFile?.mkdirs()
                        target.writeText(content)
                        written.add(path)
                    }

                    if (!File(dir, MANIFEST_FILE_NAME).exists()) {
                        error("没写 manifest.json，插件扫不到")
                    }

                    val manager = getPluginManager()
                    manager.reloadAllPlugins()
                    val info = manager.getPlugin(pluginId)

                    buildJsonObject {
                        put("success", true)
                        put("action", "install")
                        put("id", pluginId)
                        put("dir", dir.absolutePath)
                        put("written", buildJsonArray { written.forEach { add(JsonPrimitive(it)) } })
                        put("scanned", info != null)
                        put("plugin_name", info?.manifest?.name.orEmpty())
                        put("load_error", info?.loadError.orEmpty())
                        put(
                            "note",
                            if (info == null) "写完也重扫了，但列表里没有它 —— 多半是 manifest.json 字段不对"
                            else "已重扫，默认就是启用状态。要让它彻底不跑，用 plugin_switch 关。"
                        )
                    }.toString()
                }

                "delete" -> {
                    if (pluginId.isEmpty()) error("delete 需要 id")

                    val dir = File(rootDir(), pluginId)
                    val existed = dir.exists()

                    val manager = getPluginManager()
                    val removed = manager.deletePlugin(pluginId)

                    buildJsonObject {
                        put("success", true)
                        put("action", "delete")
                        put("id", pluginId)
                        put("dir_existed", existed)
                        put("removed", removed)
                        put("dir_still_there", dir.exists())
                        put(
                            "note",
                            if (!existed && !removed) "目录本来就没有，列表里也没有它 —— 可能 id 写错了"
                            else "已卸内存、删目录、清记录、重扫。"
                        )
                    }.toString()
                }

                "list" -> {
                    val root = rootDir()
                    val dirs = if (root.exists() && root.isDirectory) {
                        root.listFiles { f -> f.isDirectory }?.sortedBy { it.name }.orEmpty()
                    } else emptyList()

                    buildJsonObject {
                        put("success", true)
                        put("action", "list")
                        put("root", root.absolutePath)
                        put("count", dirs.size)
                        put(
                            "items",
                            buildJsonArray {
                                dirs.forEach { d ->
                                    val hasManifest = File(d, MANIFEST_FILE_NAME).exists()
                                    add(
                                        buildJsonObject {
                                            put("id", d.name)
                                            put("has_manifest", hasManifest)
                                            put("file_count", d.listFiles()?.size ?: 0)
                                        }
                                    )
                                }
                            }
                        )
                    }.toString()
                }

                else -> error("action 只能是 install / delete / list，收到的是\"$action\"")
            }
        }.getOrElse { e ->
            buildJsonObject {
                put("success", false)
                put("action", action)
                put("error", e.message ?: e.toString())
            }.toString()
        }

        listOf(UIMessagePart.Text(json))
    }
)
