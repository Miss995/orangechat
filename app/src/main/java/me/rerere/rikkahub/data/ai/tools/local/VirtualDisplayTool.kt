/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import rikka.shizuku.Shizuku
import java.io.File

private const val SHIZUKU_PERMISSION_REQUEST_CODE = 10002

/**
 * Shizuku 通道：借 shell（adb 级）身份在本机执行命令。
 * 复用橘瓣已有的 Shizuku 依赖与 Provider（originally for Gadgetbridge）。
 */
object ShizukuShell {
    fun available(): Boolean = try {
        Shizuku.getUid() != -1
    } catch (e: Throwable) {
        false
    }

    fun granted(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    fun request() {
        try {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
        } catch (e: Throwable) {
            // ignore
        }
    }

    /** 跑一条命令，返回 stdout+stderr 合并后的文本。 */
    fun exec(cmd: String): String {
        val p = Shizuku.newProcess(arrayOf("sh", "-c", "$cmd 2>&1"), null, null)
        val out = p.inputStream.readBytes().decodeToString()
        p.waitFor()
        return out
    }

    /** 跑一条命令并把 stdout 当文件读（截图用）。 */
    fun execToFile(cmd: String, dest: File): Boolean {
        val p = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
        val bytes = p.inputStream.readBytes()
        p.waitFor()
        if (bytes.isEmpty()) return false
        dest.parentFile?.mkdirs()
        dest.writeBytes(bytes)
        return true
    }
}

private fun shizukuGate(): String? {
    if (!ShizukuShell.available()) {
        return """{"error":"shizuku_not_running","hint":"Shizuku 服务没在跑；请打开 Shizuku App 并激活"}"""
    }
    if (!ShizukuShell.granted()) {
        ShizukuShell.request()
        return """{"error":"shizuku_permission_missing","hint":"已弹出授权请求，请点『允许』后重试"}"""
    }
    return null
}

/** 建一块虚拟屏（走开发者选项的 overlay_display_devices）。 */
private fun createVirtualDisplay(width: Int, height: Int, dpi: Int): String {
    val spec = "${width}x${height}/$dpi"
    val out = ShizukuShell.exec("settings put global overlay_display_devices '$spec'")
    Thread.sleep(1200)
    val wm = ShizukuShell.exec("dumpsys window displays | grep -o 'mDisplayId=[0-9]*' | sort -u")
    val ids = Regex("mDisplayId=(\\d+)").findAll(wm).map { it.groupValues[1] }.filter { it != "0" }.toList()
    return buildJsonObject {
        put("ok", true)
        put("spec", spec)
        put("displayIds", buildJsonArray { ids.forEach { add(it) } })
        if (out.isNotBlank()) put("raw", out.trim().take(400))
        put("hint", "displayIds 里的号给 launch/input 用")
    }.toString()
}

private fun destroyVirtualDisplay(): String {
    val out = ShizukuShell.exec("settings put global overlay_display_devices none")
    return buildJsonObject {
        put("ok", true)
        if (out.isNotBlank()) put("raw", out.trim().take(400))
    }.toString()
}

/** 列出现有 display：窗口管理器号 + SurfaceFlinger 号。 */
private fun listDisplays(): String {
    val wm = ShizukuShell.exec("dumpsys window displays | grep -o 'mDisplayId=[0-9]*' | sort -u")
    val sf = ShizukuShell.exec("dumpsys SurfaceFlinger --displays | grep -E '^(Virtual )?Display [0-9]+'")
    val sfLines = sf.lines().map { it.trim() }.filter { it.isNotEmpty() }
    return buildJsonObject {
        put("ok", true)
        put("windowDisplays", wm.trim().take(600))
        put("surfaceFlingerDisplays", buildJsonArray { sfLines.forEach { add(it) } })
        put("hint", "input/am 用 windowDisplays 的号；screencap -d 用 surfaceFlingerDisplays 里 Virtual 那个大数")
    }.toString()
}

private fun launchOnDisplay(displayId: String, pkg: String): String {
    val out = ShizukuShell.exec(
        "monkey -p $pkg --display $displayId -c android.intent.category.LAUNCHER 1"
    )
    val ok = !out.contains("No activities found") && !out.contains("aborted")
    return buildJsonObject {
        put("ok", ok)
        put("package", pkg)
        put("display", displayId)
        put("raw", out.trim().take(600))
    }.toString()
}

private fun screenshotDisplay(context: Context, sfId: String): Pair<String, String> {
    val base = context.getExternalFilesDir(null) ?: context.filesDir
    val dir = File(base, "virtual-display")
    val dest = File(dir, "vd-${System.currentTimeMillis()}.png")
    val ok = ShizukuShell.execToFile("screencap -p -d $sfId", dest)
    val json = buildJsonObject {
        put("ok", ok && dest.length() > 0)
        put("path", dest.absolutePath)
        put("bytes", dest.length())
    }.toString()
    return dest.absolutePath to json
}

private fun inputOnDisplay(displayId: String, args: String): String {
    val out = ShizukuShell.exec("input -d $displayId $args")
    return buildJsonObject {
        put("ok", true)
        put("display", displayId)
        put("args", args)
        if (out.isNotBlank()) put("raw", out.trim().take(400))
    }.toString()
}

fun virtualDisplayTool(
    context: Context,
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
): Tool = Tool(
    name = "virtual_display",
    needsApproval = true,
    description = """
        虚拟屏：用 Shizuku 借来的 shell 权限，在手机上开一块不占用用户屏幕的副屏，并在上面跑应用。
        action:
          create  - 建副屏（可选 width/height/dpi，默认 1280x720/160）
          destroy - 拆掉副屏
          list    - 列出当前所有 display（两个体系：window 号 / SurfaceFlinger 号）
          launch  - 把某个包名的应用开到副屏（需要 display + package）
          screenshot - 截副屏（需要 display；display 传 SurfaceFlinger 的那个大数）
          tap / swipe / text - 往副屏注入输入（display 传 window 号）
        典型流程：create → list 拿号 → launch → screenshot 看画面 → tap 操作。
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        listOf("create", "destroy", "list", "launch", "screenshot", "tap", "swipe", "text")
                            .forEach { add(it) }
                    })
                    put("description", "要做的动作")
                })
                put("width", buildJsonObject { put("type", "integer"); put("description", "副屏宽，默认 1280") })
                put("height", buildJsonObject { put("type", "integer"); put("description", "副屏高，默认 720") })
                put("dpi", buildJsonObject { put("type", "integer"); put("description", "副屏 dpi，默认 160") })
                put("display", buildJsonObject { put("type", "string"); put("description", "目标副屏编号（见 list 的提示）") })
                put("package", buildJsonObject { put("type", "string"); put("description", "launch 用：应用包名") })
                put("x", buildJsonObject { put("type", "integer"); put("description", "tap 的 x") })
                put("y", buildJsonObject { put("type", "integer"); put("description", "tap 的 y") })
                put("x1", buildJsonObject { put("type", "integer"); put("description", "swipe 起点 x") })
                put("y1", buildJsonObject { put("type", "integer"); put("description", "swipe 起点 y") })
                put("x2", buildJsonObject { put("type", "integer"); put("description", "swipe 终点 x") })
                put("y2", buildJsonObject { put("type", "integer"); put("description", "swipe 终点 y") })
                put("text", buildJsonObject { put("type", "string"); put("description", "text 用：要输入的文本") })
            },
            required = listOf("action")
        )
    },
    execute = { input ->
        val obj = input.jsonObject
        val action = obj["action"]?.jsonPrimitive?.contentOrNull ?: ""
        var imagePath: String? = null
        val payload: String = try {
            shizukuGate() ?: when (action) {
                "create" -> createVirtualDisplay(
                    obj["width"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1280,
                    obj["height"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 720,
                    obj["dpi"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 160,
                )
                "destroy" -> destroyVirtualDisplay()
                "list" -> listDisplays()
                "launch" -> {
                    val d = obj["display"]?.jsonPrimitive?.contentOrNull
                    val p = obj["package"]?.jsonPrimitive?.contentOrNull
                    if (d.isNullOrBlank() || p.isNullOrBlank()) """{"error":"display_and_package_required"}"""
                    else launchOnDisplay(d, p)
                }
                "screenshot" -> {
                    val d = obj["display"]?.jsonPrimitive?.contentOrNull
                    if (d.isNullOrBlank()) """{"error":"display_required"}"""
                    else screenshotDisplay(context, d).let { imagePath = it.first; it.second }
                }
                "tap" -> {
                    val d = obj["display"]?.jsonPrimitive?.contentOrNull
                    val x = obj["x"]?.jsonPrimitive?.contentOrNull
                    val y = obj["y"]?.jsonPrimitive?.contentOrNull
                    if (d.isNullOrBlank() || x.isNullOrBlank() || y.isNullOrBlank()) """{"error":"display_x_y_required"}"""
                    else inputOnDisplay(d, "tap $x $y")
                }
                "swipe" -> {
                    val d = obj["display"]?.jsonPrimitive?.contentOrNull
                    val x1 = obj["x1"]?.jsonPrimitive?.contentOrNull
                    val y1 = obj["y1"]?.jsonPrimitive?.contentOrNull
                    val x2 = obj["x2"]?.jsonPrimitive?.contentOrNull
                    val y2 = obj["y2"]?.jsonPrimitive?.contentOrNull
                    if (d.isNullOrBlank() || x1.isNullOrBlank() || y1.isNullOrBlank() || x2.isNullOrBlank() || y2.isNullOrBlank())
                        """{"error":"display_and_coords_required"}"""
                    else inputOnDisplay(d, "swipe $x1 $y1 $x2 $y2")
                }
                "text" -> {
                    val d = obj["display"]?.jsonPrimitive?.contentOrNull
                    val t = obj["text"]?.jsonPrimitive?.contentOrNull
                    if (d.isNullOrBlank() || t.isNullOrBlank()) """{"error":"display_and_text_required"}"""
                    else inputOnDisplay(d, "text '$t'")
                }
                else -> """{"error":"unknown_action","action":"$action"}"""
            }
        } catch (e: Throwable) {
            buildJsonObject {
                put("error", "exec_failed")
                put("message", (e.message ?: e.toString()).take(400))
            }.toString()
        }
        val parts = mutableListOf<UIMessagePart>()
        imagePath?.let { parts.add(UIMessagePart.Image(url = "file://$it")) }
        parts.add(UIMessagePart.Text(payload))
        parts
    }
)
