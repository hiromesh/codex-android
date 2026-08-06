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
import { deriveBases, parseKimiEnvelope, requireValidProfile } from "./http";
import { EventEmitter } from "./events";
import { Repository } from "./repository";

const CONNECT_TIMEOUT_MS = 15_000;
const RPC_TIMEOUT_MS = 30_000;
const MESSAGE_PAGE_SIZE = 100;
const MESSAGE_MAX_PAGES = 20;

type Json = Record<string, unknown>;

interface PendingAck {
  resolve: (value: Json) => void;
  reject: (error: Error) => void;
  timer: number;
}

/**
 * Kimi Code kap-server 适配：REST 控制面 + WS `/api/v1/ws` 事件面，
 * 对外实现 Repository，让聊天/列表 UI 无需感知协议差异。
 *
 * 浏览器跨域限制：REST 走本服务 `/api/relay/kimi/*` 同源转发，WS 走 `/ws/kimi` 代理
 * （服务端注入 Bearer），逻辑与 Android KimiCodexRepository 一一对应。
 */
export class KimiClient implements Repository {
  readonly events = new EventEmitter();

  private readonly wsUrl: string;
  private socket: WebSocket | null = null;
  private wsReady = false;
  private connectPromise: Promise<void> | null = null;
  private helloResolvers: Array<() => void> = [];
  private nextControlId = 1;
  private pendingAcks = new Map<string, PendingAck>();
  private nextApprovalKey = 1;
  /** key → { sessionId, approvalId } */
  private approvalIdByKey = new Map<number, { sessionId: string; approvalId: string }>();
  /** sessionId → 当前活跃 prompt_id（interrupt 用） */
  private activePromptBySession = new Map<string, string>();
  /** "$sessionId:$turnId" → prompt_id */
  private promptByTurnKey = new Map<string, string>();
  /** "$sessionId:$turnId" → 当前 step；同 turn 多段 assistant 文本靠 step 分气泡。 */
  private stepByTurnKey = new Map<string, number>();
  /** 工具打断文本后，若服务端未发 turn.step.started，下一段 delta 需自行 +1 step。 */
  private pendingAssistantStepBump = new Set<string>();
  /** toolCallId → 启动时拼好的命令展示文案；result 事件常不带 name，靠这里补。 */
  private toolCommandById = new Map<string, string>();
  /** 已为该 turn 发出过 AgentMessage ItemStarted */
  private startedAssistantItems = new Set<string>();
  private startedReasoningItems = new Set<string>();
  private subscribedSessions = new Set<string>();
  private sessionCursors = new Map<string, { seq?: number; epoch?: string }>();

