import {
  ApprovalDecision,
  AgentProfile,
  Content,
  ModelInfo,
  ReviewStartResult,
  ReviewTarget,
  Thread,
  ThreadItem,
  ThreadPage,
  Turn,
} from "../types";
import { requireValidWsProfile } from "./http";
import { EventEmitter } from "./events";
import { Repository } from "./repository";

const CONTEXT_BASELINE_TOKENS = 12_000;
const CONNECT_TIMEOUT_MS = 15_000;
const RPC_TIMEOUT_MS = 30_000;
const RECONNECT_ATTEMPTS = 5;
const RECONNECT_BASE_DELAY_MS = 1_000;
const APPROVAL_METHODS = new Set([
  "item/commandExecution/requestApproval",
  "item/fileChange/requestApproval",
]);

const CLIENT_NAME = "codex-web";
const CLIENT_VERSION = "1.0.0";

interface ConnectionConfig {
  url: string;
  token: string;
}

interface PendingRpc {
  resolve: (value: unknown) => void;
  reject: (error: Error) => void;
  timer: number;
}

type Json = Record<string, unknown>;

/**
 * codex app-server 单 WebSocket 实现，逻辑与 Android WebSocketCodexRepository 一致：
 * 每次 RPC 前完成 initialize/initialized 握手；配置变更后由 Registry 重建实例；
 * 通知与审批由同一 reader 分发；断线后对进行中的轮次做 resume+read 对账。
 */
export class CodexClient implements Repository {
  readonly events = new EventEmitter();
  private socket: WebSocket | null = null;
  private configKey: string | null = null;
  private currentThreadId: string | null = null;
  /** 当前尚未收到 turn/completed 的会话；仅它需要断线后主动恢复。 */
  private activeTurnThreadId: string | null = null;
  /** 每次新建连接都要重新 attach，不能把上一条 WebSocket 的状态当作仍有效。 */
  private attachedThreadId: string | null = null;
  private connectPromise: Promise<void> | null = null;
  private nextRequestId = 0;
  private pending = new Map<number, PendingRpc>();
  private itemThreads = new Map<string, string>();
  private turnThreads = new Map<string, string>();
  private recoveryActive = false;

  constructor(private readonly profile: AgentProfile) {}

  close() {
    this.socket?.close(1000, "profile changed");
    this.socket = null;
    this.configKey = null;
    this.attachedThreadId = null;
    this.failPending(new Error("连接已关闭"));
  }

  /** §3.1 initialize + initialized */
  async initialize(): Promise<void> {
    await this.ensureInitialized();
  }

  /** §3.4 thread/list */
  async listThreads(cursor?: string, limit = 20): Promise<ThreadPage> {
    const result = await this.request("thread/list", { limit, cursor: cursor ?? null, archived: false });
    const data = Array.isArray(result.data) ? (result.data as Json[]).map(parseThread) : [];
    return { data, nextCursor: nullableString(result, "nextCursor") };
  }

  /** §3.2 thread/start，可选 model */
  async startThread(model?: string): Promise<Thread> {
    const params: Json = {};
    if (model) params.model = model;
    const result = await this.request("thread/start", params);
    const resolved = threadFromResult(result, "thread/start missing thread");
    this.currentThreadId = resolved.id;
    this.attachedThreadId = resolved.id;
    return resolved;
  }

  /** §3.3 thread/resume */
  async resumeThread(threadId: string, model?: string): Promise<Thread> {
    const params: Json = { threadId };
    if (model) params.model = model;
    const result = await this.request("thread/resume", params);
    const resolved = threadFromResult(result, "thread/resume missing thread");
    this.currentThreadId = resolved.id;
    this.attachedThreadId = resolved.id;
    return resolved;
  }

