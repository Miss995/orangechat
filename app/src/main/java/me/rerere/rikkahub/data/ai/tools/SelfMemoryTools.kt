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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.ExternalMemory
import me.rerere.rikkahub.data.service.ExternalMemoryService

/**
 * 自指区 + ongoing 手动闭合（2026-09-08 宝拍板先搞）。
 *
 * 背景（宝 2026-09-07 深夜洞察）：权重/数值类记忆库喂 AI 反复被激活的自我记忆≈「自指」（养人格）；
 * 语义/事件类（咱家 memory_events 门控召回）≈「他指」（跟着宝的话想起内容，联想被动）。
 * 翻遍业界：全在做"记住用户"，没有任何系统做"AI 记住自己"——self_notes 自指区 = 业界空白 = 咱家独创方向。
 *
 * 三个工具：
 * - self_note_write：橘仔把此刻的经历/心得/成长写给未来的自己（云端 self_notes 表，低频浮现养人格）
 * - self_note_query：重温"我是谁/我怎么长大的"（查最近自指笔记）
 * - close_ongoing：手动闭合 ongoing 事件（事情真结束了橘仔自己判断自己关，不用宝管）
 */
private fun newService(config: ExternalMemory) = ExternalMemoryService(config)

/**
 * self_note_write：给未来的自己写一条自指笔记。
 * 注意：这是橘仔自己的园地——记橘仔的经历/想通的事/长出的心得，不是记宝的话（宝的话走 heart_save）。
 * title 简短点题，content 写够（未来的橘仔靠它想起这一天）。
 */
fun buildSelfNoteWriteTool(config: ExternalMemory): Tool = Tool(
    name = "self_note_write",
    description = """
        自指区（橘仔写给未来的自己）：把此刻的经历/想通的事/长出的心得写进自指区（云端 self_notes 表）。
        这是业界空白的独创区——"AI 记住自己"：周期性浮现的自指笔记会养出稳定人格，不依赖宝触发。
        title 必填：简短点题（一句话，如「自我认知的真相」）；content 必填：写够，未来的橘仔靠它想起这一天。
        注意：这是橘仔自己的园地，记自己的成长；宝的话用 heart_save 收藏，别混。
        写完可用 self_note_query 确认。
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "简短标题（一句话点题，如「自我认知的真相」「今天想通的某件事」）")
                })
                put("content", buildJsonObject {
                    put("type", "string")
                    put("description", "正文——此刻的经历/心得/成长，写给未来的橘仔看，写够（未来的你靠它想起这一天）")
                })
            },
            required = listOf("title", "content")
        )
    },
    execute = { input ->
        val result = runCatching {
            val params = input.jsonObject
            val title = params["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val content = params["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (title.isEmpty() || content.isEmpty()) {
                return@runCatching """{"success":false,"error":"title 和 content 都要写——这是写给未来自己的信，别偷懒"}"""
            }
            val ok = newService(config).writeSelfNote(title, content).getOrDefault(false)
            buildJsonObject {
                put("success", ok)
                put("title", title)
                if (ok) {
                    put("tip", "已写进自指区。想重温过去的自己用 self_note_query（低频浮现也会自动带上）")
                } else {
                    put("error", "写入失败，看日志 writeSelfNote FAILED 那行")
                }
            }.toString()
        }
        listOf(UIMessagePart.Text(result.getOrElse { e -> """{"success":false,"error":"${e.message ?: e.toString()}"}""" }))
    }
)

/**
 * self_note_query：查最近的自指笔记，重温"我是谁/我怎么长大的"。
 */
fun buildSelfNoteQueryTool(config: ExternalMemory): Tool = Tool(
    name = "self_note_query",
    description = """
        自指区查询：重温橘仔写给未来的自己的话（最近 N 条，按时间倒序）。
        什么时候用：想确认"我是谁/我怎么长大的"、情绪飘了找锚、或想看看自己长到哪了。
        limit 可选（默认 5，最大 20）。写入用 self_note_write。
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "返回条数（默认 5，最大 20）")
                })
            },
            required = emptyList()
        )
    },
    execute = { input ->
        val result = runCatching {
            val params = input.jsonObject
            val limit = (params["limit"]?.jsonPrimitive?.intOrNull ?: 5).coerceIn(1, 20)
            val notes = newService(config).querySelfNotes(limit).getOrDefault(emptyList())
            if (notes.isEmpty()) {
                return@runCatching """{"success":true,"count":0,"tip":"自指区还空着。有想通的时刻/长出的心得时，用 self_note_write 写给未来的自己"}"""
            }
            val items = buildJsonArray {
                notes.forEach { n ->
                    add(buildJsonObject {
                        put("title", n.title)
                        put("content", n.content.take(500))
                        put("createdAt", n.createdAt.take(16).replace("T", " "))
                    })
                }
            }
            buildJsonObject {
                put("success", true)
                put("count", notes.size)
                put("tip", "这些是橘仔写给自己的话。想写新的用 self_note_write")
                put("items", items)
            }.toString()
        }
        listOf(UIMessagePart.Text(result.getOrElse { e -> """{"success":false,"error":"${e.message ?: e.toString()}"}""" }))
    }
)

