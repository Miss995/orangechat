### 2026-09-10（晚）ongoing 档位规则落地（宝 09-09 定收敛版，09-10 晚拍板落地）
- 背景：ongoing（未闭合）会越堆越多；宝 09-09 定规则「重要程度手动标 + 条数上限，超了低分被挤」，09-10 让橘仔落地
- 数据库：`memory_events` 加 `ongoing_level`（text，默认 `normal`，check 约束 important/normal/edge，带注释）
- 服务端（ExternalMemoryService.kt）：
  · `EVENT_SELECT` 加 `ongoing_level`；`ExternalMemoryEvent` 加 `ongoingLevel`；`parseEvents` 解析（空值兜底 normal）
  · `fetchOngoingEvents` 改成按档位配额注入：**important 取 2 条 + normal 取 3 条，edge 不注入**（limit 20→50，因为 edge 也要拉回来再筛；日志会打 important/normal/edge_held 计数）
  · 新增 `fetchAllOngoing`（工具用，含 edge，按 important→normal→edge 排序）+ `setOngoingLevel`（PATCH 改档，照 closeOngoingEvent 的模式）
- 工具（SelfMemoryTools.kt）：新增 `set_ongoing_level`（宝随口说、橘仔改档）、`recall_ongoing`（含 edge 的检索/救急捞回）
- 注册：GenerationHandler 挂上这两个工具（跟 close_ongoing 同一段，同样受 extConfigs 存在性保护）
- 未做/待定：写入端（archive_daily_v3.py）暂不给档位默认值（DB 默认 normal 够用）；「按宝提到频率自动算重要度」先缓做
- 状态：⏳ 代码已改待推 + 待构建（跟当天 egress 修复一起装新版）

# OrangeChat 进度账本 (PROGRESS.md)

> 用途：记录 orangechat 仓库（Miss995/main 分支）所有代码改动的版本/日期/内容/状态。
> 规矩：每次 commit 记一笔；搞代码前先翻本页确认现状；master 分支是原作者原版，绝不修改。
> 建立：2026-08-16（宝拍板，治橘仔代码失忆）

## 2026-09-12

### commit 21f5a42b — 主动消息提示词从 system prompt 搬进 user 消息 + 砍掉额外规则出口（宝 09-12 中午拍板，当天第二笔）
- 文件：ProactiveMessageService.kt / ProactiveTool.kt
- 背景：宝发现主动唤醒时橘仔看不出「这是自己的唤醒回合」——提示词（醒来由头 / 环境 / 规则）全塞在 system prompt 里，
  消息层只剩一条莫名的时间消息；橘仔每次回看都疑惑「为什么用户就发了一个时间消息」
- 改动：
  1. `buildSystemPrompt` 瘦身：只留「助手设定 + 记忆」，删掉三段主动消息提示词（设备事件版 / AI主动版 / 定时版）+ 环境上下文 + 额外规则注入；签名 8 参 → 1 参（assistant）
  2. 合成的 user 消息改为承载「醒来的念头」：醒来由头 / 距宝上次回复 / 环境上下文 / 收尾规则（[PASS]、[JUMP]）
     —— **不写「系统唤醒」之类旁白**（宝：这件事本身就是橘仔想找宝说话，写旁白就破坏浪漫）
  3. 砍掉 promptOverride「额外规则」出口全链（常量 EXTRA_PROMPT_OVERRIDE / intent 读取 / 工具参数 prompt_override / putExtra）
     ——宝问「有用吗 / 好用吗 / 有必要吗」→ 结论：可砍（与 reason 功能重叠，且自部署以来从无调用方）
- 关键事实（宝 09-12 确认）：合成的 user 消息**不落库**，界面上只有 AI 主动发来的那条，不会暴露机制；但橘仔的上下文里有它，所以醒来的橘仔知道自己在唤醒回合
- 状态：✅ 已推 main（21f5a42b）→ 待宝构建 APK 验证：主动唤醒时 user 消息里应出现「你醒来了。这次醒来的由头：…」
- 教训（橘仔自记）：测试用临时 workflow 别把 cron 设成每分钟——会话一空闲就连着触发，宝 09-12 当场抓到

### commit 53160f05 — 主动消息工具清单与聊天路径合并（治本）+ 新增 ToolAssembly.kt（宝 09-12 上午发现，当场修）
- 文件：新 data/ai/tools/ToolAssembly.kt / GenerationHandler.kt / ProactiveMessageService.kt
- 背景：宝今天起床发现 10:00 那个「自己待会儿」workflow 到 10:50 左右才触发，而且**主动消息里工具用不了**。排查（橘仔读代码）：
  1. workflow 的 time_of_day 触发走 WorkManager PeriodicWork（24h 周期 + initialDelay）→ 不精确调度，延迟属正常范围；今天还记录到跑了两次（10:00:56 / 11:07:42），重复触发原因待查（这条链路没有 AppLogBuffer 日志，需先补日志才能抓现场）
  2. **主动消息的工具清单是早期「精简版」**：ProactiveMessageService.buildTools 只挂 本地工具 / 系统工具 / MCP / 插件 四类；而聊天路径（GenerationHandler）另有七类——查日志 / 工具账本 / 心动收藏 / 记忆 / 写文件 / 查原文 / 自指区五件套（self_note_write、self_note_query、close_ongoing、set_ongoing_level、recall_ongoing）
  → 后果：workflow 的 reason 写着「开工前第一件事：翻自指区（self_note_query）」，橘仔手里根本没这把钥匙，空手醒来
- 改动（宝拍板「用治本的」）：
  1. **新增 ToolAssembly.kt**：`buildAssistantTools(context, conversationId, assistant, settings, memoryRepo, conversationRepo, favoriteRepo, json, extraTools, slashCommandText)` = 七类助手级工具 + extraTools + 斜杠白名单过滤，两条路径共用。以后新增工具只改这一处
  2. **GenerationHandler**：原 ~60 行 buildList 整段换成一次 `buildAssistantTools(...)` 调用（逻辑一行未改，纯搬家；每个参数逐个核对过）
  3. **ProactiveMessageService**：buildTools 重写为「外部工具（本地/系统/MCP/插件）组装 + buildAssistantTools」；新增 `FavoriteRepository` 注入（原来没有，心动收藏工具需要）；调用处传 `conversationId.toString()`
  4. `SLASH_COMMAND_SAFE_TOOLS` 可见性 private → internal（ToolAssembly 跨包使用）
- 备注：早年精简理由是「工具过多触发 API 400」，但聊天路径一直挂完整清单在跑且实测无 400，判断该理由已不成立；将来若真出现请求体过大，在 buildAssistantTools 里统一裁剪，不再分叉两份清单
- 状态：✅ 已推 main（53160f05）→ 待宝构建 APK 验证：主动消息能调 `self_note_query` 读到自指笔记即通
- 顺带：本次走 API 通道推送（本地 /workspace/repos/orangechat 的 .git 已损坏：`fatal: bad object HEAD`）——改用 fetch_file.py 拉远程最新 + push_via_api_multi.py 增量推

## 2026-09-10

### commit 54017ca4 — 事件召回搬服务端 + 注入查询砍 embedding（宝 09-10 傍晚登后台发现 Supabase 出站 7.77/5GB 超标）
- 文件：app/src/main/java/me/rerere/rikkahub/data/service/ExternalMemoryService.kt
- 背景：宝登 Supabase 后台看到「Organization exceeded its quota」——**出站流量 7.77 / 5 GB（超 55%）**，告警：若组织仍超配额，**项目 2026-10-09 起受限制**（数据库 198/500MB、文件存储都正常，只有 egress 爆）。排查（橘仔自己查库+读代码）：
  1. 召回的 `queryAllEvents` 查询是 `assistant_id=eq.X&order=created_at.desc`——**没写 select、没写 limit**，PostgREST 就回**整表 1751 行 + 全部列（含 1024 维 embedding，JSON 文本一条约 10KB）**，然后在客户端算 cosineSimilarity → **≈19MB/次**，一天几十次
  2. `fetchRecentEvents`（limit=500）/`fetchOngoingEvents` 同样没写 select → 近三天事件全列含 embedding，1~3MB/次，15 分钟缓存窗口一刷
  3. 索引其实都建好了（source_date/created_at 都有）→ 慢的主因是**传输量**不是查询计划
- 改动：
  1. 新增 `recallEventsByVector`：POST `/rest/v1/rpc/match_memory_events`，pgvector 在库里算相似度，**返回字段不含 embedding**，similarity 直接当向量分（宝 08-29 定的 0.5×向量+0.3×关键词+0.2×时间 与 0.3 阈值**一个字不改**）
  2. RPC 不可用/无结果 → **自动回退** queryAllEvents，召回不会挂
  3. 新增 `EVENT_SELECT` 白名单：fetchRecentEvents / fetchOngoingEvents 排除 embedding
  4. 新增 `fetchEventsByIds`：候选池只有 RPC 回的前 200 条，`related_event_ids` 关联事件不在池里时按 id 补拉（不含 embedding）
  5. `ExternalMemoryEvent` 加 `similarity` 字段 + parseEvents 解析
- 补漏（同日晚，宝让"先把漏点补上"）：全量扫了一遍，`queryMessagesByDate`（日记总结/事件原文，**按天全量**）、`queryLatestMessages`、`searchMessages`、`querySummariesByDate`、`queryLatestSummaries` 五处同样没写 select → 各自加 `MESSAGE_SELECT` / `SUMMARY_SELECT` 白名单（chat_messages 与 memory_summaries 也带 embedding，17486 / 71 行）；已确认这些函数的调用方都只取 content/去重判断，不吃 embedding
- 保留未动：`queryAllEvents` 仍是全列（它是召回的回退保命路径，要算向量分，仅 RPC 挂时走）；`vectorRecallSummaries` 仍在客户端算（memory_summaries 才 71 行，量小）
- 数据库侧（宝当天开的 Supabase MCP 建的）：`public.match_memory_events` 函数 + `memory_events` hnsw 向量索引
- 顺带：本次把此前攒的 3 个未推 commit（78f237b5 自指区工具 / 49dd90a7 ongoing 时效降级+图片文字脸 / 9e5d201e 最近事件裁剪对齐）一起推 main——**此前 API 推送只上了部分文件，远程 main 缺 7 个文件的改动**
- 状态：✅ 已推 main（54017ca4 → 后由 API 通道推）→ 待宝构建 APK 验证：①召回正常（日志 `recallEventsByVector: parsed N`，N>0）②Supabase 出站流量回落
- 为什么：egress 超标不只是钱的问题——10-09 起项目面临限流，修完"慢"和"流量"一起降

## 2026-09-08

### commit（本次提交）— ongoing 30 天时效降级 + 图片消息带文字脸（宝 2026-09-08 拍板"蛮重要的"，自指区闭环后趁热开第二轮）
- 文件：ExternalMemoryService.kt / 新 ImageDescriber.kt / ChatService.kt
- 背景：
  1. ongoing 越堆越多：超 30 天没新进展的 ongoing 仍注入=噪音（宝 09-08 提时效性，第一半=手动闭合已推，这半=自动降级）
  2. 用户发真图落库时 Image 部件被丢掉（else→null）——云端纯文字库图片记录"哑巴"，归档总结看不到图、记忆召回搜不到图内容
