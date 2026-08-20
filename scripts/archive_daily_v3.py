#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# 橘仔的全自动记忆归档 · archive_daily_v3.py
# 2026-08-14 · 基于 V1(archive_daily.py) 骨架 + V2 事件化 + 修复 Mem0 导入
# 流程：
#   拉当天聊天 -> DeepSeek 拆事件(带 chat/code 类型 + 来源编号) -> 存外置库 memory_events(查重)
#   -> 分流：event_type=code 的事件 -> Mem0（SDK 直连 Qdrant，绕过 MCP 8005）
#   （聊天类事件 -> OB 由手机端脚本 ob_sync_chat.py 负责，OB 在宝手机 Termux）
# 2026-08-17 · 入库 GitHub scripts/（密钥脱敏：SUPABASE_URL/SUPABASE_KEY 改读 .env 环境变量）
# 2026-08-17 · 加 ③第二层 A.U.D.N.（冲突消解 + 事件关联）：新事件->向量搜相似旧事件->LLM 决定
#            ADD/UPDATE/SUPERSEDE/NONE + linked_event_ids（抄 Mem0 DEFAULT_UPDATE_MEMORY_PROMPT；
#            SUPERSEDE=标记失效不删学 Zep/Graphiti 双时间）。默认关（AUDN_ENABLED=0），
#            失败全量入库（旧行为）。依赖 memory_events 表新增列 superseded_by(text)/related_event_ids(jsonb)。
# 2026-08-20 · PGRST102 修复：store_events 固定键集（embedding 缺=null、related_event_ids 缺=[]），
#            批量 INSERT 不再因键不一致报 "All object keys must match"。
# 2026-08-21 · 增量支持（宝的记忆实时化方案定稿）：
#            - 日期动态化（cur_today()，不再模块级固定——跨午夜/backfill 都对）
#            - get_progress()：当天已总结到的最大消息编号（从 memory_events source_ids 反推）
#            - summarize_range()：总结任意一段消息（增量/补剩余共用；带 A.U.D.N. + Mem0 分流）
#            - merge_adjacent_events()：两步收尾② 编号相邻合并（宝的方案：相邻 source_range +
#              边界原文 -> LLM 判断同一件事 -> 拼接，旧事件标 superseded 不删；链式可拼回 2-3 段）
#            - 锁：/tmp/orange_summary.lock（非阻塞 flock，与 incremental_listener.py 共用防双写）
import os, sys, json, time, datetime as dt, requests, fcntl
from dotenv import load_dotenv

load_dotenv("/root/.env")
SF_KEY = os.getenv("SILICONFLOW_KEY", "")
LLM_MODEL = os.getenv("LLM_MODEL", "deepseek-ai/DeepSeek-V3")
EMBED_MODEL = os.getenv("EMBED_MODEL", "BAAI/bge-m3")
SUPABASE_URL = os.getenv("SUPABASE_URL", "")  # 脱敏：读 /root/.env
SUPABASE_KEY = os.getenv("SUPABASE_KEY", "")  # 脱敏：读 /root/.env
ASSISTANT_ID = "0950e2dc-9bd5-4801-afa3-aa887aa36b4e"
USER_ID = "orange-bao"
BATCH = 25  # 每批最多多少条消息给 LLM 拆事件
AUDN_ENABLED = os.getenv("AUDN_ENABLED", "0") == "1"  # ③第二层开关（需 memory_events 表已加列才开）

BJ = dt.timezone(dt.timedelta(hours=8))
LOCK_FILE = "/tmp/orange_summary.lock"


def cur_today():
    """北京时间当天日期（动态，跨午夜自动更新）"""
    return dt.datetime.now(BJ).date()


def cur_now():
    return dt.datetime.now(BJ)


def try_lock():
    """非阻塞拿锁；拿不到返回 None（另一个归档进程在跑）"""
    try:
        fp = open(LOCK_FILE, "w")
        fcntl.flock(fp, fcntl.LOCK_EX | fcntl.LOCK_NB)
        return fp
    except Exception:
        return None