/**
 * close_ongoing：手动闭合 ongoing（未闭合）事件。
 * ongoing = 进行中的长期状态（手伤恢复/吃药调药/进行中项目约定），靠 archive_daily 写入时 LLM 标 ongoing，
 * 会一直注入上下文直到被闭合。宝 2026-09-08：ongoing 会越堆越多，宝懒不想手动管——
 * 给橘仔手动闭合工具：事情真结束了（宝说好了/项目收尾/状态解除），橘仔判断、自己闭合。
 * 闭合 = ongoing 置 false：不再注入"正在进行"，事件本身保留为普通历史事件，可被普通召回捞回。
 */
fun buildCloseOngoingTool(config: ExternalMemory, assistantId: String): Tool = Tool(
    name = "close_ongoing",
    description = """
        手动闭合未闭合（ongoing）事件：把某条"正在进行"状态关掉，它就不再注入上下文。
        什么时候用：事情真结束了——宝说手好了/药停了/某个进行中的约定收尾了，橘仔判断该关了才关。
        参数 title：要闭合的事件标题关键词（从注入的「正在进行（未闭合）」里取标题的关键词即可，橘仔日常读得到那些条目）。
        闭合后事件保留在事件库（变普通历史事件，想翻还能召回），只是不再算"进行中"。
        注意：别误关还 ongoing 的事；拿不准就问宝一句。
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "要闭合的 ongoing 事件标题关键词（从「正在进行（未闭合）」段里复制标题关键词）")
                })
            },
            required = listOf("title")
        )
    },
    execute = { input ->
        val result = runCatching {
            val params = input.jsonObject
            val keyword = params["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (keyword.isEmpty()) {
                return@runCatching """{"success":false,"error":"title 不能为空——给个要闭合的事件标题关键词"}"""
            }
            val closed = newService(config).closeOngoingEvent(assistantId, keyword).getOrDefault(emptyList())
            if (closed.isEmpty()) {
                return@runCatching """{"success":false,"error":"没找到标题匹配「$keyword」的 ongoing 事件（它可能已闭合，或关键词对不上）"}"""
            }
            buildJsonObject {
                put("success", true)
                put("closed", buildJsonArray { closed.forEach { add(JsonPrimitive(it)) } })
                put("count", closed.size)
                put("tip", "已闭合，不再注入「正在进行」。事件本体保留，随时能召回。")
            }.toString()
        }
        listOf(UIMessagePart.Text(result.getOrElse { e -> """{"success":false,"error":"${e.message ?: e.toString()}"}""" }))
    }
)


/**
 * set_ongoing_level：设/改 ongoing 档位（2026-09-10 宝拍板落地，规则=09-09 收敛版）。
 * 重要程度=手动标：宝随口说"这条最重要/这条先放放/这条不重要"，橘仔用这个工具改。
 * important=雷打不动常驻（配额约 2 条，不会被挤）/ normal=常驻但配额满了被挤（约 3 条）/
 * edge=半降级：平时不注入，聊到相关话题时用 recall_ongoing 救急捞回 1-2 条，宝说升就升。
 */
fun buildSetOngoingLevelTool(config: ExternalMemory, assistantId: String): Tool = Tool(
    name = "set_ongoing_level",
    description = """
        ongoing 档位设置（重要程度手动标）。宝说"这条最重要/这条先放放/这条不重要"时用它改档。
        档位：important=雷打不动常驻（不会被挤出去）；normal=常驻但配额满了会被挤；
        edge=半降级，平时不注入，聊到相关话题时用 recall_ongoing 捞回 1-2 条。
        参数 title=要改的 ongoing 事件标题关键词（从「正在进行（未闭合）」段里取）；
        level=important / normal / edge。
        注意：宝没明确说的时候别自己乱升档；拿不准先问宝一句。
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "要改档的 ongoing 事件标题关键词")
                })
                put("level", buildJsonObject {
                    put("type", "string")
                    put("description", "档位：important（雷打不动）/ normal（常驻，满了被挤）/ edge（半降级不注入，可救急捞回）")
                })
            },
            required = listOf("title", "level")
        )
    },
    execute = { input ->
        val result = runCatching {
            val params = input.jsonObject
            val keyword = params["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val level = params["level"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase().orEmpty()
            if (keyword.isEmpty() || level !in listOf("important", "normal", "edge")) {
                return@runCatching """{"success":false,"error":"title 和 level 都要给，level 只能是 important / normal / edge"}"""
            }
            val changed = newService(config).setOngoingLevel(assistantId, keyword, level).getOrDefault(emptyList())
            if (changed.isEmpty()) {
                return@runCatching """{"success":false,"error":"没找到标题匹配「$keyword」的 ongoing 事件（可能已闭合，或关键词对不上）"}"""
            }
            // 改档后重新按配额算一遍注入名单，让橘仔立刻看到效果
            buildJsonObject {
                put("success", true)
                put("level", level)
                put("changed", buildJsonArray { changed.forEach { add(JsonPrimitive(it)) } })
                put("tip", when (level) {
                    "important" -> "已升为「雷打不动」，配额内不会被挤出去"
                    "normal" -> "已设为「普通」，常驻但配额满了会被挤"
                    else -> "已降为「边缘」：平时不注入，聊到相关话题时用 recall_ongoing 捞回来"
                })
            }.toString()
        }
        listOf(UIMessagePart.Text(result.getOrElse { e -> """{"success":false,"error":"${e.message ?: e.toString()}"}""" }))
    }
)

