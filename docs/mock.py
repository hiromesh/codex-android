"""Interactive mock-phone client for codex app-server.

Usage:
    export WS_TOKEN=<your-token>
    uv run --with websockets python mock.py

Env:
    WS_URL   default: wss://codex.waibozishu.com
    WS_TOKEN required (or put the token in ~/.codex/ws-token)

While chatting: exit / quit to leave, y/n to answer approval prompts.
"""

import asyncio
import json
import os
import sys
from pathlib import Path

import websockets

WS_URL = os.environ.get("WS_URL", "wss://codex.waibozishu.com:8443")
TOKEN = os.environ.get("WS_TOKEN")
if not TOKEN:
    token_file = Path.home() / ".codex" / "ws-token"
    if token_file.exists():
        TOKEN = token_file.read_text().strip()
if not TOKEN:
    sys.exit("WS_TOKEN not set and ~/.codex/ws-token not found")

next_id = 0
pending = {}
thread_id = None
turn_done = asyncio.Event()
ws = None


def req(method, params=None):
    global next_id
    next_id += 1
    msg = {"method": method, "id": next_id}
    if params is not None:
        msg["params"] = params
    return next_id, json.dumps(msg)


async def send(method, params=None):
    rid, payload = req(method, params)
    pending[rid] = method
    await ws.send(payload)
    return rid


async def handle_approval(msg):
    """Server -> client request: prompt y/n and answer."""
    method = msg["method"]
    params = msg.get("params", {})
    print()
    if method == "item/commandExecution/requestApproval":
        print(f"[审批] 执行命令: {params.get('command') or params}")
    elif method == "item/fileChange/requestApproval":
        print(f"[审批] 修改文件: {params.get('reason') or params}")
    else:
        print(f"[审批] {method}: {json.dumps(params, ensure_ascii=False)[:200]}")
    answer = await asyncio.to_thread(
        lambda: input("[审批] 批准? [y/n] ").strip().lower()
    )
    decision = "accept" if answer in ("y", "yes", "") else "decline"
    await ws.send(json.dumps({"id": msg["id"], "result": {"decision": decision}}))
    print(f"[审批] 已回复: {decision}")


async def receiver():
    global thread_id
    async for raw in ws:
        msg = json.loads(raw)
        # response to one of our requests
        if "id" in msg and "method" not in msg:
            rid = msg["id"]
            method = pending.pop(rid, "?")
            if "error" in msg:
                print(f"\n!! {method} 错误: {msg['error']}")
                if method == "turn/start":
                    turn_done.set()
                continue
            if method == "thread/start":
                thread_id = msg["result"]["thread"]["id"]
            continue
        # server -> client request (approvals etc.)
        if "id" in msg and "method" in msg:
            await handle_approval(msg)
            continue
        # notification
        method = msg.get("method")
        params = msg.get("params", {})
        if method == "item/agentMessage/delta":
            print(params.get("delta", ""), end="", flush=True)
        elif method == "turn/completed":
            print()
            turn_done.set()
        elif method == "item/started":
            item = params.get("item", {})
            if item.get("type") == "commandExecution":
                print(f"\n[执行命令] {item.get('command', '')}")
        elif method == "error":
            print(f"\n!! {params}")
            turn_done.set()


async def main():
    global ws
    async with websockets.connect(
        WS_URL, additional_headers={"Authorization": f"Bearer {TOKEN}"}
    ) as conn:
        ws = conn
        print(f"[已连接] {WS_URL}")
        recv_task = asyncio.create_task(receiver())

        await send(
            "initialize", {"clientInfo": {"name": "mock-phone", "version": "0.2.0"}}
        )
        await ws.send('{"method":"initialized"}')
        await asyncio.sleep(0.3)

        await send("thread/start", {})
        for _ in range(100):
            if thread_id:
                break
            await asyncio.sleep(0.05)
        if not thread_id:
            sys.exit("thread/start 失败")
        print(f"[会话就绪] thread.id = {thread_id}")
        print("直接输入开始对话，exit 退出\n")

        while True:
            try:
                text = await asyncio.to_thread(input, "你> ")
            except (EOFError, KeyboardInterrupt):
                break
            text = text.strip()
            if not text:
                continue
            if text.lower() in ("exit", "quit"):
                break
            turn_done.clear()
            await send(
                "turn/start",
                {"threadId": thread_id, "input": [{"type": "text", "text": text}]},
            )
            try:
                await asyncio.wait_for(turn_done.wait(), timeout=300)
            except asyncio.TimeoutError:
                print("\n[超时] turn 300s 未完成")
        recv_task.cancel()


asyncio.run(main())
