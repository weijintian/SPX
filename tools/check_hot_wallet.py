#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""检查提现热钱包的 BNB / USDT / SPX001 / FISS 余额"""
import json, urllib.request

RPC = "https://bsc-dataseed1.binance.org"
HOT = "0xFec25630f06d967C525FD02759a05f1c55f3E1Fb"
TOKENS = {
    "USDT": "0x55d398326f99059ff775485246999027b3197955",
    "SPX001": "0x0E920821464625cf661A4dceDEb6406E1B48FFFf",
    "FISS": "0x4e4372146c2a7d4d5c8580fd8e8f0f1cfdbafb5a",
}


def rpc(method, params):
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params}).encode()
    req = urllib.request.Request(RPC, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=20) as r:
        d = json.loads(r.read().decode())
    if "error" in d:
        raise RuntimeError(d["error"])
    return d["result"]


def token_balance(token, addr):
    data = "0x70a08231" + addr[2:].rjust(64, "0")
    return int(rpc("eth_call", [{"to": token, "data": data}, "latest"]), 16) / 10 ** 18


bnb = int(rpc("eth_getBalance", [HOT, "latest"]), 16) / 10 ** 18
print(f"热钱包地址 : {HOT}")
print(f"BNB (gas) : {bnb:,.6f}")
for sym, addr in TOKENS.items():
    print(f"{sym:<9} : {token_balance(addr, HOT):,.6f}")

print()
# 估算：一笔 FISS 提现大约需要多少 gas
gas_price = int(rpc("eth_gasPrice", []), 16) / 10 ** 18
print(f"当前 gas 价: {gas_price:.2f} gwei")
print(f"单笔约需   : {gas_price * 200000:.6f} BNB（按 20 万 gas 估算）")
if bnb > 0:
    print(f"可支付笔数 : 约 {int(bnb / (gas_price * 200000))} 笔")