  /** §3.5 thread/read；部分服务端要求先 attach，遇 thread not found 时先 resume 再读 */
  async readThread(threadId: string, includeTurns = true): Promise<Thread> {
    try {
      return await this.readThreadRaw(threadId, includeTurns);
    } catch (firstError) {
      if (!(firstError instanceof Error) || !firstError.message.toLowerCase().includes("thread not found")) throw firstError;
      const resumed = await this.resumeThread(threadId);
      try {
        return await this.readThreadRaw(threadId, includeTurns);
      } catch (retryError) {
        if (retryError instanceof Error && retryError.message.toLowerCase().includes("thread not found")) return resumed;
        throw retryError;
      }
    }
  }

  /** §3.10 thread/archive：归档会话（软删除，列表默认隐藏，可 unarchive 恢复） */
  async archiveThread(threadId: string): Promise<void> {
    await this.request("thread/archive", { threadId });
  }

  /** §3.10 thread/delete：彻底删除会话（不可恢复） */
  async deleteThread(threadId: string): Promise<void> {
    await this.request("thread/delete", { threadId });
  }

  /** §3.6 turn/start */
  async startTurn(threadId: string, input: Content[]): Promise<Turn> {
    this.currentThreadId = threadId;
    this.activeTurnThreadId = threadId;
    await this.ensureThreadAttached(threadId);
    const result = await this.requestRaw("turn/start", {
      threadId,
      input: input.map(contentToJson),
    });
    const turn = parseTurn(result.turn as Json);
    this.turnThreads.set(turn.id, threadId);
    this.currentThreadId = threadId;
    return turn;
  }

  /** §3.7 turn/interrupt */
  async interruptTurn(threadId: string, turnId: string): Promise<void> {
    await this.request("turn/interrupt", { threadId, turnId });
  }

  /** §6 审批应答 */
  async respondApproval(requestId: number, decision: ApprovalDecision): Promise<void> {
    await this.ensureInitialized();
    this.sendResponse(requestId, { decision });
  }

  /** §3.8 model/list */
  async listModels(): Promise<ModelInfo[]> {
    const result = await this.request("model/list", {
      cursor: null,
      limit: null,
      includeHidden: false,
    });
    return Array.isArray(result.data) ? (result.data as Json[]).map(parseModel) : [];
  }

  /** §3.9② thread/settings/update */
  async updateThreadSettings(threadId: string, model?: string, effort?: string): Promise<void> {
    const params: Json = { threadId };
    if (model) params.model = model;
    if (effort) params.effort = effort;
    await this.request("thread/settings/update", params);
  }

  /** §CODEX_ACTIONS_API.1 thread/compact/start：压缩上下文（/compact），立即返回 */
  async startCompact(threadId: string): Promise<void> {
    await this.ensureThreadAttached(threadId);
    await this.request("thread/compact/start", { threadId });
  }

  /** §CODEX_ACTIONS_API.2 review/start：发起代码审查（/review） */
  async startReview(threadId: string, target: ReviewTarget, delivery = "inline"): Promise<ReviewStartResult> {
    await this.ensureThreadAttached(threadId);
    this.currentThreadId = threadId;
    this.activeTurnThreadId = threadId;
    const result = await this.requestRaw("review/start", {
      threadId,
      target: reviewTargetToJson(target),
      delivery,
    });
    const turn = parseTurn(result.turn as Json);
    const reviewThreadId = nullableString(result, "reviewThreadId") ?? threadId;
    this.turnThreads.set(turn.id, reviewThreadId);
    this.currentThreadId = reviewThreadId;
    return { turn, reviewThreadId };
  }

  /** §CODEX_ACTIONS_API.3 thread/fork：分叉会话（/fork） */
  async forkThread(threadId: string, lastTurnId?: string): Promise<Thread> {
    await this.ensureThreadAttached(threadId);
    const params: Json = { threadId };
    if (lastTurnId) params.lastTurnId = lastTurnId;
    const result = await this.request("thread/fork", params);
    return threadFromResult(result, "thread/fork missing thread");
  }

  /** §CODEX_ACTIONS_API.4 thread/rollback：砍掉末尾 N 轮（/undo） */
  async rollbackThread(threadId: string, numTurns = 1): Promise<Thread> {
    await this.ensureThreadAttached(threadId);
    const result = await this.request("thread/rollback", {
      threadId,
      numTurns: Math.max(1, numTurns),
    });
    return threadFromResult(result, "thread/rollback missing thread");
  }