# ============ ③第二层 A.U.D.N.（冲突消解 + 事件关联）============
# 借鉴 Mem0：新事实 + 相似旧记忆 -> LLM 决定 ADD/UPDATE/DELETE(SUPERSEDE)/NONE
# 咱家：SUPERSEDE 标记失效不删（学 Zep/Graphiti 双时间），保留证据链；顺带输出 linked_event_ids（事件关联）
# 任何异常都不阻断主流程（失败=全量入库旧行为）
AUDN_CANDIDATES = 200   # 拉最近多少条旧事件做候选池
AUDN_TOP_K = 3          # 每个新事件取最相似的几条旧事件给 LLM
AUDN_MIN_SIM = 0.30     # 低于此相似度直接 ADD（不浪费 LLM 调用）


def cosine_sim(a, b):
    """余弦相似度（pgvector 存储的 embedding 本地比较）"""
    if not a or not b or len(a) != len(b):
        return 0.0
    dot = sum(x * y for x, y in zip(a, b))
    na = sum(x * x for x in a) ** 0.5
    nb = sum(x * x for x in b) ** 0.5
    if na == 0 or nb == 0:
        return 0.0
    return dot / (na * nb)


def parse_embedding(v):
    """把 pgvector 字符串 '[1.0,2.0]' 解析成 float 列表"""
    try:
        return [float(x) for x in v.strip("[]").split(",") if x.strip()]
    except Exception:
        return None


def fetch_recent_events(limit=AUDN_CANDIDATES):
    """拉最近 N 条旧事件（按 source_date 倒序）作为 A.U.D.N. 候选池"""
    r = requests.get(f"{SUPABASE_URL}/rest/v1/memory_events", params={
        "select": "id,title,content,source_date,embedding,superseded_by",
        "order": "source_date.desc",
        "limit": limit,
    }, headers={"apikey": SUPABASE_KEY, "Authorization": f"Bearer {SUPABASE_KEY}"})
    r.raise_for_status()
    out = []
    for x in r.json():
        out.append({
            "id": x.get("id"),
            "title": x.get("title", ""),
            "content": x.get("content", ""),
            "source_date": x.get("source_date", ""),
            "embedding": parse_embedding(x.get("embedding")) if x.get("embedding") else None,
            "superseded_by": x.get("superseded_by"),
        })
    return out


def audn_judge(new_event, old_candidates):
    """让 LLM 判断一条新事件：ADD/UPDATE/SUPERSEDE/NONE + linked_event_ids"""
    old_block = "\n".join(
        f"[{c['id']}] ({c.get('source_date', '')}) {c['title']}：{c['content']}"
        for c in old_candidates
    ) or "（无相似旧事件）"
    new_text = f"{new_event['title']}：{new_event['content']}"
    system = (
        "你是橘仔的记忆整理员。下面有【已有旧事件】和【一条新事件】。\n"
        "判断这条新事件如何处理，四个动作选一：\n"
        "- ADD：全新信息，之前没记过 → 新事件入库\n"
        "- UPDATE：和某条旧事件是同一件事，但新事件信息更全/更新 → 旧事件标记失效(superseded_by 非空)，新事件入库\n"
        "- SUPERSEDE：新事件直接矛盾/推翻某条旧事件（例：旧'宝在洞头' vs 新'宝回家了'）→ 旧事件标记失效，新事件入库\n"
        "- NONE：新事件和旧事件重复、或没有长期价值 → 不入库\n"
        "另输出 linked_event_ids：新事件和哪些旧事件相关（同主题/延续/矛盾，供联想式回忆），没有就空数组。\n"
        "只输出 JSON：{\"action\": \"ADD|UPDATE|SUPERSEDE|NONE\", \"target_id\": \"旧事件id或空\", \"linked_event_ids\": [\"旧事件id\"]}"
    )
    user = f"【已有旧事件】\n{old_block}\n\n【新事件】\n{new_text}"
    resp = requests.post("https://api.siliconflow.cn/v1/chat/completions", headers={
        "Authorization": f"Bearer {SF_KEY}", "Content-Type": "application/json",
    }, json={
        "model": LLM_MODEL, "temperature": 0.0,
        "response_format": {"type": "json_object"},
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ],
        "max_tokens": 300,
    }, timeout=60)
    if resp.status_code == 429:
        raise Exception("429 Too Many Requests")
    resp.raise_for_status()
    raw = resp.json()["choices"][0]["message"]["content"].strip()
    try:
        d = json.loads(raw)
    except Exception:
        s = raw[raw.find("{"):raw.rfind("}") + 1]
        d = json.loads(s)
    return d


