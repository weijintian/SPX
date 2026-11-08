#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""核对 FISS 总供应量与黑洞地址余额，判断档位阈值是否合理。"""
import json
import urllib.request

RPC = "https://bsc-dataseed1.binance.org"
TOKEN = "0x4e4372146c2a7d4d5c8580fd8e8f0f1cfdbafb5a"
DEAD = "0x000000000000000000000000000000000000dEaD"
ZERO = "0x0000000000000000000000000000000000000000"


def call(data):
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "eth_call",
                       "params": [{"to": TOKEN, "data": data}, "latest"]}).encode()
    req = urllib.request.Request(RPC, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.loads(r.read().decode())["result"]


def to_dec(hexstr):
    return int(hexstr, 16) / 10 ** 18


def balance_of(addr):
    data = "0x70a08231" + addr[2:].rjust(64, "0")
    return to_dec(call(data))


supply = to_dec(call("0x18160ddd"))          # totalSupply()
dead = balance_of(DEAD)
zero = balance_of(ZERO)
print(f"总供应量      : {supply:,.0f} FISS")
print(f"黑洞地址余额  : {dead:,.0f} FISS（{dead / supply * 100:.2f}%）")
print(f"零地址余额    : {zero:,.0f} FISS")
print()
for t in (100_000_000, 200_000_000, 300_000_000):
    print(f"阈值 {t:,} 是否已达成: {'是' if dead >= t else '否'}")
