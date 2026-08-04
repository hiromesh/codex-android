## 总体判断

**Yes — kimi-code 有非常接近 Codex `app-server` 的常驻服务能力。**  
入口是 `kimi web`（底层 `@moonshot-ai/kap-server`）：**Fastify REST `/api/v1/*` + WebSocket `/api/v1/ws`**，可 `--host` 绑到局域网/公网，带 bearer token。  

与 Codex 的差异：控制面是 **REST**，事件面是 **自定义 WS 帧**（不是 JSON-RPC 2.0 over WS）。另有 **ACP（stdio JSON-RPC）**，那是 IDE 子进程模式，不是网络常驻服务。

---

## 1. 目录结构

| 路径 | 包名 | 角色 |
|------|------|------|
| `/Users/hiro/kimi-code/apps/kimi-code` | `@moonshot-ai/kimi-code` | 主 CLI（bin: `kimi`），挂载 TUI / `-p` / `web` / `acp` |
| `/Users/hiro/kimi-code/apps/kimi-web` | `@moonshot-ai/kimi-web` | Web UI（Vue），由 `kimi web` 同进程托管 |
| `/Users/hiro/kimi-code/apps/kimi-inspect` | `@moonshot-ai/kimi-inspect` | 内部 inspect 工具 |
| `/Users/hiro/kimi-code/apps/vis` | `@moonshot-ai/vis` | 会话 debug 可视化（server+web） |
| `/Users/hiro/kimi-code/apps/vscode` | `kimi-code` | VS Code 扩展 |
| `/Users/hiro/kimi-code/packages/kap-server` | `@moonshot-ai/kap-server` | **常驻 HTTP/WS 服务器**（app-server 等价物） |
| `/Users/hiro/kimi-code/packages/protocol` | `@moonshot-ai/protocol` | REST + WS 协议 schema |
| `/Users/hiro/kimi-code/packages/agent-core` / `agent-core-v2` | agent 引擎 | 会话/工具/权限核心 |
| `/Users/hiro/kimi-code/packages/node-sdk` | `@moonshot-ai/kimi-code-sdk` | 官方 TS SDK（进程内） |
| `/Users/hiro/kimi-code/packages/klient` | `@moonshot-ai/klient` | v2 契约客户端（memory / **ipc**） |
| `/Users/hiro/kimi-code/packages/acp-adapter` | `@moonshot-ai/acp-adapter` | ACP 适配器 |
| `/Users/hiro/kimi-code/packages/kaos` | 执行环境抽象 | |
| `/Users/hiro/kimi-code/packages/kosong` | LLM 抽象层 | |
| `/Users/hiro/kimi-code/packages/minidb` | 嵌入式 KV（WAL 持久化） | |
| `/Users/hiro/kimi-code/packages/oauth` / `telemetry` / `transcript` / `pi-tui` / `migration-legacy` / `tree-sitter-bash` | 支撑库 | |
| `/Users/hiro/kimi-code/plugins/official/kimi-datasource` | 官方插件示例 | |

证据：`package.json` 的 `dev:server` / `dev:kap-server` 指向 `kimi web`；`kap-server` 描述为 server。

---

## 2. 常驻 / 服务模式

**Yes.**

| 命令 | 状态 |
|------|------|
| `kimi web` | **当前正式入口**：前台常驻 REST+WS+Web UI |
| `kimi server` | **已废弃**（仅 `server kill` 清理旧版后台进程） |
| `kimi acp` | stdio ACP，非网络 daemon |
| 无独立 `app-server` / `daemon` 子命令名 | — |

顶层命令（commander 注册）：

```583:594:/Users/hiro/kimi-code/apps/kimi-code/test/cli/options.test.ts
      expect(commandNames).toEqual([
        'export',
        'provider',
        'acp',
        'web',
        'server',
        'login',
        'doctor',
        'vis',
        'migrate',
        'upgrade',
      ]);
```

```19:27:/Users/hiro/kimi-code/apps/kimi-code/src/cli/sub/web/index.ts
export function registerWebCommand(program: Command): void {
  const web = buildWebCommand(
    program
      .command('web')
      .description('Run the local Kimi server and open the web UI.'),
  );
```

```14:14:/Users/hiro/kimi-code/apps/kimi-code/src/cli/sub/web/run.ts
import { createServerLogger, startServer, type ServerLogger } from '@moonshot-ai/kap-server';
```

远程部署常用：`kimi web --host --no-open --port 58627`（`--host` 无参 → `0.0.0.0`）。默认端口 `58627`：

```15:15:/Users/hiro/kimi-code/apps/kimi-code/src/cli/sub/web/shared.ts
export const DEFAULT_SERVER_PORT = 58627;
```

