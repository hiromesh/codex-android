# claude-server — Claude Code 套皮服务

在本地/服务器上常驻一个 Claude Code 包装服务：**REST 控制面 + WebSocket NDJSON 事件面**，
协议对齐官方 direct-connect（`CLAUDE_DEPLOY.md`），供 Android App 远程控制 Claude Code。

> 为什么需要套皮：公开版 `claude` CLI 没有 `server` 子命令（DIRECT_CONNECT 是编译期
> feature flag，仅内部构建），因此用官方 `@anthropic-ai/claude-agent-sdk` 包一层薄服务。

## 架构

```
Android App ──HTTPS/WSS──> Caddy ──> 127.0.0.1:58628 (claude_server.js)
```

本地联调可跳过 Caddy：App 直接填 `ws://<电脑IP>:58628` 或 `http://<电脑IP>:58628`。

## 启动

```bash
npm install
node claude_server.js            # 默认 127.0.0.1:58628
```

环境变量：

| 变量 | 默认 | 说明 |
|---|---|---|
| `PORT` | 58628 | 监听端口 |
| `HOST` | 127.0.0.1 | 监听地址（局域网联调可设 0.0.0.0） |
| `WORKSPACE` | `$HOME` | 建会话未指定 cwd 时的默认工作目录 |
| `MAX_SESSIONS` | 4 | 并发会话硬上限（SDK 每次 query 起一个 claude 子进程） |
| `QUERY_TIMEOUT_MS` | 1800000 (30min) | 单轮 query 超时（SDK 默认 10min 偏短） |
| `AUTH_TOKEN` | 自动生成 | 覆盖鉴权 token（否则读/写 `~/.claude/server-token`） |
| `LIST_CLI_SESSIONS` | `false` | **默认隔离**：列表只显示本服务创建的会话，不扫描本机 CLI 会话（避免 web 干扰 CLI 正在跑的会话）。设 `true` 恢复共享（能看到 CLI 开过的所有会话） |

### 会话互斥（web/CLI 并发写保护）

- **turn 前检测**：目标会话被交互式 `claude` 进程占用（`claude -r <id>` / `--resume=` / `--session-id`，自动排除 SDK 子进程）时拒绝，返回错误「该会话正在被 CLI 使用」。
- **锁文件**：turn 期间写 `~/.claude/server-locks/<serverId>.lock`（含 pid/时间），结束即删；CLI 侧为黑盒无法强制，只能靠文档自觉。
- **已知局限**：`claude -r` 交互选择器启动的进程不带会话 id，识别不到；此时用 `LIST_CLI_SESSIONS=false` 隔离最稳妥。
- 单连接异常不会拖垮整个服务（进程级 uncaughtException/unhandledRejection 兜底）。

前置条件：`claude` CLI 已安装并登录（`claude` 跑一次 `/login`，OAuth 或 `claude auth login --console`），
`~/.claude/` 里要有登录态——SDK 子进程自动继承。

## 验证

```bash
curl -s http://127.0.0.1:58628/healthz                        # ok
TOKEN=$(cat ~/.claude/server-token)
curl -s -H "Authorization: Bearer $TOKEN" -X POST \
  http://127.0.0.1:58628/sessions -d '{"cwd":"'$HOME'"}'
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:58628/sessions   # 历史会话列表

# 交互式对话（含审批应答）：
uv run --with websockets python claude_mock.py
# 无审批模式：DSP=1 uv run --with websockets python claude_mock.py
```

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/healthz` | 健康检查（无需鉴权） |
| POST | `/sessions` | 建会话，body `{"cwd"?, "dangerously_skip_permissions"?}` → `{session_id, ws_url, work_dir}` |
| GET | `/sessions` | 会话列表（含 CLI 直接开过的，按更新时间倒序） |
| GET | `/sessions/{id}` | 会话元数据（resume 用） |
| GET | `/sessions/{id}/messages` | 历史消息（来自 `~/.claude/projects/**/*.jsonl`） |
| DELETE | `/sessions/{id}` | 移除会话（不删本地 jsonl） |
| WS | `/sessions/{id}/ws` | NDJSON 事件面（见下） |

WS 消息（每行一个 JSON）：

- 客户端发：`user`（`{message:{content:[{type:"text",text}]}}`）、`control_response`（审批应答）、`control_request`（`request.subtype:"interrupt"` 打断）
- 服务端发：`system/init`（含 `session_id`）、`stream_event`（assistant 增量，content 里 `text`/`thinking`/`tool_use` 块）、`control_request`（`subtype:"can_use_tool"` 审批）、`result`（一轮结束，含 usage/cost）、`keep_alive`（忽略）、`error`

## 数据落盘

| 路径 | 内容 |
|---|---|
| `~/.claude/server-token` | 鉴权 token（自动生成，0600） |
| `~/.claude/server-sessions.json` | serverId → claude session id 映射（重启后 resume 不丢） |
| `~/.claude/projects/<cwd>/<uuid>.jsonl` | claude 会话历史（SDK 子进程写，服务重启后仍可读） |

## 已知坑

1. **每次 query 起一个 claude 子进程**：`MAX_SESSIONS` 是硬并发上限，会话多了注意内存。
2. **resume 要求 cwd 一致**：SDK 会话文件按 `~/.claude/projects/<cwd>/` 落盘，恢复会话时 cwd 必须与原来一致（App 端 resume 用 jsonl 里记录的 cwd）。
3. **单轮 10 分钟超时**：SDK 默认单轮超时 10 分钟，`QUERY_TIMEOUT_MS` 已调大到 30 分钟；长任务仍可能在服务端超时，App 等待上限要匹配。
4. **重启断所有 WS**：进行中的轮会被打断（未答审批按 deny 处理）；App 断线后重连即可。
5. **审批只支持 allow/deny**：SDK 0.3.221 的 canUseTool 只有这两个行为；`allowOnce` 语义需映射为 allow + updatedPermissions，MVP 未做。
