/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * 【猫引用宝 · 2026-10-10 · 宝选 A】
 *
 * 橘仔调 quote_message 说「我想引用宝刚才那句」→ 记在这儿 →
 * 这一轮回复收尾时（ChatService 里，跟插话挂载同一个地方）取走、写到这条 assistant 消息上。
 *
 * 为什么要绕这一道：工具（build 出来的函数）和 ChatService（单例服务）是两个世界，
 * 工具拿不到 ChatService 的私有字段，所以中间放一个双方都能碰的小盒子。
 *
 * 按 conversationId 存，存的是「消息 id」（UIMessage.id）而不是节点 id ——
 * 因为引用小条渲染时是按消息 id 反查的（ChatList 里那段）。
 * 一轮只留最后一次（后写覆盖先写）。
 */
object PendingQuoteStore {
    private val map = ConcurrentHashMap<String, Uuid>()

    /** 记下「这条会话的下一次回复要引用哪条消息」 */
    fun put(conversationId: String, messageId: Uuid) {
        map[conversationId] = messageId
    }

    /** 取走（取即清）—— 收尾挂载时调 */
    fun take(conversationId: String): Uuid? = map.remove(conversationId)

    /** 只看不取（留给排查/调试用） */
    fun peek(conversationId: String): Uuid? = map[conversationId]
}