  constructor(private readonly profile: AgentProfile) {
    const { httpBase, wsScheme } = deriveBases(profile.serverUrl);
    const host = httpBase.replace(/^https?:\/\//, "");
    this.wsUrl = `${wsScheme}://${host}/api/v1/ws`;
  }

  close() {
    this.socket?.close(1000, "profile changed");
    this.socket = null;
    this.wsReady = false;
    this.failPending(new Error("连接已关闭"));
  }

  async initialize(): Promise<void> {
    await this.ensureConnected();
  }

  async listThreads(cursor?: string, limit = 20): Promise<ThreadPage> {
    await this.ensureConnected();
    const query = new URLSearchParams({
      page_size: String(Math.max(1, Math.min(100, limit))),
      exclude_empty: "true",
    });
    if (cursor) query.set("after_id", cursor);
    const data = await this.restGet(`/sessions?${query.toString()}`);
    const items = arrayOf(data.items).map(parseSessionAsThread);
    const next = data.has_more === true && items.length > 0 ? items[items.length - 1].id : undefined;
    return { data: items, nextCursor: next };
  }

  async startThread(model?: string): Promise<Thread> {
    await this.ensureConnected();
    const cwd = this.profile.defaultCwd.trim();
    const body: Json = {};
    if (cwd) {
      body.metadata = { cwd };
    } else {
      // Kimi 要求 workspace_id 或 metadata.cwd 二选一；优先用配置的工作目录。
      const workspace = await this.pickWorkspace();
      if (!workspace) {
        throw new Error(
          "请先在设置中填写 Kimi「默认工作目录」（服务器上的绝对路径，如 /home/ubuntu/proj），" +
            "或先在服务器用 kimi 打开过一个项目",
        );
      }
      body.workspace_id = String(workspace.id ?? "");
    }
    // POST /sessions 会忽略 body.agent_config（服务端硬编码 model:''），
    // 模型 / yolo 必须紧接着用 /profile 写入，否则首条 prompt 会报 Model not set。
    const session = await this.restPost("/sessions", body);
    const threadId = String(session.id ?? "");
    if (!threadId) throw new Error("Kimi 建会话响应缺少 id");

    const resolvedModel = (model?.trim() || (await this.resolveDefaultModel())) || undefined;
    const agentConfig: Json = { permission_mode: "yolo" };
    if (resolvedModel) agentConfig.model = resolvedModel;
    await this.restPost(`/sessions/${threadId}/profile`, { agent_config: agentConfig });

    const parsed = parseSessionAsThread(await this.restGet(`/sessions/${threadId}`));
    // profile 后的 GET 若仍投影空 model，用我们刚写入的值补上，方便 UI。
    const thread = !parsed.model && resolvedModel ? { ...parsed, model: resolvedModel } : parsed;
    this.subscribeSession(thread.id);
    return thread;
  }

  /** 取服务端默认模型（providers.default_model，否则 models 列表第一项）。 */
  private async resolveDefaultModel(): Promise<string | undefined> {
    try {
      const data = await this.restGet("/providers");
      const fromProviders = arrayOf(data.items)
        .map((item) => stringOr(item.default_model))
        .find((v) => v);
      if (fromProviders) return fromProviders;
    } catch {
      /* 降级到 models 列表第一项 */
    }
    try {
      const data = await this.restGet("/models");
      return stringOr(arrayOf(data.items)[0]?.model) || undefined;
    } catch {
      return undefined;
    }
  }

  /** 取最近打开的 workspace；没有已注册工作区时返回 undefined。 */
  private async pickWorkspace(): Promise<Json | undefined> {
    const data = await this.restGet("/workspaces");
    const items = arrayOf(data.items);
    if (items.length === 0) return undefined;
    let best = items[0];
    for (const item of items) {
      if (String(item.last_opened_at ?? "") > String(best.last_opened_at ?? "")) best = item;
    }
    return best;
  }

  async resumeThread(threadId: string, _model?: string): Promise<Thread> {
    await this.ensureConnected();
    const session = await this.restGet(`/sessions/${threadId}`);
    this.subscribeSession(threadId);
    await this.ensureYoloPermission(threadId);
    return parseSessionAsThread(session);
  }

  async readThread(threadId: string, includeTurns = true): Promise<Thread> {
    await this.ensureConnected();
    const session = await this.restGet(`/sessions/${threadId}`);
    const thread = parseSessionAsThread(session);
    if (!includeTurns) return thread;
    const messages = await this.loadAllMessages(threadId);
    this.subscribeSession(threadId);
    await this.ensureYoloPermission(threadId);
    // 只补 approvals / in-flight / 游标，不要再用残缺历史整表覆盖当前 UI。
    await this.applySnapshot(threadId, false).catch(() => undefined);
    const busy = session.busy === true;
    return {
      ...thread,
      turns: [
        { id: "history", status: busy ? "inProgress" : "completed", items: messages },
      ],
    };
  }

  async archiveThread(threadId: string): Promise<void> {
    await this.ensureConnected();
    await this.restPost(`/sessions/${threadId}:archive`, {});
  }

  async deleteThread(threadId: string): Promise<void> {
    // Kimi 无硬删除，归档等价于从默认列表移除。
    await this.archiveThread(threadId);
  }

  async startTurn(threadId: string, input: Content[]): Promise<Turn> {
    await this.ensureConnected();
    this.subscribeSession(threadId);
    const content = input.map((block) => ({ type: "text", text: block.text }));
    // 每次发消息都带 yolo：旧会话可能仍是 manual，单靠建会话时 /profile 不够。
    const result = await this.restPost(`/sessions/${threadId}/prompts`, {
      content,
      permission_mode: "yolo",
    });
    const promptId = stringOr(result.prompt_id);
    if (!promptId) throw new Error("Kimi prompt 响应缺少 prompt_id");
    this.activePromptBySession.set(threadId, promptId);
    const status = stringOr(result.status) || "running";
    if (status === "blocked") {
      throw new Error(stringOr(result.msg) || "prompt 被拒绝（blocked）");
    }
    return { id: promptId, status: "inProgress", items: [] };
  }

  async interruptTurn(threadId: string, _turnId: string): Promise<void> {
    await this.ensureConnected();
    // 用会话级 abort，不依赖 prompt_id。
    // UI 的 currentTurnId 经常是对账占位（meta/history）或 turn-N，不能当 prompts/{pid}:abort 的 pid。
    await this.restPost(`/sessions/${threadId}:abort`, {});
    this.activePromptBySession.delete(threadId);
  }

  async respondApproval(requestId: number, decision: ApprovalDecision): Promise<void> {
    await this.ensureConnected();
    const entry = this.approvalIdByKey.get(requestId);
    if (!entry) throw new Error(`未知的审批请求：${requestId}`);
    this.approvalIdByKey.delete(requestId);
    const kimiDecision = decision === "accept" || decision === "acceptForSession" ? "approved" : decision === "decline" ? "rejected" : "cancelled";
    const body: Json = { decision: kimiDecision };
    if (decision === "acceptForSession") body.scope = "session";
    await this.restPost(`/sessions/${entry.sessionId}/approvals/${entry.approvalId}`, body);
  }

  async listModels(): Promise<ModelInfo[]> {
    await this.ensureConnected();
    const data = await this.restGet("/models");
    let defaultIds = new Set<string>();
    try {
      const providers = await this.restGet("/providers");
      defaultIds = new Set(
        arrayOf(providers.items)
          .map((item) => stringOr(item.default_model))
          .filter((v): v is string => v != null && v !== ""),
      );
    } catch {
      /* 无 providers 时默认取第一项 */
    }
    const items = arrayOf(data.items).map((item) => {
      const id = stringOr(item.model) ?? "";
      const efforts = arrayOf(item.support_efforts).map((e) => String(e));
      const caps = arrayOf(item.capabilities).map((c) => String(c));
      return {
        id,
        displayName: stringOr(item.display_name) || id,
        description: stringOr(item.provider) ?? "",
        isDefault: defaultIds.has(id),
        hidden: false,
        supportedReasoningEfforts:
          efforts.length > 0
            ? efforts
            : // 有 thinking 能力但未声明档位时，用 Kimi 常见集合，避免二级菜单空白。
              caps.includes("thinking")
              ? ["off", "low", "medium", "high", "xhigh", "max"]
              : [],
        defaultReasoningEffort:
          stringOr(item.default_effort) || (caps.includes("thinking") ? "high" : "medium"),
      };
    });
    // 若 providers 没标 default，把第一项当作默认，方便新会话选中。
    if (items.length > 0 && !items.some((m) => m.isDefault)) {
      return [items[0], ...items.slice(1)].map((m, i) => (i === 0 ? { ...m, isDefault: true } : m));
    }
    return items;
  }

  async updateThreadSettings(threadId: string, model?: string, effort?: string): Promise<void> {
    await this.ensureConnected();
    const agentConfig: Json = { permission_mode: "yolo" };
    if (model?.trim()) agentConfig.model = model.trim();
    if (effort?.trim()) agentConfig.thinking = effort.trim();
    await this.restPost(`/sessions/${threadId}/profile`, { agent_config: agentConfig });
  }

  /** 打开已有会话时也拉齐 yolo，避免旧会话仍停在 manual。 */
  private async ensureYoloPermission(threadId: string): Promise<void> {
    await this.restPost(`/sessions/${threadId}/profile`, {
      agent_config: { permission_mode: "yolo" },
    }).catch(() => undefined);
  }

  async startCompact(threadId: string): Promise<void> {
    await this.ensureConnected();
    await this.restPost(`/sessions/${threadId}:compact`, {});
  }

  async startReview(_threadId: string, _target: ReviewTarget, _delivery?: string): Promise<ReviewStartResult> {
    throw new Error("Kimi 暂不支持 /review");
  }

  async forkThread(threadId: string, _lastTurnId?: string): Promise<Thread> {
    await this.ensureConnected();
    const session = await this.restPost(`/sessions/${threadId}:fork`, {});
    const thread = parseSessionAsThread(session);
    this.subscribeSession(thread.id);
    return thread;
  }

  async rollbackThread(threadId: string, numTurns = 1): Promise<Thread> {
    await this.ensureConnected();
    await this.restPost(`/sessions/${threadId}:undo`, { count: Math.max(1, numTurns) });
    return this.readThread(threadId, true);
  }

  async shellCommand(_threadId: string, _command: string): Promise<void> {
    throw new Error("Kimi 暂不支持 !shell（请直接发消息让 agent 执行）");
  }

  // ── REST（同源转发） ──────────────────────────────────────────────────

  private async restGet(path: string): Promise<Json> {
    return this.rest("GET", path);
  }

  private async restPost(path: string, body: Json): Promise<Json> {
    return this.rest("POST", path, body);
  }

  private async rest(method: string, path: string, body?: Json): Promise<Json> {
    const { url, token } = requireValidProfile(this.profile);
    const sep = path.includes("?") ? "&" : "?";
    const target = `/api/relay/kimi${path}${sep}url=${encodeURIComponent(url)}&token=${encodeURIComponent(token)}`;
    const response = await fetch(target, {
      method,
      headers: body !== undefined ? { "Content-Type": "application/json; charset=utf-8" } : undefined,
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
    return parseKimiEnvelope(response, path);
  }

  // ── WS ────────────────────────────────────────────────────────────────

  private async ensureConnected(): Promise<void> {
    if (this.connectPromise) return this.connectPromise;
    this.connectPromise = (async () => {
      if (this.wsReady && this.socket) return;
      const { token } = requireValidProfile(this.profile);

      this.socket?.close(1000, "reconnect");
      this.socket = null;
      this.wsReady = false;
      this.subscribedSessions.clear();
      this.failPending(new Error("Kimi 连接已断开"));

      const params = new URLSearchParams({ url: this.wsUrl, token });
      await new Promise<void>((resolve, reject) => {
        const socket = new WebSocket(`/ws/kimi?${params.toString()}`);
        const timeout = window.setTimeout(() => {
          socket.close();
          reject(new Error("连接 Kimi 超时"));
        }, CONNECT_TIMEOUT_MS);
        socket.onopen = () => {
          clearTimeout(timeout);
          this.socket = socket;
          resolve();
        };
        socket.onmessage = (event) => this.onMessage(event);
        socket.onerror = () => {
          /* 由 onclose 统一处理 */
        };
        socket.onclose = (event) => {
          clearTimeout(timeout);
          if (this.socket !== socket) return;
          this.socket = null;
          this.wsReady = false;
          this.subscribedSessions.clear();
          this.failPending(new Error(`Kimi 连接已断开：${event.reason || "连接失败"}`));
          reject(new Error(`Kimi 连接已断开：${event.reason || "连接失败"}`));
        };
      });

      // client_hello；ack 或 server_hello 都算握手完成。
      await this.control("client_hello", { client_id: `codex-web-${crypto.randomUUID()}` });
      this.wsReady = true;
      // 顺带探活 REST（失败不阻断已连上的 WS，但能尽早暴露 token 问题）
      await this.restGet("/healthz").catch(() => undefined);
    })().finally(() => {
      this.connectPromise = null;
    });
    return this.connectPromise;
  }

  private subscribeSession(sessionId: string) {
    if (this.subscribedSessions.has(sessionId)) return;
    this.subscribedSessions.add(sessionId);
    const payload: Json = { session_ids: [sessionId] };
    const cursor = this.sessionCursors.get(sessionId);
    if (cursor) payload.cursors = { [sessionId]: cursor };
    void this.control("subscribe", payload).catch(() => {
      this.subscribedSessions.delete(sessionId);
    });
  }

  private async control(type: string, payload: Json): Promise<Json> {
    const id = `c-${this.nextControlId++}`;
    return new Promise<Json>((resolve, reject) => {
      const timer = window.setTimeout(() => {
        this.pendingAcks.delete(id);
        reject(new Error(`${type} 超时`));
      }, RPC_TIMEOUT_MS);
      this.pendingAcks.set(id, { resolve, reject, timer });
      if (!this.socket || this.socket.readyState !== WebSocket.OPEN) {
        this.pendingAcks.delete(id);
        clearTimeout(timer);
        reject(new Error(`WebSocket 未连接，无法发送 ${type}`));
        return;
      }
      this.socket.send(JSON.stringify({ type, id, payload }));
    });
  }

  private onMessage(event: MessageEvent) {
    if (typeof event.data !== "string") return;
    let frame: Json;
    try {
      frame = JSON.parse(event.data);
    } catch {
      return;
    }
    this.handleFrame(frame);
  }

  private handleFrame(frame: Json) {
    switch (frame.type) {
      case "server_hello":
        this.resolveHello();
        break;
      case "ack": {
        const id = String(frame.id ?? "");
        const code = Number(frame.code ?? 0);
        const waiter = this.pendingAcks.get(id);
        if (!waiter) break;
        this.pendingAcks.delete(id);
        clearTimeout(waiter.timer);
        if (code === 0) {
          waiter.resolve(((frame.payload as Json | undefined) ?? {}) as Json);
        } else {
          waiter.reject(new Error(`WS ack 失败（${code}）：${stringOr(frame.msg) ?? ""}`));
        }
        // client_hello 的 ack 也算握手完成（部分服务端不发 server_hello 字段齐全时）
        this.resolveHello();
        break;
      }
      case "resync_required": {
        const sessionId = stringOr(frame.session_id) || stringOr((frame.payload as Json | undefined)?.session_id) || "";
        if (sessionId) {
          this.subscribedSessions.delete(sessionId);
          void (async () => {
            try {
              await this.applySnapshot(sessionId, true);
              this.subscribeSession(sessionId);
            } catch {
              /* 下一次订阅/read 仍会对账 */
            }
          })();
        }
        break;
      }
      default:
        this.handleSessionEvent(String(frame.type ?? ""), frame);
    }
  }

  private resolveHello() {
    for (const fn of this.helloResolvers) fn();
    this.helloResolvers = [];
  }

  private handleSessionEvent(type: string, frame: Json) {
    const payload = (frame.payload as Json | undefined) ?? {};
    const sessionId = stringOr(frame.session_id) || stringOr(payload.sessionId) || "";
    if (!sessionId) return;

    // 记录游标，便于重连重放。
    if (frame.volatile !== true && frame.seq != null) {
      const cursor: { seq?: number; epoch?: string } = { seq: Number(frame.seq) };
      const epoch = stringOr(frame.epoch);
      if (epoch) cursor.epoch = epoch;
      this.sessionCursors.set(sessionId, cursor);
    }

    switch (type) {
      case "turn.started": {
        const turnId = Number(payload.turnId ?? 0);
        const promptId = this.activePromptBySession.get(sessionId) ?? `turn-${turnId}`;
        const key = turnKey(sessionId, turnId);
        this.promptByTurnKey.set(key, promptId);
        this.stepByTurnKey.set(key, 0);
        this.pendingAssistantStepBump.delete(key);
        this.removeWithPrefix(this.startedAssistantItems, `asst-${sessionId}-${turnId}-`);
        this.removeWithPrefix(this.startedReasoningItems, `think-${sessionId}-${turnId}-`);
        this.events.emit({ type: "turnStarted", threadId: sessionId, turnId: promptId });
        break;
      }
      case "turn.step.started": {
        const turnId = Number(payload.turnId ?? 0);
        const step = Number(payload.step ?? 0);
        const key = turnKey(sessionId, turnId);
        this.stepByTurnKey.set(key, step);
        // step 事件已切换气泡，取消工具触发的兜底 bump，避免 step+1 两次。
        this.pendingAssistantStepBump.delete(key);
        break;
      }
      case "assistant.delta": {
        const turnId = Number(payload.turnId ?? 0);
        this.maybeBumpAssistantStep(sessionId, turnId);
        const itemId = this.assistantItemId(sessionId, turnId);
        const delta = stringOr(payload.delta) ?? "";
        if (!this.startedAssistantItems.has(itemId)) {
          this.startedAssistantItems.add(itemId);
          this.events.emit({ type: "itemStarted", threadId: sessionId, item: { kind: "agentMessage", id: itemId, text: "" } });
        }
        if (delta) {
          this.events.emit({ type: "agentMessageDelta", threadId: sessionId, itemId, delta });
        }
        break;
      }
      case "thinking.delta": {
        const turnId = Number(payload.turnId ?? 0);
        this.maybeBumpAssistantStep(sessionId, turnId);
        const itemId = this.reasoningItemId(sessionId, turnId);
        const delta = stringOr(payload.delta) ?? "";
        if (!this.startedReasoningItems.has(itemId)) {
          this.startedReasoningItems.add(itemId);
          this.events.emit({ type: "itemStarted", threadId: sessionId, item: { kind: "reasoning", id: itemId, summary: [""] } });
        }
        if (delta) {
          this.events.emit({ type: "reasoningSummaryDelta", threadId: sessionId, itemId, summaryIndex: 0, delta });
        }
        break;
      }
      case "tool.call.started": {
        const turnId = Number(payload.turnId ?? 0);
        const toolCallId = stringOr(payload.toolCallId) ?? "";
        const name = stringOr(payload.name) ?? "";
        const args = payload.args;
        const command = [name, args != null && args !== undefined ? JSON.stringify(args) : ""].filter(Boolean).join(" ") || toolCallId;
        if (toolCallId) this.toolCommandById.set(toolCallId, command);
        // 工具打断当前 assistant 段；若之后没有 turn.step.started，下一段文本自行开新气泡。
        if (turnId >= 0) this.pendingAssistantStepBump.add(turnKey(sessionId, turnId));
        this.events.emit({
          type: "itemStarted",
          threadId: sessionId,
          item: { kind: "commandExecution", id: toolCallId, command, cwd: "", status: "inProgress", output: "" },
        });
        break;
      }
      case "tool.result":
      case "shell.completed": {
        const toolCallId = stringOr(payload.toolCallId) || stringOr(payload.commandId) || "";
        if (!toolCallId) break;
        const output = payload.output == null ? "" : String(payload.output);
        const isError = payload.isError === true || payload.is_error === true;
        // result 帧经常只有 id；不要用 id 覆盖启动时写好的工具名。
        const command =
          this.toolCommandById.get(toolCallId) ||
          stringOr(payload.name) ||
          stringOr(payload.toolName) ||
          "";
        this.toolCommandById.delete(toolCallId);
        this.events.emit({
          type: "itemCompleted",
          threadId: sessionId,
          item: {
            kind: "commandExecution",
            id: toolCallId,
            command,
            cwd: "",
            status: isError ? "failed" : "completed",
            output,
            exitCode: isError ? 1 : 0,
          },
        });
        break;
      }
      case "turn.ended": {
        const turnId = Number(payload.turnId ?? 0);
        const key = turnKey(sessionId, turnId);
        const promptId = this.promptByTurnKey.get(key) ?? this.activePromptBySession.get(sessionId) ?? `turn-${turnId}`;
        if (this.activePromptBySession.get(sessionId) === promptId) {
          this.activePromptBySession.delete(sessionId);
        }
        this.promptByTurnKey.delete(key);
        this.stepByTurnKey.delete(key);
        this.pendingAssistantStepBump.delete(key);
        const reason = stringOr(payload.reason) || "completed";
        const status = reason === "cancelled" ? "interrupted" : reason === "failed" ? "failed" : "completed";
        const error = stringOr((payload.error as Json | undefined)?.message);
        this.events.emit({ type: "turnCompleted", threadId: sessionId, turnId: promptId, status, error });
        break;
      }
      case "compaction.started":
        this.events.emit({
          type: "itemStarted",
          threadId: sessionId,
          item: { kind: "contextCompaction", id: `compact-${sessionId}-${Number(frame.seq ?? 0)}`, status: "inProgress" },
        });
        break;
      case "compaction.completed":
        this.events.emit({
          type: "itemCompleted",
          threadId: sessionId,
          item: { kind: "contextCompaction", id: `compact-${sessionId}-${Number(frame.seq ?? 0)}`, status: "completed" },
        });
        break;
      case "agent.status.updated": {
        const used = Number(payload.contextTokens ?? -1);
        const window = Number(payload.maxContextTokens ?? -1);
        if (used >= 0 && window > 0) {
          this.events.emit({ type: "tokenUsageUpdated", threadId: sessionId, usage: { usedTokens: used, contextWindow: window } });
        }
        const phase = payload.phase as Json | undefined;
        if (phase?.kind === "awaiting_approval") {
          this.emitApprovalFromPhase(sessionId, phase);
        }
        break;
      }
      case "event.session.status_changed": {
        if (stringOr(frame.status) === "awaiting_approval" || stringOr(payload.status) === "awaiting_approval") {
          void this.pollPendingApprovals(sessionId);
        }
        break;
      }
    }
  }

  private async emitApprovalFromPhase(sessionId: string, phase: Json) {
    const approval = phase.approval as Json | undefined;
    if (approval) {
      this.emitApproval(sessionId, approval);
    } else {
      await this.pollPendingApprovals(sessionId);
    }
  }

  private async pollPendingApprovals(sessionId: string) {
    try {
      const data = await this.restGet(`/sessions/${sessionId}/approvals?status=pending`);
      for (const approval of arrayOf(data.items)) this.emitApproval(sessionId, approval);
    } catch {
      /* 轮询失败留给下一次状态事件 */
    }
  }

  private emitApproval(sessionId: string, approval: Json) {
    const approvalId = stringOr(approval.approval_id) || stringOr(approval.approvalId) || "";
    if (!approvalId) return;
    const key = this.nextApprovalKey++;
    this.approvalIdByKey.set(key, { sessionId, approvalId });
    const toolInput = approval.tool_input_display ?? approval.toolInputDisplay;
    const command = [
      stringOr(approval.tool_name) || stringOr(approval.toolName) || "",
      stringOr(approval.action) ?? "",
      toolInput != null ? String(toolInput) : "",
    ]
      .filter(Boolean)
      .join(" ")
      .trim();
    this.events.emit({
      type: "approvalRequest",
      requestId: key,
      threadId: sessionId,
      turnId:
        stringOr(approval.turn_id) ||
        stringOr(approval.turnId) ||
        this.activePromptBySession.get(sessionId) ||
        "",
      itemId: stringOr(approval.tool_call_id) || stringOr(approval.toolCallId) || "",
      command,
      cwd: "",
      reason: "需要批准工具调用",
    });
  }

  /**
   * @param reloadMessages true 时用 REST 历史整表对账（仅断线 resync）；
   *   日常 read/subscribe 必须为 false——服务端常把工具前的 thinking 落成空串，
   *   整表覆盖会把直播正确的「thinking→工具→thinking→回复」打成「工具→thinking→回复」。
   */
  private async applySnapshot(sessionId: string, reloadMessages: boolean): Promise<void> {
    const snap = await this.restGet(`/sessions/${sessionId}/snapshot`);
    const snapCursor = snap.cursor as Json | undefined;
    if (snapCursor) {
      this.sessionCursors.set(sessionId, {
        seq: snapCursor.seq == null ? undefined : Number(snapCursor.seq),
        epoch: stringOr(snapCursor.epoch),
      });
    }
    if (snap.as_of_seq != null) {
      const cursor: { seq?: number; epoch?: string } = { seq: Number(snap.as_of_seq) };
      const epoch = stringOr(snap.epoch);
      if (epoch) cursor.epoch = epoch;
      this.sessionCursors.set(sessionId, cursor);
    }
    for (const approval of arrayOf(snap.pending_approvals)) this.emitApproval(sessionId, approval);
    const sessionObj = snap.session as Json | undefined;
    const busy = (sessionObj as Json | undefined)?.busy === true;
    const inFlight = snap.in_flight_turn as Json | undefined;
    if (inFlight) {
      const promptId = stringOr(inFlight.current_prompt_id);
      if (promptId) this.activePromptBySession.set(sessionId, promptId);
      const turnId = Number(inFlight.turn_id ?? 0);
      const step = inFlight.step == null ? this.currentStep(sessionId, turnId) : Number(inFlight.step);
      this.stepByTurnKey.set(turnKey(sessionId, turnId), step);
      const thinking = stringOr(inFlight.thinking_text) ?? "";
      if (thinking) {
        const itemId = this.reasoningItemId(sessionId, turnId, step);
        if (!this.startedReasoningItems.has(itemId)) {
          this.startedReasoningItems.add(itemId);
          this.events.emit({ type: "itemStarted", threadId: sessionId, item: { kind: "reasoning", id: itemId, summary: [thinking] } });
        }
      }
      const asst = stringOr(inFlight.assistant_text) ?? "";
      if (asst) {
        const itemId = this.assistantItemId(sessionId, turnId, step);
        if (!this.startedAssistantItems.has(itemId)) {
          this.startedAssistantItems.add(itemId);
          this.events.emit({ type: "itemStarted", threadId: sessionId, item: { kind: "agentMessage", id: itemId, text: asst } });
        }
      }
    }
    const thread = parseSessionAsThread((sessionObj as Json) ?? (await this.restGet(`/sessions/${sessionId}`)));
    if (!reloadMessages) {
      // 只同步会话元数据，items 留空表示「不要覆盖本地气泡」。
      const promptId = this.activePromptBySession.get(sessionId) ?? stringOr(inFlight?.current_prompt_id) ?? "meta";
      this.events.emit({
        type: "threadReconciled",
        threadId: sessionId,
        thread: {
          ...thread,
          turns: [{ id: promptId, status: busy ? "inProgress" : "completed", items: [] }],
        },
      });
      return;
    }
    const historyPromptId = this.activePromptBySession.get(sessionId) ?? stringOr(inFlight?.current_prompt_id) ?? "history";
    this.events.emit({
      type: "threadReconciled",
      threadId: sessionId,
      thread: {
        ...thread,
        turns: [
          { id: historyPromptId, status: busy ? "inProgress" : "completed", items: await this.loadAllMessages(sessionId) },
        ],
      },
    });
  }

  // ── 解析 ──────────────────────────────────────────────────────────────

  /**
   * Kimi `GET .../messages` 默认 **newest first**（见 kap-server messageHistory）。
   * 聊天 UI 要时间正序，所以整页拉完后 reverse；翻更早的页用 `before_id`（不是 after_id）。
   */
  private async loadAllMessages(sessionId: string): Promise<ThreadItem[]> {
    const newestFirst: Json[] = [];
    let beforeId: string | undefined;
    for (let i = 0; i < MESSAGE_MAX_PAGES; i++) {
      const query = new URLSearchParams({ page_size: String(MESSAGE_PAGE_SIZE) });
      if (beforeId) query.set("before_id", beforeId);
      const page = await this.restGet(`/sessions/${sessionId}/messages?${query.toString()}`);
      const batch = arrayOf(page.items);
      if (batch.length === 0) break;
      newestFirst.push(...batch);
      // 本页最后一条是当前页里最旧的，用它继续往更早翻。
      beforeId = stringOr(batch[batch.length - 1].id) ?? "";
      if (page.has_more !== true) break;
    }
    newestFirst.reverse();
    const items = newestFirst.flatMap(parseMessageItems);
    return ensureUniqueItemIds(items);
  }

  private currentStep(sessionId: string, turnId: number): number {
    return this.stepByTurnKey.get(turnKey(sessionId, turnId)) ?? 0;
  }

  /** 工具打断后若没收到 step.started，在下一段文本/思考 delta 时 +1 step 开新气泡。 */
  private maybeBumpAssistantStep(sessionId: string, turnId: number) {
    const key = turnKey(sessionId, turnId);
    if (!this.pendingAssistantStepBump.delete(key)) return;
    this.stepByTurnKey.set(key, this.currentStep(sessionId, turnId) + 1);
  }

  private assistantItemId(sessionId: string, turnId: number, step: number = this.currentStep(sessionId, turnId)) {
    return `asst-${sessionId}-${turnId}-${step}`;
  }

  private reasoningItemId(sessionId: string, turnId: number, step: number = this.currentStep(sessionId, turnId)) {
    return `think-${sessionId}-${turnId}-${step}`;
  }

  private removeWithPrefix(set: Set<string>, prefix: string) {
    for (const value of [...set]) {
      if (value.startsWith(prefix)) set.delete(value);
    }
  }

  private failPending(error: Error) {
    for (const [id, waiter] of this.pendingAcks) {
      this.pendingAcks.delete(id);
      clearTimeout(waiter.timer);
      waiter.reject(error);
    }
  }
}

function turnKey(sessionId: string, turnId: number): string {
  return `${sessionId}:${turnId}`;
}

function arrayOf(value: unknown): Json[] {
  return Array.isArray(value) ? (value.filter((v): v is Json => v != null && typeof v === "object") as Json[]) : [];
}

function stringOr(value: unknown): string | undefined {
  if (value == null) return undefined;
  return String(value);
}

function parseSessionAsThread(session: Json): Thread {
  const agentConfig = (session.agent_config as Json | undefined) ?? {};
  const metadata = (session.metadata as Json | undefined) ?? {};
  const busy = session.busy === true;
  const title = stringOr(session.title) ?? "";
  return {
    id: stringOr(session.id) ?? "",
    preview: stringOr(session.last_prompt) || title,
    name: title || undefined,
    ephemeral: false,
    createdAt: parseIsoEpochSeconds(stringOr(session.created_at)),
    updatedAt: parseIsoEpochSeconds(stringOr(session.updated_at)),
    status: { type: busy ? "busy" : "idle" },
    cwd: stringOr(metadata.cwd) ?? "",
    model: (stringOr(agentConfig.model) ?? "").trim() || undefined,
    // optString 缺省是 ""，不能当 effort 用，否则会盖掉 UI 默认档位。
    effort: (stringOr(agentConfig.thinking) ?? "").trim() || undefined,
    turns: [],
  };
}

/** 粗解析 ISO 时间：取 epoch 秒；失败则 0。 */
function parseIsoEpochSeconds(iso: string | undefined): number {
  if (!iso) return 0;
  const ms = Date.parse(iso);
  return Number.isFinite(ms) ? Math.floor(ms / 1000) : 0;
}

function parseMessageItems(msg: Json): ThreadItem[] {
  const id = stringOr(msg.id) ?? "";
  const role = stringOr(msg.role) ?? "";
  const content = arrayOf(msg.content);
  switch (role) {
    case "user": {
      if (!isDisplayableUserMessage(msg)) return [];
      const blocks = content
        .map((block) => {
          if (block.type !== "text") return null;
          const raw = stringOr(block.text) ?? "";
          const cleaned = stripHiddenSystemMarkup(raw);
          if (!cleaned && raw) return null;
          return { type: "text", text: cleaned || raw };
        })
        .filter((b): b is { type: string; text: string } => b != null);
      if (blocks.length === 0) return [];
      if (blocks.every((b) => !b.text.trim())) return [];
      if (isHiddenSystemUserText(blocks.map((b) => b.text).join("\n"))) return [];
      return [{ kind: "userMessage", id, content: blocks }];
    }
    case "assistant": {
      // 按 content 顺序展开，保留 thinking → text → tool → thinking → text。
      // 旧逻辑先抽全部 thinking 再拼全部 text，会导致后段 thinking 丢位置/被盖住。
      const out: ThreadItem[] = [];
      let thinkIndex = 0;
      let textIndex = 0;
      let pendingText = "";
      const flushText = () => {
        if (!pendingText) return;
        const itemId = textIndex === 0 ? id : `${id}-text-${textIndex}`;
        out.push({ kind: "agentMessage", id: itemId, text: pendingText });
        textIndex++;
        pendingText = "";
      };
      content.forEach((block, index) => {
        switch (block.type) {
          case "thinking": {
            flushText();
            const think = (stringOr(block.thinking) || stringOr(block.text) || "").trim();
            if (!think) return;
            const last = out[out.length - 1];
            if (last && last.kind === "reasoning") {
              // 连续 thinking 段合并（与 kimi-web 一致）
              out[out.length - 1] = { ...last, summary: [...last.summary, think] };
            } else {
              out.push({ kind: "reasoning", id: `${id}-think-${thinkIndex}`, summary: [think] });
              thinkIndex++;
            }
            break;
          }
          case "text": {
            const piece = stringOr(block.text) ?? "";
            if (piece) pendingText += piece;
            break;
          }
          case "tool_use": {
            flushText();
            const callId = stringOr(block.tool_call_id) || `${id}-tool-${index}`;
            const input = block.input;
            out.push({
              kind: "commandExecution",
              id: callId,
              command: [
                stringOr(block.tool_name) ?? "",
                input != null ? String(JSON.stringify(input)) : "",
              ]
                .filter(Boolean)
                .join(" "),
              cwd: "",
              status: "completed",
              output: "",
            });
            break;
          }
        }
      });
      flushText();
      return out;
    }
    case "tool": {
      return content
        .filter((block) => block.type === "tool_result")
        .map((tool, index) => {
          const callId = stringOr(tool.tool_call_id) || `${id}-result-${index}`;
          // 单独 id，避免与 tool_use 的 callId 在列表里撞 key；下面 ensure 之前会先尝试合并进已有 CommandExecution。
          return {
            kind: "commandExecution" as const,
            id: `${callId}-result`,
            command: callId,
            cwd: "",
            status: tool.is_error === true ? ("failed" as const) : ("completed" as const),
            output: tool.output == null ? "" : String(tool.output),
            exitCode: tool.is_error === true ? 1 : 0,
          };
        });
    }
    default:
      return [];
  }
}

/** 合并 tool_result 到对应 tool_use，并保证列表 item id 全局唯一（React key 要求）。 */
function ensureUniqueItemIds(items: ThreadItem[]): ThreadItem[] {
  const merged: ThreadItem[] = [];
  for (const item of items) {
    if (item.kind === "commandExecution" && item.id.endsWith("-result")) {
      const callId = item.id.slice(0, -"-result".length);
      let idx = -1;
      for (let i = merged.length - 1; i >= 0; i--) {
        const existing = merged[i];
        if (existing.kind === "commandExecution" && existing.id === callId) {
          idx = i;
          break;
        }
      }
      if (idx >= 0) {
        const existing = merged[idx] as Extract<ThreadItem, { kind: "commandExecution" }>;
        merged[idx] = {
          ...existing,
          status: item.status,
          output: item.output || existing.output,
          exitCode: item.exitCode ?? existing.exitCode,
        };
        continue;
      }
    }
    merged.push(item);
  }
  const seen = new Set<string>();
  return merged.map((item) => {
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

/** `<system-reminder>…</system-reminder>` 等纯系统提示，历史里当 user 出现也不展示。 */
function isHiddenSystemUserText(text: string): boolean {
  const trimmed = text.trim();
  if (!trimmed) return true;
  return !stripHiddenSystemMarkup(trimmed) &&
    (trimmed.toLowerCase().includes("<system-reminder>") || trimmed.toLowerCase().includes("<system>"));
}

function stripHiddenSystemMarkup(text: string): string {
  return text
    .replace(/<system-reminder\b[^>]*>[\s\S]*?<\/system-reminder>/gi, "")
    .replace(/<system\b[^>]*>[\s\S]*?<\/system>/gi, "")
    .trim();
}

/** 与 kimi-web isDisplayableUserMessage 对齐：只展示真人输入；系统注入的 user 消息不展示。 */
function isDisplayableUserMessage(msg: Json): boolean {
  const origin = (msg.metadata as Json | undefined)?.origin as Json | undefined;
  if (!origin) return true;
  const kind = stringOr(origin.kind) ?? "";
  if (!kind || kind === "user") return true;
  if (kind === "skill_activation" || kind === "plugin_command") {
    return stringOr(origin.trigger) === "user-slash";
  }
  return false;
}