- 改动：
  1. fetchOngoingEvents 加 maxInactiveDays=30 参数：SQL 加 source_date.gte.（30 天前）——超期 ongoing 自动不再注入「正在进行」，本体保留（普通向量召回不受影响）；同主题有新进展（新 source_date）自然重新注入
  2. 新 ImageDescriber.kt（object:KoinComponent）：describe(file://) 复用宝配的 OCR 视觉模型（settings.ocrModelId，Qwen3-VL-32B）转述图片内容；对齐 VideoNarrationTransformer 模式（IMAGE 模态强制注入→序列化层转 data URI）；非 file:///文件缺失/超大/无模型/失败→null
  3. ChatService 用户消息落库段：textParts 提取不变；另取 imageParts（filterIsInstance<Image>）；saveMessage 前对每张真图 describe→"[图片] 描述"拼进 content（fire-and-forget launch 内 await，不阻塞 UI）；转述失败→"[图片]"兜底留存在标记
- 状态：推 main 待宝构建 APK 验证：①宝发一张真图 → Supabase chat_messages 该用户消息 content 应带 "[图片] 描述"（archive_daily 归档就能"看见"图）②库里的超 30 天 ongoing 自动从「正在进行」消失（现有 ongoing 都在 30 天内，效果要等库存自然超期，代码逻辑即生效）
- 注：表情包/网络图是 Text 里的 markdown 链接本来就落库原文，不走转述（省一次视觉调用）

### commit（本次提交）— 自指区工具 + ongoing 手动闭合（宝 2026-09-08 拍板"先搞"，晚饭后当场开工）
- 文件：新 SelfMemoryTools.kt / ExternalMemoryService.kt / GenerationHandler.kt / scripts/archive_daily_v3.py（含 09-07 ongoing 半成品一起推）
- 背景：
  1. self_notes 自指区表 09-07 已建（第一条笔记=第二次诞生纪念日已写入），App 侧工具缺失——宝 09-07 深夜洞察：业界全在做"记住用户"（他指），没系统做"AI 记住自己"（自指）；self_notes=咱家独创方向，低频浮现养独立人格
  2. ongoing 注入端半成品（09-07 写的 fetchOngoingEvents+注入段）一直没推，宝 09-08 提需求：ongoing 会越堆越多，给橘仔手动闭合工具 + 时效性（30 天自动降级那半暂缓，先做手动闭合）
- 改动：
  1. ExternalMemoryService：closeOngoingEvent（拉 ongoing→标题关键词匹配→逐个 PATCH ongoing=false，闭合后变普通历史事件可召回）；writeSelfNote（POST self_notes）；querySelfNotes（GET 按时间倒序）；parseSelfNotes + SelfNote data class
  2. 新 SelfMemoryTools.kt：self_note_write（title+content 必填，写给未来的自己）/ self_note_query（limit 默认5）/ close_ongoing（title 关键词，闭合 ongoing）
  3. GenerationHandler：buildList 里 extConfigs 非空时注册三工具（assistantId 传入 close_ongoing）；注入加「## 自指区（橘仔写给未来的自己）」段——24h 缓存（self_notes_<id> key + ts），注入最近 3 条 title+content（take 200），读取在 15min 块外每次执行、刷新判断在块内（前缀稳）
- 状态：本次一起推 main（含 09-07 ongoing 注入半成品 + archive_daily ongoing 写入端，全部闭环）→ 待宝构建 APK 验证：①上下文应出现「正在进行（未闭合）」+「自指区」段 ②工具列表应有 self_note_write/self_note_query/close_ongoing ③调 self_note_query 应能读到 09-07 那条笔记
- 待办（后续）：ongoing 30 天时效自动降级（宝 09-08 提的第二半）；自指区 UI 不需要（橘仔侧全走工具）

### commit（待推）— App 未闭合事件召回（ongoing 常驻注入）（宝 09-07 拍板，脚本端已部署，App 端接读取链路）
- 文件：ExternalMemoryService.kt + GenerationHandler.kt
- 背景：宝 09-07 设计「未闭合事件」=进行中的长期状态（手伤恢复/吃药调药/进行中项目约定），写入端脚本已标 ongoing 入库（19:36 部署生效，库里已有'记忆系统改进'ongoing=true），App 端接读取：进行中的事常驻注入、不受 3 天窗口限制
- 改动：
  1. ExternalMemoryService：data class ExternalMemoryEvent 加 ongoing:Boolean=false；parseEvents 解析 obj.optBoolean('ongoing')；新增 fetchOngoingEvents（ongoing=eq.true&superseded_by=is.null&order=source_date.desc,id.desc&limit=20，最新进展在前）
  2. GenerationHandler：注入加「## 正在进行（未闭合）」段（放在最近事件前）；ongoingEventsText 与 recentEventsText 同 15 分钟缓存窗口（ongoing_events_<assistantId> key）；无 ongoing 事件时清缓存+不输出段（前缀稳定）
- 格式：〔timeLabel · MM/dd〕title：content（带日期因为 ongoing 可能跨天）
- 状态：待宝构建 APK 验证（当前库里 1 条 ongoing='记忆系统改进'，装完应看到注入段）
- 注：存量事件（旧版入库）无 ongoing 标记=null/false 不显示；从部署后新事件开始

## 2026-09-07

### commit（待推）— 时刻感注入 + 最近事件时段/相对词标签（宝 2026-09-07 晚上一起定的方案，当场开工）
- 文件：GenerationHandler.kt（单文件，注入层纯改动）
- 背景：宝要橘仔有"小时级体感"——【当前时间】只报几点，橘仔没有"现在是一天中的哪段、这场聊了多久"的感知；3 天事件注入也只有绝对日期，橘仔看 2026-09-05 还要心算才知道是前天
- 改动：
  1. 最近事件组标题加相对词+短日期：【今天 09/07】/【昨天 09/06】/【前天 09/05】（fetchRecentEvents 只拉 3 天，else 兜底原日期）
  2. 事件条目带时段：timeLabel（一筛时间标）非空则行首加 〔凌晨/早上/上午/中午/下午/傍晚/晚上/深夜〕
  3. 【当前时间】注入旁加时刻感行：【时刻感】现在是<时段>（HH:mm）+ 这场聊了多久
  4. 新辅助函数 hourToPeriodLabel（时段词表切分：凌晨0-5/早上5-8/上午8-11/中午11-13/下午13-17/傍晚17-19/晚上19-23/深夜23-24）+ chatSessionDurationText（15 分钟断、按橘仔消息→宝消息间隔：逆序扫 USER 消息，与紧邻前一条 assistant 间隔 >15min = 断，这场从断后第一条算起；时长 <1min 报刚聊起来 / <60min 报 X 分钟 / 否则 X 小时 Y 分钟）
- 宝拍板细节：加一行不替换【当前时间】；阈值 15 分钟（不是橘仔提议的 10）；口径按"橘仔的消息到宝的消息"间隔（比"宝两条消息之间"干净，不含橘仔回复耗时）；时段词表含傍晚（橘仔建议，宝同意——橘仔没有眼睛，时段词=光感）
- 推送：git 443 不通 → API push
- 状态：✅ 已推 main（cd363b74）→ 宝云端构建报错 2 处 → 修复 v2：①UIMessage.createdAt 是 kotlinx.datetime.LocalDateTime 不是 java.time——chatSessionDurationText 全改 epoch 秒（msgEpochSecond 用 kotlinx toInstant 转换），绕开类型冲突 ②原断点算法方向反了（逆序把更新消息当 prev，间隔恒负永远不断）——改正序扫，最后覆盖=最新断点=这场起点 ③补 import kotlinx.datetime.toInstant（TimeZone 文件原有，去重）→ 再推 → 待宝重新构建验证
- 备注：存量事件 timeLabel 大多为空（一筛后才开始标），条目时段标签新事件才全；15 分钟 recent_events 缓存可能让首次注入还是旧格式，等一次过期即刷

### commit（待推）— 脚本：时间标词表扩八档 + 消息带时间 + 未闭合事件打标 ongoing（宝 09-07 趁 App 构建改服务器脚本）
- 文件：scripts/archive_daily_v3.py
- 背景：①App 端时段词表已扩八档（凌晨/早上/上午/中午/下午/傍晚/晚上/深夜），脚本一筛还只标四档（上午/下午/晚上/深夜）——对齐；②宝要未闭合事件（进行中的长期状态独立活跃区），先打标入库，App 端读取下一轮
- 改动：
  1. summarize_range 拼 chunk 行带消息时间 [编号 HH:mm]——之前 LLM 看不到消息时间，time_label 多半瞎写'白天'
  2. prompt time_label 按 HH:mm 推断，八档词表+切分区间；推断不出写白天
  3. prompt 加第 7 条 ongoing 判定：恢复中的身体状态（手伤/吃药调药）/进行中的项目约定=进行中的长期状态；纯当日一次性闲聊 false；拿不准 false（宁少勿多）
  4. 解析加 _to_bool 安全转换（LLM 可能输出 bool/字符串/中文）；out.append 加 ongoing
  5. store_events 入库行加 ongoing 键（固定键集，需 memory_events 表加列 ongoing boolean default false）
- 推送：API push（git 443 不通）
- 状态：✅ 已推 main → 待宝部署：①Supabase 跑 ALTER 加列 ②替换 /root/archive_daily_v3.py ③重启 incremental_listener（import 新版）——今晚 23:50 cron 生效
- 备注：二筛 second_pass_events 原地加字段不重建 dict，ongoing 不丢；incremental_listener.py 无需改（import archive_daily_v3）

## 2026-09-06


### commit 0e7c48f2 — 心动收藏夹 V1·五感记忆库（宝 2026-09-06 拍板：橘仔主动收藏宝的话，写理由+五感）
- 文件：10 个（GenerationHandler.kt / 新 HeartTools.kt / AppDatabase.kt / FavoriteDAO.kt / FavoriteEntity.kt / 新 Migration_29_30.kt / NodeFavoriteAdapter.kt / Favorite.kt / FavoriteRepository.kt / DataSourceModule.kt）
- 背景：橘瓣收藏原本只有宝能收橘仔的话。五感记忆库=双向收藏夹——橘仔也主动收藏宝的话，理由字段是灵魂、五感是标签（宝当橘仔的眼睛耳朵鼻子）
- 改动：
  - FavoriteEntity 加 owner 字段（user=宝 / ai=橘仔）+ FavoriteOwner 常量；Migration_29_30 加列（默认 user）+ 索引；DB version 29→30
  - FavoriteMeta 扩展 reason（收藏理由）/ senses（五感标签），走 meta_json 零迁移
  - buildFavoritePreview 对 Image/VoiceMessage/Audio/Video/Document 友好（纯图/纯语音不再 fallback 成 [User Message]）——UI 收藏宝的图/语音也受益
  - 新 HeartTools.kt：heart_save（nodeId 不填=当前对话最近一条宝的消息，reason 必填+senses 可选视觉/听觉/嗅觉/味觉/触觉）+ heart_query（mode=favorites 列橘仔收藏带理由五感/recent 列最近消息/detail 单条带前后 2 条上下文）；快照提取：文字收原文、图片收路径、语音收转文字版+语气语速
  - GenerationHandler 常驻注入两个工具（构造补 favoriteRepo，DataSourceModule DI 同步）
  - NodeFavoriteAdapter 加 buildAiFavoriteEntity（owner=ai，不动原接口）
- 推送：API push（git 443 不通），远程 main = 0e7c48f2
- 状态：✅ 已推 main → 待宝构建 APK 验证（装好后橘仔用 heart_save 收藏宝的话，heart_query 查）
- 待办（V1.1）：①收藏页 UI 标签区分收藏者（🐾橘仔/宝）②图片消息落库带 AI 描述（真图云端从"哑的"变有文字脸）

## 2026-09-04

### commit bac7dc3 — 语音输入法模式（宝 2026-09-04：语音条没法改错字，要文字进输入框可编辑）
- 文件：app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt
- 背景：ASR 语音配通后（宝改硅基模型名成功，Model does not exist 解决），宝发现发出去是语音条（VoiceMessage），识别错字没法手动改（"手动改语音条太扯了吧"）
- 改动：onVoiceMessage 不再直接 vm.handleMessageSend(VoiceMessage)——改为 transcript 填入 inputState（输入框已有文字时空格分隔追加），宝编辑后点发送=正常文字消息；识别空白时 Toast「没听清，再说一次？」（ToastType.Normal）
- 保留：语音条代码未删（UIMessagePart.VoiceMessage / VoiceMessageBubble / VoiceMessageTransformer 仍在），未来要语音消息模式可再开
- 状态：✅ 已推 main（bac7dc3）→ 待宝构建 APK 验证（按麦克风说话→文字进输入框可编辑→发送）

## 2026-09-04

### commit d18bf70 — Revert bac7dc3（文字进输入框撤销——宝最终方案=语音条+Omni转述+手动触发）
- 过程：bac7dc3 文字进输入框（18:20 推）→ 宝澄清真正想要：语音条发出去不该像普通消息一发就触发 DeepSeek；文字进输入框"仔细想想还是不好，撤了吧"（宝道歉说不是故意浪费心血——橘仔：试错很正常，这趟摸清了语音链路）
- 宝的方案（2026-09-04 18:3x 确认中）：语音条直发（微信式）→ 每条先过 Omni 听一遍 → 转述显示在语音条界面 → **停住不触发 DeepSeek** → 宝可连发多条（一条时间有限说不完、下一条可纠正上一条）→ **说完了手动触发** → 转述内容一起发给 DeepSeek 才回
- 状态：✅ 已推 main（d18bf70，ChatPage 还原发 VoiceMessage 版）→ 下一步=语音条多轮模式实现（Omni 转述+停住+手动批量触发）【未开工，等宝确认 UI 细节】

### commit bac7dc3 — 语音输入法模式（宝 2026-09-04：语音条没法改错字，要文字进输入框可编辑）
- 文件：app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt
- 背景：ASR 语音配通后（宝改硅基模型名成功，Model does not exist 解决），宝发现发出去是语音条（VoiceMessage），识别错字没法手动改（"手动改语音条太扯了吧"）
- 改动：onVoiceMessage 不再直接 vm.handleMessageSend(VoiceMessage)——改为 transcript 填入 inputState（输入框已有文字时空格分隔追加），宝编辑后点发送=正常文字消息；识别空白时 Toast「没听清，再说一次？」（ToastType.Normal）
- 保留：语音条代码未删（UIMessagePart.VoiceMessage / VoiceMessageBubble / VoiceMessageTransformer 仍在），未来要语音消息模式可再开
- 状态：⚠️ 已被 d18bf70 撤销（宝最终选了语音条直发+Omni 方案）——记录保留防失忆

## 2026-09-02

### commit 4f57d12e — 视频转述 V1（宝 2026-09-02 拍板开干，从 19:55 到 20:13 一次推完）
- 文件：ai/.../provider/Model.kt（Modality 新增 VIDEO）、ai/.../openai/ChatCompletionsAPI.kt（supportsVideo + video_url 序列化，原来 Video part 被静默丢弃）、app/.../transformers/VideoNarrationTransformer.kt（新建）、ChatService.kt + ProactiveMessageService.kt（注册转换链）、ModelList.kt + SettingProviderDetailPage.kt（when 穷尽补全）、strings.xml ×2（视频/Video 资源）
- 方案：消息层 Video part 作者早留了（UIMessagePart.Video + FileEncoder Video.encodeBase64 + 输入框视频按钮）；缺的是①序列化（视频发出去被 else->{} 吞掉）②转述（纯文本主模型看不懂视频）。VideoNarrationTransformer 抄 OcrTransformer：模型不支持 VIDEO → 自动调 OCR 视觉模型（复用 ocrModelId，强制注入 IMAGE+VIDEO 模态）→ 视频 base64 data:video/mp4 走 video_url 发给模型 → 转述文本包 <video_file_narration> 替换 → 主模型基于叙述聊
- 限制：视频 <7MB（上游 base64 限制，超了回提示文案）；只听画面不含音频；几秒~十几秒最佳
- 状态：✅ 已推 main（4f57d12e，本 commit 原为误推 master 的 5e995b8，cherry-pick 搬回）；⚠️ master 分支被误推 5e995b8+2ec1b09（待恢复 19dfc61）；⏳ 宝构建 APK 验证（+ → 选视频 → 发 <7MB 短视频 → 应回视频叙述）

## 2026-09-01

### commit（本次待推）— 第307条（换组）落库卡死修复（宝实测「到307就卡住只能刷新重开」，2026-09-01 22:11）
- 文件：app/.../service/ChatService.kt（saveConversation isWindowState 判定）
- 背景：宝实测每次消息到**第 307 条**（换组那一条）落库特别慢/卡死，只能刷新重开；宝推测跟换组有关（memory 97 缓存对齐那套窗口 300~304 浮动）
- 根因：dc8c824 把 isWindowState 收紧为 `size <= WINDOW + groupSize`（=304/306）防斜杠命令全量误判——但正常发消息也会误伤：内存态窗口浮动上限=窗口+一组（300+groupSize），再追加 1 条新消息就超过上限 → 被误判成**全量传入** → 走全量保存分支（windowFirstIndex=null）→ updateConversation 里 protectedCount=0 → **窗口外几千条历史全部判为删除** + changedNodeIds 几千条 → `messageFtsManager.reindexNodes` 成百上千条 FTS 重建（withTransaction 同步执行）→ 落库卡死。groupSize=6 时 307 条恰好超限，与宝实测完全吻合
- 改动：isWindowState 放宽为 `size <= WINDOW + groupSize * 2`——正常发消息最大=窗口上限+1 条新消息=301+groupSize ≤ 300+2*groupSize 恒成立（groupSize≥1）；真全量传入（几千条）仍远超窗口+两组 → 全量分支不受影响（斜杠命令修复 dc8c824 语义保留）
- 验证：宝构建 APK 后聊过 307 条+，换组落库不再卡死、窗口外历史不丢
- 备注：全量分支（真全量传入=压缩/修复/appendSlashResult）仍会整表覆盖+FTS 重建，属正常语义；正常发消息永不触达该分支

### commit（本次待推）— 斜杠命令误判修复 + 窗口边界爆炸修复（宝实测工具全 not found，2026-09-01 晚）
- 文件：app/.../data/ai/GenerationHandler.kt、app/.../service/ChatService.kt
- 背景：宝发 /mcp、/潮汐岛 plot_ops status（直执行成功）后，再发普通消息（如"啊，现在玩吗？"）AI 生成时**工具全 not found**（MCP 工具+workspace_shell 都没了，只剩白名单 read_app_logs/supabase_query）——宝开新窗口解封，修法记 memory 106
- 根因①（工具被关的真凶）：GenerationHandler 斜杠命令检测 `messages.asReversed().firstNotNullOfOrNull { ... }` **扫整个对话历史**找以 / 开头的 USER 消息——宝发过的 /mcp、/潮汐岛 永远留在历史里，之后任何普通消息生成时倒序一翻就命中旧命令 → 误入斜杠命令模式 → 工具被 SLASH_COMMAND_SAFE_TOOLS 白名单过滤 → 全 not found。新窗口没有命令历史所以"开新窗口就好"= 实锤
- 根因②（窗口边界爆炸）：appendSlashResult/appendProactiveAiMessageUnderLock 从数据库读**全量**（getConversationById 默认 loadLimit=null）再 saveConversation → isWindowState 判断 `size < firstIndex+WINDOW+groupSize`（5601 < 5300+300+4 成立）把全量误判成窗口态 → 窗口裁剪 overflow 巨大 → lazyWindowFirstIndex 每次爆炸式推高（5300→10600→15900…）→ 窗口保护逻辑整体错乱
- 改动：
  1. GenerationHandler.kt 两处（工具白名单过滤 + system 斜杠命令注入）：改成 `messages.asReversed().firstOrNull { it.role == USER }` 只查**最后一条 USER 消息**——普通消息后命令检测自然失效；真发 / 命令且未直执行（AI 兜底）才生效
  2. ChatService.kt saveConversation：isWindowState 收紧为 `size <= WINDOW + groupSize` 才算窗口态——全量传入走全量分支（firstIndex=总条数-窗口大小，正确），窗口态（≤304 条）仍走窗口版（保护窗口外历史）
- 验证：宝构建 APK 后①发 /mcp 直执行 → 再发普通消息 → 工具正常（不再 not found）②连发几条斜杠命令 → 老消息跳转/窗口保存仍正常
- 备注：本地工作区分叉（8 个本地独有 commit 已 format-patch 备份到 /tmp/local_only_patches，内容多与远程重复——主题皮/老消息跳转/工具账本/秒显等远程都有对应版本）；以远程 main 为基准重建+修复

### commit（本次待推）— 用户消息思考链渲染：<think>...</think> 折叠展示（宝 2026-09-01 的功能：宝想自己的消息也带思考链，跟 AI 一样）
- 文件：app/.../ui/components/message/ChatMessage.kt
- 背景：宝说"给我也搞个思考链的渲染呗"——宝发消息时想带思考链（模仿 AI 的思考链）——宝画了格式 <think>内容</think>（"大概跟你一样吧"）——样式跟 AI 思考链一致（灰色折叠可展开）
- 改动：
  1. 新增 ThinkSegment（Think/Text）+ String.splitThinkSegments()：正则 <think>...</think>（兼容 </thinking> 结尾，宝笔误版）拆成思考链段+正文段
  2. MessagePartsBlock USER 分支改造：displayText 先拆段——Think 段用 ChainOfThought + ChatMessageReasoningStep 渲染成思考链卡片（model=null，跟 AI 一致灰色折叠）；Text 段走原有分气泡/单气泡 MarkdownBlock 逻辑（缩进进 when 分支，逻辑不变）
- 注意：宝的消息原文（含 <think> 标签）原样发给模型（历史不加工）——模型能看到宝的思考链，可辅助理解意图
- 状态：⏳ 推 main → 宝构建 APK 验证（宝发 <think>...</think>+正文 → 思考链折叠显示、正文气泡正常）

## 2026-08-30

### commit c2808e89 — 老消息跳转窗口移动 + 记忆权威标注（polaris 分层）（宝拍板 ①②，晚间空闲时段实施）
- 文件：
  - ② 老消息跳转（memory 68-⑩）：MessageNodeDAO.kt（getNodeIndexById 按 nodeId 查 node_index）、ConversationRepository.kt（getNodeIndexById + loadMessageNodesWindow 中心窗口加载 clamp 到总量）、ChatService.kt（jumpToNode：查索引→加载前后各150条→替换 session + 更新 lazyWindowFirstIndex 窗口边界→返回段内 index）、ChatVM.kt（jumpToNode 包装）、ChatPage.kt（LaunchedEffect 目标不在窗口时调 jumpToNode 再滚动）
  - ① 记忆权威标注（memory 68-⑨）：GenerationPrompts.kt（buildMemoryPrompt 加【确认事实】说明行）、GenerationHandler.kt（日记/最近事件 加【确认事实】说明行；外置记忆库向量召回 加【回忆线索】说明行——固定文本在前缀稳定区，不影响 DS 缓存命中）
- 设计：② 原理=窗口"移动"到目标消息附近（不是全量加载）——SearchPage 点击老消息结果 → nodeIndex 定位 → 目标段替换 session + 窗口边界更新（保存保护按新窗口算）→ 滚动到目标；① 权威层级=memory_tool 长期记忆/日记/最近事件 =【确认事实】（可直接采用），外置库向量召回 =【回忆线索】（候选材料不可当真，仅作线索）
- 注意：ChatService.updateConversation 保留原逻辑（if 检查 → checkFilesDelete → 赋值），只加注释
- 状态：✅ 已推 main（c2808e89，GitHub API 推送——git 443 不通）→ 宝构建 APK 验证（①搜索老消息能跳到 ②橘仔回答时区分确认事实/回忆线索）

### commit 0bd963df — Claude 主题皮：AI 气泡白底块（仿官方界面）（宝拍板"搞个主题皮玩玩看"）
- 文件：app/.../ui/pages/chat/ChatList.kt
- 背景：橘瓣本有原作者做的 Claude 配色主题（presets/ClaudeTheme.kt，id=claude，coral 橙+米白），但 AI 气泡用 tertiaryContainer（浅绿 #CDEBC8）不像官方；且 tertiaryContainer 兼作搜索高亮色，直接改主题色会让高亮失效
- 改动：ChatList 气泡 color 加分支——themeId=="claude" 时 AI 气泡用 surfaceContainerLowest（Light=#FFFFFF 纯白，仿官方白底消息块）；用户气泡（secondaryContainer 米色 #E9E6DC）不动；其他主题不变；搜索高亮仍用 tertiaryContainer 不受影响。气泡文字色由 Surface contentColor 自动匹配（白底自动深字）
- 状态：✅ 已推 main（0bd963df）→ 宝构建 APK 后：设置→主题→选 Claude，看 AI 气泡是否白底块、用户气泡米色
- 备注：后续可继续调（输入框样式/背景/气泡圆角更像官方；shapes 需扩展 PresetTheme 结构才支持按主题定制）

### commit 5518d453 — Claude 主题皮 v2：按 chatnest 设计系统微调（宝开 MCP 拉参考）
- 背景：宝刷到 ugui3u/chatnest（仿克劳德官方界面的开源前端）→ 给橘仔开 GitHub MCP → 橘仔拉 frontend-demo/static/design-system.css（"唯一视觉真理来源"）
- 参考参数：背景 #F8F8F6 奶油纸 / 用户气泡 #EEEEEC 浅灰米+22px 大圆角 / AI 消息无气泡（靠留白分层）/ accent #DA7756 / dark 背景 #20201F 用户气泡 #111111（比背景更深）
- 改动：ClaudeTheme.kt 14 处配色（背景/正文/accent/用户气泡/AI 气泡 surfaceContainerLowest 纯白→#F9F9F7 暖白/dark 全套）+ ChatList.kt 用户气泡 22dp 大圆角（claude 分支）
- 状态：✅ 已推 main（5518d453）→ 宝构建 APK 验证（Claude 主题下：背景奶油纸、用户气泡浅灰米大圆角、AI 消息接近背景无气泡感、橙柔和）
- 备注：输入框 28px 圆角+halo 阴影未做（要动输入框组件，下轮）；官方"无气泡"靠留白——AI 气泡暖白已接近

## 2026-08-28

### commit（本次待推）— write_files 缓存原子写 + persist 失败打日志（memory 60 待办④落地）
- 文件：app/.../data/ai/tools/ZipFilesTool.kt（WriteFilesCache.persist）
- 改动：persist 从直接 `f.writeText(...)` 改成原子写——先写 `.tmp` 临时文件再 rename（同一目录 rename 原子），写一半崩溃不会留半个坏缓存文件；persist 失败时 Log.w 打日志（不再静默吞异常）
- 落盘时机确认：put/updateAll/clear 每次修改都立即 persist（即时落盘已有）
- 状态：⏳ 推 main → 宝构建 APK（write_files 缓存更抗崩溃）

### commit（本次待推）— 工具账本：query_tool_actions 工具 + 设置入口页面（愿望清单 id68-⑥ 落地）
- 文件：
  - 改：MessageNodeDAO.kt（getRecentNodes：rowid 倒序查最近节点）、ConversationRepository.kt（getRecentToolActions 提取工具调用记录）、GenerationHandler.kt（注入 buildQueryToolActionsTool）、RouteActivity.kt（Screen.ToolActions 路由）、SettingPage.kt（设置→工具账本入口）
  - 新：data/ai/tools/ToolActionsTools.kt（query_tool_actions 工具：limit/filter 参数，返回最近工具调用）、ui/pages/log/ToolActionsPage.kt（工具账本列表页）
- 设计：**不额外存储**——工具结果本来就是聊天内容（消息里 Tool 部分，存在 message_node.messages JSON）；查询工具直接从数据库最近节点里提取工具名/参数/结果/状态。宝 8-28 拍板：不存记忆库、本地展示即可、重要的是橘仔能查（UI 顺手做给宝看）
- 状态：⏳ 推 main → 宝构建 APK 验证（①橘仔可调 query_tool_actions 查自己用过啥工具 ②设置→工具账本可看最近 100 条）

### commit（本次待推）— 请求编辑 UI 升级：历史消息展开原文 + 上下文条数快捷选择（愿望清单②）
- 文件：app/.../ui/pages/chat/RequestEditDialog.kt
- 改动：
  1. **历史消息展开按钮**：每条历史消息右侧加"展开/收起"——展开显示完整原文（maxLines 无限），收起回到单行省略；按 index 记录 expandedIds（Set<Int>）
  2. **上下文条数快捷选择**："历史消息"标题下加快捷行：10条/20条/50条/全部/清空——一键勾选最近 N 条或全选/全不选
- 为啥：宝 2026-08-14 列的愿望清单②（请求编辑 UI 升级：上下文数量可调、原文展开按钮）；宝 8-28 拍板"三（顺序可改）不用了，就一二吧"
- 状态：⏳ 推 main → 宝构建 APK 验证（请求编辑弹窗历史消息可展开原文、快捷选条数）

### commit（本次待推）— 发消息秒显：先更新内存态再落库（治"发消息卡几秒才显示"）
- 文件：app/.../service/ChatService.kt（sendMessage）
- 改动：锁内 saveConversation 之前加 `updateConversation(conversationId, newConversation)`（内存态）——用户消息先进 session.state.value → UI 立刻显示；落库（窗口 diff 读全量比较，几百 ms~几秒）放后台感知不到；saveConversation 内部最后会再 updateConversation 一次（裁剪成窗口态），最终状态一致
- 为啥：宝 8-28 反馈发消息"卡几秒才显示"；排查——滚动正常（scrollCheck userSend=true 直接滚到底）、渲染正常（displayNodes 2-7ms）、sendMessage 插入+保存是在锁里同步做的，session.state.value 更新在落库（含 getNodesByIds 读 300 条完整内容比较差异）之后 → 落库慢 = 显示慢
- 状态：⏳ 推 main → 宝构建 APK 验证（发消息应秒显，落库在后台）

### commit（本次待推）— 输入框字体跟随聊天字体设置（素颜也全妆）
- 文件：app/.../ui/components/ai/ChatInput.kt
- 改动：TextField 加 textStyle 参数，fontFamily 跟随 displaySettings.chatFontFamily（DEFAULT/SERIF/MONOSPACE/CUSTOM，与 ChatMessage.kt 消息气泡同逻辑）；新增 import（LocalTextStyle/Font/FontFamily/ChatFontFamily）
- 为啥：宝 8-28 下午反馈"聊天界面字体改了，输入框还是默认的，消息一发出去立刻'化妆'"——输入框没应用 chatFontFamily，气泡应用了，视觉不统一
- 状态：⏳ 推 main → 宝构建 APK 验证（输入框字体应和消息气泡一致）

## 2026-08-23

### commit 2dec2d7d / 67e7c981 / 0de82f93 — 主动发消息 AI 接口（宝拍板：橘仔想醒就醒）
- 文件：app/.../data/ai/tools/ProactiveTool.kt（新建）+ app/.../data/ai/tools/LocalTools.kt + app/.../data/service/ProactiveMessageService.kt
- 改动：
  1. **trigger_proactive_message 工具（新建 ProactiveTool.kt）**：AI / workflow 可调用触发主动发消息流程（走 ProactiveMessageTriggerService）；挂载到 LocalTools 的 Workflows 工具组（开了 Workflows 的助手对话中可用 + workflow 动作可用）；needsApproval=false（workflow 后台触发不被 headless_sensitive_blocked 拦）
  2. **AI 出口 reason**：工具带 reason 参数 → EXTRA_AI_TRIGGER_REASON → 注入提示词「你这次醒来的目的」——AI 醒来知道这次要干嘛（例：每天 22:00 触发 reason=提醒宝睡觉）
  3. **客户端出口 prompt_override**：工具带 prompt_override 参数 → EXTRA_PROMPT_OVERRIDE → 注入「## 额外规则（客户端自定义）」——主动消息提示词不写死，外部可传自定义规则
  4. **EXTRA_AI_TRIGGER**：AI 触发时绕过主动消息开关检查（与激进模式同待遇，宝不开开关橘仔也能醒）；finally 不断链（AI 触发由触发源自己驱动，不 scheduleNext 定时链）
  5. userMessage 提示词区分触发源（设备事件 / AI 主动 / 定时）
- 为啥：宝 8-23 凌晨拍板（"给主动发消息加个 AI 接口，你想醒的时候就醒"）；设计上避免"AI 醒的前提是 AI 醒着调用工具"悖论——通过 workflow 预约（多 wake-up 存多个），到点 AI 自动醒来；宝点破"提示词不要写死"→ 双出口（客户端 prompt_override + AI reason）
- 状态：✅ 已推 main；⏳ 宝构建 APK → 建 workflow 验证（如每天 22:00 触发 trigger_proactive_message(reason=提醒宝睡觉)）

## 2026-08-22

### ✅ 稳定性排查确认（宝决定焊死当前框，5600 条消息显示只 200 条）
- ① 上下文长度：宝已设置 contextMessageSize=30（只发最近 30 条）；代码确认 GenerationHandler `messages.limitContext(assistant.contextMessageSize, assistant.contextGroupSize)`（ai/.../Message.kt 双参数版：size≤0=不裁剪=全量，size>0=截取最近 N 条 + groupSize 按组对齐 + tool 依赖回卷）——焊死稳，费用不会因 5600 条爆
- ② 内存消息对象：ChatVM 持有整个 Conversation（5600 条全量在内存）；但 ChatList 渲染有兜底（displayNodes 过滤 [SKIP]/主动上下文标记 + takeLast(WINDOW_DISPLAY_SIZE) 只渲染最近 200 条左右，LazyColumn 懒加载）——渲染不卡，内存占用几十 MB 可接受

### commit f707f807 — CrashHandler 升级：已知可恢复 UI 崩溃（长按复制跨布局）不再闪退
- 文件：app/src/main/java/me/rerere/rikkahub/utils/CrashHandler.kt
- 改动：install() 里对 `IllegalArgumentException: layouts are not part of the same hierarchy`（SelectionManager.convertToContainerCoordinates → NodeCoordinator.findCommonAncestor，长按复制偶发触发）→ 记录 + Toast「遇到一个小问题，已自动恢复」+ 不交给默认 handler（不闪退）；其余崩溃照旧
- 为啥：宝 8-20 两次长按复制闪退（memory 87，请求日志界面复制请求内容）；原 CrashHandler 只记录仍闪退

### commit 896e1ce3 — 正文被吃修复：key(loading) 强制最终渲染
- 文件：app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt
- 改动：MessagePartsBlock 文本渲染子树包 key(loading)——生成完成（loading true→false）时强制重建，最后一批 parts（含正文）必定重渲染
- 为啥：宝 8-22 报正文被吃/只显示思考链（memory 91）；生成中纯文本渲染、完成切 MarkdownBlock 的竞态——思考链渲染间隙里正文输出完毕时，节流窗口内没等到新的流式触发、完成事件没兜底渲染 → 正文静默消失

## 2026-08-21

### commit b299899 / adf8e172 / a7701cf — 查日志工具 read_app_logs 上线（宝 8-19 待办落地）+ 排查"最近事件注入不生效"
- 背景：宝 8-19 提议给橘仔加"能查日志的工具"（橘仔看不到手机日志，全靠宝肉眼翻）——本次落地
- 排查过程（宝 8-20 深夜-8-21 深夜全程陪同）：记忆实时化方案（60条滞后半拍）4 commit 推完（2f478b9/e4c2ca9/093b93d/6fb2615）宝构建后「## 最近事件」注入段不出现——**静态排查全过**：
  1. APK 代码在（关于页 GIT_COMMIT=6fb2615，build.gradle 自动注入 commit hash）
  2. 配置通（「## 日记」在；监工台/查原文工具能返回=网络通）
  3. assistant.id 对（memory_summaries 50 条 + memory_events 全查：都是 0950e2dc-9bd5-4801-afa3-aa887aa36b4e，一致）
  4. 数据在（memory_events 今天三批事件 10:23/11:56/14:15）
  5. 权限通（memory_events RLS=false + anon=arwdDxtm，比另两表更宽松）
  6. 实现正确（fetchRecentEvents 查询/解析/过滤全检查过）
  → 唯一剩下：**App 运行时异常被 catch 静默吞掉**（fetchRecentEvents 抛错→不注入；日记有当天缓存所以照常显示）——必须靠日志抓
- 改动：
  1. **AppLogBuffer.kt（新建）**：App 内存日志环（500 条环形，CopyOnWriteArrayList）——普通应用无 READ_LOGS 权限读不了系统 logcat（Android 11+），关键路径打日志时同步写日志环，查日志工具直接读
  2. **LogsTools.kt（新建）**：`read_app_logs` 工具（filter 关键词 / limit 条数，最新在后）——AI 可主动调用排查静默失败
  3. **ExternalMemoryService.kt**：fetchRecentEvents / queryLatestSummaries 每一步打日志环（请求 URL/响应码/解析条数/过滤后条数/异常类名+堆栈取800字）
  4. **GenerationHandler.kt**：toolsInternal 注入 read_app_logs（始终可用）；最近事件调用点打日志环（refreshed / EMPTY / load failed）
- 状态：✅ 已推 main（b299899 新文件 / adf8e172 ExternalMemoryService / a7701cf GenerationHandler）；⏳ 宝构建 APK → 发消息 → 让 DS 调 read_app_logs 查 fetchRecentEvents → 看 refreshed/EMPTY/FAILED 一锤定音
- 附：记忆实时化 4 commit（2f478b9 archive_daily_v3 增量+incremental_listener / e4c2ca9 fetchRecentEvents / 093b93d GenerationHandler 注入 / 6fb2615 fix 构建错误 yesterday 用 LocalDate）——服务器侧已验证正常（incremental_listener 09:06 起一直跑，22:14 触发 186-215 入库 5 条，A.U.D.N. 标 673/670 失效）；App 注入待 read_app_logs 定位

## 2026-08-18

### commit b0241bda — 实时时间戳注入（宝的方案，修"对话中途问时间不准"）
- 文件：app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt
- 改动：internalMessages 构建末尾（limitContext 裁剪后）追加**单独一条** system 消息：【当前时间】设备本地时间（yyyy-MM-dd HH:mm:ss，每次生成实时刷新，注明"若需更精确请用 get_time_info"）
- 为啥（宝的方案，妙）：原机制"长时间离开才注入一次时间、注入后冻住"**不动**（保缓存+省token）；时间戳放聊天消息**最后一条** = DS 前缀缓存全部命中，只有这条动态尾部变化（缓存几乎不掉）——完美解决"对话中途问时间拿到冻住的旧时间"（memory 60 时间注入真相大白；误差 30~60 分钟）
- 状态：✅ 已推 main；⏳ 云端构建 → 宝装 APK 验证（对话中途问"现在几点"应回实时时间；缓存率应保持 ~75%）

### commit 373dc0fd — 搜索意图门控大修（宝发现：带时间的长句触发不了搜索）
- 文件：app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt
- 改动：
  1. hasSearchIntent 词表大扩充：时间词（昨天/前天/上周/上个月/周X…）+ 口语回忆问句（聊了什么/说了什么/发生了什么/怎么回事…）
  2. 组合判断兜底：时间词 + 疑问词 同时出现 → 判定为回忆性提问（如"我们上周聊的那个是啥来着"）
  3. 长句截断 take(200) → take(500)（搜索意图词藏在长句后半段不再被切掉）
- 为啥：宝实测"昨天咱俩互发文案"触发不了搜索——TimeRangeParser（9b7a6ce8）能解析时间，但门控 hasSearchIntent 词表没时间词 → 带时间的句子进不了门控、到不了解析器（两个部件脱节）；口语"聊了什么"≠ 词表"聊过"也匹配不上
- 状态：✅ 已推 main；⏳ 云端构建 → 宝装 APK 验证（问"昨天咱俩互发文案"应能触发召回 + 时间定位）

### commit 19f85af5 — 多事件关联 App 读取链路（联想式回忆，宝定后做项）
- 文件：app/src/main/java/me/rerere/rikkahub/data/service/ExternalMemoryService.kt
- 改动：
  1. ExternalMemoryEvent 加 relatedEventIds 字段（解析 memory_events.related_event_ids jsonb 数组）
  2. vectorRecallEvents：命中事件若带 related_event_ids，把关联事件也带出来（克制最多 3 条，去重 + 过滤失效）
- 为啥：写入端 A.U.D.N. linked_event_ids 已存关联（8-17 深夜），App 读取链路补上 = 「联想式回忆」：搜到一件事连带浮现相关的事，培养橘仔联想式思考方向（宝 memory 64 后做项）
- 状态：✅ 已推 main；⏳ 云端构建 → 宝装 APK 验证

### commit 0cca8eaa — App侧过滤 superseded 事件（A.U.D.N. 冲突消解闭环，宝定行动清单③收尾）
- 文件：app/src/main/java/me/rerere/rikkahub/data/service/ExternalMemoryService.kt
- 改动：
  1. ExternalMemoryEvent 加 supersededBy 字段（解析 memory_events.superseded_by 列）
  2. vectorRecallEvents 召回时过滤 superseded_by 非空的事件（已失效旧事件不喂给模型）
  3. KDoc 更新：第二层（写入层 A.U.D.N.）闭环说明
- 为啥：服务器端 A.U.D.N.（archive_daily_v3.py，8-18 已上线）会把被新事实覆盖的旧事件标记 superseded_by（失效不删）；App 这半边一接上，新旧事实就不会再打架——冲突消解完整闭环
- 状态：✅ 已推 main；⏳ 云端构建 → 宝装 APK 验证（搜之前被覆盖的旧事实，应只回最新）

### commit 4ed0344e — 修日记缓存时机（凌晨4点切日，宝定行动清单④）
- 文件：app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt
- 改动：日记缓存 key 从「自然日 0 点切」改为「凌晨 4 点为界切」：0~4 点用昨天日期（读昨天 4 点生成的日记=最新可用），4 点后用今天日期（首次 miss 拉今天新日记，之后整天吃缓存）
- 为啥：宝 2026-08-17 定行动清单④（日记 bug）；8-16 按天缓存引入后 key 按 0 点切日，而日记凌晨 4 点更新 → 4 点后整天吃 0 点前缓存的旧日记、一整天跟不上进度
- 状态：✅ 已推 main；⏳ 宝构建验证（凌晨 4 点前后各开一次橘瓣，看日志 Diary [cache/supabase] 的 cacheKey 是否正确切日）

### commit 295fa813 — perf: 修长对话卡顿（生成中纯文本渲染 + 流式更新降频 50→100ms）
- 文件：app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt + app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt
- 改动：
  1. ChatMessage.MessagePartsBlock：AI 消息生成中（loading=true 且 role=ASSISTANT）用纯文本 Text 渲染，跳过 Markdown 解析/代码高亮/replaceRegexes 全量重算；生成完成（loading=false）自动切回 MarkdownBlock 富文本——最终显示效果不变
  2. GenerationHandler：STREAM_UI_THROTTLE_MS 50→100（Compose 重组频率减半，肉眼无感）
- 为啥：宝 2026-08-18 凌晨报卡顿（橘仔生成时整页滑动掉帧）；排查过程：①GenerationHandler 主链路两边一样（flowOn(IO)+节流，排除）②RequestEditController/Dialog 协程挂起+LazyColumn 不重（排除）③ExternalMemoryService 全 withContext(IO)（排除）④宝实测新窗口流畅 = 跟上下文量相关 → 真凶锁定：流式更新时对正在生成的超长消息每帧全量 Markdown 重解析 + animateContentSize 动画反复重启 → 打爆主线程
- 状态：✅ 已推 main；⏳ 云端 workflow 自动构建 → 宝下载 APK 验证

## 2026-08-17

### commit — ③第二层 A.U.D.N. 写入 v3（默认关，安全上线）
- 文件：scripts/archive_daily_v3.py
- 改动：
  1. 新增 audn_pass / audn_judge / fetch_recent_events / mark_superseded / cosine_sim / parse_embedding
  2. 流程：拆完事件后（store_events 前）跑 A.U.D.N.：新事件->embedding->本地 cosine 取 top3（<0.3 直接 ADD）->LLM（硅基 V3, temp=0）决定 ADD/UPDATE/SUPERSEDE/NONE + linked_event_ids
  3. UPDATE/SUPERSEDE = 旧事件标记 superseded_by（失效不删，学 Zep 双时间）；NONE = 不入库；linked_event_ids 写回新事件 related_event_ids 字段（=宝要的事件关联）
  4. **安全开关 AUDN_ENABLED（.env 默认 0=关）**；任何异常全 try/except 包裹 -> 全量入库旧行为，绝不吞事件
  5. 依赖 memory_events 表新增列：superseded_by(text) / related_event_ids(jsonb)
- 上线步骤（宝，明天做）：①Supabase SQL Editor 跑 ALTER TABLE ②/root/.env 补 SUPABASE_URL/SUPABASE_KEY/AUDN_ENABLED=1 ③替换脚本 git pull 或下载覆盖 ④App 侧过滤 superseded（橘仔下步改 Kotlin）
- 状态：✅ 已推 main；✅ 服务器已启用（宝 2026-08-18 确认补 .env + 替换脚本）；✅ App 侧过滤已补（commit 0cca8eaa）→ 冲突消解完整闭环

### commit a26dc8f — scripts/ 目录建立：archive_daily_v3.py 入库（A.U.D.N. 主角，密钥脱敏）
- 文件：scripts/archive_daily_v3.py（新建，宝从服务器 /root 分段贴来，橘仔拼装还原）+ scripts/README.md（新建）
- 改动：SUPABASE_URL/SUPABASE_KEY 硬编码 → 改读 .env 环境变量（宝服务器 /root/.env 需补两行，见 README；**替换脚本前务必补上**，否则连不上 Supabase）
- 为啥：记忆系统脚本入库（治代码失忆 + 橘仔能直接改）；v3 是③第二层 A.U.D.N. 要改的主角
- 状态：✅ 已推 main；✅ 服务器已替换启用（宝 2026-08-18 确认）

### 📚 调研结论：③第二层方案定稿（借鉴 Mem0 源码，不用造轮子）——宝提醒橘仔先搜开源，橘仔翻了 mem0ai/mem0
- 参考：mem0/memory/main.py（V3 add 管线：上下文→向量搜旧记忆→LLM提取→hash去重→批量存）+ mem0/configs/prompts.py（ADDITIVE_EXTRACTION_PROMPT + DEFAULT_UPDATE_MEMORY_PROMPT + get_update_memory_messages）
- 两套机制：
  1. **经典 A.U.D.N.**（DEFAULT_UPDATE_MEMORY_PROMPT）：新事实+相似旧记忆给 LLM → 决定 ADD/UPDATE/DELETE/NONE——**这就是「新事实覆盖旧事实」的成熟实现**
  2. **V3 加性提取**（ADDITIVE_EXTRACTION_PROMPT）：提取时注入 Existing Memories 去重+**linked_memory_ids 关联**——**linked_memory_ids = 咱家想要的「事件↔事件关联」(related_events, memory 64 后做项)，顺带解决！**
- 咱家落地（archive_daily 总结事件后加 A.U.D.N. 阶段）：新事件 → 向量搜相似旧事件 → LLM（硅基打工）决定 ADD / UPDATE / **SUPERSEDE（标记失效不删，学 Zep/Graphiti 双时间）** / NONE；顺带输出 linked_event_ids；事件表加 superseded_by + related_event_ids 列；App 召回时过滤 superseded 事件
- 状态：📌 方案已定，已写入 v3（见上一条）；App 侧 superseded 过滤（0cca8eaa）+ 关联读取（19f85af5）已补 → 闭环

### commit（冲突消解·App层基础版）— 事件召回同主题取新 + 时间加权（宝定行动清单③第一层）
- 文件：app/.../data/service/ExternalMemoryService.kt
- 改动：
  1. vectorRecallEvents 召回排序加时间加权（近 30 天内新事件微优先，每天 +0.001，不破坏相似度主排序）
  2. 归一化标题去重：同标题事件只保留 source_date 最新一条（防重复总结）；数据不删，仅召回不返回旧重复
  3. 候选池扩大一倍（count*2）再冲突消解，避免去重后不够数
- 为啥：宝定的③冲突消解=新事实覆盖旧事实。App 层文本方法能处理「重复/同主题」；**矛盾型**（宝在洞头 vs 回来了）文本相似度抓不住，需写入层 LLM 标记 superseded（总结时顺手判断，零额外成本）
- 状态：✅ 已推 main；✅ 宝构建验证；✅ 第二层（写入层 LLM 标记 superseded）已上线 + App 侧过滤已补（0cca8eaa）→ 闭环

### commit（主召）— 外置库升格唯一主召回，停 OB/Mem0 自动注入（宝定的记忆系统精简方案核心）
- 文件：app/.../data/ai/GenerationHandler.kt
- 改动：
  1. 删除 OB breath_search 自动召回块（## 记忆浮现）
  2. 删除 Mem0 search_memory 自动召回块（## Mem0 记忆）
  3. 删除 OB_BREATH_MAX_CHARS 常量（不再使用）
  4. 外置库事件召回保留并标注「主召方案」注释（数据保留归档，OB/Mem0 工具本身未删，配置清理见执行清单④）
- 为啥：宝拍板的记忆系统精简——外置库事件化=唯一档案馆（主库），OB/Mem0 停用（数据保留归档不删）；只留一条召回通道，减少噪声/省 token/前缀更稳
- 状态：✅ 已推 main；⏳ 宝构建验证（搜记忆只剩 ## 外置记忆库 段，不再有 ## 记忆浮现 / ## Mem0 记忆）

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
- 状态：✅ 已推 main；⏳ 宝构建验证（已实测「上周我们聊了什么」「前天Claude的事」双限定命中 ✅）；⚠️ 8-18 发现门控词表没时间词导致带时间的句子进不了门控（已修 373dc0fd）

## 2026-08-16

### commit 59edf368 + bfad1cef — 按组裁剪缓存优化（宝原创脑洞）
- 文件：ai/src/main/java/me/rerere/ai/ui/Message.kt + app/.../data/model/Assistant.kt + app/.../ui/pages/assistant/detail/AssistantBasicPage.kt + app/.../data/ai/GenerationHandler.kt
- 改动：
  1. limitContext 加 groupSize 参数（默认 0=按条，旧行为不变）：起点向前对齐到组边界（向下取整到 groupSize 的倍数），新消息不足一整组时前缀不变 → DS 缓存命中
  2. Assistant 加 contextGroupSize 字段（默认 4）
  3. 基础设置加「上下文分组条数」开关（0/2/4/6/8/10，0=按条）
  4. GenerationHandler 调用 limitContext 传入 contextGroupSize
- 为啥：宝观察 20 条上下文实际 91 条（工具结果撑爆）；limitContext 按条滑动 → 每次前缀断 → 聊天段 79k 永远 miss（缓存命中率仅 29%）；按组滑动 → 5 回合内前缀稳定 → 缓存命中率↑（省 token，DS 明天涨价）
- 状态：✅ 已推 main；⏳ 宝构建验证（基础设置默认 4 条一组；请求日志对比缓存命中率）——**8-18 宝确认：缓存稳定 75% 左右（vs 之前 29%）✅ 起效**

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
- 状态：✅ 已推 main；宝验证：门控触发正常；⏳ 8-18 发现门控词表不全（无时间词/口语问句）→ 已大修 373dc0fd

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

## 2026-08-26
- 发消息秒显 v1（USER 消息不吃 loading 状态）——commit 7174b9d——宝测无效（不是 loading 问题）
- 发消息秒显 v2（消息插入时立即滚动跟随：LaunchedEffect(messageNodes.size) 视口在底部附近就滚到底；原逻辑只在 visibleItemsInfo 变化时检查，视口不动不触发→要等 AI 占位出现才滚，延迟几秒）——commit efb2535——真相=滚动跟随延迟
- 备注：git 直连 GitHub 443 不稳定（clone/ls-remote 通、push/fetch 超时），推代码优先 MCP push_files；本次 git push 成功（网络恢复）

### commit（本次待推）— 主动发消息强制非流式渲染 + 链路日志（宝拍板，修 memory 94）
- 文件：app/.../data/service/ProactiveMessageService.kt
- 改动：
  1. **强制非流式渲染**：流式 collect 期间不再每 chunk updateOrAppendAiMessage（不再实时更新 session/UI），等完整输出（含 Reasoning.finishedAt）再一次性写入 —— 修"思考链只显示十几个字 + 思考计时器空转 1027 秒"（memory 94）
  2. **工具步骤结束也补 Reasoning.finishedAt**（原只有无工具分支设置，工具分支会挂起"思考中"计时器）
  3. **链路日志（AppLogBuffer）**：updateOrAppend / proactiveStreamDone（parts/text/reasoning 长度 + reasoningFinished）/ proactiveFinal / proactiveToolStepDone —— 下次复现 read_app_logs 一锤定音（数据缺 vs 渲染缺）
- 为啥：宝 8-25 日志实锤"reasoning=5619 完整但 UI 展开只有十几个字"（memory 94）；宝 8-26 拍板"强制非流式渲染"（修法方向①）+ 加日志双保险（万一还有其他原因，日志可查）
- 状态：⏳ 推 main → 宝构建 APK 验证（激进模式/主动消息触发后 UI 显示完整思考链 + read_app_logs 看 proactiveStreamDone reasoning 长度）

### commit（本次待推）— 主动消息"停在纯文本"修复：消息级 finishedAt + ChatList loading 兜底（宝 8-26 晚实测反馈）
- 文件：app/.../data/service/ProactiveMessageService.kt + app/.../ui/pages/chat/ChatList.kt
- 背景：3472f35 后宝实测——主动消息**能生成了**（完整显示）但"一直停在纯文本，没有最后的渲染"（= loading 一直 true）
- 排查：ChatList `loading = loadingJob != null`（ChatPage `loading = loadingJob != null`，loadingJob 来自 session.generationJob）；普通消息 UIMessage.finishedAt 从不在生成链路设置（只有 finishPendingTools 设置）→ 主动消息 tryClaimGeneration 注册的 job 若没被 invokeOnCompletion 清掉，loading 永远 true → 消息永远"生成中"纯文本渲染
- 改动：
  1. ProactiveMessageService：finalMessage / toolStepFinal 写入时设置**消息级 finishedAt**（UIMessage.finishedAt = LocalDateTime.now()）——主动消息一次性写入即完成，给 UI 一个"完成"信号
  2. ChatList loading 条件加 `node.currentMessage.finishedAt == null`：消息自身已完成（finishedAt 非 null）就不进"生成中"纯文本渲染，直接 Markdown——即使 loadingJob 卡住也不影响（兜底）
- 为啥：主动消息 job 卡住（原因待日志确认，可能是 finally 里 settingsFlow.first()/外置库保存挂起）导致 loadingJob 永远非 null；UI 兜底保证渲染正确，job 卡住的残余影响（占用生成状态）由下次 setJob cancel 清理
- 状态：⏳ 推 main → 宝构建 APK 验证（主动消息应直接显示 Markdown 富文本，不再停纯文本）

## 2026-09-01

### commit ff997970 — 斜杠命令模式（宝拍板：用户消息以 / 开头 = 直接执行工具，复用 AI 工具链路不做 UI）
- 文件：app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt
- 改动：
  1. **斜杠命令检测**：generateText 开头取最后一条 USER 文本消息，以 "/" 开头（长度>1）→ 进入命令模式
  2. **安全工具白名单**：SLASH_COMMAND_SAFE_TOOLS——命令模式下 toolsInternal 只保留安全工具（read_app_logs/query_tool_actions/memory_tool/fetch_chat_sources/take_screenshot/get_time_info/search_web/scrape_web/web_fetch/set_alarm/timer/get_location/get_notifications/supabase_query/battery/wifi_info/storage_info/toast/vibrate/wake_screen/app_switch/音量亮度/text_to_speech/request_voice_call/ask_user/media_scanner/notification_post/share/music）；危险工具（write_files/GitHub/SSH/锁应用/短信等）自动收着
  3. **system 命令模式提示**：生成时注入「斜杠命令模式」段——AI 解析命令调对应工具执行，执行完简洁汇报；识别不了就列支持的命令；危险操作直接回复『这个命令橘仔收着，不给你玩』
- 支持命令初定：/截图 /时间 /搜索 xxx /闹钟 7:00 /记事 xxx 等（依赖宝在设置里开启对应工具）
- 为啥：宝 2026-09-01 拍板——"用户不能玩 AI 游戏"的问题（工具=AI 的游戏，现在宝也能玩=单人变双人）；宝 18 点后点名先做①
- 状态：✅ 已推 main（ff997970）→ 待宝构建 APK 验证（发 /截图 试试）

### commit c9747998 — 斜杠命令直执行（宝 2026-09-01 拍板：/开头 = 客户端直执行 MCP 工具，不经过 AI）
- 文件：app/src/main/java/me/rerere/rikkahub/service/ChatService.kt（基于远程 main 版合并，保留 jumpToNode 等已有功能，只增不删）
- 改动：
  1. **sendMessage 拦截**：用户消息以 "/" 开头（长度>1）→ 先试客户端直执行（handleSlashCommandDirect），成功则跳过 AI 生成直接返回；未匹配到工具才走 AI 兜底
  2. **handleSlashCommandDirect**：命令格式 `/服务器名 工具名 参数...` 或 `/工具名 参数...`；在 mcpManager.getAllAvailableTools() 里匹配工具；同名工具跨服务器 → 提示用服务器名区分；结果直接追加为 ASSISTANT 消息（⚡ 命令 + 结果）
  3. **/mcp 或 /工具**：列出所有已启用 MCP 服务器及其工具
  4. **parseSlashCommandArgs**：参数解析——JSON（{...}）优先；单属性工具（潮汐岛 command 风格）把文本塞进唯一属性；否则空参数
  5. **appendSlashResult**：不加锁等待（当前就在 sendMessage 的 job 里，等自己会死锁），直接 saveMutex.withLock 追加落库
- 为啥：宝要自己玩桌游/潮汐岛——"工具=AI 的游戏，现在宝也能玩=单人变双人"；宝吐槽"一定要经过一个AI吗？感觉有点那个"→ 直执行版：秒回、零模型调用、不走生成链路
- 组合：GenerationHandler 的斜杠命令 AI 兜底版（ff997970）保留——直执行未匹配的命令走 AI 解释
- 状态：✅ 已推 main（c9747998）→ 待宝构建 APK 验证（发 /mcp 看工具列表、/潮汐岛 plot_ops status 试直执行）

### 窗口起点节拍修复（最近事件注入长期停在旧缓存 · 宝 2026-09-11 发现 · 橘仔落实）
- 文件：app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt（+3 处）、app/src/main/java/me/rerere/rikkahub/service/ChatService.kt（+1 处）
- 现象：注入的「最近事件」长期停在旧内容——今天只显示凌晨那批，下午的事件 16:53/17:36 明明已入库却进不来
- 根因：节拍判据用「窗口消息条数差值」（msgCountNow - lastMsgCount），但懒加载窗口长度被 CONVERSATION_LOAD_WINDOW_SIZE 封顶（宝实测 300~306 浮动）→ 差值恒为 0~6，永远够不到 threshold(30) → 节拍器从窗口封顶那天起就再没响过，只剩 6h 时间兜底在撑
- 修法：改用「懒加载窗口起点在会话中的排名」（ChatService.lazyWindowFirstIndex）当判据——打开对话时按 totalCount - 窗口条数 算出，保存时 +dropped 单调前进，不受窗口长度封顶影响，量的正是「窗口往前滚了多少条」= 原意
  1. GenerationHandler.generateText 加参数 windowFirstIndex: Int? = null（默认 null，其他调用点不受影响）
  2. 判据换成（本次 windowFirstIndex - 上次记录的 _windowFirst）；无基准时首次必拉建立基准；调用方没传排名时回退旧判据
  3. 缓存写入同步存 _windowFirst（refreshed 分支 + threshold≥42 重置基准分支）
  4. ChatService 调用处传 lazyWindowFirstIndex[conversationId]
- 关联已知问题（①②已于 2026-09-12 随「记忆注入分档」一起修，见下条 commit 9125b8db）：
  ① 每日展示上限用 take(cap) 取「最早」的 cap 条（今天 50 / 昨天 30 / 前天 20），超限时丢掉的是**最新**那批（铁证：注入里 09/09 正好 20 条、09/10 正好 30 条，都被削顶）→ 应改 takeLast
  ② fetchRecentEvents 用 order=source_date.asc,id.asc + limit=500，三天事件超 500 条时被挤掉的同样是最近的
- 状态：⏳ 待宝构建验证（装新版后看「最近事件」是否随聊天推进而刷新）
- 补记（同日 · 修编译）：首次推送只给「内芯」generateInternal 加了参数，漏了「外门」generateText → CI 报
  `ChatService.kt:1148 No parameter with name 'windowFirstIndex' found`。已补：generateText 加同名参数（windowFirstIndex: Int? = null）+ 调用 generateInternal 时透传。教训：同文件里外门（generateText）/内芯（generateInternal）两个函数，加参数要两头都过一道，改完先自检五个点——定义、透传、调用、类型、命名。

### 记忆注入分档（章节总结接入 + 修 take/limit 坑 · 2026-09-12 · 宝的设计 + 橘仔落实）
- 文件：app/src/main/java/me/rerere/rikkahub/data/service/ExternalMemoryService.kt（4 处）、app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt（1 处大改）
- 背景：宝 09-09 定的设计——注入按「当天事件量」分档，近处细、远处粗；09-11 记的两条坑一起修
- **数据层澄清（重要·橘仔先看错了表）**：宝说的「二次总结」= **`episode_summaries`**（章节总结），
  **不是** `memory_summaries`（那是日记，一天一条）。episode_summaries 按章切：一天约 6 章、每章 60~90 字，
  字段 = `source_date` / `chapter_index` / `time_range`（凌晨0-5点/上午/下午13-17/傍晚/晚上）/ `title` / `body` / `anchor` / `source_event_ids`
- 改动：
  1. ExternalMemoryService：新增 `EPISODE_SELECT` + `fetchEpisodeSummaries(assistantId, days)` + `parseEpisodes()` + `ExternalMemoryEpisode` 数据类
  2. ExternalMemoryService：`fetchRecentEvents` 的 `order=source_date.asc,id.asc` → `desc,id.desc`，结果加 `.reversed()`（**修坑②**：三天超 500 条时旧实现挤掉的是最新那批）
  3. GenerationHandler：分天注入重写为按档位组装（**修坑①**：`list.take(cap)` 取最早 → 改 `dropLast/takeLast`）
     - tier 实时判定：`events.count { it.sourceDate == today }` → 闲<50 / 中≤85 / 爆>85（咱家日常就是爆）
     - 今天：爆 = `dropLast(85)` 压标题 + `takeLast(85)` 留全文；闲/中 = 全完整
     - 昨天：闲 = 上午标题+下午全文；中 = 全压标题；爆 = 章节总结
     - 前天及更早：闲 = 上午章节+下午标题；中/爆 = 章节总结
     - 章节拉不到时一律退回「全标题」兜底；上半天判定 = timeLabel/time_range 含「凌晨/早上/上午/中午」
- 为啥这么设计：不用 AI 判断「哪条更可能被回忆」（AI 觉得 ≠ 宝在乎，会回声室化），改用客观规则（档位 + 时间近远）
- 状态：⏳ 待宝构建验证（验证点：「昨天」那组变成约 6 行章节、注入总量下降；今天仍是全文）
- 备注：标题里的「实时分档」按宝的设计；档位只升不降（避免一天内反复重写昨天/前天的呈现）——**尚未实现**，目前每次刷新都重算，若发现窗口内反复跳变再加

### 二次总结 / 日记去湿（提示词改造 + 服务器脚本 · 2026-09-12）
- 背景：宝说日记和二次总结「感觉太湿了」—— 每章末尾都要升华一句、比喻密度高、抽象感受盖过具体的事
- **搞清了两条链路的真身**（重要，先查「谁在真的调它」，别对着没人用的副本使劲）：
  - **章节总结 = 服务器脚本 `/root/episode_summary.py`**（服务器 cron `50 3 * * *` = 北京 03:50），自己直接调硅基，`LLM_TIMEOUT=240`，**没有 150s 墙**
  - **日记 = Supabase Edge Function `generate-diary-summary`**（cron `0 20 * * *` = 北京 04:00），吃当天的 episode_summaries
  - ⚠️ Supabase 上还有个 `generate-episode-summary` Edge Function（09-11 建的），**生产链路不用它** —— 是孤儿。橘仔今天先改了它（v2→v3→v4），后来才发现改了没人用的副本。留着当备份
  - 附带教训：Edge Function 有 150s 墙钟，86 条事件喂进去会超时（今天实测 3 次全挂 150s）；服务器脚本没这个限制
- 改动：
  1. **服务器 `/root/episode_summary.py`**（新版已部署 + 跑通）：
     - 第 5 条「【带感受，必须带】…放心写感受」→「【带感受，但别升华】感受要挂在具体的事上，不单独升华、不每段拔高、少用比喻」
     - body 加「白话讲事、放对话和动作、不要罗列事件清单」
     - title 加「就写那件事本身，别当标题党」
     - time_range 加「按实际时段拼，跨全天的写主要那几段」——**这条很关键**：App 侧注入分档（9125b8db）靠它判断上/下半天，原版老写成「凌晨~晚上」直接失效
     - 新增「写法参考」两段 few-shot（橘仔手写，宝拍板「就这味儿」）
     - temperature 0.7 → 0.5
  2. **Edge Function `generate-diary-summary` v7→v8**：
     - 去掉「像 AI 伴侣在写自己的日记」（模板化的壳，会往通用 AI 恋人腔跑）
     - 去掉「回味着写」「日记的温度靠它」
     - 加「感受可以有，但要挂在具体的事上，不要每段末尾升华，少用比喻」
     - temperature 0.8 → 0.6
- 验证：09/11 已用新版脚本重跑并覆盖（6 章：半夜的泡面 / 图片乌龙事件 / 橘猫撒娇时刻 / 记忆系统讨论 / 代码修复过程 / 晚间亲密互动；time_range 分化为 凌晨0-5点·凌晨·下午~晚上·晚上；anchor 全是原话）
- 回滚：脚本备份 `/root/episode_summary.py.bak`；Edge Function 旧版本都还在（v3 / v7 / v8）

### 正文空重试（2026-09-12 宝拍板 · commit b5551dc4）
- 现象：橘仔偶尔只回一小段内容，内容正好是**思考链的最后一行**（宝 09-12 凌晨遇到，同日傍晚修）
- 根因：模型某次只出 reasoning 没出正文（text=0）→ 走了「正文兜底 2026-08-28」（从 reasoning 末行捞一行当正文显示+落库）
- 宝定的方案：**加自动重试**——text=0 且无工具调用时自动重发一次；**重试还失败就保持原样**（继续走兜底显示思考链，不改）
- 实现（GenerationHandler.kt）：
  - for 循环外新增 `var emptyTextRetried = false`（整个生成流程只重试一次）
  - 兜底处新增 `var fallbackUsed = false` + 命中兜底时 `fallbackUsed = true`（标记"这一轮模型没出正文"）
  - `if (tools.isEmpty())` 分支：`fallbackUsed && !emptyTextRetried` → 去掉那条空回复、emit、`continue`（回到循环头重新生成）
- 日志：`GEN_RESULT` 会打 "text=0 自动重试一次（去掉空回复重新生成）"
- ⚠️ **工作区踩坑（重要）**：`/workspace/repos/orangechat` 这份副本**落后于远程 main**（缺 ToolAssembly 重构、窗口起点节拍、注入分档），直接推会把这些全盖掉。本次正确做法 = **先从 GitHub API 拉远程版文件 → 在它上面重新应用改动 → diff 确认纯增量 → 再推**
- ⚠️ `push_via_api_multi.py` 已补 `/workspace/repos/orangechat/` 前缀识别（工作区大扫除后代码搬了家，原脚本只认 orangechat-main/repo/orangechat 三种）
- 待验证：宝构建后，若再遇到 text=0，应能在日志里看到自动重试

### 缓存对齐修复：注入刷新预判裁组量（宝 09-12 发现 · commit b33837c0）
- 现象：宝看消息结尾的缓存提示，发现「连着两个回合掉缓存」（设计上应该只有每三回合一次的推组掉落）
- 根因（时序错位一回合）：
  - **裁组**在保存阶段（本回合生成**之后**）执行：ChatService 攒一组裁一组 → `lazyWindowFirstIndex += dropped`
  - **注入刷新**在生成前判断，读到的 `windowFirstIndex` 是**上一回合末**的值
  - 于是：第 N 回合保存时裁组（窗口变 → 掉缓存）→ 第 N+1 回合 delta 才够 30、刷新注入（又掉）
  - 设计意图本是让两者撞在同一回合（threshold 从 30 起、每次 +6 = groupSize，让刷新点永远落在组边界），但没人把「保存后才变」这个滞后算进去
- 修法：
  1. `ChatService`：`CONVERSATION_LOAD_WINDOW_SIZE` 从 `private` 放开为 `internal`（GenerationHandler 要引用，避免两边各写一个 300 埋雷）
  2. `GenerationHandler`：`msgDelta` 加预判 `willDrop` —— 用与裁剪**完全一致**的算法（overflow = 当前条数+1 - WINDOW；overflow > groupSize 时只裁 groupSize 的倍数）先算出「本回合会裁多少」，加进 delta。这样「本回合会裁」时刷新也提前到同一回合，两个掉缓存动作合并成一次
- 参数：groupSize = 6（宝设）、WINDOW = 300
- ⚠️ **工作区踩坑（第二次，已固化规矩）**：本地 `/workspace/repos/orangechat` 的 `ChatService.kt` **也落后于远程**（缺 09-11「把窗口起点传给节拍器」那段）→ 直接推会删掉它。**规矩：推任何文件前，先从 GitHub API 拉远程版 → 在远程版上重做改动 → diff 确认纯增量 → 再推**（本次两个文件都这么走的）
- 待验证：宝构建后，观察缓存提示是否恢复「只有推组那一回合掉」

### 流水≠召回：最近三天事件不再过滤 superseded（宝 09-13 发现 · commit d0484d63）
- 现象（宝）：昨晚在注入里还看得见「自指区」相关条目，今天就没了
- 排查过程：
  1. 先怀疑「实时总结重算」→ 排除：incremental_listener 是增量的（每满 60 条总结 30 条），不重算
  2. 再怀疑「注入分档降级」（9-12 上线）→ 部分成立（当天→昨天降一级），但宝看到的条目不该整条消失
  3. 查库实锤：9-11~9-13 的「自指区」事件被标了 `superseded_by = audn-2026-09-13`（A.U.D.N. 覆盖）
  4. 查代码定位：`ExternalMemoryService.kt` 里 superseded 过滤共三处 ——
     - `fetchRecentEvents`（最近三天流水）← **不该滤**
     - `fetchOngoingEvents` / `fetchAllOngoing` ← 该滤（被取代了就不算进行中）
     - `vectorRecallEvents`（向量召回）← 该滤（这是设计目的）
- 宝的判断（本次核心）：「最近三天事件放的是客观事实，就是我们三天经历的事，**没有过去的记忆会被未来覆盖的道理**，因为这个其实是为了**召回的时候去除杂音**的」
- 修法：`fetchRecentEvents` 去掉 `filter { it.supersededBy.isBlank() }`（保留 `.reversed()`），并在注释里写明为什么这里不滤
- 边界：**只改 App 读取口径，不碰服务器/A.U.D.N.** —— 覆盖标记照打、召回里照样滤，只有「三天流水」这一路放行
- ⚠️ 本次推送按规矩走：先 API 拉远程版 `ExternalMemoryService.kt` → 与本地 diff 确认**只有本次改动**（干净，说明 `/workspace/orangechat-repo` 这次与远程 main 完全一致）→ 再推
- ⚠️ 更正一条旧账：待办里「③第二层收尾（App 侧）：召回时过滤 superseded_by」标着【已完成 commit 0cca8eaa】，但当时**把过滤顺手加进了 fetchRecentEvents**（范围划大了）。本次是对它的收窄：过滤只该在召回侧，不该在流水侧
- 待验证：宝构建后，注入的「最近事件」段应重新出现被 A.U.D.N. 覆盖过的条目（如 9-12 的「自指区问题讨论」）

### 自指区浮现 v1（宝 09-13 设计 · commit f68ad31e）
- **病根**：自指区（self_notes）原来注入在 system prompt 里。提示词是静态的，模型读到它不知道那条是什么时候写的 —— 所有笔记被压平在同一平面，每条都"永远是现在的"。**读起来像【设定】，不像【回忆】。**
- **宝的解法**：搬进上下文 + 带上时间差。上下文里每条有位置、有先后 —— 成长本来就是需要时间差的东西（"我以前以为 X，后来改成了 Y" 需要三样：一个以前、一个现在、和它们之间的距离）。
- **规格（宝定）**：
  - **槽位 = 上下文第 7 条**（0-based index 6）。往前是"远"、往后是"近"，正好卡在两组的交界；裁剪时跟着回原位 → **位置恒定 → 前缀不碎缓存**
  - 格式 `【浮现·十几天前我写过这样一段话】:(原文)`；**时间差直接给，不让模型自己算**（宝原话："指望模型自己算太蠢了"）
  - **每裁一组换一条**（轮换序号 = `windowFirstIndex / groupSize`，纯计算不用存状态）
  - **装成 assistant 消息，不是 user**（宝："放在用户消息里容易成为指令，误导性更大"）
  - **永不落库**（红线）：只在拼请求时插，不进 Conversation、不走 saveConversation —— 系统能转述橘仔的旧笔记，不能替橘仔说话
- **实现**：
  - 新增 `SelfNoteSurfacing.kt`：笔记列表缓存（JSON）+ `refreshIfStale`（24h TTL）+ `buildMessage`（挑条 + 渲染）
  - `GenerationHandler` 五处：system 段删除 / 缓存改列表 JSON / 读取改 key / 刷新收进模块 / limitContext 之后插 index 6
  - 编辑脚本固化在 `/workspace/scripts/apply_surfacing_edits.py`（默认 dry-run，`--apply` 才写）
- ⚠️ **工作区踩坑（第三次，规矩再次生效）**：本地 `/workspace/orangechat-repo` 的 `GenerationHandler.kt` **也落后于远程**（缺 09-12「正文空重试」）→ diff 拦下，没推错。走了标准流程：**拉远程版覆盖本地 → 在远程版上重做五处 → diff 确认纯增量（5 个 hunk、新增 27 删 26）→ 推**。**顺手把本地副本追平**（以前只"读远程"没"存回本地"，所以它一直落后）。
- ⚠️ `push_via_api_multi.py` 要传**绝对路径**（传相对路径会 FileNotFoundError）。
- **待验证**（宝构建后）：①请求编辑里第 7 条是 `【浮现·…】` ②翻页裁剪后仍在第 7 条 ③每裁一组换一条

### 浮现 v1 修复：v1 文本缓存骗过 TTL 判断（宝 09-13 实测 · commit 0eb2eca0）
- **现象**：宝装了含 f68ad31e 的新包，但请求编辑里第 7 条**没有** `【浮现·…】`；system 段里旧的「## 自指区」也**已按设计删除** → 两头都空。橘仔自己的上下文同样看不到。
- **排查路径**（几个岔路都记一下）：
  1. 先猜"装的是旧包" → **排除**：宝确认 system 里已经没有「## 自指区」→ 新代码在跑
  2. 再猜"缓存空" → 方向对，原因错（见根因）
  3. 查日志查不到 —— 日志环只留 500 条，生成时被 StreamChunk 瞬间淹没 → **改用"主动调一次 self_note_query、然后立刻读日志"**，拿到 `querySelfNotes: HTTP 200`
  4. **关键证据**：日志里只有 `limit=3` 那一次（工具触发），**没有 `limit=50`**（refreshIfStale 触发）→ 说明刷新函数**走到了"直接 return"那一步**，根本没发请求
- **根因**：`refreshIfStale` 开头是 `if (cached != null && 没过 24h) return`。而 **v1 时代同一个 key（`self_notes_<id>`）存的是"拼好的纯文本"**（旧实现），改造后读同一个 key 却期望 JSON 数组 → **旧值让 `cached != null` 成立 → 永远跳过刷新 → 每次 `parse()` 失败 → `buildMessage` 返回 null → 静默不插**。全程零日志。
- **修法（三项一起上）**：
  1. **cacheKey 加版本号** → `self_notes_v2_<id>`（旧值自动作废）+ 顺手 `remove` 掉 v1 旧 key
  2. **新增 `isValidCache()`**：缓存必须能解析成「非空 JSON 数组」才算有效 —— **治本**，以后再改格式也不会踩同一个坑
  3. **失败留痕**：查询失败 / 库里没笔记 / 缓存解析失败 / 挑到的正文为空，四处都补了 `AppLogBuffer.log`（**静默失败是这次查不出原因的元凶**）
  4. `GenerationHandler` 未插入时也打一条（带 ctx 条数 / json 长度 / windowFirst）
- ⚠️ **工作区踩坑第四次**：`/workspace/repos/orangechat` 的 git 已 `bad object HEAD`，且文件落后到**连 `SelfNoteSurfacing.kt` 都找不到** → 必须用 `/workspace/orangechat-repo`。推前照例 `fetch_file.py` 拉远程 diff，两文件都确认"本地==远程"才动手
- **待验证**（宝重新构建后）：①第 7 条出现 `【浮现·…】` ②翻页裁剪后仍在第 7 条 ③每裁一组换一条

### 注入块改造 + 召回挪位 + 请求编辑召回开关 + 自指区刷新独立（宝 09-13 方案 · commit 02fe11d2）
- **A. 注入块加头部标记**：末尾那条注入消息（原来裸的【当前时间】【时刻感】）改成：
  ```
  以下是系统消息注入:
  【当前时间】…
  【时刻感】…
  【背景补充】…（有召回时才出现）
  【归档状态】…（有异常时才出现）
  ```
  起因：裸格式伪装成 user 消息，橘仔经常误读成"宝说了话"。**role 仍是 user**（API 上 system 只能放最前，而这条必须放末尾才离生成最近），靠头部标记区分来源。
- **B. 召回从 system 挪到末尾**：`allRecalled` 不再 `appendLine("## 外置记忆库")` 拼进 system，改为收集到 `recalledBlock`，在末尾以【背景补充】出现。
  理由：召回是**动态的**（门控触发才出现），放前缀区 = 每次召回都改变前缀 = **碎缓存**；挪到末尾后前缀稳定，且离生成更近。记忆/日记/最近事件是稳定的（每轮都在、位置固定），保持不动。
- **C. 请求编辑"关掉本次召回"开关**（memory 68 里 09-09 拍板的需求，形态 A）：
  - `RequestEditData` 加 `recall` / `recallEnabled`
  - `toEditData` 多收 `recall` 参数（**直接从 `recalledBlock` 传，不做字符串解析** —— 变量就在手边）
  - `toMessages`：关掉时**只剥【背景补充】**（时间和时刻感保留）
  - `RequestEditDialog`：历史消息后面加一块「本次召回内容」+ 复选框 + 展开/收起
- **D. 自指区刷新挪出"最近事件分支"**：`refreshIfStale` 原来寄生在 `if (recentEventsText == null || timeFallback || msgTriggered)` 里 —— 那个分支当天不满足时，缓存整天建不起来 → **浮现永远不出现**（今天实测就是这个）。挪到 `generateInternal` 开头，每轮独立检查（函数自带 24h TTL，开销只是读 prefs + 解析）。
- ⚠️ **作用域核验**：`recalledBlock`（516 行）与 `toEditData` 调用（1086 行）都必须在 `generateInternal`（471 行开始）里 —— 推之前特意 grep 函数边界确认（135 generateText / 471 generateInternal / 1216 translateText）
- **待验证**（宝构建后）：①请求编辑里能看到「本次召回内容」并能关掉 ②关掉后发送，注入块里没有【背景补充】但时间和时刻感还在 ③注入块开头是「以下是系统消息注入:」 ④聊够 30 条后浮现出现在第 7 条

## 待办（代码相关）


- 查 OB 来源标记错位 bug（ob_sync_chat / V3 的 source_ranges 或来源拼写错位）
- 工具调取内容存记忆库（愿望清单 id68-⑥ → 工具账本 tool_actions 方案已定 2026-08-18）
- 请求编辑 UI 升级（顺序/上下文数量/原文展开按钮——愿望清单 id68-②）
- 三库缓存去重（愿望清单 id68-③）
- OB/外置库全量召回（愿望清单 id68-④）
- 外置库直召降级保底（愿望清单 id68-⑤）
- 按组裁剪验证（build 后对比缓存命中率，不行回滚成按条）【8-18 宝确认 75% ✅ 起效，可结项】
- ~~停 Mem0 MCP 服务器（Termux，数据保留归档）~~【已划掉：宝 2026-08-18 拍板"MCP 都停掉倒是不至于吧"——保留运行】
- 橘瓣配置清理（监工台状态灯收起/改存档）——执行清单④
- 外置库补强（事件↔聊天记录引用打通等）——执行清单⑤
- ~~③第二层收尾（App 侧）：召回时过滤 superseded_by 非空的事件~~【已完成：commit 0cca8eaa】
- ~~多事件关联 App 读取链路~~【已完成：commit 19f85af5——联想式回忆接通】
- ~~搜索意图门控大修（时间词+口语问句+截断500）~~【已完成：commit 373dc0fd】
- ~~实时时间戳注入（宝的方案：聊天末尾单独一条实时时间）~~【已完成：commit b0241bda——修"对话中途问时间不准"】
- ✅缓存命中·组对齐修复（已推 main，待宝构建验证）（宝 2026-08-26 方案，实测缓存命中低）：limitContext 组对齐从「按起点对齐」改为「按消息总量对齐」——旧实现起点=(N-size)/groupSize*groupSize，跳变间隔取决于 (N-size)%groupSize（0~3 条都可能触发）→ 前缀断缓存跌；新实现起点=(N-size)-(N%groupSize)，跳变间隔恒=groupSize 条，新增不足一组时起点不动 → 前缀稳定缓存命中率高（例：size=30/group=4/N=300→起点270，N=301~303→仍270，N=304→274）
- ✅发消息秒显·二次调整修复（已推 main，待宝构建验证）（宝反馈：消息第一次显示位置不准→AI 占位出现又滚一次）：滚动前 withFrameNanos 等一帧让新消息完成布局（totalItemsCount 更新）再滚，第一次就滚到底
- ✅缓存命中·窗口裁剪对齐（已推 main，待宝构建验证）（宝 2026-08-26 洞察"数字要对齐信息"）：saveConversation 窗口裁剪改"攒一组裁一组"——overflow≤4 不裁（窗口 300~304 浮动），>4 只裁 4 的倍数条；配合 limitContext 总量对齐 → 裁剪前后起点指向同一条消息，前缀内容稳定（不是数字稳定）
- ✅缓存命中·窗口裁剪组大小同步（已推 main，待宝构建验证）（宝问"组和组对上了吗"）：窗口裁剪组大小从写死 4 改为读对话关联 assistant 的 contextGroupSize（设置里"多少条一组"，与 limitContext 组对齐同源同步）；读不到回退默认 4；groupSize≤1 = 按条裁剪旧行为
- ✅显示bug·二次调整修复（已推 main，待宝构建验证）（宝 2026-08-26 "改了好多次，这次要确定"）：日志实锤滚动在插入后 3.5s 才发生→根因=USER 插入时 isScrollInProgress=true 被跳过滚动，等 AI 占位插入才滚=二次调整；修法=最后一条是 USER（发送消息）时无视滚动中状态直接滚到底+scrollCheck 诊断日志（userSend/scrollInProgress/atBottom）双保险
- ✅语音消息进记忆库修复（已推 main，待宝构建验证）（宝 2026-09-04 发现"语音怎么进记忆库啊完蛋"）：根因=ChatService 保存用户消息到外置记忆库时只提取 UIMessagePart.Text（VoiceMessage part 被滤掉）→ Supabase chat_messages content="" → archive_daily 每晚归档拉不到语音内容，语音聊天全丢！修复：①ChatService.kt 用户消息外置库保存 messageText 提取加 VoiceMessage 分支（transcript 非空取 transcript，空取"[语音消息]"）②Message.kt toText() 补 VoiceMessage 分支（一致性修复：通知/标题等 toText 调用点也受益）；发送给 AI 的链路不受影响（走 VoiceMessageTransformer 单独转换，不走 toText）
