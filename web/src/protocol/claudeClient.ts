import {
  AgentProfile,
  ApprovalDecision,
  Content,
  ModelInfo,
  ReviewStartResult,
  ReviewTarget,
  Thread,
  ThreadItem,
  ThreadPage,
  Turn,
} from "../types";
import { deriveBases, parseClaudeResponse, requireValidProfile } from "./http";
import { EventEmitter } from "./events";
import { Repository } from "./repository";

const CONNECT_TIMEOUT_MS = 15_000;

type Json = Record<string, unknown>;

interface ClaudeSession {
  serverId: string;
  cwd: string;
  wsUrl: string;
  socket: WebSocket | null;
  wsReady: boolean;
  connectPromise: Promise<void> | null;
  nextTurnId: number;
  nextThinkId: number;
  nextToolId: number;
  activeTurnId: string | null;
  startedAssistant: Set<string>;
  startedReasoning: Set<string>;
  startedTools: Set<string>;
  activeTools: Set<string>;
}

/**
 * Claude Code 套皮服务适配：REST 控制面（POST/GET /sessions）+ 每会话一条 WS（NDJSON），
 * 对外实现 Repository，让聊天/列表 UI 无需感知协议差异。
 *
 * 浏览器跨域限制：REST 走本服务 `/api/relay/claude/*` 同源转发，WS 走 `/ws/claude` 代理
 * （服务端注入 Bearer），逻辑与 Android ClaudeCodexRepository 一一对应。
 */
export class ClaudeClient implements Repository {
  readonly events = new EventEmitter();

  private readonly httpBase: string;
  /** profile.serverUrl 的 WS scheme（ws / wss），用于把服务端返回的 ws_url 对齐。 */
  private readonly wsScheme: "ws" | "wss";
  /** serverSessionId → 打开的会话（各自独立 WS 连接）。 */
  private sessions = new Map<string, ClaudeSession>();
  /** 本地审批 key → (serverSessionId, 服务端 request_id)。 */
  private approvalIdByKey = new Map<number, { sessionId: string; requestId: string }>();
  private nextApprovalKey = 1;
  /** 直播 delta 的系统标签剥离（标签可能跨 delta 切分，需状态机）。 */
  private readonly tagStripper = new SystemTagStripper();

  constructor(private readonly profile: AgentProfile) {
    const { httpBase, wsScheme } = deriveBases(profile.serverUrl);
    this.httpBase = httpBase;
    this.wsScheme = wsScheme;
  }

  close() {
    for (const session of this.sessions.values()) this.closeSession(session);
    this.sessions.clear();
    this.approvalIdByKey.clear();
  }

  async initialize(): Promise<void> {
    // healthz 无需鉴权，但跨域 fetch 读不到响应，统一走同源 relay。
    const { url, token } = requireValidProfile(this.profile);
    const target = `/api/relay/claude/healthz?url=${encodeURIComponent(url)}&token=${encodeURIComponent(token)}`;
    const response = await fetch(target);
    if (!response.ok) {
      throw new Error("Claude 服务器连接失败，请检查地址与 Token（healthz）");
    }
  }

  async listThreads(_cursor?: string, limit = 20): Promise<ThreadPage> {
    const data = await this.restGet(`/sessions?limit=${Math.max(1, Math.min(100, limit))}`);
    const items = arrayOf(data.items).map(parseSession);
    return { data: items };
  }

  async startThread(_model?: string): Promise<Thread> {
    const body: Json = { dangerously_skip_permissions: false };
    const cwd = this.profile.defaultCwd.trim();
    if (cwd) body.cwd = cwd;
    const resp = await this.restPost("/sessions", body);
    const id = stringOr(resp.session_id) ?? "";
    if (!id) throw new Error("Claude 建会话响应缺少 session_id");
    this.registerSession(id, resp);
    return { id, preview: "", ephemeral: false, createdAt: 0, updatedAt: 0, status: { type: "idle" }, cwd: stringOr(resp.work_dir) ?? "", turns: [] };
  }

