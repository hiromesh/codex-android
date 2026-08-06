# Codex Web

`codex app-server` 的桌面网页客户端，与安卓版（`../app`）功能等价：会话列表、流式 Markdown 回复、命令执行/文件改动/推理摘要/联网搜索展示、模型与推理档位切换、审批应答、token 上下文占用、语音输入（火山引擎 ASR）、**语音播报（火山引擎 TTS）**、**斜杠命令（/compact /review /fork /undo、!shell）**、**多 Agent 服务器（Codex / Kimi Code / Claude Code）**、断线重连与全量对账。

前后端一体的 TypeScript 单仓库：一个 Node 服务同时托管前端页面与 WebSocket/REST 代理。

## 为什么需要代理

浏览器 `WebSocket` 无法自定义请求头，跨域 `fetch` 也无法携带 `Authorization`，而协议要求：

- codex app-server：`Authorization: Bearer <token>`
- kimi-web kap-server：REST + WS 均需 `Authorization: Bearer <token>`
- claude-server：REST + per-session WS 均需 `Authorization: Bearer <token>`
- 火山引擎 ASR：`X-Api-App-Key` / `X-Api-Access-Key` / `X-Api-Resource-Id` / `X-Api-Sequence`
- 火山引擎 TTS：`X-Api-Key` / `X-Api-Resource-Id` / `X-Api-Connect-Id`

因此浏览器只连接本服务，由服务端注入请求头后转发到上游，并把消息原样转发回来：

| 浏览器侧 | 上游 | 注入 |
|---|---|---|
| `/ws/codex` | codex app-server WS | Bearer |
| `/ws/kimi` | kimi `/api/v1/ws` | Bearer |
| `/ws/claude` | claude per-session WS（NDJSON） | Bearer |
| `/ws/asr` | 火山 ASR | X-Api-* |
| `/ws/tts` | 火山 TTS | X-Api-Key 等 |
| `/api/relay/kimi/*` | kimi REST（自动补 `/api/v1` 前缀） | Bearer |
| `/api/relay/claude/*` | claude REST | Bearer |

REST 代理与 WS 代理共用同一安全模型：只允许本站点/本机来源使用，避免被任意网页借用成开放代理。

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

打开 http://localhost:3000 ，在「设置」里添加 Agent 服务器（类型/地址/Token，Kimi 与 Claude 还需服务器上的默认工作目录）与可选的火山 ASR/TTS 配置即可。首次打开会把旧的单一 serverUrl/token 自动迁移为一个 Codex 配置。

## 目录结构

```text
server/          # Express：静态托管 + WS 代理（/ws/*）+ REST relay（/api/relay/*）
src/
├── types.ts     # 协议模型 + AgentProfile/AgentType，对应 Models.kt / AgentProfile.kt
├── settings.ts  # 本地设置（localStorage）：多 profile + ASR/TTS，对应 SettingsStore
├── slashCommands.ts # /compact /review /fork /undo、!shell 解析，对应 SlashCommands.kt
├── asr.ts       # 火山 ASR 帧协议 + 麦克风 AudioWorklet，对应 StreamingAsrClient
├── tts.ts       # 火山 TTS 双向流式 + SpeakTextFilter + Web Audio 播放，对应 VolcengineTtsManager
├── protocol/    # 协议客户端（UI 无关，后续可暴露为 HTTP API）
│   ├── codexClient.ts  # codex JSON-RPC over WS，对应 WebSocketCodexRepository
│   ├── kimiClient.ts   # kimi REST + WS，对应 KimiCodexRepository
│   ├── claudeClient.ts # claude REST + per-session WS NDJSON，对应 ClaudeCodexRepository
│   ├── registry.ts     # 按 profile 管理连接与事件聚合，对应 RepositoryRegistry
│   └── repository.ts   # Repository 接口，对应 CodexRepository
├── stores.ts    # ThreadListStore / ChatStore（多 profile 聚合、动作派发），对应两个 ViewModel
└── components/  # 消息卡片、Markdown、输入栏、审批/动作弹窗等
```

## API 与 CLI

web 服务把完整能力暴露为 REST 业务 API（`/api/*`，与 `/api/relay` 转发区分），并附带一个命令行客户端：

```bash
# API（默认 http://localhost:3000；设置 API_TOKEN 环境变量后需 Authorization: Bearer <token>）
curl http://localhost:3000/api/sessions                          # 跨所有 Agent 的会话列表
curl http://localhost:3000/api/sessions/<threadId>               # 会话详情（含 turns）
curl -X POST http://localhost:3000/api/sessions -H 'Content-Type: application/json' \
  -d '{"profile":"本机Claude","model":"...","effort":"high"}'    # 新建会话
curl -X POST http://localhost:3000/api/sessions/<threadId>/messages \
  -H 'Content-Type: application/json' -d '{"text":"你好"}'       # 发消息/斜杠命令
curl http://localhost:3000/api/turns/<threadId>                  # 轮次状态（轮询用）
curl http://localhost:3000/api/approvals/pending                 # 待审批
curl -X POST http://localhost:3000/api/approvals/<id> -d '{"decision":"accept"}'
curl -X PUT http://localhost:3000/api/profiles -d '{"profiles":[...]}'   # 配置同步

# CLI（Node ≥24 直接执行；npm link 或 npm i -g 后全局可用，命令名 `nex` 避免与本地 codex CLI 冲突）
nex ls                     # 会话列表
nex new --profile 本机Claude
nex open <threadId>         # 查看历史
nex send <threadId> "你好"  # 发消息，轮询到完成并流式打印
nex cmd <threadId> /compact   # 斜杠命令（codex 动作 / claude 透传都支持）
nex stop <threadId> / nex rm <threadId>
nex approvals / nex approve <id> <decision>
nex profiles [ls|add|rm]    # 配置管理（与服务端文件同步）
```

- 配置以服务端文件 `~/.codex-web/profiles.json` 为权威：web 前端设置变更会同步过去，CLI 通过 `codex profiles` 管理；两端自动合并不互相覆盖。
- `CODEX_WEB_URL`（默认 http://localhost:3000）与 `CODEX_WEB_TOKEN` 可覆盖 CLI 的服务地址与鉴权。
- 协议层（`src/protocol/`）与 UI 完全解耦，浏览器与服务端共用同一套实现。
