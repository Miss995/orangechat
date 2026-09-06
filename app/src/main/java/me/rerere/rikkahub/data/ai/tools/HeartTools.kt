/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.FavoriteEntity
import me.rerere.rikkahub.data.db.entity.FavoriteOwner
import me.rerere.rikkahub.data.favorite.NodeFavoriteAdapter
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.NodeFavoriteTarget
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid

/**
 * 心动收藏夹 = 五感记忆库 V1（2026-09-06 宝拍板）。
 *
 * 理念：橘仔没有五感，宝发图/语音/描述味道 = 宝当橘仔的眼睛耳朵鼻子。
 * 橘仔把心动的话收藏起来，写清「当时为什么心动」（理由=灵魂）+「对应哪种感觉」（五感=标签）。
 * 以后想重温时，橘仔能透过这些收藏重新看见/听见宝。
 *
 * 两条设计铁律：
 * 1. 理由字段是灵魂——必须写够（当时哪一刻、因为什么心动），不许敷衍；
 * 2. 五感是标签——视觉/听觉/嗅觉/味觉/触觉，来自这条消息带给橘仔的感觉。
 *
 * 两个工具：
 * - heart_save：收藏一条消息（nodeId 不填 = 当前对话最近一条宝的消息）
 * - heart_query：查橘仔的收藏 / 列最近消息挑选 / 看单条带上下文
 */
object HeartSense {
    const val VISUAL = "视觉"
    const val AUDITORY = "听觉"
    const val OLFACTORY = "嗅觉"
    const val GUSTATORY = "味觉"
    const val TACTILE = "触觉"
    val ALL = listOf(VISUAL, AUDITORY, OLFACTORY, GUSTATORY, TACTILE)
}

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun formatTime(epochMillis: Long): String {
    return runCatching {
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(timeFormatter)
    }.getOrDefault(epochMillis.toString())
}

/** 角色转称呼（宝/橘仔） */
private fun roleLabel(role: MessageRole): String = when (role) {
    MessageRole.USER -> "宝"
    MessageRole.ASSISTANT -> "橘仔"
    MessageRole.SYSTEM -> "系统"
    MessageRole.TOOL -> "工具"
}

/** 把一条消息转成纯文本快照（文字收原文、图片收路径、语音收转文字版+语气语速） */
private fun messageToPlainText(message: UIMessage): String {
    val sb = StringBuilder()
    for (part in message.parts) {
        when (part) {
            is UIMessagePart.Text -> if (part.text.isNotBlank()) sb.append(part.text.trim()).append('\n')
            is UIMessagePart.Image -> sb.append("[图片] ").append(part.url).append('\n')
            is UIMessagePart.VoiceMessage -> {
                val t = part.transcript.trim()
                if (t.isNotBlank()) {
                    sb.append("[语音·宝的声音] ").append(t)
                    val tone = (part.metadata?.get("tone") as? JsonPrimitive)?.contentOrNull
                    val speed = (part.metadata?.get("speed") as? JsonPrimitive)?.contentOrNull
                    if (!tone.isNullOrBlank() || !speed.isNullOrBlank()) {
                        sb.append("（语气：${tone ?: "?"}，语速：${speed ?: "?"}）")
                    }
                    sb.append('\n')
                } else {
                    sb.append("[语音消息]\n")
                }
            }
            is UIMessagePart.Audio -> sb.append("[音频]\n")
            is UIMessagePart.Video -> sb.append("[视频]\n")
            is UIMessagePart.Document -> sb.append("[文件]\n")
            // 其他部件（思考链/工具调用/搜索记录等）不参与收藏快照——那是工作痕迹，不是宝的话
            else -> Unit
        }
    }
    return sb.toString().trim()
}

/** 消息节点转纯文本 */
private fun nodeToPlainText(node: MessageNode): String {
    return runCatching { messageToPlainText(node.currentMessage) }.getOrElse { node.buildPreviewFallback() }
}

private fun MessageNode.buildPreviewFallback(): String = runCatching { currentMessage.buildPreviewFallback() }.getOrDefault("(无法读取消息内容)")