  async resumeThread(threadId: string, _model?: string): Promise<Thread> {
    const resp = await this.restGet(`/sessions/${threadId}`);
    this.registerSession(threadId, resp);
    return parseSession(resp);
  }

  async readThread(threadId: string, includeTurns = true): Promise<Thread> {
    const meta = await this.restGet(`/sessions/${threadId}`);
    this.registerSession(threadId, meta);
    const items = includeTurns ? await this.loadMessages(threadId) : [];
    return { ...parseSession(meta), turns: [{ id: "history", status: "completed", items }] };
  }

  async archiveThread(threadId: string): Promise<void> {
    // Claude 无归档概念：等同删除（服务端移除映射，本地 jsonl 保留）。
    await this.deleteThread(threadId);
  }

  async deleteThread(threadId: string): Promise<void> {
    await this.restDelete(`/sessions/${threadId}`).catch(() => undefined);
    const session = this.sessions.get(threadId);
    if (session) {
      this.sessions.delete(threadId);
      this.closeSession(session);
    }
  }

  async startTurn(threadId: string, input: Content[]): Promise<Turn> {
    const session = this.sessions.get(threadId);
    if (!session) throw new Error("会话未打开，请先进入会话");
    const text = input.filter((block) => block.type === "text").map((block) => block.text).join("");
    if (!text.trim()) throw new Error("空消息");
    await this.connectSession(session);
    const turnId = `turn-${session.nextTurnId++}`;
    session.activeTurnId = turnId;
    const frame: Json = {
      type: "user",
      message: { role: "user", content: [{ type: "text", text }] },
      parent_tool_use_id: null,
      session_id: "",
    };
    if (!this.sendTo(session, JSON.stringify(frame))) {
      session.activeTurnId = null;
      throw new Error("WebSocket 未连接，无法发送消息");
    }
    this.events.emit({ type: "turnStarted", threadId, turnId });
    return { id: turnId, status: "inProgress", items: [] };
  }

  async interruptTurn(threadId: string, _turnId: string): Promise<void> {
    const session = this.sessions.get(threadId);
    if (!session || !session.wsReady) return;
    this.sendTo(
      session,
      JSON.stringify({
        type: "control_request",
        request_id: crypto.randomUUID(),
        request: { subtype: "interrupt" },
      }),
    );
  }

  async respondApproval(requestId: number, decision: ApprovalDecision): Promise<void> {
    const entry = this.approvalIdByKey.get(requestId);
    if (!entry) throw new Error(`未知的审批请求：${requestId}`);
    this.approvalIdByKey.delete(requestId);
    const session = this.sessions.get(entry.sessionId);
    if (!session) throw new Error("会话已关闭");
    const behavior = decision === "accept" || decision === "acceptForSession" ? "allow" : "deny";
    const frame: Json = {
      type: "control_response",
      response: {
        subtype: "success",
        request_id: entry.requestId,
        response: { behavior },
      },
    };
    if (!this.sendTo(session, JSON.stringify(frame))) {
      throw new Error("WebSocket 未连接，无法应答审批");
    }
    if (decision === "cancel") {
      await this.interruptTurn(entry.sessionId, session.activeTurnId ?? "");
    }
  }

  async listModels(): Promise<ModelInfo[]> {
    return [];
  }

  async updateThreadSettings(_threadId: string, _model?: string, _effort?: string): Promise<void> {
    throw new Error("Claude 暂不支持切换模型/推理档位");
  }

  async startCompact(_threadId: string): Promise<void> {
    throw new Error("Claude 暂不支持 /compact");
  }

  async startReview(_threadId: string, _target: ReviewTarget, _delivery?: string): Promise<ReviewStartResult> {
    throw new Error("Claude 暂不支持 /review");
  }

  async forkThread(_threadId: string, _lastTurnId?: string): Promise<Thread> {
    throw new Error("Claude 暂不支持 /fork");
  }

  async rollbackThread(_threadId: string, _numTurns?: number): Promise<Thread> {
    throw new Error("Claude 暂不支持 /undo");
  }

