#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# 橘仔的全自动记忆归档 · archive_daily_v3.py
# 2026-08-14 · 基于 V1(archive_daily.py) 骨架 + V2 事件化 + 修复 Mem0 导入
# 流程：
#   拉当天聊天 -> DeepSeek 拆事件(带 chat/code 类型 + 来源编号) -> 存外置库 memory_events(查重)
#   -> 分流：event_type=code 的事件 -> Mem0（SDK 直连 Qdrant，绕过 MCP 8005）
#   （聊天类事件 -> OB 由手机端脚本 ob_sync_chat.py 负责，OB 在宝手机 Termux）
# 2026-08-17 · 入库 GitHub scripts/（密钥脱敏：SUPABASE_URL/SUPABASE_KEY 改读 .env 环境变量）
import os, sys, json, time, datetime as dt, requests
from dotenv import load_dotenv

load_dotenv("/root/.env")
SF_KEY = os.getenv("SILICONFLOW_KEY", "")
LLM_MODEL = os.getenv("LLM_MODEL", "deepseek-ai/DeepSeek-V3")
EMBED_MODEL = os.getenv("EMBED_MODEL", "BAAI/bge-m3")
SUPABASE_URL = os.getenv("SUPABASE_URL", "")  # 脱敏：原为硬编码 https://rttzjckjbelsbuvnhpkn.supabase.co
SUPABASE_KEY = os.getenv("SUPABASE_KEY", "")  # 脱敏：原为硬编码 sb_publishable_...(从 Supabase 控制台复制)
ASSISTANT_ID = "0950e2dc-9bd5-4801-afa3-aa887aa36b4e"
USER_ID = "orange-bao"
BATCH = 25  # 每批最多多少条消息给 LLM 拆事件

BJ = dt.timezone(dt.timedelta(hours=8))
now_bj = dt.datetime.now(BJ)
today = now_bj.date()


def call_with_retry(fn, tries=3):
    """撞限流(429)就等一会重试"""
    for i in range(tries):
        try:
            return fn()
        except Exception as e:
            if "429" in str(e) and i < tries - 1:
                wait = 30 * (i + 1)
                print(f"[{now_bj:%H:%M:%S}] 撞限流了，{wait} 秒后重试（{i+1}/{tries}）")
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


def fetch_msgs():
    """拉当天消息（Supabase REST，按北京时间）"""
    start_local = f"{today} 00:00:00"
    end_local = f"{today} 23:59:59"
    r = requests.get(f"{SUPABASE_URL}/rest/v1/chat_messages", params={
        "select": "role,content,created_at",
        "created_at": [f"gte.{start_local}", f"lte.{end_local}"],
        "order": "created_at.asc", "limit": 500,
    }, headers={"apikey": SUPABASE_KEY, "Authorization": f"Bearer {SUPABASE_KEY}"})
    r.raise_for_status()
    msgs = r.json()
    print(f"[{now_bj:%H:%M:%S}] fetch_msgs 拉到 {len(msgs)} 条消息（{today} 北京时间口径）")
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


def store_events(events):
    """全量先存外置库 memory_events（主存储，必须成功），事件向量一起算好存进去"""
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
            "source_date": str(today),
            "source_ids": e["source_ids"],
            "source_range": e["source_range"],
            "created_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        }
        if i < len(embs):
            row["embedding"] = vec_str(embs[i])
        rows.append(row)
    r = requests.post(f"{SUPABASE_URL}/rest/v1/memory_events", json=rows, headers={
        "apikey": SUPABASE_KEY, "Authorization": f"Bearer {SUPABASE_KEY}",
        "Content-Type": "application/json", "Prefer": "return=minimal",
    })
    r.raise_for_status()
    return len(rows)


def already_done():
    """当天是否已有事件（防同日重复跑）"""
    r = requests.get(f"{SUPABASE_URL}/rest/v1/memory_events", params={
        "select": "id", "source_date": f"eq.{today}",
    }, headers={"apikey": SUPABASE_KEY, "Authorization": f"Bearer {SUPABASE_KEY}"})
    r.raise_for_status()
    return len(r.json()) > 0


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
        print("  没有 code 类事件，跳过 Mem0 同步")
        return 0
    from mem0 import Memory
    m = Memory.from_config({
        "llm": {"provider": "openai", "config": {"model": LLM_MODEL, "api_key": SF_KEY, "openai_base_url": "https://api.siliconflow.cn/v1"}},
        "embedder": {"provider": "openai", "config": {"model": EMBED_MODEL, "api_key": SF_KEY, "openai_base_url": "https://api.siliconflow.cn/v1"}},
        "vector_store": {"provider": "qdrant", "config": {"host": "127.0.0.1", "port": 6333, "collection_name": "orange_mem0", "embedding_model_dims": 1024}},
    })
    n = 0
    for e in code_evs:
        text = f"（{today} 代码事件）{e['title']}：{e['content']}"
        m.add(text, user_id=USER_ID)
        n += 1
        print(f"  Mem0 ✓ {e['title']}")
    return n


def main():
    # 先补存量事件向量（幂等，日常兜底）
    try:
        n = backfill_missing_embeddings()
        if n:
            print(f"[{now_bj:%Y-%m-%d %H:%M}] 回填 {n} 条存量事件向量 ✓")
    except Exception as e:
        print(f"[{now_bj:%Y-%m-%d %H:%M}] 回填向量失败（不阻断主流程）: {e}")
    msgs = fetch_msgs()
    if not msgs:
        print(f"[{now_bj:%Y-%m-%d %H:%M}] 今天没有新消息，跳过")
        return
    if already_done():
        print(f"[{now_bj:%Y-%m-%d %H:%M}] {today} 已有事件记录（防重复跑），跳过。要重跑请先删掉 memory_events 里 source_date={today} 的行")
        return
    print(f"[{now_bj:%Y-%m-%d %H:%M}] 拉取 {len(msgs)} 条消息，开始拆事件…")
    all_events = []
    start_idx = 1
    for i in range(0, len(msgs), BATCH):
        chunk = msgs[i:i + BATCH]
        lines = []
        for j, m in enumerate(chunk):
            who = "用户" if m.get("role") == "user" else "橘仔"
            lines.append(f"[{start_idx + j}] {who}：{m.get('content', '')}")
        text = "\n".join(lines)[-30000:]
        evs = call_with_retry(lambda: split_events(text, start_idx, len(chunk)))
        all_events.extend(evs)
        print(f"  批 {i // BATCH + 1}：拆出 {len(evs)} 条事件")
        start_idx += len(chunk)
    if not all_events:
        print(f"[{now_bj:%Y-%m-%d %H:%M}] 今天没有值得归档的事件")
        return
    call_with_retry(lambda: store_events(all_events))
    print(f"[{now_bj:%Y-%m-%d %H:%M}] 外置库 memory_events 入库 {len(all_events)} 条 ✓")
    # 分流：code -> Mem0（失败不阻断，外置库已是主存储）
    try:
        n = call_with_retry(lambda: sync_mem0(all_events), tries=2)
        print(f"[{now_bj:%Y-%m-%d %H:%M}] Mem0 同步 {n} 条 code 事件 ✓")
    except Exception as e:
        print(f"[{now_bj:%Y-%m-%d %H:%M}] Mem0 同步失败（不影响外置库）: {e}")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print(f"[{now_bj:%Y-%m-%d %H:%M}] 归档失败: {e}")
