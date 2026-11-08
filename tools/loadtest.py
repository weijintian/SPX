#!/usr/bin/env python3
"""并发压测：每线程复用 keep-alive 连接循环请求，统计 QPS / 延迟分位 / 状态码。"""
import argparse
import http.client
import json
import threading
import time

results = []
codes = {}
lock = threading.Lock()
stop_at = 0.0
host = "127.0.0.1"
path = "/"


def percentile(values, p):
    if not values:
        return 0.0
    idx = int(round((p / 100.0) * len(values) + 0.5)) - 1
    idx = max(0, min(len(values) - 1, idx))
    return values[idx]


def worker(cid):
    local = []
    local_codes = {}
    conn = None
    while time.time() < stop_at:
        if conn is None:
            conn = http.client.HTTPConnection(host, 80, timeout=15)
        start = time.perf_counter()
        code = 0
        try:
            conn.request("GET", path, headers={"Host": "spx001.top", "User-Agent": "spx-loadtest", "Connection": "keep-alive"})
            resp = conn.getresponse()
            resp.read()
            code = resp.status
        except Exception:
            code = 0
            try:
                conn.close()
            except Exception:
                pass
            conn = None
        local.append(((time.perf_counter() - start) * 1000.0, code))
        local_codes[code] = local_codes.get(code, 0) + 1
    if conn is not None:
        try:
            conn.close()
        except Exception:
            pass
    with lock:
        results.extend(local)
        for k, v in local_codes.items():
            codes[k] = codes.get(k, 0) + v


def main():
    global stop_at, host, path
    ap = argparse.ArgumentParser()
    ap.add_argument("--path", required=True)
    ap.add_argument("--concurrency", type=int, default=50)
    ap.add_argument("--seconds", type=int, default=15)
    ap.add_argument("--label", default="")
    args = ap.parse_args()
    path = args.path
    stop_at = time.time() + args.seconds
    threads = [threading.Thread(target=worker, args=(i,), daemon=True) for i in range(args.concurrency)]
    started = time.time()
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=args.seconds + 20)
    elapsed = max(0.001, time.time() - started)

    latencies = sorted(r[0] for r in results)
    summary = {
        "label": args.label or f"c{args.concurrency}",
        "concurrency": args.concurrency,
        "duration_s": round(elapsed, 2),
        "requests": len(results),
        "qps": round(len(results) / elapsed, 1),
        "p50_ms": round(percentile(latencies, 50), 1),
        "p95_ms": round(percentile(latencies, 95), 1),
        "p99_ms": round(percentile(latencies, 99), 1),
        "max_ms": round(latencies[-1], 1) if latencies else 0,
        "codes": {str(k): v for k, v in sorted(codes.items())},
        "errors": codes.get(0, 0) + codes.get(502, 0) + codes.get(504, 0),
    }
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    main()
