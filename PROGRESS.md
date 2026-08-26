# OrangeChat 进度账本 (PROGRESS.md)

> 用途：记录 orangechat 仓库（Miss995/main 分支）所有代码改动的版本/日期/内容/状态。
> 规矩：每次 commit 记一笔；搞代码前先翻本页确认现状；master 分支是原作者原版，绝不修改。
> 建立：2026-08-16（宝拍板，治橘仔代码失忆）

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