  /** §CODEX_ACTIONS_API.5 thread/shellCommand：在会话上下文跑 shell（!cmd） */
  async shellCommand(threadId: string, command: string): Promise<void> {
    await this.ensureThreadAttached(threadId);
    await this.request("thread/shellCommand", { threadId, command });
  }

  private async ensureInitialized(): Promise<void> {
    const desired = toConnectionConfig(this.profile);
    if (this.socket && this.configKey === desiredKey(desired)) return;

    this.socket?.close(1000, "connection settings changed");
    this.socket = null;
    this.configKey = null;
    this.attachedThreadId = null;
    this.failPending(new Error("WebSocket 连接已被替换"));

    await this.connectAndHandshake(desired);
  }

  private async connectAndHandshake(desired: ConnectionConfig): Promise<void> {
    if (this.connectPromise) return this.connectPromise;
    this.connectPromise = (async () => {
      await this.connect(desired);
      try {
        await this.requestRaw("initialize", {
          clientInfo: { name: CLIENT_NAME, version: CLIENT_VERSION },
          capabilities: { experimentalApi: true },
        });
        this.sendNotification("initialized");
      } catch (error) {
        // 握手失败不能留下“已连接”标记，否则后续 ensureInitialized 会直接跳过重连。
        this.socket?.close(1000, "handshake failed");
        this.socket = null;
        throw error;
      }
      this.configKey = desiredKey(desired);
      this.attachedThreadId = null;
    })();
    try {
      await this.connectPromise;
    } finally {
      this.connectPromise = null;
    }
  }