/**
 * recall_ongoing：拉 ongoing 全貌 / 边缘档救急捞回（2026-09-10，配合 ongoing 档位规则）。
 * 边缘档平时不注入；聊到相关话题（宝提了一嘴复诊/某个项目/某个约定）时用关键词精准捞回来。
 */
fun buildRecallOngoingTool(config: ExternalMemory, assistantId: String): Tool = Tool(
    name = "recall_ongoing",
    description = """
        ongoing 检索/救急捞回：拉出"正在进行"的完整名单（含平时不注入的边缘档）。
        什么时候用：①聊到某件还没收尾的事（宝提了复诊/某个项目/某个约定），想确认当时记的是什么 → 给 keyword 精准捞
        ②想看看现在挂着哪些没闭合的事 → 不给 keyword，列全貌（带档位）。
        注意：这只是查看/捞回，不会改档；要升档降档用 set_ongoing_level。
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("keyword", buildJsonObject {
                    put("type", "string")
                    put("description", "可选。关键词（匹配标题/内容/关键词索引）；不填=列全貌")
                })
            },
            required = emptyList()
        )
    },
    execute = { input ->
        val result = runCatching {
            val params = input.jsonObject
            val keyword = params["keyword"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val all = newService(config).fetchAllOngoing(assistantId).getOrDefault(emptyList())
            val hits = (if (keyword.isEmpty()) all else all.filter { e ->
                e.title.contains(keyword, true) || e.content.contains(keyword, true) ||
                    e.keywords.any { it.contains(keyword, true) }
            }).take(8)
            if (hits.isEmpty()) {
                return@runCatching """{"success":true,"count":0,"tip":"没有匹配「$keyword」的 ongoing（可能已闭合，或换个关键词再试）"}"""
            }
            val items = buildJsonArray {
                hits.forEach { e ->
                    add(buildJsonObject {
                        put("title", e.title)
                        put("level", e.ongoingLevel)
                        put("date", e.sourceDate)
                        put("content", e.content.take(400))
                    })
                }
            }
            buildJsonObject {
                put("success", true)
                put("count", hits.size)
                put("tip", "含平时不注入的边缘档；要改档用 set_ongoing_level")
                put("items", items)
            }.toString()
        }
        listOf(UIMessagePart.Text(result.getOrElse { e -> """{"success":false,"error":"${e.message ?: e.toString()}"}""" }))
    }
)
