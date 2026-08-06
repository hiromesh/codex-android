# Claude Code 远程接入（套皮服务 + Android 对接）

## 总体判断

**Claude Code 没有可直接部署的服务端**：源码里有官方 `claude server` 命令（DIRECT_CONNECT 特性），
但是编译期 feature flag（`feature('DIRECT_CONNECT')` 构建时烘焙），公开版 CLI 不含此命令；
运行时 A/B gate 覆盖入口被 `process.env.USER_TYPE === 'ant'` 锁死（仅内部账号）。
因此用官方 SDK `@anthropic-ai/claude-agent-sdk` 包一层薄服务（本仓库 `claude-server/`），
wire 协议按 direct-connect 形状设计——官方放出 `claude server` 后 App 端零改动切换。

与 Codex/Kimi 的差异：控制面 **REST**（`POST /sessions`）+ 事件面 **WS NDJSON**（每会话一条连接），
不是 JSON-RPC over WS，也不是 kimi 的订阅式单 WS。

## 1. 服务端（claude-server/）

实现与用法见 `claude-server/README.md`。要点：

- Node ≥20，依赖 `@anthropic-ai/claude-agent-sdk`（**≥0.3.223**，事件形状以它为准）+ `ws`
- 鉴权：Bearer token，`~/.claude/server-token`（自动生成，0600）
- 会话：SDK `query()` 每次拉起一个 `claude` 子进程；claude 会话落盘 `~/.claude/projects/<cwd>/<uuid>.jsonl`，
  按 claude session id resume；serverId↔claudeSessionId 映射持久化 `~/.claude/server-sessions.json`
- 历史会话列表/消息直接扫 jsonl——App 能看到本机 CLI 开过的所有 claude 会话

### 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/healthz` | 健康检查（无需鉴权） |
| POST | `/sessions` | 建会话，body `{"cwd"?, "dangerously_skip_permissions"?}` → `{session_id, ws_url, work_dir}` |
| GET | `/sessions` | 会话列表 `{items:[{session_id,cwd,created_at,updated_at,last_prompt}]}`，按更新时间倒序 |
| GET | `/sessions/{id}` | 会话元数据（resume 用）：`{session_id,cwd,work_dir,active,claude_session_id,updated_at,last_prompt}` |
| GET | `/sessions/{id}/messages` | 历史消息（过滤 isMeta 注入行）：`{session_id,cwd,items:[{type,role,content,timestamp}]}` |
| DELETE | `/sessions/{id}` | 移除会话（标记删除，不删本地 jsonl） |
| WS | `/sessions/{id}/ws` | 事件面，NDJSON 一行一个 JSON |

### WS 协议（SDK 0.3.223 实测）

**客户端发**：

- 发消息：`{"type":"user","message":{"role":"user","content":[{"type":"text","text":"..."}]},"parent_tool_use_id":null,"session_id":""}`
- 审批应答：`{"type":"control_response","response":{"subtype":"success","request_id":"<同审批的>","response":{"behavior":"allow"|"deny","message"?}}}`
- 打断：`{"type":"control_request","request_id":"<uuid>","request":{"subtype":"interrupt"}}`

**服务端发**：

- `{"type":"system","subtype":"init","session_id":...}` 会话建立（内部 claude session id）
- `{"type":"assistant","message":{"content":[blocks]}}` 流式正文；content 块类型：
  - `text`（增量片段，App 累积追加）
  - `thinking`（思考过程，`thinking` 字段）
  - `tool_use`（工具调用，`id`/`name`/`input`；工具输出不回传）
- `{"type":"control_request","request_id":...,"request":{"subtype":"can_use_tool","tool_name","input",...}}` 审批
- `{"type":"result","subtype":"success"|"interrupted"|"error",...}` 一轮结束（含 `total_cost_usd`/`usage`）
- `{"type":"error",...}` 异常；`{"type":"keep_alive"}` 心跳（忽略）

注意：**0.3.221 的 `stream_event` 顶层事件在 0.3.223 改成了 `assistant`**；`query()` 参数也从
顶层 `permissionMode/canUseTool/timeout` 改成了 `options` 内传。写客户端以 0.3.223 为准。

## 2. Android 对接（ClaudeCodexRepository）

实现：`app/src/main/java/com/hiro/codex_android/data/ClaudeCodexRepository.kt`，
对外实现 `CodexRepository` 接口，聊天/列表 UI 复用，无协议感知。

与 codex/kimi 的差异处理：

- **每会话一条 WS**：claude 的 WS 是 per-session（`/sessions/{id}/ws`），repository 里每个打开的
  会话各持一个连接，事件统一汇入 `events`；首次发消息才连接（惰性），断线后下一次 startTurn 重连，
  历史对账靠 `readThread` 全量重拉（jsonl 天然可重放）。
- **事件映射**：
  - `assistant.content[]`：`text` → `AgentMessageDelta`（先发 `ItemStarted`）；`thinking` → `ReasoningSummaryDelta`；
    `tool_use` → `ItemStarted(CommandExecution inProgress)`
  - `control_request(can_use_tool)` → `ApprovalRequest`；应答 `allow`/`deny`（AcceptForSession 也映射 allow，MVP 不做 allowOnce），
    `Cancel` = deny + interrupt
  - `result`：success→completed、interrupted→interrupted、其他→failed
  - 工具卡片收尾：claude 无工具输出流，收到下一段 text 或 result 时把 in-progress 工具统一标 completed
- **不支持项**（抛 `Claude 暂不支持...`）：模型/推理档位切换（`listModels` 返回空，UI 隐藏选择器）、
  `/compact`、`/review`、`/fork`、`/undo`、`!shell`
- **归档**：claude 无归档概念，`archiveThread` 等同 `deleteThread`（DELETE /sessions/{id}，本地 jsonl 保留）

## 3. 已知坑

1. **每次 query 起一个 claude 子进程**：`MAX_SESSIONS` 是硬并发上限；会话多注意服务器内存。
2. **resume 要求 cwd 一致**：SDK 会话文件按 `~/.claude/projects/<cwd>/` 落盘，恢复会话的 cwd 必须与原来一致
   （jsonl 里记录着原 cwd，服务端 resume 时用它）。
3. **SDK 默认单轮 10 分钟超时（0.3.221 文档值）**：0.3.223 的 options 没有 timeout 字段，
   服务端用 `setTimeout` + AbortController 实现 30 分钟兜底，中断时报 `subtype: interrupted`。
4. **无害命令不触发审批**：`echo`/`sleep` 等在 Claude 的 auto-allow 列表，`canUseTool` 不回调；
   需要审批的是写文件、网络、危险命令等。App 审批弹窗只会在真正需要时出现。
5. **打断竞态**：客户端收到 `result` 后应立即允许下一条消息——服务端在发完 result 后同步释放轮锁
   （不等 SDK for-await 的 finally），否则下一条消息会撞 "turn already in progress"。
6. **重启断所有 WS**：进行中的轮被打断（未答审批按 deny 处理）；App 断线后重连 + `readThread` 对账。