注意：0.28+ **故意前台运行**（Ctrl+C 退出），不是 systemd 式后台 daemon；可用 `nohup`/`systemd` 包一层。

---

## 3. 远程控制协议

**Yes — REST + 自定义 WebSocket（非 JSON-RPC over WS）。**

| 层 | 证据 |
|----|------|
| Fastify HTTP | `packages/kap-server` 依赖 `fastify`；`start.ts` bootstrap |
| WS 路径 | `/api/v1/ws` |
| OpenAPI / AsyncAPI | 运行时 `GET /openapi.json`、`GET /asyncapi.json` |

```22:22:/Users/hiro/kimi-code/packages/kap-server/src/transport/ws/v1/registerWsV1.ts
export const WS_PATH = '/api/v1/ws';
```

```39:40:/Users/hiro/kimi-code/packages/protocol/src/asyncapi.ts
      description:
        'WebSocket protocol for Kimi Code daemon control frames, acknowledgements, system frames, and session event streaming.',
```

**会话控制（REST，对标 Codex thread/\*）：**

```1:16:/Users/hiro/kimi-code/packages/protocol/src/rest/session.ts
 *   POST    /v1/sessions                  body: SessionCreate   data: Session
 *   GET     /v1/sessions                  query: ListSessions   data: Page<Session>
 *   GET     /v1/sessions/{id}             -                     data: Session
 *   ...
 *   POST    /v1/sessions/{id}:archive     -                     data: { archived: true }
 *   POST    /v1/sessions/{id}:restore     -                     data: Session
```

**Turn / Prompt（对标 turn/start、turn/interrupt）：**

```1:29:/Users/hiro/kimi-code/packages/protocol/src/rest/prompt.ts
 *   POST /v1/sessions/{sid}/prompts
 *   ...
 *   POST /v1/sessions/{sid}/prompts/{pid}:abort
```

**流式事件（WS，对标 item/agentMessage/delta、turn/completed）：**  
`assistant.delta`、`thinking.delta`、`turn.started`、`turn.ended`（`packages/protocol/src/events.ts`）。

WS 控制帧：`client_hello` / `subscribe` / `unsubscribe`（带 `{seq, epoch}` 游标可重放）：

```481:496:/Users/hiro/kimi-code/packages/protocol/src/ws-control.ts
export const clientControlOperations = [
  {
    type: 'client_hello',
    ...
    description: 'Start a client session and optionally subscribe to existing daemon sessions.',
  },
  {
    type: 'subscribe',
    ...
    description: 'Subscribe the connection to one or more session event streams.',
  },
```

另有：**ACP = JSON-RPC over stdio**；**klient IPC**（本机 socket）；**无** vscode-jsonrpc / MessageConnection 网络服务。

鉴权：`~/.kimi-code/server.token` bearer；可用 `--dangerous-bypass-auth`（仅可信网络）。

---

## 4. ACP 支持

**Yes。** 依赖 `@agentclientprotocol/sdk`；入口 `kimi acp`。

```38:41:/Users/hiro/kimi-code/apps/kimi-code/src/cli/sub/acp.ts
  parent
    .command('acp')
    .description('Run kimi-code as an Agent Client Protocol (ACP) server over stdio.')
```

`initialize` 广告的能力：

```309:324:/Users/hiro/kimi-code/packages/acp-adapter/src/server.ts
    const agentCapabilities: AgentCapabilities = {
      loadSession: true,
      promptCapabilities: { image: true, audio: false, embeddedContext: true },
      mcpCapabilities: { http: true, sse: true },
      sessionCapabilities: { list: {}, resume: {} },
    };
```

已实现的 session 原语：`initialize`、`session/new`、`session/prompt`、`session/cancel`、`session/load`、`session/resume`、`session/list`，以及流式 `session/update`（adapter 内把 SDK 事件映射为 ACP updates）。  

**不能**直接当网络 app-server 用；适合 Zed/JetBrains 子进程。

---

## 5. SDK

**Yes（源码包名 `@moonshot-ai/kimi-code-sdk`；仓库内仍 `private: true`，带 `publishConfig.access: public`）。**

公开表面：`createKimiHarness` / `KimiHarness` / `Session`：

```1:5:/Users/hiro/kimi-code/packages/node-sdk/src/index.ts
export { KimiHarness } from '#/kimi-harness';
...
export { Session } from '#/session';
export { createKimiHarness, SDKRpcClient, ... } from '#/sdk-rpc-client';
```

流式订阅：**Yes** — `Session.onEvent`：

```87:94:/Users/hiro/kimi-code/packages/node-sdk/src/session.ts
  onEvent(listener: (event: Event) => void): Unsubscribe {
    this.ensureOpen();
    return this.rpc.onEvent((event) => {
      if (event.sessionId === this.id) {
        listener(event);
      }
    });
  }
```