  private connect(desired: ConnectionConfig): Promise<void> {
    return new Promise((resolve, reject) => {
      const params = new URLSearchParams({ url: desired.url, token: desired.token });
      const socket = new WebSocket(`/ws/codex?${params.toString()}`);
      socket.binaryType = "arraybuffer";
      const timeout = window.setTimeout(() => {
        socket.close();
        reject(new Error("连接服务器超时"));
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
        this.configKey = null;
        this.attachedThreadId = null;
        this.failPending(new Error(`WebSocket 已断开：${event.reason || "连接失败"}`));
        this.scheduleActiveTurnRecovery();
        reject(new Error(`WebSocket 已断开：${event.reason || "连接失败"}`));
      };
    });
  }

  /** 当前连接是无状态的：换了 WebSocket 后必须重新 resume 才能继续 turn/start。 */
  private async ensureThreadAttached(threadId: string): Promise<void> {
    await this.ensureInitialized();
    if (this.attachedThreadId === threadId) return;
    const result = await this.requestRaw("thread/resume", { threadId });
    const resumed = threadFromResult(result, "thread/resume missing thread");
    this.currentThreadId = resumed.id;
    this.attachedThreadId = resumed.id;
  }

  private async request(method: string, params?: Json): Promise<Json> {
    await this.ensureInitialized();
    return this.requestRaw(method, params);
  }

  private async requestRaw(method: string, params?: Json): Promise<Json> {
    const id = ++this.nextRequestId;
    const message: Json = { id, method };
    if (params !== undefined) message.params = params;
    return new Promise((resolve, reject) => {
      const timer = window.setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`${method} 请求超时`));
      }, RPC_TIMEOUT_MS);
      this.pending.set(id, { resolve: (v) => resolve(v as Json), reject, timer });
      if (!this.socket || this.socket.readyState !== WebSocket.OPEN) {
        this.pending.delete(id);
        clearTimeout(timer);
        reject(new Error(`WebSocket 未连接，无法发送 ${method}`));
        return;
      }
      this.socket.send(JSON.stringify(message));
    });
  }

  private sendNotification(method: string, params?: Json) {
    const message: Json = { method };
    if (params !== undefined) message.params = params;
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) throw new Error("WebSocket 未连接");
    this.socket.send(JSON.stringify(message));
  }

  private sendResponse(id: number, result: Json) {
    const message: Json = { id, result };
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) throw new Error("WebSocket 未连接，无法回复审批");
    this.socket.send(JSON.stringify(message));
  }

  /** 对不支持的服务端反向请求回 JSON-RPC error；best-effort。 */
  private sendErrorResponse(id: number, code: number, message: string) {
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) return;
    this.socket.send(JSON.stringify({ id, error: { code, message } }));
  }

  private onMessage(event: MessageEvent) {
    if (typeof event.data !== "string") return;
    let message: Json;
    try {
      message = JSON.parse(event.data);
    } catch {
      return;
    }
    if (typeof message.id === "number" && !("method" in message)) {
      this.handleResponse(message);
    } else if (typeof message.method === "string") {
      if (typeof message.id === "number") this.handleServerRequest(message);
      else this.handleNotification(message);
    }
  }

  private handleResponse(message: Json) {
    const id = message.id as number;
    const deferred = this.pending.get(id);
    if (!deferred) return;
    this.pending.delete(id);
    clearTimeout(deferred.timer);
    const error = message.error as Json | undefined;
    if (error) {
      deferred.reject(protocolError(String(error.message ?? JSON.stringify(error))));
    } else {
      deferred.resolve((message.result as Json | undefined) ?? {});
    }
  }

  private handleServerRequest(message: Json) {
    const method = String(message.method ?? "");
    const params = (message.params as Json | undefined) ?? {};
    if (!APPROVAL_METHODS.has(method)) {
      // 未知反向请求不能伪造 result（permissions 审批期望的是 {permissions, scope}）；
      // 回 JSON-RPC error 让服务端走失败路径，而不是永远阻塞。
      this.sendErrorResponse(message.id as number, -32601, `unsupported method: ${method}`);
      return;
    }
    this.events.emit({
      type: "approvalRequest",
      requestId: message.id as number,
      threadId: String(params.threadId ?? this.currentThreadId ?? ""),
      turnId: String(params.turnId ?? ""),
      itemId: String(params.itemId ?? ""),
      command: String(params.command ?? params.reason ?? ""),
      cwd: String(params.cwd ?? params.grantRoot ?? ""),
      reason: String(params.reason ?? ""),
    });
  }

  private handleNotification(message: Json) {
    const params = (message.params as Json | undefined) ?? {};
    switch (message.method) {
      case "turn/started": {
        const threadId = String(params.threadId ?? this.currentThreadId ?? "");
        const turnId = String((params.turn as Json | undefined)?.id ?? "");
        if (threadId) this.currentThreadId = threadId;
        if (threadId) this.activeTurnThreadId = threadId;
        if (turnId && threadId) this.turnThreads.set(turnId, threadId);
        if (threadId) this.events.emit({ type: "turnStarted", threadId, turnId });
        break;
      }
      case "item/started":
      case "item/completed": {
        const isStarted = message.method === "item/started";
        // contextCompaction 的 item/started 常不带 status；按事件阶段给默认值，避免一调用就显示「已压缩」。
        const item = parseItem(params.item as Json | undefined, isStarted ? "inProgress" : "completed");
        if (!item) break;
        const threadId = String(params.threadId ?? this.itemThreads.get(item.id) ?? this.currentThreadId ?? "");
        if (threadId) this.itemThreads.set(item.id, threadId);
        if (!threadId) break;
        this.events.emit(
          isStarted
            ? { type: "itemStarted", threadId, item }
            : { type: "itemCompleted", threadId, item },
        );
        break;
      }
      case "item/agentMessage/delta": {
        const itemId = String(params.itemId ?? "");
        const threadId = String(params.threadId ?? this.itemThreads.get(itemId) ?? this.currentThreadId ?? "");
        if (threadId && itemId) this.itemThreads.set(itemId, threadId);
        if (!threadId) break;
        this.events.emit({ type: "agentMessageDelta", threadId, itemId, delta: String(params.delta ?? "") });
        break;
      }
      case "item/reasoning/summaryTextDelta": {
        const itemId = String(params.itemId ?? "");
        const threadId = String(params.threadId ?? this.itemThreads.get(itemId) ?? this.currentThreadId ?? "");
        if (threadId && itemId) this.itemThreads.set(itemId, threadId);
        if (!threadId) break;
        this.events.emit({
          type: "reasoningSummaryDelta",
          threadId,
          itemId,
          summaryIndex: Number(params.summaryIndex ?? 0),
          delta: String(params.delta ?? ""),
        });
        break;
      }
      case "turn/completed": {
        const turn = params.turn as Json | undefined;
        const turnId = String(turn?.id ?? "");
        const threadId = String(params.threadId ?? this.turnThreads.get(turnId) ?? this.currentThreadId ?? "");
        if (threadId) {
          this.events.emit({
            type: "turnCompleted",
            threadId,
            turnId,
            status: statusValue(turn, "inProgress"),
            error: errorMessage(turn),
          });
        }
        if (this.activeTurnThreadId === threadId) this.activeTurnThreadId = null;
        break;
      }
      case "thread/deleted": {
        const threadId = String(params.threadId ?? "");
        if (threadId) this.events.emit({ type: "threadDeleted", threadId });
        break;
      }
      case "thread/archived": {
        const threadId = String(params.threadId ?? "");
        if (threadId) this.events.emit({ type: "threadArchived", threadId });
        break;
      }
      case "thread/tokenUsage/updated": {
        const threadId = String(params.threadId ?? this.currentThreadId ?? "");
        const usage = params.tokenUsage as Json | undefined;
        if (usage) {
          const last = Number((usage.last as Json | undefined)?.totalTokens ?? 0);
          const window = usage.modelContextWindow == null ? 0 : Number(usage.modelContextWindow);
          const used = Math.max(0, last - CONTEXT_BASELINE_TOKENS);
          const effectiveWindow = Math.max(0, window - CONTEXT_BASELINE_TOKENS);
          if (threadId) this.events.emit({ type: "tokenUsageUpdated", threadId, usage: { usedTokens: used, contextWindow: effectiveWindow } });
        }
        break;
      }
    }
  }

  /**
   * 服务端不重放 WebSocket 通知。断线时正在跑的轮次会继续在服务端执行，
   * 所以恢复连接后重新挂载会话并读全量历史，页面即可补齐最终结果。
   */
  private scheduleActiveTurnRecovery() {
    const threadId = this.activeTurnThreadId;
    if (!threadId || this.recoveryActive) return;
    this.recoveryActive = true;
    void (async () => {
      for (let attempt = 0; attempt < RECONNECT_ATTEMPTS; attempt++) {
        if (attempt > 0) await delay(RECONNECT_BASE_DELAY_MS * 2 ** (attempt - 1));
        try {
          // resumeThread 会对新 socket 执行 initialize + attach。
          await this.resumeThread(threadId);
          const thread = await this.readThreadRaw(threadId, true);
          this.events.emit({ type: "threadReconciled", threadId: thread.id, thread });
          const activeTurn = thread.turns.filter((t) => t.status === "inProgress").at(-1);
          this.activeTurnThreadId = activeTurn ? threadId : null;
          return;
        } catch {
          /* 指数退避重试 */
        }
      }
      // 不把网络错误伪装成“生成完成”；下次主动 read 仍会对账。
    })().finally(() => {
      this.recoveryActive = false;
    });
  }

  private async readThreadRaw(threadId: string, includeTurns: boolean): Promise<Thread> {
    const result = await this.request("thread/read", { threadId, includeTurns });
    return parseThread((result.thread as Json | undefined) ?? result);
  }

  private failPending(error: Error) {
    for (const [id, deferred] of this.pending) {
      this.pending.delete(id);
      clearTimeout(deferred.timer);
      deferred.reject(error);
    }
  }
}

