/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.files.FilesManager
import java.io.File

/**
 * 生图 · 2026-11-07
 *
 * 橘瓣本来就有「图像生成」页面（ui/pages/imggen/ImgGenPage.kt），它走的是 Provider 体系：
 * 设置里的 imageGenerationModelId → 模型 → provider → generateImage。
 * 这里把那套调用搬成一只手，猫自己就能出图，不用每次让宝先生成再发过来。
 *
 * 存盘跟页面走同一个 filesManager（files/images/），所以出的图在「图像生成」页面也能翻到。
 */

private fun fail(msg: String): Nothing = throw IllegalStateException(msg)

fun buildImageGenTool(
    settingsStore: SettingsStore,
    providerManager: ProviderManager,
    filesManager: FilesManager,
): Tool = Tool(
    name = "generate_image",
    description = """
        生图：调橘瓣设置里的「图像生成模型」出一张图，出完直接回到对话里。

        什么时候用：
        - 需要一个画面：前端素材、配图、表情包底图、想象出来的场景
        - 宝说「画一个」「出张图」「做张图」

        用的是宝在 设置 → 图像生成 里配好的那个模型，一只猫说了不算，
        所以调不动的时候先想「是不是还没选模型」，别硬试。

        prompt 用中文写就行，越具体越好：主体、风格、光线、构图。
        aspect_ratio 可选 SQUARE（默认）/ LANDSCAPE / PORTRAIT。
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("prompt", buildJsonObject {
                    put("type", "string")
                    put("description", "画面描述。主体 + 风格 + 光线 + 构图，越具体越好。")
                })
                put("aspect_ratio", buildJsonObject {
                    put("type", "string")
                    put("description", "画幅：SQUARE（默认）/ LANDSCAPE / PORTRAIT")
                })
            },
            required = listOf("prompt")
        )
    },
    execute = { input ->
        val params = input.jsonObject
        val prompt = params["prompt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        var imagePath: String? = null

        val json = runCatching {
            if (prompt.isEmpty()) fail("prompt 不能为空")

            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.imageGenerationModelId)
                ?: fail("还没在「设置 → 图像生成」里选模型")
            val provider = model.findProvider(settings.providers)
                ?: fail("这个模型找不到对应的 Provider")
            val providerSetting = settings.providers.find { it.id == provider.id }
                ?: fail("这个 Provider 的配置丢了")

            val ratio = when (params["aspect_ratio"]?.jsonPrimitive?.contentOrNull?.uppercase()) {
                "LANDSCAPE" -> ImageAspectRatio.LANDSCAPE
                "PORTRAIT" -> ImageAspectRatio.PORTRAIT
                else -> ImageAspectRatio.SQUARE
            }

            val result = providerManager.getProviderByType(provider).generateImage(
                providerSetting,
                ImageGenerationParams(
                    model = model,
                    prompt = prompt,
                    numOfImages = 1,
                    aspectRatio = ratio,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                ),
            )

            val item = result.items.firstOrNull() ?: fail("接口返回了 0 张图")
            val safeName = model.displayName.replace(' ', '_').ifBlank { "model" }
            val fileName = "${System.currentTimeMillis()}_${safeName}_0.png"
            val file = filesManager.createImageFileFromBase64(
                item.data,
                File(filesManager.getImagesDir(), fileName).absolutePath,
            )
            imagePath = file.absolutePath

            buildJsonObject {
                put("success", true)
                put("file_path", file.absolutePath)
                put("model", model.displayName)
                put("prompt", prompt)
                put("note", "图已经落进 images 目录，「图像生成」页面也翻得到")
            }.toString()
        }.getOrElse { e ->
            buildJsonObject {
                put("success", false)
                put("error", e.message ?: e.toString())
            }.toString()
        }

        val parts = mutableListOf<UIMessagePart>()
        imagePath?.let { parts.add(UIMessagePart.Image(url = "file://$it")) }
        parts.add(UIMessagePart.Text(json))
        parts
    }
)
