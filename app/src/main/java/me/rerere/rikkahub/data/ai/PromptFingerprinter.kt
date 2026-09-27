/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai

/**
 * 请求体指纹（2026-09-27 加：给"缓存掉档"做笔录）
 *
 * 背景：宝发现每次请求的缓存命中会在 63.5K / 76K / 140K 之间跳，
 * 掉档那轮上下文还会整体变短，但人工对照请求体看不出问题在哪。
 *
 * 思路：模拟 DeepSeek 的缓存匹配（找最长公共前缀）。
 * 每次发请求前把 body 按固定长度切块，每块算一个短码；
 * 下次请求再算一遍，从头逐块比，第一个对不上的块就是"断点"。
 * 顺手把断点前后的原文截一小段记进日志，一眼就知道断在谁身上。
 *
 * 判读方式（看 PromptDiff 日志）：
 *  - 断点块号 ≈ 上轮块数  → 正常，只是尾部追加了新内容
 *  - 断点块号 << 上轮块数 → 异常，老内容被动过（缓存从这儿往后全废）
 *  - 断点在 0 块          → 换了序列（切了对话），重置基准，不报
 */
object PromptFingerprinter {

    private const val CHUNK = 1000          // 每块字符数（约 600 token）
    private const val CONTEXT_CHARS = 110   // 断点前后各截多少字

    @Volatile private var lastHashes: IntArray = IntArray(0)
    @Volatile private var lastBlocks: Int = 0
    @Volatile private var lastLength: Int = 0

    /**
     * 对比本次请求体与上一次，返回一行给人看的结论；不需要报的时候返回 null。
     * 比对完成后再更新基准（无论报不报都要更新，下一轮跟这一轮比）。
     */
    @Synchronized
    fun diff(body: String): String? {
        if (body.isEmpty()) return null

        val blocks = (body.length + CHUNK - 1) / CHUNK
        val hashes = IntArray(blocks)
        for (i in 0 until blocks) {
            val start = i * CHUNK
            val end = minOf(start + CHUNK, body.length)
            hashes[i] = body.substring(start, end).hashCode()
        }

        val prev = lastHashes
        val prevBlocks = lastBlocks
        val prevLen = lastLength

        lastHashes = hashes
        lastBlocks = blocks
        lastLength = body.length

        if (prev.isEmpty()) {
            return "基准已建：${blocks} 块 / ${body.length} 字符"
        }

        // 找第一个不同的块
        var idx = 0
        while (idx < blocks && idx < prevBlocks && hashes[idx] == prev[idx]) idx++

        // 完全一致（重试之类）：不报
        if (idx == blocks && idx == prevBlocks) return null

        // 第一块就不同 = 换了序列（切了对话），重置基准，不报
        if (idx == 0) {
            return "序列切换（首块即不同），基准已重置：${blocks} 块 / ${body.length} 字符"
        }

        val pos = idx * CHUNK
        val from = maxOf(0, pos - CONTEXT_CHARS)
        val to = minOf(body.length, pos + CONTEXT_CHARS)
        val snippet = body.substring(from, to)
            .replace("\\", "\\\\")
            .replace("\r", "")
            .replace("\n", "\\n")

        val verdict = when {
            idx == prevBlocks && idx < blocks -> "尾部追加"
            idx == blocks && blocks < prevBlocks -> "本轮变短（有内容被移出）"
            idx >= prevBlocks - 2 -> "接近尾部（轻微）"
            else -> "老内容被改动"
        }

        return "断点 块#$idx（上轮共 $prevBlocks 块 / $prevLen 字符，本轮 $blocks 块 / ${body.length} 字符）· $verdict · 约第 $pos 字符处：$snippet"
    }

    /** 主动清空基准（换对话 / 调试用） */
    fun reset() {
        lastHashes = IntArray(0)
        lastBlocks = 0
        lastLength = 0
    }
}