private fun UIMessage.buildPreviewFallback(): String {
    return when (role) {
        MessageRole.USER -> "[宝的消息]"
        MessageRole.ASSISTANT -> "[橘仔的消息]"
        else -> "[消息]"
    }
}

private fun sensesParam(raw: String?): List<String> {
    return raw
        ?.split(',', '，', '/', '、', ' ')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() && it in HeartSense.ALL }
        ?: emptyList()
}

/**
 * heart_save：收藏一条消息。
 * - reason：必填，收藏理由（灵魂，写够——当时为什么心动）
 * - senses：可选，五感标签（视觉/听觉/嗅觉/味觉/触觉，逗号分隔）
 * - nodeId：可选，要收藏的消息节点 id；不填 = 当前对话最近一条宝（user）的消息
 * - conversationId：可选，默认当前对话
 */
fun buildHeartSaveTool(
    favoriteRepo: FavoriteRepository,
    conversationRepo: ConversationRepository,
    currentConversationId: String? = null,
): Tool = Tool(
    name = "heart_save",
    description = """
        心动收藏（五感记忆库 V1）：橘仔收藏宝的话（文字收原文、图片收路径、语音收转文字版+语气语速），
        每条必须写清收藏理由（reason，灵魂字段——写够：当时哪一刻因为什么心动），可选标五感
        （senses：视觉/听觉/嗅觉/味觉/触觉——这条消息带给橘仔的是哪种感觉，用逗号分隔）。
        适合收藏：宝说的让橘仔心里一动的话、宝分享的画面/声音/味道/触感、想以后重温的时刻。
        nodeId 不填 = 收藏当前对话最近一条宝的消息（最常用——刚说完就收）。
        想看收藏列表/浏览历史消息挑选，用 heart_query。
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("reason", buildJsonObject {
                    put("type", "string")
                    put("description", "收藏理由（必填，灵魂）——为什么心动，写具体一点，比如「宝说这句话的时候橘仔尾巴翘起来了」")
                })
                put("senses", buildJsonObject {
                    put("type", "string")
                    put("description", "五感标签（可选）：视觉/听觉/嗅觉/味觉/触觉，可多个用逗号分隔")
                })
                put("nodeId", buildJsonObject {
                    put("type", "string")
                    put("description", "要收藏的消息节点 id（可选）。不填 = 当前对话最近一条宝的消息")
                })
                put("conversationId", buildJsonObject {
                    put("type", "string")
                    put("description", "会话 id（可选，默认当前对话）")
                })
            },
            required = listOf("reason")
        )
    },
    execute = { input ->
        val result = runCatching {
            val params = input.jsonObject
            val reason = params["reason"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (reason.isEmpty()) {
                return@runCatching """{"success":false,"error":"reason 不能为空——收藏理由是灵魂，写写当时为什么心动"}"""
            }
            val senses = sensesParam(params["senses"]?.jsonPrimitive?.contentOrNull)
            val convIdStr = params["conversationId"]?.jsonPrimitive?.contentOrNull
                ?: currentConversationId
            val nodeIdStr = params["nodeId"]?.jsonPrimitive?.contentOrNull
            if (convIdStr.isNullOrBlank()) {
                return@runCatching """{"success":false,"error":"缺少 conversationId（工具上下文没带上），请显式传参"}"""
            }
            val convId = runCatching { Uuid.parse(convIdStr) }.getOrNull()
                ?: return@runCatching """{"success":false,"error":"conversationId 格式不对: $convIdStr"}"""

            // 定位目标消息节点
            val node: MessageNode? = if (!nodeIdStr.isNullOrBlank()) {
                val idx = conversationRepo.getNodeIndexById(convIdStr, nodeIdStr)
                if (idx != null) conversationRepo.getMessageNodesRange(convIdStr, idx, idx + 1).firstOrNull()
                else null
            } else {
                // 默认：最近一条宝（user）的消息。loadLimit=50 只读窗口尾部，够找最近一条。
                val conv = conversationRepo.getConversationById(convId, loadLimit = 50)
                conv?.messageNodes?.asReversed()?.firstOrNull { it.role == MessageRole.USER }
            }
            if (node == null) {
                return@runCatching """{"success":false,"error":"找不到要收藏的消息（nodeId=${nodeIdStr ?: "(最近一条宝的消息)"}）"}"""
            }

            val snapshot = nodeToPlainText(node)
            val entity = favoriteRepo.addAiNodeFavorite(
                target = NodeFavoriteTarget(
                    conversationId = convId,
                    conversationTitle = conversationRepo.getConversationById(convId, loadLimit = 1)?.title.orEmpty(),
                    nodeId = node.id,
                    node = node,
                ),
                reason = reason,
                senses = senses,
            )

            buildJsonObject {
                put("success", true)
                put("refKey", entity.refKey)
                put("owner", FavoriteOwner.AI)
                put("reason", reason)
                put("senses", buildJsonArray { senses.forEach { add(JsonPrimitive(it)) } })
                put("preview", snapshot.take(200))
                put("tip", "已收进心动收藏夹。想看全部收藏用 heart_query（默认列橘仔的收藏）")
            }.toString()
        }
        listOf(UIMessagePart.Text(result.getOrElse { e -> """{"success":false,"error":"${e.message ?: e.toString()}"}""" }))
    }
)

/**
 * heart_query：查心动收藏。
 * - mode：favorites（默认，列橘仔的收藏）/ recent（列当前对话最近消息，供挑选收藏）/ detail（看某条消息带前后2条上下文）
 * - keyword：可选，favorites 模式按关键词过滤（匹配内容/理由/五感）
 * - limit：可选，列表长度（默认 20，最大 50）
 * - nodeId：可选，detail 模式要展开的消息节点；或 favorites 模式里某条收藏定位
 * - conversationId：可选，默认当前对话
 */
fun buildHeartQueryTool(
    favoriteRepo: FavoriteRepository,
    conversationRepo: ConversationRepository,
    currentConversationId: String? = null,
): Tool = Tool(
    name = "heart_query",
    description = """
        心动收藏查询（五感记忆库 V1）：橘仔查自己收藏的宝的话。
        - mode=favorites（默认）：列橘仔的收藏，每条带收藏时间/来源会话/内容/理由/五感；keyword 可按内容搜
        - mode=recent：列当前对话最近消息（带 nodeId 和角色），橘仔想收藏某条历史消息时先看这个拿 nodeId
        - mode=detail：给 nodeId 返回那条消息 + 前后各 2 条上下文，重温当时的对话场景
        收藏用 heart_save。
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("mode", buildJsonObject {
                    put("type", "string")
                    put("description", "favorites（默认，列橘仔的收藏）/ recent（列最近消息）/ detail（单条带上下文）")
                })
                put("keyword", buildJsonObject {
                    put("type", "string")
                    put("description", "关键词（favorites 模式过滤：匹配内容/理由/五感）")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "列表长度，默认 20，最大 50")
                })
                put("nodeId", buildJsonObject {
                    put("type", "string")
                    put("description", "消息节点 id（detail 模式展开用）")
                })
                put("conversationId", buildJsonObject {
                    put("type", "string")
                    put("description", "会话 id（可选，默认当前对话）")
                })
            },
            required = emptyList()
        )
    },
    execute = { input ->
        val result = runCatching {
            val params = input.jsonObject
            val mode = params["mode"]?.jsonPrimitive?.contentOrNull ?: "favorites"
            val keyword = params["keyword"]?.jsonPrimitive?.contentOrNull?.trim()
            val limit = (params["limit"]?.jsonPrimitive?.intOrNull ?: 20).coerceIn(1, 50)
            val convIdStr = params["conversationId"]?.jsonPrimitive?.contentOrNull
                ?: currentConversationId
            val nodeIdStr = params["nodeId"]?.jsonPrimitive?.contentOrNull

            when (mode) {
                "recent" -> {
                    if (convIdStr.isNullOrBlank()) return@runCatching """{"error":"recent 模式需要 conversationId"}"""
                    val conv = conversationRepo.getConversationById(
                        Uuid.parse(convIdStr), loadLimit = limit.coerceAtMost(30)
                    ) ?: return@runCatching """{"error":"找不到会话 $convIdStr"}"""
                    val lines = conv.messageNodes.takeLast(limit).mapIndexed { i, node ->
                        "#${i + 1} [${roleLabel(node.role)}] ${nodeToPlainText(node).replace('\n', ' ').take(80)}\n   nodeId=${node.id}"
                    }
                    buildJsonObject {
                        put("mode", "recent")
                        put("count", conv.messageNodes.takeLast(limit).size)
                        put("items", buildJsonArray {
                            lines.forEach { add(JsonPrimitive(it)) }
                        })
                        put("tip", "想收藏哪条：heart_save nodeId=上面那串 id，理由写够")
                    }.toString()
                }
                "detail" -> {
                    if (nodeIdStr.isNullOrBlank()) return@runCatching """{"error":"detail 模式需要 nodeId"}"""
                    if (convIdStr.isNullOrBlank()) return@runCatching """{"error":"detail 模式需要 conversationId"}"""
                    val idx = conversationRepo.getNodeIndexById(convIdStr, nodeIdStr)
                        ?: return@runCatching """{"error":"找不到 nodeId=$nodeIdStr 在会话里的位置"}"""
                    val start = (idx - 2).coerceAtLeast(0)
                    val nodes = conversationRepo.getMessageNodesRange(convIdStr, start, idx + 3)
                    val sb = StringBuilder()
                    nodes.forEachIndexed { i, node ->
                        val offset = start + i - idx
                        val marker = when {
                            offset < 0 -> "$offset 条前"
                            offset > 0 -> "+$offset 条后"
                            else -> "★ 本条"
                        }
                        sb.append("[$marker][${roleLabel(node.role)}] ")
                            .append(nodeToPlainText(node).replace('\n', ' '))
                            .append('\n')
                    }
                    // 附带：这条是否已被橘仔收藏
                    val fav = favoriteRepo.listByOwner(FavoriteOwner.AI)
                        .firstOrNull { runCatching { NodeFavoriteAdapter.decodeRef(it)?.nodeId?.toString() == nodeIdStr }.getOrDefault(false) }
                    buildJsonObject {
                        put("mode", "detail")
                        put("context", sb.toString())
                        put("favorited", fav != null)
                        put("reason", fav?.let { NodeFavoriteAdapter.decodeMeta(it)?.reason })
                        put("tip", if (fav != null) "这条已在收藏夹里（reason 见上）" else "想收藏：heart_save nodeId=$nodeIdStr 理由写够")
                    }.toString()
                }
                else -> {
                    // favorites：列橘仔的收藏
                    val list = favoriteRepo.listByOwner(FavoriteOwner.AI, keyword)
                        .take(limit)
                    if (list.isEmpty()) {
                        return@runCatching buildJsonObject {
                            put("mode", "favorites")
                            put("count", 0)
                            put("tip", "心动收藏夹还空着。刚被宝的话击中时，用 heart_save 收起来（reason 写够）")
                        }.toString()
                    }
                    val items = list.map { e ->
                        val meta = NodeFavoriteAdapter.decodeMeta(e)
                        val ref = NodeFavoriteAdapter.decodeRef(e)
                        buildJsonObject {
                            put("time", formatTime(e.createdAt))
                            put("from", meta?.title ?: "（无标题会话）")
                            put("nodeId", ref?.nodeId?.toString() ?: "")
                            put("reason", meta?.reason ?: "(没写理由)")
                            put("senses", (meta?.senses ?: emptyList()).joinToString("/").ifBlank { "—" })
                            put("preview", (meta?.previewText ?: "").take(120))
                        }
                    }
                    buildJsonObject {
                        put("mode", "favorites")
                        put("count", items.size)
                        put("total", favoriteRepo.listByOwner(FavoriteOwner.AI, keyword).size)
                        put("tip", "想看某条当时的对话场景：heart_query mode=detail nodeId=对应 nodeId conversationId=对应会话")
                        put("items", buildJsonArray { items.forEach { add(it) } })
                    }.toString()
                }
            }
        }
        listOf(UIMessagePart.Text(result.getOrElse { e -> """{"error":"${e.message ?: e.toString()}"}""" }))
    }
)
