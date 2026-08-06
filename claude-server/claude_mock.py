"""Interactive mock client for the claude-web server（对齐 CLAUDE_DEPLOY.md §6 的最小实现）。"""
import asyncio, json, os, sys
from pathlib import Path
import websockets

WS = os.environ.get("CLAUDE_WS", "ws://127.0.0.1:58628")
TOKEN = os.environ.get("CLAUDE_TOKEN") or Path.home().joinpath(".claude", "server-token").read_text().strip()
DSP = os.environ.get("DSP", "0") == "1"

async def main():
    async with websockets.connect(WS, additional_headers={"Authorization": f"Bearer {TOKEN}"}) as http_ws:
        # 1. 建会话（REST）
        import urllib.request
        req = urllib.request.Request(
            WS + "/sessions", data=json.dumps({"cwd": os.getcwd(), "dangerously_skip_permissions": DSP}).encode(),
            headers={"Authorization": f"Bearer {TOKEN}", "content-type": "application/json"}, method="POST")
        sess = json.load(urllib.request.urlopen(req))
        print(f"[会话] session_id={sess['session_id']} work_dir={sess['work_dir']}")
        # 走 Caddy（https/wss）时换 scheme；本地直连（http/ws）保持原样
        ws_url = sess["ws_url"]
        if WS.startswith(("https://", "wss://")):
            ws_url = ws_url.replace("ws://", "wss://").replace("http://", "https://")

    async with websockets.connect(ws_url) as ws:
        print("直接输入开始对话，y/n 应答审批，exit 退出")
        async def receiver():
            async for raw in ws:
                m = json.loads(raw)
                t = m.get("type")
                if t == "assistant":
                    # SDK ≥0.3.223：assistant 顶层事件的 message 即 SDKAssistantMessage，content 直接挂在其下
                    for b in m.get("message", {}).get("content", []):
                        if b.get("type") == "text":
                            print(b["text"], end="", flush=True)
                elif t == "control_request":
                    print(f"\n[审批] {m['request'].get('tool_name')}: {json.dumps(m['request'].get('input'), ensure_ascii=False)[:120]}")
                    ans = (await asyncio.to_thread(input, "[审批] 批准? [y/n] ")).strip().lower()
                    await ws.send(json.dumps({"type": "control_response", "response": {
                        "subtype": "success", "request_id": m["request_id"],
                        "response": {"behavior": "allow" if ans in ("y", "", "yes") else "deny"}}}))
                elif t == "result":
                    print(f"\n[done] subtype={m.get('subtype')} cost=${m.get('total_cost_usd', 0):.4f}")
        asyncio.create_task(receiver())
        while True:
            try: text = await asyncio.to_thread(input, "你> ")
            except (EOFError, KeyboardInterrupt): break
            text = text.strip()
            if not text: continue
            if text.lower() in ("exit", "quit"): break
            await ws.send(json.dumps({"type": "user", "message": {"role": "user",
                "content": [{"type": "text", "text": text}]}, "parent_tool_use_id": None, "session_id": ""}))

asyncio.run(main())
