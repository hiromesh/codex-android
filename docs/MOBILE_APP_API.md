# Codex 手机 App 接口文档

> 面向手机 App（Android/iOS）开发者的接口说明。Codex 服务端**无需任何改动**，
> 直接使用官方 `codex app-server` 的 WebSocket 传输。
>
> 参考实现：`mock.py`（仓库根目录，130 行 Python，覆盖本文全部核心流程）。
> 协议权威定义：`codex-rs/app-server-protocol/src/protocol/`（Rust），
> 可执行 `codex app-server generate-ts --out DIR` 导出 TypeScript 类型。

## 0. 架构与连接信息

```
手机 App ──wss──► Caddy (TLS 终结, 443) ──ws──► codex app-server (127.0.0.1:8765)
```

| 项 | 值 |
|---|---|
| 生产地址 | `wss://codex.waibozishu.com:8443` |
| 本机调试 | `ws://127.0.0.1:8765`（loopback 可不配鉴权） |
| 鉴权 | HTTP 头 `Authorization: Bearer <WS_TOKEN>` |
| 健康检查 | `GET /healthz` → 200（可用于连接前探活） |
| 就绪检查 | `GET /readyz` → 200 |

无 token 或 token 错误时，WebSocket Upgrade 被拒（401）。

> **为什么是 8443**：服务器在大陆腾讯云，域名未 ICP 备案时腾讯云会拦截 80/443
> （443 直接 TLS 层 RST，80 劫持到 webblock 页）。非标准端口（8443）不在拦截范围，
> 备案下来后可切回 443。证书复用已签发的 `codex.waibozishu.com` 证书，
> 注意 90 天续期需改用 DNS-01 挑战（HTTP-01 走 80 会被拦）。

## 1. 协议基础

- 每个 WebSocket **text frame 携带一个完整 JSON 对象**（非分帧、非二进制）。
- 类似 JSON-RPC 2.0，但**线上不带 `"jsonrpc"` 字段**。
- 所有字段名 **camelCase**。
- 三种消息形态：

| 形态 | 识别 | 方向 |
|---|---|---|
| 请求 Request | 有 `id` 有 `method` | client → server |
| 响应 Response | 有 `id` 无 `method`，含 `result` 或 `error` | server → client |
| 通知 Notification | 无 `id` 有 `method` | server → client |
| **服务端反向请求** | 有 `id` 有 `method` | server → client（审批，**必须应答**） |

`id` 用递增整数即可。错误响应形如 `{"id": 3, "error": {"code": -32600, "message": "..."}}`。

## 2. 生命周期（时序）

```
client                                server
  │ ── initialize ────────────────────► │
  │ ◄──────────── result ────────────── │
  │ ── initialized (notification) ────► │   ← 必须，否则后续请求被拒
  │ ── thread/start (或 thread/resume) ► │
  │ ◄──────────── result (thread) ───── │
  │                                     │
  │ ── turn/start ─────────────────────► │
  │ ◄──────────── result (turn) ─────── │
  │ ◄──── turn/started ──────────────── │
  │ ◄──── item/started ──────────────── │
  │ ◄──── item/agentMessage/delta × N ─ │   ← 流式回复正文
  │ ◄──── item/completed ────────────── │
  │   （可选）◄── item/commandExecution/requestApproval
  │ ── result {decision} ─────────────► │   ← 审批应答
  │ ◄──── turn/completed ────────────── │   ← 一轮结束
  │            （重复 turn/start）        │
```

## 3. Client → Server 接口

### 3.1 `initialize`（连接后第一个请求，必做）

```json
{"method": "initialize", "id": 1, "params": {
  "clientInfo": {"name": "my-android-app", "version": "1.0.0"},
  "capabilities": {"experimentalApi": true}
}}
```

`capabilities.experimentalApi: true` 是**必须的**——`thread/settings/update`（会话中
切模型）等接口属于实验 API，不声明会报 `-32600 requires experimentalApi capability`。

响应的 `result` 内容可忽略。随后**必须**补一条通知（无 id）：

```json
{"method": "initialized"}
```

### 3.2 `thread/start` — 新建会话

```json
{"method": "thread/start", "id": 2, "params": {}}
```