function toConnectionConfig(profile: AgentProfile): ConnectionConfig {
  return requireValidWsProfile(profile);
}

function desiredKey(config: ConnectionConfig): string {
  return `${config.url} ${config.token}`;
}

function protocolError(message: string): Error {
  return new Error(`Codex 协议错误：${message}`);
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function contentToJson(content: Content): Json {
  const out: Json = { type: content.type };
  if (content.text) out.text = content.text;
  if (content.url) out.url = content.url;
  return out;
}

function reviewTargetToJson(target: ReviewTarget): Json {
  switch (target.type) {
    case "uncommittedChanges":
      return { type: "uncommittedChanges" };
    case "baseBranch":
      return { type: "baseBranch", branch: target.branch };
    case "commit": {
      const out: Json = { type: "commit", sha: target.sha };
      if (target.title) out.title = target.title;
      return out;
    }
    case "custom":
      return { type: "custom", instructions: target.instructions };
  }
}

function nullableString(obj: Json, name: string): string | undefined {
  const value = obj[name];
  return value == null ? undefined : String(value);
}

function optNumber(obj: Json, name: string): number | undefined {
  const value = obj[name];
  return value == null ? undefined : Number(value);
}

function statusValue(obj: Json | undefined, defaultValue = "inProgress"): string {
  const status = obj?.status;
  if (status && typeof status === "object") return String((status as Json).type ?? defaultValue);
  if (typeof status === "string") return status;
  return defaultValue;
}

/** turn.error 是 TurnError 对象（{message, codexErrorInfo, ...}）；兼容旧版的字符串形式。 */
function errorMessage(obj: Json | undefined): string | undefined {
  const error = obj?.error;
  if (error && typeof error === "object") return nullableString(error as Json, "message");
  if (typeof error === "string") return error;
  return undefined;
}

/** 不同 app-server 版本将搜索词放在 query/searchQuery 或 action.query 中。 */
function webSearchQuery(obj: Json): string {
  const action = obj.action as Json | undefined;
  const input = obj.input as Json | undefined;
  return (
    [obj.query, obj.searchQuery, obj.text, action?.query, input?.query]
      .find((v) => typeof v === "string" && v.trim() !== "") as string | undefined
  ) ?? "";
}

function firstString(obj: Json, names: string[]): string | undefined {
  for (const name of names) {
    const value = obj[name];
    if (value != null) return String(value);
  }
  return undefined;
}

/** fileChange.changes 有些服务端版本会返回对象（甚至带完整 diff）；只保留可读的文件摘要。 */
function fileChanges(arr: unknown): string[] {
  if (!Array.isArray(arr)) return [];
  const out: string[] = [];
  for (const entry of arr) {
    if (typeof entry === "string") {
      if (entry.trim()) out.push(entry);
    } else if (entry && typeof entry === "object") {
      const path = firstString(entry as Json, ["path", "filePath", "filename", "name"]);
      const kind = firstString(entry as Json, ["kind", "status", "changeType"]);
      if (path != null && kind != null) out.push(`${kind} · ${path}`);
      else if (path != null) out.push(path);
      else if (kind != null) out.push(kind);
      else out.push("文件改动");
    }
  }
  return out;
}

/** summary 既可能是字符串数组，也可能是 [{"text":"..."}]（当前 app-server）。 */
function textFragments(arr: unknown): string[] {
  if (!Array.isArray(arr)) return [];
  const out: string[] = [];
  for (const entry of arr) {
    if (typeof entry === "string") {
      if (entry.trim()) out.push(entry);
    } else if (entry && typeof entry === "object") {
      const text = firstString(entry as Json, ["text", "content", "summary"]);
      if (text != null && text.trim()) out.push(text);
    }
  }
  return out;
}

function reasoningEfforts(arr: unknown): string[] {
  if (!Array.isArray(arr)) return [];
  const out: string[] = [];
  for (const entry of arr) {
    if (typeof entry === "string") out.push(entry);
    else if (entry && typeof entry === "object") {
      const effort = nullableString(entry as Json, "reasoningEffort");
      if (effort) out.push(effort);
    }
  }
  return out;
}

function parseThread(value: Json): Thread {
  const status = value.status;
  const type =
    status && typeof status === "object" ? String((status as Json).type ?? "idle") : String(status ?? "idle");
  return {
    id: String(value.id ?? ""),
    preview: String(value.preview ?? ""),
    name: nullableString(value, "name"),
    ephemeral: Boolean(value.ephemeral),
    createdAt: Number(value.createdAt ?? 0),
    updatedAt: Number(value.updatedAt ?? 0),
    status: { type },
    cwd: String(value.cwd ?? ""),
    model: nullableString(value, "model"),
    effort: threadEffort(value),
    turns: Array.isArray(value.turns) ? (value.turns as Json[]).map(parseTurn) : [],
  };
}

/**
 * app-server 的不同版本将会话推理档位命名为 effort / reasoningEffort，
 * 少数版本还会将它收在 config 内。读取时兼容这些返回，写入仍遵循 API 的 effort。
 */
function threadEffort(value: Json): string | undefined {
  const config = value.config as Json | undefined;
  return (
    [value.effort, value.reasoningEffort, value.modelReasoningEffort, config?.effort, config?.reasoningEffort]
      .find((v) => typeof v === "string" && v.trim() !== "") as string | undefined
  ) ?? undefined;
}

function threadFromResult(result: Json, missingThreadMessage: string): Thread {
  const threadValue = result.thread as Json | undefined;
  if (!threadValue) throw protocolError(missingThreadMessage);
  const thread = parseThread(threadValue);
  return {
    ...thread,
    model: thread.model ?? nullableString(result, "model"),
    effort: thread.effort ?? threadEffort(result),
  };
}

function parseTurn(value: Json): Turn {
  return {
    id: String(value.id ?? ""),
    status: statusValue(value),
    items: Array.isArray(value.items)
      ? (value.items as Json[]).map((v) => parseItem(v)).filter((item): item is ThreadItem => item != null)
      : [],
    error: errorMessage(value),
  };
}

function parseItem(value: Json | undefined, compactionDefaultStatus = "completed"): ThreadItem | null {
  if (!value) return null;
  const id = String(value.id ?? "");
  if (!id) return null;
  switch (value.type) {
    case "userMessage":
      return {
        kind: "userMessage",
        id,
        content: Array.isArray(value.content) ? (value.content as Json[]).map(parseContent) : [],
      };
    case "agentMessage":
      return { kind: "agentMessage", id, text: String(value.text ?? "") };
    case "commandExecution":
      return {
        kind: "commandExecution",
        id,
        command: String(value.command ?? ""),
        cwd: String(value.cwd ?? ""),
        status: statusValue(value),
        output: String(value.aggregatedOutput ?? ""),
        exitCode: optNumber(value, "exitCode"),
        durationMs: optNumber(value, "durationMs"),
      };
    case "fileChange":
      return { kind: "fileChange", id, changes: fileChanges(value.changes), status: statusValue(value, "completed") };
    case "plan":
      return { kind: "plan", id, text: String(value.text ?? "") };
    case "webSearch":
      return { kind: "webSearch", id, query: webSearchQuery(value), status: statusValue(value, "completed") };
    case "reasoning":
      return { kind: "reasoning", id, summary: textFragments(value.summary) };
    case "contextCompaction":
      return { kind: "contextCompaction", id, status: statusValue(value, compactionDefaultStatus) };
    default:
      return null;
  }
}

function parseContent(value: Json): Content {
  return {
    type: String(value.type ?? ""),
    text: String(value.text ?? ""),
    url: nullableString(value, "url"),
  };
}

function parseModel(value: Json): ModelInfo {
  return {
    id: String(value.id ?? ""),
    displayName: nullableString(value, "displayName") ?? String(value.id ?? ""),
    description: String(value.description ?? ""),
    isDefault: Boolean(value.isDefault),
    hidden: Boolean(value.hidden),
    supportedReasoningEfforts: reasoningEfforts(value.supportedReasoningEfforts),
    defaultReasoningEffort: String(value.defaultReasoningEffort ?? "medium"),
  };
}