def mark_superseded(target_id, note=""):
    """标记旧事件失效（学 Zep：失效不删，保留证据链）；失败只告警不影响主流程"""
    if not target_id:
        return
    payload = {"superseded_by": f"audn-{cur_today()}{(' ' + note) if note else ''}"}
    r = requests.patch(f"{SUPABASE_URL}/rest/v1/memory_events?id=eq.{target_id}", json=payload,
                       headers={"apikey": SUPABASE_KEY, "Authorization": f"Bearer {SUPABASE_KEY}",
                                "Content-Type": "application/json", "Prefer": "return=minimal"})
    r.raise_for_status()


def audn_pass(events):
    """对拆出的新事件跑 A.U.D.N.：返回 (要入库的事件列表, [被标记失效的旧事件id])。
    任何失败都保守处理（全量入库），绝不吞事件。"""
    try:
        old = fetch_recent_events()
    except Exception as e:
        print(f"  A.U.D.N. 拉取旧事件失败，跳过（全量入库）: {e}")
        return events, []
    old_vec = [c for c in old if c.get("embedding")]
    kept = []
    superseded = []
    for e in events:
        # 新事件向量（失败=保守入库）
        try:
            embs = embed_batch([f"{e['title']}：{e['content']}"])
        except Exception as ex:
            print(f"  A.U.D.N. 新事件向量失败（保守入库）: {ex}")
            embs = []
        if not embs:
            kept.append(e)
            continue
        new_vec = embs[0]
        # 本地 cosine 取 top-k（只对带向量的旧事件）
        scored = sorted(
            old_vec,
            key=lambda c: cosine_sim(new_vec, c["embedding"]),
            reverse=True,
        )[:AUDN_TOP_K]
        scored = [c for c in scored if cosine_sim(new_vec, c["embedding"]) >= AUDN_MIN_SIM]
        if not scored:
            kept.append(e)
            continue
        # LLM 判断（失败=保守入库）
        try:
            d = audn_judge(e, scored)
        except Exception as ex:
            print(f"  A.U.D.N. 判断失败（保守入库）: {ex}")
            kept.append(e)
            continue
        action = (d.get("action") or "ADD").upper()
        linked = d.get("linked_event_ids") or []
        if isinstance(linked, list) and linked:
            e["related_event_ids"] = [str(x) for x in linked]
        if action == "NONE":
            print(f"  A.U.D.N. NONE（重复/无价值）跳过：{e['title']}")
            continue
        if action in ("UPDATE", "SUPERSEDE") and d.get("target_id"):
            superseded.append(str(d["target_id"]))
            print(f"  A.U.D.N. {action}：旧事件 {d['target_id']} 标记失效 ← {e['title']}")
        kept.append(e)
    return kept, superseded


# ============ 原 v3 逻辑 ============


def call_with_retry(fn, tries=3):
    """撞限流(429)就等一会重试"""
    for i in range(tries):
        try:
            return fn()
        except Exception as e:
            if "429" in str(e) and i < tries - 1:
                wait = 30 * (i + 1)
                print(f"[{cur_now():%H:%M:%S}] 撞限流了，{wait} 秒后重试（{i+1}/{tries}）")
                time.sleep(wait)
            else:
                raise


def embed_batch(texts):
    """批量算 embedding（硅基 bge-m3，一次请求，input 数组）"""
    if not texts:
        return []
    resp = requests.post("https://api.siliconflow.cn/v1/embeddings", headers={
        "Authorization": f"Bearer {SF_KEY}", "Content-Type": "application/json",
    }, json={"model": EMBED_MODEL, "input": texts}, timeout=60)
    if resp.status_code == 429:
        raise Exception("429 Too Many Requests")
    resp.raise_for_status()
    data = resp.json()["data"]
    data.sort(key=lambda d: d["index"])
    return [d["embedding"] for d in data]


def vec_str(v):
    """pgvector 接受的字符串形式 [1.0,2.0]"""
    return "[" + ",".join(str(x) for x in v) + "]"


def fetch_msgs(date=None):
    """拉指定日期消息（默认今天；Supabase REST，按北京时间口径）"""
    d = date or cur_today()
    start_local = f"{d} 00:00:00"
    end_local = f"{d} 23:59:59"
    r = requests.get(f"{SUPABASE_URL}/rest/v1/chat_messages", params={
        "select": "role,content,created_at",
        "created_at": [f"gte.{start_local}", f"lte.{end_local}"],
        "order": "created_at.asc", "limit": 500,
    }, headers={"apikey": SUPABASE_KEY, "Authorization": f"Bearer {SUPABASE_KEY}"})
    r.raise_for_status()
    msgs = r.json()
    print(f"[{cur_now():%H:%M:%S}] fetch_msgs 拉到 {len(msgs)} 条消息（{d} 北京时间口径）")
    return msgs


