# Codex 动作接口（/compact、/review 等单独触发）

> 配套文档：连接、鉴权、握手、会话/对话接口见 `MOBILE_APP_API.md`（§1-§3）。
> 本文只覆盖"动作类"接口——TUI 斜杠命令的 app-server 对应物，可在同一 WebSocket 上随时单独触发。
> 协议依据：`codex-rs/app-server-protocol/src/protocol/{common.rs, v2/thread.rs, v2/review.rs}`。

前置条件（与主文档相同）：
- 已建立 `wss://codex.waibozishu.com:8443` 连接，`Authorization: Bearer <token>`；
- 已完成 `initialize` + `initialized` 握手；
- 目标会话已在当前连接上 attach（`thread/start` 或 `thread/resume` 过）。

---

## 1. `thread/compact/start` — 压缩上下文（/compact）

上下文快满时触发，把会话历史压缩成摘要，释放上下文窗口。

```jsonc
// →
{"id": 10, "method": "thread/compact/start", "params": {"threadId": "thr_..."}}
// ← 立即返回（压缩在后台进行，不等结果）
{"id": 10, "result": {}}
```

触发后的表现：

- 会话进入 busy，压缩作为后台任务运行；
- 完成后会话中出现 **`contextCompaction` 类型 item**（走 `item/started` → `item/completed` 通知，与命令执行卡片同样的渲染路径）；
- 旧版服务端可能改发 `thread/compacted` 通知（已 deprecated，新代码按 item 处理即可）；
- 压缩完成后会收到 `thread/tokenUsage/updated`，上下文占用环数值会明显下降。

UI 建议：在上下文占用环旁做按钮，占用 >70% 时高亮提示可压缩。压缩期间禁用发消息。

## 2. `review/start` — 发起代码审查（/review）

```jsonc
// →
{"id": 11, "method": "review/start", "params": {
  "threadId": "thr_...",
  "target": {"type": "uncommittedChanges"},
  // 可选："delivery": "inline"（默认，在当前会话里跑）或 "detached"（开新会话跑）
}}
// ←
{"id": 11, "result": {
  "turn": {"id": "turn_...", "status": "inProgress", ...},
  "reviewThreadId": "thr_..."   // inline 时 = 原会话；detached 时 = 新审查会话
}}
```

`target` 四选一（tagged union，`type` 判别）：

| target | JSON | 审查对象 |
|---|---|---|
| 未提交改动 | `{"type": "uncommittedChanges"}` | 工作区 staged + unstaged + untracked |
| 相对分支 | `{"type": "baseBranch", "branch": "main"}` | 当前分支与 base 分支的 diff |
| 单个 commit | `{"type": "commit", "sha": "abc123", "title": "可选标题"}` | 指定 commit 引入的改动 |
| 自定义 | `{"type": "custom", "instructions": "审查最近的性能改动"}` | 自由文本指令 |

审查结果就是一轮普通 turn：`reviewThreadId` 会话上走 `turn/started` → `item/*` → `turn/completed` 全套流式通知，手机端按现有聊天渲染即可。

## 3. `thread/fork` — 分叉会话（/fork）

```jsonc
// → 完整复制
{"id": 12, "method": "thread/fork", "params": {"threadId": "thr_..."}}
// → 截断分叉：只保留到 lastTurnId（含）为止，实现"回到某一轮重开"
{"id": 12, "method": "thread/fork", "params": {"threadId": "thr_...", "lastTurnId": "turn_..."}}
// ←
{"id": 12, "result": {"thread": {...}}}   // 新会话对象
```

注意：`lastTurnId` 指向的轮次不能是进行中的轮次。

## 4. `thread/rollback` — 砍掉末尾 N 轮（≈/undo）

```jsonc
// →
{"id": 13, "method": "thread/rollback", "params": {"threadId": "thr_...", "numTurns": 1}}
// ← 返回砍完的完整会话（turns 已填充）
{"id": 13, "result": {"thread": {...}}}
```

⚠️ **只删对话历史，不回滚文件改动**（协议注释明确说明）。agent 改过的文件保持原样，需要 UI 明确提示用户，避免误以为"撤销了一切"。

## 5. `thread/shellCommand` — 在会话上下文跑 shell（!cmd）

```jsonc
// →
{"id": 14, "method": "thread/shellCommand", "params": {"threadId": "thr_...", "command": "ls -lh"}}
```

⚠️ **不受沙箱限制、直接 full access 执行**（协议注释原话，与 `command/exec` 不同，保留管道/重定向等 shell 语法）。服务端已配置 `danger-full-access` 时风险相当，但手机端做这个入口建议藏深一点或加确认。

---

## 通用说明

- 以上全部为**稳定 API**，不需要 `experimentalApi` capability（`thread/fork` 的 `beforeTurnId`/`path` 两个高级参数除外，一般用不到）。
- 幂等性：这些动作都**不幂等**，按钮触发后应禁用至收到结果/对应通知，避免连点重复触发。
- 错误处理：标准 JSON-RPC error 返回（如会话不存在、轮次进行中不可 fork），按 `MOBILE_APP_API.md` §1 的错误格式解析即可。
