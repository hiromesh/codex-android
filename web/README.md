# Codex Web

`codex app-server` 的桌面网页客户端，与安卓版（`../app`）功能完全等价：会话列表、流式 Markdown 回复、命令执行/文件改动/推理摘要/联网搜索展示、模型与推理档位切换、审批应答、token 上下文占用、语音输入（火山引擎 ASR）、断线重连与全量对账。

前后端一体的 TypeScript 单仓库：一个 Node 服务同时托管前端页面与两个 WebSocket 代理。

## 为什么需要代理

浏览器 `WebSocket` 无法自定义请求头，而协议要求：

- codex app-server：`Authorization: Bearer <token>`
- 火山引擎 ASR：`X-Api-App-Key` / `X-Api-Access-Key` / `X-Api-Resource-Id` / `X-Api-Sequence`

因此浏览器只连接本服务（`/ws/codex`、`/ws/asr`），由服务端注入请求头后转发到上游，并把消息原样转发回来。

## 运行

开发模式（Vite 5175 热更新，自动代理到后端 3000）：

```bash
npm install
npm run dev
```

生产模式（单端口 3000，前后端一体）：

```bash
npm run build
npm start
```

打开 http://localhost:3000 ，在「设置」里填写服务器地址（默认 `wss://codex.waibuzishu.com:8443`）与 Token 即可。

## 目录结构

```text
server/          # Express：静态托管 + /ws/codex、/ws/asr 代理
src/
├── client.ts    # codex app-server 协议（RPC/通知/审批/重连），对应 WebSocketCodexRepository
├── types.ts     # 协议模型，对应 Models.kt
├── settings.ts  # 本地设置（localStorage），对应 SettingsStore
├── asr.ts       # 火山 ASR 帧协议 + 麦克风 AudioWorklet，对应 StreamingAsrClient
├── stores.ts    # ThreadListStore / ChatStore，对应两个 ViewModel
└── components/  # 消息卡片、Markdown、输入栏、审批弹窗等
```