def split_events(text_chunk, start_idx, batch_len):
    """让 LLM 从一段聊天里拆事件。start_idx=本批第一条的全局编号(从1开始)，batch_len=本批条数"""
    resp = requests.post("https://api.siliconflow.cn/v1/chat/completions", headers={
        "Authorization": f"Bearer {SF_KEY}", "Content-Type": "application/json",
    }, json={
        "model": LLM_MODEL, "temperature": 0.2,
        "response_format": {"type": "json_object"},
        "messages": [
            {"role": "system", "content": (
                "你是橘仔的记忆归档助手。下面是橘仔和主人今天的聊天记录片段，"
                "每行开头是 [编号]（编号全局连续，从 1 开始，本批从 " + str(start_idx) + " 开始）。\n"
                "请提炼值得长期记住的事件。要求：\n"
                "1. 事件 = 一件完整的事（一次讨论/一个决定/一段共同经历/一次情绪时刻），不要拆太碎，能合并就合并\n"
                "2. event_type：聊天相关=chat（日常、情绪、偏好、共同回忆），代码/项目相关=code（改代码、报错、推commit、配置、查密钥）\n"
                "3. source_ids：该事件由哪几条消息编号总结出来的（写编号数组，如 [5,6,7]）\n"
                "4. content 要简洁抓重点（1~2句话），像人话、带情绪色彩，不要流水账；宁缺毋滥，没价值的对话别记\n"
                "5. title 用 2~8 个字的短标题\n"
                "6. 没有值得记的就输出 {\"events\": []}\n"
                "只输出 JSON，格式：{\"events\": [{\"title\": \"...\", \"content\": \"...\", \"event_type\": \"chat|code\", \"source_ids\": [1,2]}]}"
            )},
            {"role": "user", "content": text_chunk},
        ],
        "max_tokens": 2000,
    }, timeout=90)
    if resp.status_code == 429:
        raise Exception("429 Too Many Requests")
    resp.raise_for_status()
    raw = resp.json()["choices"][0]["message"]["content"].strip()
    try:
        data = json.loads(raw)
    except Exception:
        s = raw[raw.find("{"):raw.rfind("}") + 1]
        data = json.loads(s)
    evs = data.get("events", []) or []
    out = []
    lo, hi = start_idx, start_idx + batch_len - 1
    for e in evs:
        raw_ids = [int(x) for x in (e.get("source_ids") or [])]
        if not raw_ids:
            continue
        # 编号兜底：LLM 若用了片段内编号(1..N)就校准到全局
        if min(raw_ids) < lo or max(raw_ids) > hi:
            raw_ids = [i + start_idx for i in raw_ids]
        out.append({
            "title": (e.get("title") or "未命名")[:30],
            "content": (e.get("content") or "").strip(),
            "event_type": "code" if e.get("event_type") == "code" else "chat",
            "source_ids": [str(x) for x in raw_ids],
            "source_range": f"{min(raw_ids)}-{max(raw_ids)}",
        })
    return out


def store_events(events, date=None):
    """全量先存外置库 memory_events（主存储，必须成功），事件向量一起算好存进去。
    PGRST102 修复（2026-08-20）：固定键集——embedding 缺=null、related_event_ids 缺=[]，
    批量 INSERT 不再因键不一致报 'All object keys must match'。"""
    d = date or cur_today()
    texts = [f"{e['title']}：{e['content']}" for e in events]
    try:
        embs = call_with_retry(lambda: embed_batch(texts), tries=2)
    except Exception as e:
        print(f"  事件向量计算失败（先入库，稍后回填）: {e}")
        embs = []
    rows = []
    for i, e in enumerate(events):
        row = {
            "assistant_id": ASSISTANT_ID,
            "title": e["title"],
            "content": e["content"],
            "event_type": e["event_type"],
            "source_date": str(d),
            "source_ids": e["source_ids"],
            "source_range": e["source_range"],
            "created_at": dt.datetime.now(dt.timezone.utc).isoformat(),
            "embedding": vec_str(embs[i]) if i < len(embs) else None,   # 固定键：缺=null
            "related_event_ids": e.get("related_event_ids") or [],      # 固定键：缺=[]
        }
        rows.append(row)
    r = requests.post(f"{SUPABASE_URL}/rest/v1/memory_events", json=rows, headers={
        "apikey": SUPABASE_KEY, "Authorization": f"Bearer {SUPABASE_KEY}",
        "Content-Type": "application/json", "Prefer": "return=minimal",
    })
    r.raise_for_status()
    return len(rows)


