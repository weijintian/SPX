#!/usr/bin/env python3
"""给 api.php 增加「全局每秒上限」，防止大量 IP 同时打满服务器（幂等）。"""
import io
import subprocess
import sys

LIMIT_CONF = "/etc/nginx/conf.d/spx-limits.conf"
SITE = "/etc/nginx/sites-enabled/default"

conf = io.open(LIMIT_CONF, encoding="utf-8").read() if __import__("os").path.exists(LIMIT_CONF) else ""
if "spxglobal" not in conf:
    conf += "\n# 全局上限：整站每秒请求数，超过直接 429，保护服务器不被打挂\n"
    conf += "# 实测服务器极限约 2500 QPS，这里设 1500 留 40% 余量给正常流量\n"
    conf += "limit_req_zone $server_name zone=spxglobal:10m rate=1500r/s;\n"
    io.open(LIMIT_CONF, "w", encoding="utf-8").write(conf)
    print("已写入全局限流区")
else:
    print("全局限流区已存在")

site = io.open(SITE, encoding="utf-8").read()
if "zone=spxglobal" not in site:
    anchor = "        limit_req zone=spxapi burst=100 nodelay;"
    if anchor not in site:
        sys.exit("未找到 api 限流锚点")
    site = site.replace(anchor, anchor + "\n        limit_req zone=spxglobal burst=3000 nodelay;", 1)
    io.open(SITE, "w", encoding="utf-8").write(site)
    print("已在 api.php 上加全局上限")
else:
    print("全局上限已存在")

r = subprocess.run(["nginx", "-t"], capture_output=True, text=True)
print((r.stderr or r.stdout).strip().splitlines()[-1])
sys.exit(0 if r.returncode == 0 else 1)