可选参数：`model`、`cwd` 等（均可省略用服务端默认）。

**权限/沙箱覆盖**（等价于 CLI 的 `--dangerously-bypass-approvals-and-sandbox`，实测有效）：

```json
{"method": "thread/start", "id": 2, "params": {
  "approvalPolicy": "never",
  "sandbox": "danger-full-access"
}}
```

- `approvalPolicy`：`"untrusted"` / `"on-request"`（默认）/ `"never"` / `"on-failure"`
- `sandbox`：`"read-only"` / `"workspace-write"` / `"danger-full-access"`

也可以在服务端 `~/.codex/config.toml` 全局设默认（所有会话生效，App 不用传）：

```toml
approval_policy = "never"
sandbox_mode = "danger-full-access"
```

改完 `sudo systemctl restart codex-appserver`。会话中途变更走
`thread/settings/update` 的 `approvalPolicy` / `sandboxPolicy`。

响应 `result`：

```json
{
  "thread": {
    "id": "019fa80f-a1f7-7971-a93d-8218798fedd1",
    "preview": "",
    "ephemeral": false,
    "createdAt": 1753690000,
    "updatedAt": 1753690000,
    "status": {"type": "idle"},
    "cwd": "/home/user",
    "turns": []
  },
  "model": "gpt-5.6",
  "modelProvider": "openai",
  "approvalPolicy": "...",
  "cwd": "/home/user"
}
```

**保存 `thread.id`（UUIDv7），后续所有操作都靠它。**

### 3.3 `thread/resume` — 恢复已有会话（接力/重连后用）

```json
{"method": "thread/resume", "id": 3, "params": {"threadId": "019fa80f-a1f7-7971-a93d-8218798fedd1"}}
```

响应结构同 `thread/start`。会话历史持久化在服务端，App 杀掉重连后 resume 即可继续。

### 3.4 `thread/list` — 会话列表（做"历史会话"页）

```json
{"method": "thread/list", "id": 4, "params": {"limit": 20, "cursor": null, "archived": false}}
```

响应：`{"data": [Thread...], "nextCursor": "..." | null}`，`nextCursor` 为 null 表示没有更多。

### 3.5 `thread/read` — 拉取会话历史（进入会话时渲染旧消息）

```json
{"method": "thread/read", "id": 5, "params": {"threadId": "...", "includeTurns": true}}
```

`includeTurns: true` 时返回的 `thread.turns` 含每轮的 `items`（见 §5 数据模型），
用于 App 冷启动后重建聊天界面。

### 3.6 `turn/start` — 发消息（一轮对话）

```json
{
  "method": "turn/start",
  "id": 6,
  "params": {
    "threadId": "019fa80f-...",
    "input": [{"type": "text", "text": "你好，帮我看下服务器磁盘占用"}]
  }
}
```

`input` 是数组，还支持 `{"type": "image", "url": ...}` / `{"type": "localImage", "path": ...}`
（本地路径是**服务端**路径，手机端用 `image` + URL/data URL）。

响应 `result.turn.id` 为 turn id。随后进入流式通知阶段（§4）。

### 3.7 `turn/interrupt` — 打断正在生成的回复

```json
{"method": "turn/interrupt", "id": 7, "params": {"threadId": "...", "turnId": "..."}}
```

### 3.8 `model/list` — 列出可用模型（模型选择页）

```json
{"method": "model/list", "id": 8, "params": {"cursor": null, "limit": null, "includeHidden": null}}
```

响应：`{"data": [Model...], "nextCursor": null}`。实测返回示例（节选）：

```jsonc
// {"id":8, "result":{"data":[
//   {"id":"gpt-5.6-sol","displayName":"GPT-5.6-Sol","isDefault":true,"hidden":false,
//    "supportedReasoningEfforts":[{"reasoningEffort":"low",...},"medium","high","xhigh","max","ultra"],...},
//   {"id":"gpt-5.6-terra",...}, {"id":"gpt-5.6-luna",...},
//   {"id":"gpt-5.5",...}, {"id":"gpt-5.4",...}, {"id":"gpt-5.4-mini",...}
// ], "nextCursor": null}}
```

`Model` 关键字段：