  async shellCommand(_threadId: string, _command: string): Promise<void> {
    throw new Error("Claude 暂不支持 !shell（请直接发消息让 agent 执行）");
  }

  // ── REST（同源转发） ──────────────────────────────────────────────────

  private registerSession(serverId: string, resp: Json) {
    if (this.sessions.has(serverId)) return;
    const rawWs = stringOr(resp.ws_url) ?? "";
    const wsUrl = rawWs
      ? this.normalizeWsUrl(rawWs)
      : // 历史会话（GET /sessions/{id}）不带 ws_url：按同一模式拼。
        `${this.wsScheme}://${this.httpBase.replace(/^https?:\/\//, "")}/sessions/${serverId}/ws`;
    this.sessions.set(serverId, {
      serverId,
      cwd: stringOr(resp.work_dir) || stringOr(resp.cwd) || "",
      wsUrl,
      socket: null,
      wsReady: false,
      connectPromise: null,
      nextTurnId: 1,
      nextThinkId: 1,
      nextToolId: 1,
      activeTurnId: null,
      startedAssistant: new Set(),
      startedReasoning: new Set(),
      startedTools: new Set(),
      activeTools: new Set(),
    });
  }

  private normalizeWsUrl(raw: string): string {
    if (this.wsScheme === "wss" && raw.startsWith("ws://")) return `wss://${raw.slice(5)}`;
    if (this.wsScheme === "ws" && raw.startsWith("wss://")) return `ws://${raw.slice(6)}`;
    return raw;
  }

  private async loadMessages(threadId: string): Promise<ThreadItem[]> {
    const data = await this.restGet(`/sessions/${threadId}/messages`);
    return ensureUniqueIds(parseMessageItems(arrayOf(data.items)));
  }

  private async restGet(path: string): Promise<Json> {
    return this.rest("GET", path);
  }

  private async restPost(path: string, body: Json): Promise<Json> {
    return this.rest("POST", path, body);
  }

  private async restDelete(path: string): Promise<Json> {
    return this.rest("DELETE", path);
  }

