#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""查链上这笔交易的发起方与 Transfer 事件，判断到底是谁转的。"""
import json
import urllib.request

RPC = "https://bsc-dataseed1.binance.org"
TX = "0x0df1b28944ae867162a6b3f67e4ed33982c42f1c57fc40b3da2f92c195ddb21c"
BOUND = "0x27037621f53659ad46b7f4b67e8f0a7d184816f9"
DEPOSIT = "0x51f65930b9f94d8b48ad26443715c3256a411286"


def rpc(method, params):
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params}).encode()
    req = urllib.request.Request(RPC, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=20) as resp:
        return json.loads(resp.read().decode())


def main():
    tx = rpc("eth_getTransactionByHash", [TX]).get("result")
    if not tx:
        print("交易不存在或 RPC 未返回")
        return
    print("=== 链上交易 ===")
    print(f"  from   : {tx['from']}")
    print(f"  to     : {tx['to']}")
    print(f"  value  : {int(tx['value'], 16)} wei")
    print(f"  input  : {tx['input'][:74]}...")
    print(f"  block  : {int(tx['blockNumber'], 16)}")
    print(f"  status : {tx['hash']}")

    print("\n=== 地址对比 ===")
    sender = tx["from"].lower()
    print(f"  链上 from      : {sender}")
    print(f"  用户绑定地址   : {BOUND}")
    print(f"  平台充值地址   : {DEPOSIT}")
    print(f"  from == 绑定   : {'✅ 是' if sender == BOUND else '❌ 否'}")
    print(f"  from == 充值址 : {'是' if sender == DEPOSIT else '否'}")

    receipt = rpc("eth_getTransactionReceipt", [TX]).get("result")
    if receipt:
        print(f"\n=== 回执 ===\n  status: {'成功' if int(receipt['status'], 16) == 1 else '失败'}")
        transfer_topic = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"
        print("\n=== Transfer 事件（代币真实转出方）===")
        for log in receipt.get("logs", []):
            topics = log.get("topics", [])
            if topics and topics[0].lower() == transfer_topic:
                frm = "0x" + topics[1][-40:]
                to = "0x" + topics[2][-40:]
                val = int(log["data"], 16) if log["data"] not in ("", "0x") else 0
                print(f"  合约: {log['address']}")
                print(f"    from : {frm}")
                print(f"    to   : {to}")
                print(f"    value: {val}")
                print(f"    from == 绑定地址: {'✅ 是' if frm.lower() == BOUND else '❌ 否'}")
                print(f"    to   == 充值地址: {'✅ 是' if to.lower() == DEPOSIT else '❌ 否'}")


if __name__ == "__main__":
    main()