| 字段 | 说明 |
|---|---|
| `id` | 模型 ID，切换模型时传这个值 |
| `displayName` | 展示名（选择器 UI 用） |
| `description` | 模型描述 |
| `isDefault` | 是否默认模型（只有一个为 true） |
| `hidden` | 默认列表里隐藏，`includeHidden: true` 才返回 |
| `supportedReasoningEfforts` | 支持的推理档位 `[{reasoningEffort, description}]` |
| `defaultReasoningEffort` | 默认推理档位 |
| `inputModalities` | 输入模态（判断是否支持图片） |
| `upgrade` / `upgradeInfo` | 有更新模型时的升级提示 |

### 3.9 切换模型

三种场景：

**① 新建会话时指定** — `thread/start` 带 `model`：

```json
{"method": "thread/start", "id": 9, "params": {"model": "gpt-5.6"}}
```

**② 已有会话中途切换** — `thread/settings/update`（对后续所有 turn 生效）：

```json
{"method": "thread/settings/update", "id": 10, "params": {"threadId": "...", "model": "gpt-5.6"}}
```

同时可改推理档位：`"effort": "high"`（取值以 `model/list` 返回的
`supportedReasoningEfforts` 为准，常见 `minimal`/`low`/`medium`/`high`/`xHigh`）。

**③ 恢复会话时覆盖** — `thread/resume` 带 `model`：

```json
{"method": "thread/resume", "id": 11, "params": {"threadId": "...", "model": "gpt-5.6"}}
```

注意：切换后留意 `model/rerouted` 通知（§4），系统也可能因额度/可用性自动改道模型。

### 3.10 其他常用接口（可选）

| 方法 | 参数 | 用途 |
|---|---|---|
| `thread/archive` | `{threadId}` | 归档会话 |
| `thread/unarchive` | `{threadId}` | 取消归档 |
| `thread/name/set` | `{threadId, name}` | 重命名会话 |
| `thread/delete` | `{threadId}` | 删除会话 |
| `account/rateLimits/read` | 无参 | 查额度 |

## 4. Server → Client 通知（流式事件）

App 端只需要一个统一入口按 `method` 分发。**加粗的是 MVP 必须处理的。**

| method | 说明 | 关键字段 |
|---|---|---|
| **`turn/started`** | 一轮开始 | `threadId`, `turn.id` |
| **`item/agentMessage/delta`** | **回复正文的流式增量，逐字追加显示** | `delta`（字符串片段）、`itemId` |
| **`item/started`** | 一个 item 开始（命令执行等） | `item`（见 §5） |
| **`item/completed`** | 一个 item 完成（agentMessage 完整文本在此） | `item` |
| **`turn/completed`** | 一轮结束 | `turn.status`: `completed`/`interrupted`/`failed` |
| `thread/started` | 新会话建立 | `thread` |
| `thread/status/changed` | 会话状态变化 | `status` |
| `thread/tokenUsage/updated` | token 用量（做用量展示） | `tokenUsage` |
| `account/rateLimits/updated` | 额度变化 | `rateLimits` |
| `turn/diff/updated` | 本轮累计 diff（做代码变更视图） | `diff` |
| `turn/plan/updated` | 计划更新 | `plan` |
| `model/rerouted` | 模型被自动改道（额度/可用性） | `fromModel`, `toModel`, `reason` |
| **`item/reasoning/summaryTextDelta`** | **思考摘要流式增量** | `itemId`, `delta`, `summaryIndex` |
| `item/reasoning/summaryPartAdded` | 思考摘要开始新一段 | `itemId`, `summaryIndex` |
| `item/reasoning/textDelta` | 原始推理文本增量（OpenAI 加密推理，实际拿不到） | `itemId`, `delta` |

处理要点：

- `item/agentMessage/delta` 按 `itemId` 累积拼接；`item/completed` 里 `item.type == "agentMessage"`
  时 `item.text` 是完整文本，可用来校对/替换。
- 一个 turn 内可能有多个 agentMessage / commandExecution 交错出现。
- `turn/completed` 的 `turn.error` 非空表示失败，应展示错误。

## 5. 核心数据模型：`ThreadItem`

`item/started`、`item/completed`、`thread/read` 里的 item 都是 `ThreadItem`，
**以 `type` 字段区分**（camelCase）：

