#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# 橘仔的记忆实时化 · incremental_listener.py（2026-08-21 宝方案定稿）
# 常驻监听：每 10 秒查一次当天消息数 vs 已总结进度，N-M >= 60 时触发总结 30 条。
# 原理（宝的洞察）：总结永远滞后上下文半拍——总结"上一批"时"本批 30 条"还在上下文兜底，
# 不重合、无真空期（上午的事中午就进事件库，App 注入时记得住）。
# 用法：nohup /root/mem0env/bin/python /root/incremental_listener.py >> /root/incremental_listener.log 2>&1 &
# 与 archive_daily_v3.py（23:50 cron）共用 /tmp/orange_summary.lock 防双写。
import sys, time, os, fcntl
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import archive_daily_v3 as ad

TRIGGER_AHEAD = 60   # N - M >= 60 才触发（宝定的 n×30+30：60 时总结 1-30，90 时总结 31-60…）
BATCH_SIZE = 30      # 每次总结 30 条
SLEEP_SEC = 10       # 轮询间隔


def loop():
    print(f"[{ad.cur_now():%Y-%m-%d %H:%M:%S}] incremental_listener 启动（{SLEEP_SEC}s 轮询，N-M>={TRIGGER_AHEAD} 触发总结 {BATCH_SIZE} 条）")
    while True:
        try:
            today = ad.cur_today()
            msgs = ad.fetch_msgs(today)
            N = len(msgs)
            if N == 0:
                time.sleep(SLEEP_SEC)
                continue  # 今天还没消息，静默等下一轮
            M = ad.get_progress(today)
            if N - M >= TRIGGER_AHEAD:
                lock = ad.try_lock()
                if lock is None:
                    print(f"[{ad.cur_now():%H:%M:%S}] 另一个归档在跑，跳过本轮（N-M={N-M}）")
                else:
                    try:
                        end = min(M + BATCH_SIZE, N)
                        n = ad.summarize_range(msgs, M + 1, end, today)
                        print(f"[{ad.cur_now():%H:%M:%S}] 增量总结 {M+1}..{end} 入库 {n} 条（N={N}，下次从 {end} 起算）")
                    finally:
                        fcntl.flock(lock, fcntl.LOCK_UN)
                        lock.close()
            # 未到阈值就不打扰（静默）
        except Exception as e:
            print(f"[{ad.cur_now():%H:%M:%S}] 监听异常（下轮继续）: {e}")
        time.sleep(SLEEP_SEC)


if __name__ == "__main__":
    loop()
