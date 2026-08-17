# OrangeChat 进度账本 (PROGRESS.md)

> 用途：记录 orangechat 仓库（Miss995/main 分支）所有代码改动的版本/日期/内容/状态。
> 规矩：每次 commit 记一笔；搞代码前先翻本页确认现状；master 分支是原作者原版，绝不修改。
> 建立：2026-08-16（宝拍板，治橘仔代码失忆）

## 2026-08-17

### commit 5a63c756 — 修复 KeepAliveService 崩溃（ForegroundServiceDidNotStartInTimeException）
- 文件：app/.../service/KeepAliveService.kt + app/src/main/AndroidManifest.xml
- 原因：Android 15+ 对 dataSync 类型前台服务有每天累计 6 小时配额，保活服务 24h 常驻必耗尽；配额耗尽后 startForeground 抛异常，旧代码 catch 后 stopSelf，但系统仍认为「调用了 startForegroundService 却没调 startForeground」→ 5 秒后 ForegroundServiceDidNotStartInTimeException 炸整个进程（表现=宝打开 App 秒崩，一开就崩）
- 修复：dataSync → specialUse（无配额限制）；startForeground 增加不带类型兜底重试；companion 加 runningFlag 进程内标志位（避免每次冷启动 getRunningServices 拖慢主线程）；Manifest 同步声明 specialUse + PROPERTY_SPECIAL_USE_FGS_SUBTYPE
- 状态：✅ 已推 main（另一个橘瓣入口的橘仔修的，本橘仔补记账）；宝已装修复版确认能打开

### commit 9b7a6ce8 — 时间定位：搜索加时间过滤 date_from/date_to（宝定的记忆系统行动清单①）
- 文件：app/src/main/java/me/rerere/rikkahub/data/ai/TimeRangeParser.kt（新建）+ ExternalMemoryService.kt + GenerationHandler.kt
- 改动：
  1. 新增 TimeRangeParser：从用户消息解析时间范围（今天/昨天/N天前/最近N天/上周/上周X/X月X号/X月/上个月/这个月/今年/去年/前几天等，支持阿拉伯+中文数字）；解析不到返回空=不限时间（旧行为）
  2. ExternalMemoryService.vectorRecallEvents 加 dateFrom/dateTo 参数：按事件 source_date（yyyy-MM-dd 字典序=日期比较，与橘瓣同口径）本地过滤；无 source_date 的事件放行（保守不拦）
  3. GenerationHandler：外置库事件召回传时间范围；OB breath_search 调用带 date_from/date_to（工具原生支持）；日志带 timeRange
- 为啥：搜"上周说的那个事"只召回该时间范围的记忆，不整库乱捞（提升整体精确度）
- 状态：✅ 已推 main；⏳ 宝构建验证（搜"上周/昨天/X月X号"看召回是否限时；已实测「上周我们聊了什么」「前天Claude的事」双限定命中 ✅）

## 2026-08-16

### commit 59edf368 + bfad1cef — 按组裁剪缓存优化（宝原创脑洞）
- 文件：ai/src/main/java/me/rerere/ai/ui/Message.kt + app/.../data/model/Assistant.kt + app/.../ui/pages/assistant/detail/AssistantBasicPage.kt + app/.../data/ai/GenerationHandler.kt
- 改动：
  1. limitContext 加 groupSize 参数（默认 0=按条，旧行为不变）：起点向前对齐到组边界（向下取整到 groupSize 的倍数），新消息不足一整组时前缀不变 → DS 缓存命中
  2. Assistant 加 contextGroupSize 字段（默认 4）
  3. 基础设置加「上下文分组条数」开关（0/2/4/6/8/10，0=按条）
  4. GenerationHandler 调用 limitContext 传入 contextGroupSize
- 为啥：宝观察 20 条上下文实际 91 条（工具结果撑爆）；limitContext 按条滑动 → 每次前缀断 → 聊天段 79k 永远 miss（缓存命中率仅 29%）；按组滑动 → 5 回合内前缀稳定 → 缓存命中率↑（省 token，DS 明天涨价）
- 状态：✅ 已推 main；⏳ 宝构建验证（基础设置默认 4 条一组；请求日志对比缓存命中率）

### commit — 日记按天缓存（保缓存率 + 防 Supabase 慢/挂）
- 文件：app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt
- 改动：日记摘要段加本地按天缓存（SharedPreferences "diary_cache"，key=diary_{assistantId}_{今天}）——同一天只调一次 Supabase queryLatestSummaries，之后一整天直接用缓存；Supabase 拉不到时回退最近一次缓存（不阻塞、前缀稳定）
- 为啥：日记段注入不稳 → 前缀抖动 → DS 缓存命中率掉（宝洞察）；Supabase 偶尔慢/超时导致日记拉不回来
- 状态：✅ 已推 main；⏳ 宝构建验证（日志看 Diary [cache] / [supabase] / [fallback-cache]）

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
- 查 OB 来源标记错位 bug（ob_sync_chat / V3 的 source_ranges 或来源拼写错位）
- 工具调取内容存记忆库（愿望清单 id68-⑥）
- 请求编辑 UI 升级（顺序/上下文数量/原文展开按钮——愿望清单 id68-②）
- 三库缓存去重（愿望清单 id68-③）
- OB/外置库全量召回（愿望清单 id68-④）
- 外置库直召降级保底（愿望清单 id68-⑤）
- 按组裁剪验证（build 后对比缓存命中率，不行回滚成按条）