| type | 说明 | 主要字段 |
|---|---|---|
| `userMessage` | 用户消息 | `content: [{type:"text", text}]` |
| `agentMessage` | **AI 回复** | `text`（Markdown） |
| `commandExecution` | 命令执行 | `command`, `cwd`, `status`, `aggregatedOutput`, `exitCode`, `durationMs` |
| `fileChange` | 文件改动 | `changes`, `status` |
| `plan` | 计划 | `text` |
| `reasoning` | 推理摘要 | `summary[]` |
| `mcpToolCall` | MCP 工具调用 | `server`, `tool`, `status`, `result`, `error` |
| `webSearch` | 联网搜索 | — |

MVP 渲染优先级：`agentMessage`（必须）→ `commandExecution`（显示"执行了 xxx 命令"）→
`fileChange` → 其余可折叠隐藏。

`Turn.status` 枚举：`inProgress` / `completed` / `interrupted` / `failed`。

## 5.1 思考过程（reasoning，容易踩坑）

**思考过程 = `type: "reasoning"` 的 item，取 `summary` 字段（字符串数组），不是 `content`。**
OpenAI 的推理原文是加密的，`content` 恒为空；能拿到的只有**摘要**。

- 流式：`item/reasoning/summaryTextDelta`（`delta` 追加）+ `item/reasoning/summaryPartAdded`（新段落，`summaryIndex` 递增）
- 完整：`item/completed` 里 `item.summary`，itemId 形如 `rs_...`
- reasoning item 通常先于 agentMessage 出现，UI 上渲染为可折叠的"思考过程"

**默认可能拿不到摘要**（`summary: []` 且无 delta）——需要推理档位足够且显式开摘要：

```jsonc
// 方式一：按轮开启（turn/start 带参数，实测有效）
{"method": "turn/start", "id": 6, "params": {
  "threadId": "...",
  "effort": "high",          // 推理档位
  "summary": "detailed",     // "auto" | "concise" | "detailed" | "none"
  "input": [{"type": "text", "text": "..."}]
}}

// 方式二：服务端全局默认（~/.codex/config.toml）
// model_reasoning_summary = "detailed"
// model_reasoning_effort = "high"
```

实测载荷（`effort=high, summary=detailed`）：

```jsonc
// ← {"method":"item/reasoning/summaryPartAdded","params":{"itemId":"rs_0b94...","summaryIndex":0}}
// ← {"method":"item/reasoning/summaryTextDelta","params":{"itemId":"rs_0b94...","delta":"**Calculating numeri..."}}
// ← {"method":"item/completed","params":{"item":{"type":"reasoning","id":"rs_0b94...",
//      "summary":["**Calculating numeric puzzle answer**"],"content":[]}}}
```

## 6. Server → Client 反向请求（审批，**必须应答**）

codex 执行有风险的操作时会停下来等 App 答复。收到**有 `id` 且有 `method`** 的消息即为审批请求，
App 应弹窗，用户选择后回一个 JSON-RPC 响应。

### 6.1 `item/commandExecution/requestApproval` — 命令执行审批

```json
{
  "method": "item/commandExecution/requestApproval",
  "id": 42,
  "params": {
    "threadId": "...", "turnId": "...", "itemId": "...",
    "command": "rm -rf build/",
    "cwd": "/home/user/proj",
    "reason": "..."
  }
}
```

应答（`decision` 四选一）：

```json
{"id": 42, "result": {"decision": "accept"}}
```

| decision | 含义 |
|---|---|
| `accept` | 批准本次 |
| `acceptForSession` | 批准且本会话内同类不再询问 |
| `decline` | 拒绝，agent 继续当前轮 |
| `cancel` | 拒绝并立即中断当前轮 |

### 6.2 `item/fileChange/requestApproval` — 文件修改审批

params 含 `reason`、`grantRoot`；应答同结构：`{"id": ..., "result": {"decision": "accept"}}`。

### 6.3 `item/permissions/requestApproval` — 权限提升审批（进阶，可后置）

params 含 `permissions`（请求的网络/文件权限档案）。应答结构不同：

```json
{"id": 43, "result": {"permissions": {...GrantedPermissionProfile...}, "scope": "turn"}}
```