还有 `createSession` / `resumeSession` / `listSessions` / `prompt`。  
这是 **进程内嵌入 SDK**，不是远程 HTTP 客户端。远程应用应直连 REST+WS，或看 `@moonshot-ai/klient`（ipc/memory）。

---

## 6. 会话持久化

**Yes — 文件系统（jsonl + json），不是 sqlite 会话库。**

布局（`~/.kimi-code` 或 `$KIMI_CODE_HOME`）：

- `session_index.jsonl`
- `workspaces.json`
- `sessions/{wd_id}/{sid}/state.json`
- `sessions/{wd_id}/{sid}/agents/main/wire.jsonl`

```8:12:/Users/hiro/kimi-code/packages/kap-server/test/workspaceLayout.test.ts
 * `session_index.jsonl` (`{sessionId, sessionDir, workDir}` with
 * `sessionDir = <home>/sessions/{wd_id}/{session_id}`),
 * `sessions/{wd_id}/{sid}/state.json`, and
 * `sessions/{wd_id}/{sid}/agents/main/wire.jsonl`
```

程序化 resume/list：

- REST：`GET /api/v1/sessions`、创建后即持久化、`:archive` / `:restore`
- SDK：`listSessions` / `resumeSession`
- CLI：`-S/--session`、`-c/--continue`
- ACP：`session/list`、`session/load`、`session/resume`

`minidb` 存在，但是通用嵌入 KV，不是会话主存储。

---

## 7. Headless / print 模式

**Yes。**

```59:68:/Users/hiro/kimi-code/apps/kimi-code/src/cli/commands.ts
    .addOption(
      new Option(
        '-p, --prompt <prompt>',
        'Run one prompt non-interactively and print the response.',
      ),
    )
    .addOption(
      new Option(
        '--output-format <format>',
        'Output format for prompt mode. Defaults to text.',
      ).choices(['text', 'stream-json']),
    )
```

用法：`kimi -p "..." --output-format stream-json`（JSONL 流；thinking 不进 JSONL）。

---

## 8. Hooks / 插件

**Yes — 本地 shell/脚本 lifecycle hooks（非消息总线）。**

配置：`~/.kimi-code/config.toml` 的 `[[hooks]]`（`event` / `matcher` / `command` / `timeout`）。  
事件含 `UserPromptSubmit`、`PreToolUse`、`PostToolUse`、`SessionStart`/`SessionEnd`、`Notification` 等；stdin JSON 入、exit code 控阻断。  

文档：`/Users/hiro/kimi-code/docs/en/customization/hooks.md`。  
另有 marketplace 插件（skills / MCP / datasource）。  

**不适合**做手机端远程控制主通道；最多旁路转发/审计。

---

## 与 Codex app-server 对照 & 最可行路径

| 能力 | Codex app-server | kimi-code |
|------|------------------|-----------|
| 常驻进程 | `codex app-server --listen ws://...` | `kimi web [--host] [--no-open]` |
| 协议 | JSON-RPC 2.0 over WS | **REST 控制 + 自定义 WS 事件** |
| 建会话 / 列 / 恢复 | `thread/*` | `POST/GET /api/v1/sessions`、archive/restore |
| 开 turn / 中断 | `turn/start`、`turn/interrupt` | `POST .../prompts`、`:abort` |
| 流式推送 | `item/.../delta`、`turn/completed` | WS `assistant.delta`、`turn.ended` 等 |
| 鉴权 | 视部署 | bearer `server.token` |
| 额外 | — | 同进程 Web UI；ACP stdio；`-p` |

### 最可行路径（Android 远程控 kimi）

1. 服务器：`kimi web --host --no-open`（必要时 systemd/`tmux`/`nohup`），登录好 OAuth/API key。  
2. 客户端读启动横幅里的 origin + token（或 `~/.kimi-code/server.token`）。  
3. Android 实现：  
   - REST：`POST /api/v1/sessions` → `POST /api/v1/sessions/{id}/prompts` → abort/list/archive  
   - WS：`ws(s)://host:port/api/v1/ws` → `client_hello` → `subscribe` → 消费 `assistant.delta` / `turn.ended`  
4. 协议真相源：运行中 `/openapi.json` + `/asyncapi.json`，以及 `packages/protocol`。  
5. **不要**指望 `kimi acp` 当网络服务；**不要**指望 hooks 当主控通道；SDK 适合同机嵌入，不适合手机直连。

结论：**可以像 Codex 一样“常驻服务器 + 自研手机客户端远程控制”**；最接近的等价物是 **`kimi web` / kap-server**，协议要从 JSON-RPC 换成 **REST+WS**，但产品能力覆盖会话生命周期与流式事件。