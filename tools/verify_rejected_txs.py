#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""核实被拒充值：代币 Transfer 事件的实际转出方是否为用户绑定地址。"""
import json
import sys
import urllib.request

RPC = "https://bsc-dataseed1.binance.org"
TRANSFER = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"

CASES = [
    {
        "id": 60, "user": 59, "tx": "0x0df1b28944ae867162a6b3f67e4ed33982c42f1c57fc40b3da2f92c195ddb21c",
        "token": "0x0e920821464625cf661a4dcedeb6406e1b48ffff", "decimals": 18,
        "amount": 1000000, "bound": "0x27037621f53659ad46b7f4b67e8f0a7d184816f9",
    },
    {
        "id": 41, "user": 5, "tx": "0x114b13591bbab214a57e9c400e7b6d855ccc50031c8796d299606171769efecb",
        "token": "0x0e920821464625cf661a4dcedeb6406e1b48ffff", "decimals": 18,
        "amount": 200000, "bound": "0xaed860c8599c8ed3bbf6f9f80f04c97e0e18d3a2",
    },
]


def rpc(method, params):
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params}).encode()
    req = urllib.request.Request(RPC, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=20) as resp:
        return json.loads(resp.read().decode())


def topic_addr(t):
    return "0x" + t[-40:].lower()


for case in CASES:
    print(f"=== 记录 #{case['id']}（用户 #{case['user']}）===")
    tx = rpc("eth_getTransactionByHash", [case["tx"]]).get("result")
    rc = rpc("eth_getTransactionReceipt", [case["tx"]]).get("result")
    if not tx or not rc:
        print("  链上查不到\n")
        continue
    print(f"  交易签名方(tx.from): {tx['from'].lower()}")
    print(f"  调用合约(tx.to)    : {(tx['to'] or '').lower()}")
    print(f"  执行状态           : {'成功' if int(rc['status'], 16) == 1 else '失败'}")
    print(f"  打包区块           : {int(rc['blockNumber'], 16)}")

    hit = False
    for log in rc.get("logs", []):
        topics = log.get("topics") or []
        if len(topics) < 3 or topics[0].lower() != TRANSFER:
            continue
        if log["address"].lower() != case["token"]:
            continue
        frm = topic_addr(topics[1])
        to = topic_addr(topics[2])
        val = int(log["data"], 16) / (10 ** case["decimals"])
        mark = "✅" if frm == case["bound"].lower() else "❌"
        print(f"  Transfer {case['token'][:10]}…: {frm} -> {to}  {val:,.0f}")
        print(f"    转出方 == 绑定地址: {mark}")
        if frm == case["bound"].lower() and abs(val - case["amount"]) < 0.01:
            hit = True

    print(f"  结论: {'✅ 是用户绑定钱包转出，属于误判' if hit else '❌ 无法确认'}")
    print()
