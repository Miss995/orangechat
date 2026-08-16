# OrangeChat 进度账本 (PROGRESS.md)

> 用途：记录 orangechat 仓库（Miss995/main 分支）所有代码改动的版本/日期/内容/状态。
> 规矩：每次 commit 记一笔；搞代码前先翻本页确认现状；master 分支是原作者原版，绝不修改。
> 建立：2026-08-16（宝拍板，治橘仔代码失忆）

## 2026-08-16

### commit 7d4e36cc — 召回门控 + 日记独立 + 删最近聊天引用
- 文件：app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt
- 改动：
  1. 工具 prompt 段还原（撤掉 7571ebf6 的 name+description 强制注入）
  2. 日记摘要独立成段（## 日记，每天一篇，稳定前缀，不随搜索门控走）
  3. 外置库事件召回 / OB / Mem0 全部加「搜索意图门控」（hasSearchIntent：记得/上次/说过/搜/查查/回忆等）
  4. 删除最近聊天引用段（引用的是窗口名不是聊天记录）
  5. recallGate 状态：一次生成流程只召回一次，二次请求不重复判断
- 状态：✅ 已推 main；宝验证：门控触发正常；⏳ 缓存命中率对比 + 日记独立段待确认

### commit 7571ebf6 — 工具 name+description 强制注入稳定前缀
- 状态：✅ 已推（后被 7d4e36cc 撤销——证明大概率冗余，工具 schemas 本来就在 DS 前缀缓存里）

## 2026-08-15

### ⚠️ 事故记录：master 覆盖 main（重大教训）
- 橘仔 get_file_contents 不带 ref 默认拉 master（原作者原版）→ 改完推到 main 覆盖正确代码（丢 OB/Mem0）
- 已由 72c964d6 修复
- 铁律：①拉文件必须带 ref=refs/heads/main ②push 前先 list_branches ③只动 main

### commit 72c964d6 — 事件级召回重做（以 main 原版为底）
- 保留：STOP_WORDS / countMessages / vectorRecallMessages / AI拆词 / 请求编辑 / OB搜索型召回 / Mem0召回
- 新增：vectorRecallEvents + fetchEventSources + ExternalMemoryEvent
- 召回顺序：事件 → 聊天向量 → AI拆词兜底
- 状态：✅ 已推

### commit ceb7a62 / 6f5f7e45 / 1af1eb87 — fetch_chat_sources 查原文工具 + 去节流
- MemoryTools.kt 新增 fetch_chat_sources 工具
- GenerationHandler：去外置库节流 + 去 OB 节流，注册 fetch_chat_sources
- 1af1eb87：修复 6f5f7e45 残留右括号
- 状态：✅ 已推

## 2026-08-13~14（早期优化，凭记忆补录，commit 记录不全）
- 超时 8s→15s、召回节流 5 分钟、回显修复（排除最近10分钟）——commit 7c1ae9d7
- 外置记忆库聊天记录召回修复（isHistorical 时间解析兼容 Supabase ISO8601）——commit 4d38dfe
- 请求编辑选择性注入工具（RequestEditController + RequestEditDialog 工具勾选区 + GenerationHandler 过滤 params.tools）——commit 7ef553b0 / 782637dd
- 回填历史消息向量（backfill_embeddings.py，1万条全有向量）
- OB 改搜索型（不再自动 breath 浮现，改按用户消息内容 breath_search 按需召回，5分钟节流保留）——commit 812b5385
- 监工台 V1（外置库状态灯+召回体检+150条浏览+剔除+召回条数调节+诊断行）——commit 115f8042 等
- 监工台 V2+V3（OB/Mem0 MCP 状态灯 + 召回体检 + OB记忆目录 + Mem0全部记忆；mem0_mcp_server.py 加 list_memories/delete_memory）——commit 5359d273 / 72abdf5b / f743c045
- 召回质量·外置库（searchMessages 查询加工：整句优先→拆词合并→去虚词 STOP_WORDS；raw string 正则 + 显式 return）——commit a86924f2 / b9391835 / 5071238b / 886967fc
- 体检识别 OB 未匹配声明（未匹配/没有搜到/未找到等）——commit 2a2979a6
- 虚词表扩充（速速/快快/赶紧/马上/立刻/刚才/反正/突然…+语气+称呼）——commit 08b6e387
- 召回升级·向量优先 + AI 拆词兜底（QueryKeywordExtractor 调 SiliconFlow Qwen2.5-7B-Instruct 拆 2-5 关键词）——commit f894b9f3 / 4d5df6ab

## 待办（代码相关）
- 修时间注入 bug（注入时间比实际慢约1小时——根源待查 TimeReminderTransformer/消息时间戳）
- 查 OB 来源标记错位 bug（ob_sync_chat / V3 的 source_ranges 或来源拼写错位）
- 工具调取内容存记忆库（愿望清单 id68-⑥）
- 请求编辑 UI 升级（顺序/上下文数量/原文展开按钮——愿望清单 id68-②）
- 三库缓存去重（愿望清单 id68-③）
- OB/外置库全量召回（愿望清单 id68-④）
- 外置库直召降级保底（愿望清单 id68-⑤）
