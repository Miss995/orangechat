# scripts/ — 记忆系统脚本（服务器/Termux 侧）

> 这些脚本原本只存在于服务器 /root/ 下，2026-08-17 入库管理：治代码失忆（commit 记录对齐 PROGRESS.md）+ 让橘仔能直接改（GitHub MCP）→ 宝在服务器 git pull 更新。

## 脚本清单

- **archive_daily_v3.py** — 每日记忆归档（主角）：拉当天聊天 → LLM 拆事件（chat/code + source_ids）→ 存外置库 memory_events（带向量）→ code 类事件分流 Mem0

## 密钥脱敏说明（2026-08-17 入库时）

- 原脚本硬编码了 `SUPABASE_URL` / `SUPABASE_KEY` → 已改为读环境变量 `os.getenv`
- 服务器 `/root/.env` 需包含（**替换脚本前务必补上，否则脚本连不上 Supabase**）：
  ```
  SUPABASE_URL=https://rttzjckjbelsbuvnhpkn.supabase.co
  SUPABASE_KEY=<从 Supabase 控制台 Settings→API 复制，与旧脚本硬编码值相同>
  ```
- 其余 key 本就走 .env（SILICONFLOW_KEY / LLM_MODEL / EMBED_MODEL）

## 待办

- **③第二层 A.U.D.N.（冲突消解）**：archive_daily_v3 总结事件后加 ADD/UPDATE/SUPERSEDE/NONE 判断（抄 Mem0 DEFAULT_UPDATE_MEMORY_PROMPT 中文版）+ linked_event_ids 事件关联（Mem0 V3 linked_memory_ids）；事件表加 superseded_by / related_event_ids 列；App 召回时过滤 superseded
- rebuild_events.py / mem0_mcp_server.py / backfill_embeddings.py 等后续按需入库