  private async rest(method: string, path: string, body?: Json): Promise<Json> {
    const { url, token } = requireValidProfile(this.profile);
    const sep = path.includes("?") ? "&" : "?";
    const target = `/api/relay/claude${path}${sep}url=${encodeURIComponent(url)}&token=${encodeURIComponent(token)}`;
    const response = await fetch(target, {
      method,
      headers: body !== undefined ? { "Content-Type": "application/json; charset=utf-8" } : undefined,
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
    return parseClaudeResponse(response, path);
  }

  // ── WS（每会话一条，NDJSON） ──────────────────────────────────────────

  private sendTo(session: ClaudeSession, text: string): boolean {
    if (!session.socket || session.socket.readyState !== WebSocket.OPEN) return false;
    session.socket.send(text);
    return true;
  }

  private closeSession(session: ClaudeSession) {
    session.socket?.close(1000, "profile changed");
    session.socket = null;
    session.wsReady = false;
  }

  private async connectSession(session: ClaudeSession): Promise<void> {
    if (session.connectPromise) return session.connectPromise;
    session.connectPromise = (async () => {
      if (session.wsReady && session.socket) return;
      this.closeSession(session);
      const { token } = requireValidProfile(this.profile);
      const params = new URLSearchParams({ url: session.wsUrl, token });
      await new Promise<void>((resolve, reject) => {
        const socket = new WebSocket(`/ws/claude?${params.toString()}`);
        const timeout = window.setTimeout(() => {
          socket.close();
          reject(new Error("连接 Claude 超时"));
        }, CONNECT_TIMEOUT_MS);
        socket.onopen = () => {
          clearTimeout(timeout);
          session.socket = socket;
          session.wsReady = true;
          resolve();
        };
        socket.onmessage = (event) => this.onSessionMessage(session, event);
        socket.onerror = () => {
          /* 由 onclose 统一处理 */
        };
        socket.onclose = (event) => {
          clearTimeout(timeout);
          if (session.socket !== socket) return;
          session.socket = null;
          session.wsReady = false;
          reject(new Error(`Claude 连接已断开：${event.reason || "连接失败"}`));
        };
      });
    })().finally(() => {
      session.connectPromise = null;
    });
    return session.connectPromise;
  }

  private onSessionMessage(session: ClaudeSession, event: MessageEvent) {
    if (typeof event.data !== "string") return;
    // NDJSON：一个 text frame 可能带多行；空白行跳过。
    for (const line of event.data.split("\n")) {
      if (!line.trim()) continue;
      let frame: Json;
      try {
        frame = JSON.parse(line);
      } catch {
        continue;
      }
      this.handleFrame(session, frame);
    }
  }

  private handleFrame(session: ClaudeSession, frame: Json) {
    switch (frame.type) {
      // SDK ≥0.3.223：流式 assistant 消息是顶层事件，message 即 SDKAssistantMessage
      // （content 数组为 text/thinking/tool_use 块，与 0.3.221 的 stream_event 同构）。
      case "assistant":
        this.handleAssistant(session, frame);
        break;
      case "control_request": {
        const request = (frame.request as Json | undefined) ?? {};
        if (request.subtype !== "can_use_tool") return;
        const key = this.nextApprovalKey++;
        const requestId = stringOr(frame.request_id) ?? "";
        this.approvalIdByKey.set(key, { sessionId: session.serverId, requestId });
        const input = request.input;
        const command = [
          stringOr(request.tool_name) ?? "",
          input != null && input !== undefined ? String(JSON.stringify(input)).slice(0, 200) : "",
        ]
          .filter(Boolean)
          .join(" ")
          .trim();
        this.events.emit({
          type: "approvalRequest",
          requestId: key,
          threadId: session.serverId,
          turnId: session.activeTurnId ?? "",
          itemId: requestId,
          command,
          cwd: "",
          reason: "需要批准工具调用",
        });
        break;
      }
      case "result": {
        this.completeActiveTools(session);
        this.tagStripper.reset();
        const subtype = stringOr(frame.subtype) ?? "";
        const status = subtype === "success" ? "completed" : subtype === "interrupted" ? "interrupted" : "failed";
        const error = stringOr((frame.error as Json | undefined)?.message);
        if (session.activeTurnId) {
          this.events.emit({
            type: "turnCompleted",
            threadId: session.serverId,
            turnId: session.activeTurnId,
            status,
            error,
          });
        }
        // 立即释放轮锁：服务端在发完 result 后同步释放（不等 SDK for-await 的 finally），
        // 否则下一条消息会撞 "turn already in progress"。
        session.activeTurnId = null;
        break;
      }
      case "error": {
        this.completeActiveTools(session);
        this.tagStripper.reset();
        const error = stringOr((frame.error as Json | undefined)?.message) || stringOr(frame.error) || undefined;
        if (session.activeTurnId) {
          this.events.emit({
            type: "turnCompleted",
            threadId: session.serverId,
            turnId: session.activeTurnId,
            status: "failed",
            error,
          });
        }
        session.activeTurnId = null;
        break;
      }
      // system/init（claude 会话 id，服务端自管 resume）、keep_alive：忽略
      default:
        break;
    }
  }

  private handleAssistant(session: ClaudeSession, frame: Json) {
    const message = (frame.message as Json | undefined) ?? {};
    const blocks = arrayOf(message.content);
    for (const block of blocks) {
      switch (block.type) {
        case "text": {
          // 文本块出现说明此前的工具已执行完，先收尾工具卡片。
          this.completeActiveTools(session);
          // Claude 的回答流里夹带 <system-reminder>/<system> 系统标签，展示前剔除。
          const delta = this.tagStripper.feed(stringOr(block.text) ?? "");
          if (!delta) continue;
          const itemId = assistantItemId(session);
          if (!session.startedAssistant.has(itemId)) {
            session.startedAssistant.add(itemId);
            this.events.emit({ type: "itemStarted", threadId: session.serverId, item: { kind: "agentMessage", id: itemId, text: "" } });
          }
          this.events.emit({ type: "agentMessageDelta", threadId: session.serverId, itemId, delta });
          break;
        }
        case "thinking": {
          const delta = stringOr(block.thinking) || stringOr(block.text) || "";
          if (!delta) continue;
          const itemId = reasoningItemId(session);
          if (!session.startedReasoning.has(itemId)) {
            session.startedReasoning.add(itemId);
            this.events.emit({ type: "itemStarted", threadId: session.serverId, item: { kind: "reasoning", id: itemId, summary: [""] } });
          }
          this.events.emit({ type: "reasoningSummaryDelta", threadId: session.serverId, itemId, summaryIndex: 0, delta });
          break;
        }
        case "tool_use": {
          const id = stringOr(block.id) || `tool-${session.nextToolId++}`;
          if (session.startedTools.has(id)) continue;
          session.startedTools.add(id);
          const input = block.input;
          const command = [stringOr(block.name) ?? "", input != null && input !== undefined ? JSON.stringify(input) : ""]
            .filter(Boolean)
            .join(" ")
            .trim();
          session.activeTools.add(id);
          this.events.emit({
            type: "itemStarted",
            threadId: session.serverId,
            item: { kind: "commandExecution", id, command, cwd: "", status: "inProgress", output: "" },
          });
          break;
        }
      }
    }
  }

  /** 工具卡片收尾：claude 无工具输出流，下一段文本或 result 时统一标记 completed。 */
  private completeActiveTools(session: ClaudeSession) {
    for (const id of session.activeTools) {
      this.events.emit({
        type: "itemCompleted",
        threadId: session.serverId,
        item: { kind: "commandExecution", id, command: "", cwd: "", status: "completed", output: "" },
      });
    }
    session.activeTools.clear();
  }
}

function assistantItemId(session: ClaudeSession): string {
  return `asst-${session.activeTurnId ?? "x"}`;
}

function reasoningItemId(session: ClaudeSession): string {
  return `think-${session.activeTurnId ?? "x"}-${session.nextThinkId++}`;
}

function arrayOf(value: unknown): Json[] {
  return Array.isArray(value) ? (value.filter((v): v is Json => v != null && typeof v === "object") as Json[]) : [];
}

function stringOr(value: unknown): string | undefined {
  if (value == null) return undefined;
  return String(value);
}

/** 服务端已过滤 isMeta 注入行，结构见 claude-server/README.md。 */
function parseMessageItems(items: Json[]): ThreadItem[] {
  const out: ThreadItem[] = [];
  let userIndex = 0;
  let asstIndex = 0;
  items.forEach((msg, index) => {
    switch (msg.type) {
      case "user": {
        const content = arrayOf(msg.content)
          .map((block) => (block.type === "text" ? { type: "text", text: stringOr(block.text) ?? "" } : null))
          .filter((b): b is { type: string; text: string } => b != null);
        if (content.length === 0 || content.every((b) => !b.text.trim())) return;
        out.push({ kind: "userMessage", id: `hist-u-${userIndex++}`, content });
        break;
      }
      case "assistant": {
        let textIndex = 0;
        let thinkIndex = 0;
        let pendingText = "";
        const flushText = () => {
          if (!pendingText) return;
          out.push({ kind: "agentMessage", id: `hist-a-${asstIndex}-t${textIndex++}`, text: pendingText });
          pendingText = "";
        };
        arrayOf(msg.content).forEach((block) => {
          switch (block.type) {
            case "thinking": {
              flushText();
              const think = (stringOr(block.thinking) || stringOr(block.text) || "").trim();
              if (think) out.push({ kind: "reasoning", id: `hist-a-${asstIndex}-r${thinkIndex++}`, summary: [think] });
              break;
            }
            case "text":
              pendingText += stripSystemTags(stringOr(block.text) ?? "");
              break;
            case "tool_use": {
              flushText();
              const id = stringOr(block.id) || `hist-a-${asstIndex}-tool-${index}`;
              const input = block.input;
              const command = [
                stringOr(block.name) ?? "",
                input != null && input !== undefined ? JSON.stringify(input) : "",
              ]
                .filter(Boolean)
                .join(" ");
              out.push({ kind: "commandExecution", id, command, cwd: "", status: "completed", output: "" });
              break;
            }
          }
        });
        flushText();
        asstIndex++;
        break;
      }
    }
  });
  return out;
}

/** React key 要求 id 全局唯一：重复的补后缀。 */
function ensureUniqueIds(items: ThreadItem[]): ThreadItem[] {
  const seen = new Set<string>();
  return items.map((item) => {
    let current = item.id || "item";
    if (!seen.has(current)) {
      seen.add(current);
      return item;
    }
    let n = 2;
    while (seen.has(`${current}#${n}`)) n++;
    const newId = `${current}#${n}`;
    seen.add(newId);
    return { ...item, id: newId };
  });
}

/* ---------------- Claude 系统标签剥离 ---------------- */

/** 历史消息是完整文本，正则直接剥掉 <system-reminder>/<system> 标签。 */
function stripSystemTags(text: string): string {
  return text
    .replace(/<system-reminder\b[^>]*>[\s\S]*?<\/system-reminder>/gi, "")
    .replace(/<system\b[^>]*>[\s\S]*?<\/system>/gi, "")
    .trim();
}

/**
 * 直播 delta 的标签剥离：系统标签可能被 SDK 拆进多个 delta，
 * 这里按字符流状态机处理——未闭合的开标签挂起等待，跨包判定后再输出。
 */
class SystemTagStripper {
  private pending = "";
  private inTag: "reminder" | "system" | null = null;

  feed(delta: string): string {
    const s = this.pending + delta;
    let out = "";
    let i = 0;
    while (i < s.length) {
      if (this.inTag) {
        const close = this.inTag === "reminder" ? "</system-reminder>" : "</system>";
        const idx = s.toLowerCase().indexOf(close, i);
        if (idx < 0) {
          // 未闭合：保留可能包含闭合标签开头的尾部，其余（标签内容）丢弃。
          const keep = Math.min(close.length - 1, s.length - i);
          this.pending = s.slice(s.length - keep);
          return out;
        }
        this.inTag = null;
        i = idx + close.length;
        continue;
      }
      const open = s.indexOf("<", i);
      if (open < 0) {
        out += s.slice(i);
        this.pending = "";
        return out;
      }
      out += s.slice(i, open);
      const rest = s.slice(open).toLowerCase();
      if (rest.startsWith("<system-reminder")) {
        const gt = s.indexOf(">", open);
        if (gt < 0) {
          // 开标签属性未闭合：整段挂起，等下一个 delta 补齐。
          this.pending = s.slice(open);
          return out;
        }
        this.inTag = "reminder";
        i = gt + 1;
        continue;
      }
      if (rest.startsWith("<system")) {
        const after = rest.slice("<system".length);
        if (after.length === 0) {
          // 可能是 <system-reminder 或 <system 的开头，挂起判定。
          this.pending = s.slice(open);
          return out;
        }
        if (!/^[\s>]/.test(after)) {
          // 不是标签（如 "<systematic"），按普通文本输出。
          out += "<";
          i = open + 1;
          continue;
        }
        const gt = s.indexOf(">", open);
        if (gt < 0) {
          this.pending = s.slice(open);
          return out;
        }
        this.inTag = "system";
        i = gt + 1;
        continue;
      }
      out += "<";
      i = open + 1;
    }
    this.pending = "";
    return out;
  }

  reset() {
    this.pending = "";
    this.inTag = null;
  }
}

function parseSession(json: Json): Thread {
  return {
    id: stringOr(json.session_id) ?? "",
    preview: stringOr(json.last_prompt) || "Claude 会话",
    ephemeral: false,
    createdAt: Number(json.created_at ?? 0),
    updatedAt: Number(json.updated_at ?? 0),
    status: { type: "idle" },
    cwd: stringOr(json.cwd) || stringOr(json.work_dir) || "",
    turns: [],
  };
}