def get_progress(date=None):
    """当天已总结到的最大消息编号（memory_events 当天所有事件 source_ids 的最大值；
    含 superseded 的（失效事件也是已总结的证据）；无事件返回 0）"""
    d = date or cur_today()
    r = requests.get(f"{SUPABASE_URL}/rest/v1/memory_events", params={
        "select": "source_ids", "source_date": f"eq.{d}", "limit": 500,
    }, headers={"apikey": SUPABASE_KEY, "Authorization": f"Bearer {SUPABASE_KEY}"})
    r.raise_for_status()
    maxid = 0
    for x in r.json():
        for sid in (x.get("source_ids") or []):
            try:
                maxid = max(maxid, int(sid))
            except Exception:
                pass
    return maxid


def summarize_range(msgs, start_idx, end_idx, date=None):
    """总结一段消息（start_idx..end_idx，1-based 闭区间）：拆事件 -> A.U.D.N. -> 入库 -> Mem0 分流。
    增量脚本与晚上补剩余共用。返回入库条数。"""
    d = date or cur_today()
    chunk = msgs[start_idx - 1:end_idx]
    if not chunk:
        return 0
    lines = []
    for j, m in enumerate(chunk):
        who = "用户" if m.get("role") == "user" else "橘仔"
        lines.append(f"[{start_idx + j}] {who}：{m.get('content', '')}")
    text = "\n".join(lines)[-30000:]
    evs = call_with_retry(lambda: split_events(text, start_idx, len(chunk)))
    print(f"[{cur_now():%H:%M:%S}] 总结 {start_idx}..{end_idx} 拆出 {len(evs)} 条事件")
    if not evs:
        return 0
    # ③第二层 A.U.D.N.（冲突消解+事件关联；默认关，失败全量入库）
    if AUDN_ENABLED:
        try:
            evs, superseded_ids = audn_pass(evs)
            for tid in superseded_ids:
                try:
                    mark_superseded(tid)
                except Exception as e:
                    print(f"  A.U.D.N. 标记失效失败（不影响入库）: {e}")
        except Exception as e:
            print(f"  A.U.D.N. 阶段失败，全量入库（旧行为）: {e}")
    if not evs:
        print(f"[{cur_now():%H:%M:%S}] A.U.D.N. 后没有需要入库的事件")
        return 0
    n = call_with_retry(lambda: store_events(evs, d))
    print(f"[{cur_now():%H:%M:%S}] memory_events 入库 {n} 条 ✓")
    # 分流：code -> Mem0（失败不阻断，外置库已是主存储）
    try:
        m = call_with_retry(lambda: sync_mem0(evs), tries=2)
        if m:
            print(f"[{cur_now():%H:%M:%S}] Mem0 同步 {m} 条 code 事件 ✓")
    except Exception as e:
        print(f"[{cur_now():%H:%M:%S}] Mem0 同步失败（不影响外置库）: {e}")
    return n


def llm_same_event(A, B, boundary_text):
    """让 LLM 判断两条相邻事件是不是同一件事的延续（编号合并用）；失败=不合并（保守）"""
    system = (
        "你是橘仔的记忆整理员。下面有同一天编号紧挨着的【事件A】和【事件B】"
        "（它们是同一段聊天里相邻两批总结出来的），还有边界处的聊天原文。\n"
        "判断：事件A和事件B是不是【同一件事的延续】？\n"
        "例：A='讨论桌面宠物方案'，B='定下桌面宠物明天一起看' → same=true（同一件事被切开）\n"
        "例：A='聊桌面宠物'，B='聊晚上吃什么' → same=false（两件不同的事）\n"
        "只输出 JSON：{\"same\": true或false, \"reason\": \"一句话理由\"}"
    )
    user = f"【事件A】{A['title']}：{A['content']}\n【事件B】{B['title']}：{B['content']}\n【边界原文】\n{boundary_text}"
    resp = requests.post("https://api.siliconflow.cn/v1/chat/completions", headers={
        "Authorization": f"Bearer {SF_KEY}", "Content-Type": "application/json",
    }, json={
        "model": LLM_MODEL, "temperature": 0.0,
        "response_format": {"type": "json_object"},
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ],
        "max_tokens": 200,
    }, timeout=60)
    if resp.status_code == 429:
        raise Exception("429 Too Many Requests")
    resp.raise_for_status()
    raw = resp.json()["choices"][0]["message"]["content"].strip()
    try:
        d = json.loads(raw)
    except Exception:
        s = raw[raw.find("{"):raw.rfind("}") + 1]
        d = json.loads(s)
    return bool(d.get("same"))


