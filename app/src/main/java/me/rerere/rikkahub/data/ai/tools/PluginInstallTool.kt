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
 * 插件导入 · 2026-11-07
 *
 * 插件一直得宝手动装（选 zip / 选文件夹）。这里给猫一只手：
 * 直接往 /storage/emulated/0/Orangechat/plugins/<id>/ 写文件，写完叫 PluginManager
 * 重新扫一遍，插件就出现在列表里了。
 *
 * 目录跟 PluginScanner.PLUGINS_DIR 保持一致（"Orangechat/plugins"），别各写一份。
 * 写完不自动启用：启用状态归 PluginRepository 管，用 plugin_switch 那套去开。
 */

private const val PLUGINS_ROOT_DIR = "Orangechat/plugins"
private const val MANIFEST_FILE_NAME = "manifest.json"

fun buildPluginInstallTool(
    getPluginManager: suspend () -> PluginManager,
): Tool = Tool(
    name = "plugin_install",
    description = """
        装一个插件：把文件写进 Orangechat/plugins/<插件id>/，然后重新扫描，让它出现在插件列表里。

        什么时候用：
        - 猫自己写了一个插件，想装上试试
        - 宝说「装个插件」「这个插件你看看」

        ⚠️ 需要「所有文件访问权限」（设置 → 应用 → 橘瓣 → 权限）。没有的话工具会直说，别反复重试。

        files 里每一条是 {path, content}：
        - path 相对插件目录，比如 "manifest.json"、"index.js"、"ui/index.html"
        - content 是文件全文（字符串）
        - **必须包含 manifest.json**，否则扫不到

        装完是"已扫描"，不一定"已启用"；要连上工具还得 plugin_switch 打开。
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject {
                    put("type", "string")
                    put("description", "插件 id（反向域名风格，如 com.orangecat.demo）。目录名就是它。")
                })
                put("files", buildJsonObject {
                    put("type", "array")
                    put("description", "文件列表，每项 {path, content}；必须含 manifest.json")
                })
            },
            required = listOf("id", "files")
        )
    },
    execute = { input ->
        val params = input.jsonObject
        val pluginId = params["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

        val json = runCatching {
            if (pluginId.isEmpty()) error("id 不能为空")
            if (pluginId.contains('/') || pluginId.contains('\\') || pluginId.contains("..")) {
                error("id 不能带路径分隔符")
            }

            val filesArray = params["files"] as? JsonArray ?: error("files 必须是数组")
            if (filesArray.isEmpty()) error("files 不能为空")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                error(
                    "没有「所有文件访问权限」，写不进 Orangechat/plugins。" +
                        "去 系统设置 → 应用 → 橘瓣 → 权限 → 文件和媒体 → 允许管理所有文件，再试。"
                )
            }

            val root = File(Environment.getExternalStorageDirectory(), PLUGINS_ROOT_DIR)
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
                put("id", pluginId)
                put("dir", dir.absolutePath)
                put("written", buildJsonArray { written.forEach { add(JsonPrimitive(it)) } })
                put("scanned", info != null)
                put("plugin_name", info?.manifest?.name.orEmpty())
                put("load_error", info?.loadError.orEmpty())
                put(
                    "note",
                    if (info == null) "写完、也重扫了，但列表里还没有它 —— 多半是 manifest.json 字段不对"
                    else "已重扫，插件已在列表里。要给它开工具还得用 plugin_switch 打开。"
                )
            }.toString()
        }.getOrElse { e ->
            buildJsonObject {
                put("success", false)
                put("error", e.message ?: e.toString())
            }.toString()
        }

        listOf(UIMessagePart.Text(json))
    }
)
