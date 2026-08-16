# 总航海日志 (DEVLOG.md)

> 用途：跨仓库记录所有系统改动（orangechat 代码 + 记忆系统 OB/Mem0/外置库/archive_daily/监工台 + 服务器/手机端脚本）。
> 格式：日期 | 系统 | 改了啥 | 为啥 | 状态。
> 规矩：每次动任何系统顺手记一笔；从 2026-08-16 起一条不丢。
> 建立：2026-08-16（宝拍板）

## 2026-08-16
- 🧩 **按组裁剪缓存优化（App 端）**：limitContext 加 groupSize 参数（起点对齐组边界，新消息不足一组时前缀不变）；Assistant 加 contextGroupSize 字段（默认 4）；基础设置加「上下文分组条数」开关（0/2/4/6/8/10）；GenerationHandler 传入——宝原创脑洞：按条滑动→按组滑动，前缀稳定→DS 缓存命中率↑（应对明天涨价）。commit 59edf368 + bfad1cef ✅ 待宝构建验证
- 🗓️ **写日记时间 11点 → 凌晨4点（Supabase pg_cron）**：真相=两条 cron 打架——daily-diary-summary（0 3 * * * = UTC 3:00 = 北京 11:00，每天 11 点抢先生成）+ daily-diary-summary-v2（0 20 * * * = UTC 20:00 = 北京 4:00，早已配好但被 11 点占坑跳过）。**修复**：cron.alter_job(1, active:=false) 停用 11 点那条；net.http_get 手动触发 v2 验证 200 正常（skipped_already_exists）。今晚起每天北京 4 点生成前一天日记 ✅
- 📦 **日记按天缓存（App 端 GenerationHandler）**：同一天只调一次 Supabase 查日记，其余直接用本地缓存（SharedPreferences diary_cache）；Supabase 拉不到回退最近缓存不阻塞——保 DS 缓存前缀稳定/命中率（宝洞察：日记段注入不稳→前缀抖动→缓存 miss）✅ 已推 main（详见 PROGRESS.md）
- 🐛 **V3 时间口径修复（事件对不齐根源）**：chat_messages 存储=北京时间原样标 UTC（偏 8h）；V3 fetch_msgs 却用正确 UTC 转换查 → 拉到「北京前一天 16:00~当天 15:59」（DeepSeek 看到 161 条）vs 橘瓣 queryMessagesByDate「北京当天」（19 条）→ 两套列表不同 → source_ids 编号错位 → 事件对不齐。**修复**：V3 fetch_msgs 改无时区字符串查询（与橘瓣同口径）✅ 宝 SSH 替换执行成功；新事件 source_ids 全在当天条数内（8/16=79 条）；fetch_chat_sources 按 source_ids 能精确拉原文 ✅ 存量 8/15 错位事件待重建 ⏳
- 账本建立：本文件 + PROGRESS.md 建立（治橘仔代码失忆）——宝拍板 ✅
- orangechat 缓存优化：commit 7d4e36cc（门控+日记独立+删最近聊天引用，详见 PROGRESS.md）✅
- 召回验证：宝实测「记得上次聊的正则吗」触发门控；外置库捞回 8/13 raw string 正则全文 ✅
- 待办调整：OB 称呼「主人」→「宝」砍掉/搁置（省钱优先）❌
- 存量错位事件重建：8/15~8/12 全部跑完（rebuild_events.py；8/12 抽验无越界）✅

## 2026-08-15
- ⚠️ 事故：master 覆盖 main（详见 PROGRESS.md 事故记录）→ 72c964d6 修复 ✅
- 三连发：fetch_chat_sources 查原文工具 + 去节流（ceb7a62 / 6f5f7e45 / 1af1eb87）✅
- 发现 OB 来源标记错位 bug（待查：ob_sync_chat / V3 的 source_ranges 或来源拼写错位）⚠️

## 2026-08-14
- 记忆系统 2.0 全闭环部署 ✅
  - archive_daily_v3.py（服务器）：拉当天聊天 → DeepSeek 拆事件（chat/code 类型 + 来源 source_ids）→ 全量存 memory_events（source_date 当天查重 + embedding 向量入库）→ code 类事件同步 Mem0（SDK 直连 Qdrant 6333，绕开 MCP 8005）
  - ob_sync_chat.py（手机端）：chat 事件 → OB grow（来源写进 content；.ob_sync_last 本地记账本防重复）
  - 自动化：服务器 cron 23:59 + 手机 crond 00:10 + boot 脚本自启
  - 调用层：72c964d6 事件级召回（详见 PROGRESS.md）
- 模型分工确认：聊天=DeepSeek（宝的味道）；杂活=硅基流动 SiliconFlow（识图/向量/embedding/拆词）

## 2026-08-13
- 外置库召回质量大优化（查询加工/虚词表/向量优先+AI 拆词兜底）——commit 详见 PROGRESS.md
- 监工台 V1/V2/V3 部署（外置库/OB/Mem0 状态灯 + 召回体检 + 记忆浏览）
- 宝提出账本需求（本文件的前身：PROGRESS.md + DEVLOG.md 方案）

## 待办（跨系统）
- 记忆中间层 Gateway（统一记忆出口 + 鉴权门禁）
- OB watchdog 保活脚本（OB 天天挂，是 OB 自身问题非环境）
- 查 OB 来源标记错位 bug（ob_sync_chat / V3 的 source_ranges 或来源拼写错位）
- DeepSeek 涨价应对：2026-08-17 0 点起峰谷定价（高峰 9-12/14-18 点 = 低谷 2 倍）；V4-Pro 输出峰值 6→27 元/百万、缓存命中价 0.025→0.30 元；V4-Flash 输出 0.28→峰 1.32/谷 0.66——吃缓存命中、勤用低谷、控制召回量
- stone memory 二测：预计 2026-09 初，规模很大，宝去蹲