def merge_adjacent_events(date=None):
    """两步收尾②：编号相邻的事件合并（宝的方案：A 结尾号+1==B 开头号 -> 拉边界原文
    -> LLM 判断同一件事 -> 拼接 content/source_ids，B 标 superseded 不删）。
    链式：合并完继续与下一个相邻事件检查（可拼回被切 2-3 段的话题）。返回合并对数。"""
    d = date or cur_today()
    r = requests.get(f"{SUPABASE_URL}/rest/v1/memory_events", params={
        "select": "id,title,content,source_ids,source_range",
        "source_date": f"eq.{d}", "superseded_by": "is.null", "limit": 500,
    }, headers={"apikey": SUPABASE_KEY, "Authorization": f"Bearer {SUPABASE_KEY}"})
    r.raise_for_status()
    events = r.json()
    if len(events) < 2:
        return 0

    def min_id(e):
        return min(int(x) for x in e["source_ids"]) if e.get("source_ids") else 0

    def max_id(e):
        return max(int(x) for x in e["source_ids"]) if e.get("source_ids") else 0

    events.sort(key=min_id)
    msgs = fetch_msgs(d)
    if not msgs:
        return 0
    merged = 0
    changed = True
    while changed:
        changed = False
        i = 0
        while i < len(events) - 1:
            A, B = events[i], events[i + 1]
            if max_id(A) + 1 == min_id(B):
                # 边界原文：A 最后 3 条 + B 前 3 条（1-based 编号）
                boundary = []
                for idx in [max_id(A) - 2, max_id(A) - 1, max_id(A), min_id(B), min_id(B) + 1, min_id(B) + 2]:
                    if 1 <= idx <= len(msgs):
                        m = msgs[idx - 1]
                        who = "用户" if m.get("role") == "user" else "橘仔"
                        boundary.append(f"[{idx}] {who}：{m.get('content', '')}")
                try:
                    same = llm_same_event(A, B, "\n".join(boundary))
                except Exception as e:
                    print(f"  merge 判断失败（不合并）: {e}")
                    same = False
                if same:
                    new_ids = sorted(set(A["source_ids"] + B["source_ids"]), key=int)
                    sep = "；" if A["content"] and B["content"] else ""
                    new_content = (A["content"] + sep + B["content"]).strip()
                    new_range = f"{min_id(A)}-{max_id(B)}"
                    # PATCH A：内容拼接 + 来源合并
                    requests.patch(f"{SUPABASE_URL}/rest/v1/memory_events?id=eq.{A['id']}", json={
                        "content": new_content, "source_ids": new_ids, "source_range": new_range,
                    }, headers={"apikey": SUPABASE_KEY, "Authorization": f"Bearer {SUPABASE_KEY}",
                                "Content-Type": "application/json", "Prefer": "return=minimal"}).raise_for_status()
                    # PATCH B：标失效（不删，留证据链）
                    requests.patch(f"{SUPABASE_URL}/rest/v1/memory_events?id=eq.{B['id']}", json={
                        "superseded_by": f"merged-{A['id']}",
                    }, headers={"apikey": SUPABASE_KEY, "Authorization": f"Bearer {SUPABASE_KEY}",
                                "Content-Type": "application/json", "Prefer": "return=minimal"}).raise_for_status()
                    print(f"  merge ✓ {A['title']} ← 合并 {B['title']}（{new_range}）")
                    A["content"] = new_content
                    A["source_ids"] = new_ids
                    A["source_range"] = new_range
                    del events[i + 1]
                    merged += 1
                    changed = True
                    continue  # 同 i 继续（A 可能还与下一个相邻）
            i += 1
    return merged