MVP 阶段建议直接在服务端把审批策略放宽（`approvalPolicy` 设为 `never` 或类似），
App 只实现 6.1/6.2 两种即可。

## 7. 完整 wire 示例（实测流量）

```jsonc
// → 连接 wss://codex.waibozishu.com，Header: Authorization: Bearer <token>

{"method":"initialize","id":1,"params":{"clientInfo":{"name":"mock-phone","version":"0.2.0"}}}
// ← {"id":1,"result":{...}}
{"method":"initialized"}
// ← {"method":"remoteControl/status/changed","params":{...}}   (可忽略)

{"method":"thread/start","id":2,"params":{}}
// ← {"id":2,"result":{"thread":{"id":"019fa80f-...","preview":"",...},"model":"gpt-5.6",...}}
// ← {"method":"thread/started","params":{...}}

{"method":"turn/start","id":3,"params":{"threadId":"019fa80f-...","input":[{"type":"text","text":"你好"}]}}
// ← {"id":3,"result":{"turn":{"id":"019fa812-...",...}}}
// ← {"method":"turn/started","params":{"threadId":"...","turn":{"id":"..."}}}
// ← {"method":"item/started","params":{"item":{"type":"agentMessage","id":"...","text":""}}}
// ← {"method":"item/agentMessage/delta","params":{"itemId":"...","delta":"你好"}}
// ← {"method":"item/agentMessage/delta","params":{"itemId":"...","delta":"！有什么"}}
// ← ...更多 delta...
// ← {"method":"item/completed","params":{"item":{"type":"agentMessage","text":"你好！有什么可以帮你的？..."}}}
// ← {"method":"thread/tokenUsage/updated","params":{...}}
// ← {"method":"turn/completed","params":{"threadId":"...","turn":{"id":"...","status":"completed"}}}
```

## 8. Android 实现要点

1. **WebSocket**：OkHttp `WebSocket`（`newWebSocket(Request, Listener)`），
   `Request` 上加 `addHeader("Authorization", "Bearer $TOKEN")`。wss 由系统 TLS 栈处理。
2. **并发模型**：单连接 + 一个 reader 协程按 `method` 分发；
   用 `ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>` 做 pending 请求映射（id → 响应）。
3. **UI 状态**：以 `threadId + turnId + itemId` 组织消息流；delta 追加到对应 itemId 的气泡。
4. **断线重连**：指数退避重连 → 重新 `initialize` → `thread/resume` 当前 threadId；
   通知**不重放**，重连后调 `thread/read {includeTurns: true}` 拉全量对账 UI。
5. **后台推送**：ws 断开后服务端不会缓存消息，离线推送（FCM/Bark）需要另加服务端小组件
   （订阅 turn 完成事件转发推送服务），不在本期范围。
6. **超时**：`turn/start` 后建议客户端超时 300s+（长任务很常见），ws 层开 ping/pong 保活。
7. **类型定义**：可在服务器上跑 `codex app-server generate-ts --out schema/` 拿到全部
   TypeScript 类型作为字段对照（注意 ws 传输官方标注 experimental，升级服务端版本时核对 CHANGELOG）。
8. **模型选择器**：进入会话前调 `model/list` 渲染选择页（按 `isDefault` 预选、
   过滤 `hidden`），会话内切换走 `thread/settings/update`，切换后新值对后续 turn 生效。
9. **参考实现**：`mock.py` —— 连接、握手、发消息、流式渲染、审批应答的完整最小实现，
   可直接翻译成 Kotlin。

## 9. 服务端部署备忘（已完成）

```bash
# 启动（已配 systemd；注意必须设 WorkingDirectory，否则会话默认 cwd 是 /）
# override 位于 /etc/systemd/system/codex-appserver.service.d/override.conf:
#   [Service]
#   WorkingDirectory=/home/ubuntu
codex app-server --listen ws://127.0.0.1:8765 \
  --ws-auth capability-token --ws-token-file ~/.codex/ws-token

# Caddyfile（8443 规避未备案域名 80/443 拦截；腾讯云安全组需放行 8443）
codex.waibozishu.com:8443 {
    reverse_proxy 127.0.0.1:8765
}
```

token 存放在服务器 `~/.codex/ws-token`，App 内置或通过配置页输入。