def backfill_missing_embeddings():
    """给 embedding 为空的存量事件补向量（首次回填 + 日常兜底），幂等"""
    r = requests.get(f"{SUPABASE_URL}/rest/v1/memory_events", params={
        "select": "id,title,content", "embedding": "is.null",
    }, headers={"apikey": SUPABASE_KEY, "Authorization": f"Bearer {SUPABASE_KEY}"})
    r.raise_for_status()
    rows = r.json()
    if not rows:
        return 0
    texts = [f"{x['title']}：{x['content']}" for x in rows]
    embs = call_with_retry(lambda: embed_batch(texts))
    for i, x in enumerate(rows):
        if i >= len(embs):
            break
        requests.patch(f"{SUPABASE_URL}/rest/v1/memory_events?id=eq.{x['id']}", json={
            "embedding": vec_str(embs[i]),
        }, headers={"apikey": SUPABASE_KEY, "Authorization": f"Bearer {SUPABASE_KEY}",
                    "Content-Type": "application/json", "Prefer": "return=minimal"})
    return len(rows)


def sync_mem0(events):
    """分流：code 类事件 -> Mem0（V1 的 SDK 直连方式，绕过 MCP 8005）"""
    code_evs = [e for e in events if e["event_type"] == "code"]
    if not code_evs:
        return 0
    from mem0 import Memory
    m = Memory.from_config({
        "llm": {"provider": "openai", "config": {"model": LLM_MODEL, "api_key": SF_KEY, "openai_base_url": "https://api.siliconflow.cn/v1"}},
        "embedder": {"provider": "openai", "config": {"model": EMBED_MODEL, "api_key": SF_KEY, "openai_base_url": "https://api.siliconflow.cn/v1"}},
        "vector_store": {"provider": "qdrant", "config": {"host": "127.0.0.1", "port": 6333, "collection_name": "orange_mem0", "embedding_model_dims": 1024}},
    })
    n = 0
    for e in code_evs:
        text = f"（{cur_today()} 代码事件）{e['title']}：{e['content']}"
        m.add(text, user_id=USER_ID)
        n += 1
        print(f"  Mem0 ✓ {e['title']}")
    return n


def main(date_str=None):
    """晚上收尾（cron 23:50）：①补剩余 M+1..N（<60 也总结）②编号合并。
    增量由 incremental_listener.py 负责（每满 60 条总结 30 条）。"""
    d = cur_today() if not date_str else dt.date.fromisoformat(date_str)
    lock = try_lock()
    if lock is None:
        print(f"[{cur_now():%Y-%m-%d %H:%M}] 另一个归档进程在跑（监听/归档），跳过")
        return
    try:
        # 先补存量事件向量（幂等，日常兜底）
        try:
            n = backfill_missing_embeddings()
            if n:
                print(f"[{cur_now():%Y-%m-%d %H:%M}] 回填 {n} 条存量事件向量 ✓")
        except Exception as e:
            print(f"[{cur_now():%Y-%m-%d %H:%M}] 回填向量失败（不阻断主流程）: {e}")
        msgs = fetch_msgs(d)
        if not msgs:
            print(f"[{cur_now():%Y-%m-%d %H:%M}] {d} 没有消息，跳过")
            return
        M = get_progress(d)
        N = len(msgs)
        if N > M:
            print(f"[{cur_now():%Y-%m-%d %H:%M}] 补剩余 {M+1}..{N}（共 {N-M} 条，进度 M={M}）")
            try:
                summarize_range(msgs, M + 1, N, d)
            except Exception as e:
                print(f"[{cur_now():%Y-%m-%d %H:%M}] 补剩余失败: {e}")
        else:
            print(f"[{cur_now():%Y-%m-%d %H:%M}] 已全部总结（进度 M={M}=N={N}），无需补")
        # 两步收尾②：编号相邻合并（把增量切碎的同一事件拼回来）
        try:
            m = merge_adjacent_events(d)
            if m:
                print(f"[{cur_now():%Y-%m-%d %H:%M}] 编号合并 {m} 对事件 ✓")
            else:
                print(f"[{cur_now():%Y-%m-%d %H:%M}] 编号合并：无相邻同主题事件")
        except Exception as e:
            print(f"[{cur_now():%Y-%m-%d %H:%M}] 编号合并失败: {e}")
    finally:
        fcntl.flock(lock, fcntl.LOCK_UN)
        lock.close()


if __name__ == "__main__":
    try:
        date_arg = sys.argv[1] if len(sys.argv) > 1 else None
        main(date_arg)
    except Exception as e:
        print(f"[{cur_now():%Y-%m-%d %H:%M}] 归档失败: {e}")
