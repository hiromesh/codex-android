import { client } from "./client";
import { startAsr, AsrSession } from "./asr";
import { settingsStore } from "./settings";
import { ApprovalDecision, CodexEvent, ModelInfo, Thread, ThreadItem, TokenUsage } from "./types";

/* ---------------- ThreadListStore（对应 ThreadListViewModel） ---------------- */

export interface ThreadListUiState {
  loading: boolean;
  threads: Thread[];
  error: string | null;
}

export class ThreadListStore {
  private state: ThreadListUiState = { loading: false, threads: [], error: null };
  private listeners = new Set<() => void>();
  private unsubEvents: (() => void) | null = null;
  private pollTimer: number | null = null;
  private locallyWorkingThreadIds = new Set<string>();

  subscribe = (fn: () => void): (() => void) => {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  };

  getSnapshot = (): ThreadListUiState => {
    return this.state;
  };

  init = () => {
    if (this.unsubEvents) return;
    // 聊天页即使不在前台，客户端仍会分发 turn 事件；首页据此即时更新任务卡片状态。
    this.unsubEvents = client.subscribe((event) => {
      switch (event.type) {
        case "turnStarted":
          this.locallyWorkingThreadIds.add(event.threadId);
          this.updateThreadStatus(event.threadId, "busy");
          break;
        case "turnCompleted":
          this.locallyWorkingThreadIds.delete(event.threadId);
          this.updateThreadStatus(event.threadId, "idle");
          break;
        case "threadReconciled":
          this.replaceThread(event.thread);
          break;
        // 多端同步：web/其他设备删除或归档后，本机列表即时移除
        case "threadDeleted":
        case "threadArchived":
          this.removeLocally(event.threadId);
          break;
        default:
          break;
      }
    });
    this.refresh();
    // 首页可见时保持轻量轮询（与 Android 8s 一致），让其他正在工作的任务也能及时显示状态。
    this.pollTimer = window.setInterval(() => this.refresh(), 8_000);
  }

  dispose = () => {
    this.unsubEvents?.();
    this.unsubEvents = null;
    if (this.pollTimer != null) clearInterval(this.pollTimer);
    this.pollTimer = null;
  };

  /** §3.4 thread/list */
  refresh = () => {
    void (async () => {
      this.setState({ ...this.state, loading: true, error: null });
      try {
        const page = await client.listThreads(undefined, 15);
        const threads = page.data.map((thread) =>
          this.locallyWorkingThreadIds.has(thread.id) ? { ...thread, status: { type: "busy" } } : thread,
        );
        this.setState({ ...this.state, loading: false, threads });
      } catch (error) {
        this.setState({
          ...this.state,
          loading: false,
          error: error instanceof Error ? error.message : String(error),
        });
      }
    })();
  }

  /** 归档（软删除，可恢复）：先本地移除，失败则刷新列表恢复 */
  archiveThread = (threadId: string) => {
    this.removeLocally(threadId);
    void client.archiveThread(threadId).catch((error) => {
      this.setState({ ...this.state, error: `归档失败：${error instanceof Error ? error.message : String(error)}` });
      this.refresh();
    });
  };

  /** 彻底删除（不可恢复）：先本地移除，失败则刷新列表恢复 */
  deleteThread = (threadId: string) => {
    this.removeLocally(threadId);
    void client.deleteThread(threadId).catch((error) => {
      this.setState({ ...this.state, error: `删除失败：${error instanceof Error ? error.message : String(error)}` });
      this.refresh();
    });
  };

  private removeLocally(threadId: string) {
    this.locallyWorkingThreadIds.delete(threadId);
    this.setState({ ...this.state, threads: this.state.threads.filter((t) => t.id !== threadId) });
  }

  private updateThreadStatus(threadId: string, status: string) {
    this.setState({
      ...this.state,
      threads: this.state.threads.map((thread) =>
        thread.id === threadId ? { ...thread, status: { type: status } } : thread,
      ),
    });
  }

  private replaceThread(updated: Thread) {
    this.setState({
      ...this.state,
      threads: this.state.threads.map((thread) => (thread.id === updated.id ? updated : thread)),
    });
  }

  private setState(state: ThreadListUiState) {
    this.state = state;
    this.listeners.forEach((fn) => fn());
  }
}

/* ---------------- ChatStore（对应 ChatViewModel） ---------------- */

export interface ChatUiState {
  /** null 表示新会话，发第一条消息时才真正 thread/start */
  threadId: string | null;
  title: string;
  model: string;
  effort: string;
  items: ThreadItem[];
  loading: boolean;
  generating: boolean;
  currentTurnId: string | null;
  pendingApproval: (CodexEvent & { type: "approvalRequest" }) | null;
  availableModels: ModelInfo[];
  tokenUsage: TokenUsage | null;
  asrTranscript: string | null;
  asrRecording: boolean;
  error: string | null;
}

const DEFAULT_MODEL = "gpt-5.6-terra";
const DEFAULT_EFFORT = "medium";
const TURN_RECONCILE_INTERVAL_MS = 15_000;

export class ChatStore {
  private state: ChatUiState = {
    threadId: null,
    title: "新会话",
    model: DEFAULT_MODEL,
    effort: DEFAULT_EFFORT,
    items: [],
    loading: false,
    generating: false,
    currentTurnId: null,
    pendingApproval: null,
    availableModels: [],
    tokenUsage: null,
    asrTranscript: null,
    asrRecording: false,
    error: null,
  };
  private listeners = new Set<() => void>();
  private unsubEvents: () => void;
  private watchdogTimer: number | null = null;
  private asrSession: AsrSession | null = null;
  private asrSessionId: string | null = null;

  constructor(threadId: string | null) {
    this.state = { ...this.state, threadId };
    if (threadId) this.loadThread(threadId);
    // §8.2：订阅全局事件流，按 threadId 过滤
    this.unsubEvents = client.subscribe((event) => this.handleEvent(event));
    // §3.8：模型选择器数据
    void client
      .listModels()
      .then((models) => this.setState({ ...this.state, availableModels: models }))
      .catch((error) => this.setState({ ...this.state, error: error instanceof Error ? error.message : String(error) }));
    // 浏览器切回前台时主动对账（对应 Android 的 foreground reconcile）。
    document.addEventListener("visibilitychange", this.onVisibilityChange);
  }

  subscribe = (fn: () => void): (() => void) => {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  };

  getSnapshot = (): ChatUiState => {
    return this.state;
  };

  dispose = () => {
    this.unsubEvents();
    this.stopAsr();
    if (this.watchdogTimer != null) clearInterval(this.watchdogTimer);
    document.removeEventListener("visibilitychange", this.onVisibilityChange);
  };

  private onVisibilityChange = () => {
    if (!document.hidden) this.reconcileAfterForeground();
  };

  /** 进入已有会话时，先 §3.3 thread/resume 再 §3.5 thread/read 拉完整历史 */
  private loadThread(threadId: string) {
    void (async () => {
      this.setState({ ...this.state, loading: true });
      try {
        const resumed = await client.resumeThread(threadId);
        const loaded = await client.readThread(threadId, true);
        // 少数旧服务端的 read 响应不带会话设置，保留 resume 的返回。
        const thread: Thread = {
          ...loaded,
          model: loaded.model ?? resumed.model,
          effort: loaded.effort ?? resumed.effort,
        };
        this.setState({
          ...this.state,
          loading: false,
          title: thread.name ?? (thread.preview || "会话"),
          model: thread.model ?? this.state.model,
          effort: thread.effort ?? this.state.effort,
          items: thread.turns.flatMap((turn) => turn.items),
        });
      } catch (error) {
        this.setState({
          ...this.state,
          loading: false,
          error: error instanceof Error ? error.message : String(error),
        });
      }
    })();
  }

  private handleEvent(event: CodexEvent) {
    const threadId = this.state.threadId;
    if (!threadId) return;
    if (event.threadId !== threadId) return;
    switch (event.type) {
      case "turnStarted":
        this.setState({ ...this.state, generating: true, currentTurnId: event.turnId });
        break;
      case "itemStarted":
        this.appendItem(event.item);
        break;
      case "agentMessageDelta":
        this.appendDelta(event.itemId, event.delta);
        break;
      case "reasoningSummaryDelta":
        this.appendReasoningDelta(event.itemId, event.summaryIndex, event.delta);
        break;
      // item/completed 里是完整 item，直接替换以校对
      case "itemCompleted":
        this.replaceItem(event.item);
        break;
      case "turnCompleted":
        this.setState({
          ...this.state,
          generating: false,
          currentTurnId: null,
          pendingApproval: null,
          error: event.error ?? null,
        });
        this.stopWatchdog();
        break;
      case "tokenUsageUpdated":
        this.setState({ ...this.state, tokenUsage: event.usage });
        break;
      case "threadReconciled":
        this.applyServerThread(event.thread);
        break;
      // 多端同步：当前会话被 web/其他设备删除或归档
      case "threadDeleted":
        this.stopWatchdog();
        this.setState({
          ...this.state,
          generating: false,
          currentTurnId: null,
          pendingApproval: null,
          error: "会话已被其他设备删除",
        });
        break;
      case "threadArchived":
        this.stopWatchdog();
        this.setState({
          ...this.state,
          generating: false,
          currentTurnId: null,
          pendingApproval: null,
          error: "会话已被其他设备归档",
        });
        break;
      case "approvalRequest":
        this.setState({ ...this.state, pendingApproval: event });
        break;
    }
  }

  /** §3.6 turn/start */
  send = (text: string) => {
    const trimmed = text.trim();
    if (!trimmed || this.state.generating) return;
    void (async () => {
      let submittedThreadId: string | null = null;
      this.appendItem({ kind: "userMessage", id: localId(), content: [{ type: "text", text: trimmed }] });
      this.setState({ ...this.state, generating: true, error: null });
      try {
        const existingId = this.state.threadId;
        let threadId: string;
        if (existingId) {
          threadId = existingId;
        } else {
          // 新会话：第一条消息时才真正 thread/start；effort 在建会话后、首条 turn 前写入设置。
          const selection = this.state;
          const thread = await client.startThread(selection.model || undefined);
          if (selection.effort) {
            await client.updateThreadSettings(thread.id, undefined, selection.effort);
          }
          this.setState({
            ...this.state,
            threadId: thread.id,
            model: thread.model ?? this.state.model,
            title: trimmed.slice(0, 20),
          });
          threadId = thread.id;
        }
        submittedThreadId = threadId;
        this.startWatchdog(threadId);
        const turn = await client.startTurn(threadId, [{ type: "text", text: trimmed }]);
        // 正常情况会由 turn/started 通知填入；如果通知稍晚，用 RPC 结果兜底。
        if (this.state.generating && this.state.currentTurnId == null) {
          this.setState({ ...this.state, currentTurnId: turn.id });
        }
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        // 超时/断线时服务端可能已经收到 turn/start，不能直接把它当成失败。
        // watchdog 会 read 全量会话，确认服务端最终状态。
        if (submittedThreadId != null && !message.startsWith("Codex 协议错误")) {
          this.setState({ ...this.state, error: "连接中断，正在同步会话…" });
        } else {
          this.stopWatchdog();
          this.setState({ ...this.state, generating: false, currentTurnId: null, error: message });
        }
      }
    })();
  }

  /** §3.7 turn/interrupt */
  interrupt = () => {
    const { threadId, currentTurnId } = this.state;
    if (!threadId || !currentTurnId) return;
    void client.interruptTurn(threadId, currentTurnId).catch((error) =>
      this.setState({ ...this.state, error: `中断失败：${error instanceof Error ? error.message : error}` }),
    );
  }

  /** 开始将麦克风 PCM 以 200ms 分包发送至 ASR */
  startAsr = () => {
    if (this.state.asrRecording) return;
    const sessionId = crypto.randomUUID();
    this.asrSessionId = sessionId;
    try {
      const session = startAsr(
        settingsStore.get(),
        (text) => {
          if (this.asrSessionId === sessionId) this.setState({ ...this.state, asrTranscript: text });
        },
        (message) => {
          if (this.asrSessionId === sessionId) {
            this.asrSession = null;
            this.asrSessionId = null;
            this.setState({ ...this.state, asrRecording: false, error: message });
          }
        },
      );
      this.asrSession = session;
      this.setState({ ...this.state, asrRecording: true, asrTranscript: null, error: null });
    } catch (error) {
      this.asrSessionId = null;
      this.setState({ ...this.state, error: error instanceof Error ? error.message : String(error) });
    }
  }

  /** 立即停麦克风并发送 ASR 协议的最后一包，最终文本仍可在短暂回包后写入输入框。 */
  stopAsr = () => {
    const activeSession = this.asrSession;
    if (!activeSession) return;
    this.asrSession = null;
    this.setState({ ...this.state, asrRecording: false });
    activeSession.stop();
  }

  /** 从后台/锁屏回来时主动对账；后台期间 WebSocket 的通知不保证能保活或重放。 */
  reconcileAfterForeground = () => {
    const threadId = this.state.threadId;
    if (!threadId) return;
    void (async () => {
      try {
        const resumed = await client.resumeThread(threadId);
        const loaded = await client.readThread(threadId, true);
        this.applyServerThread({
          ...loaded,
          model: loaded.model ?? resumed.model,
          effort: loaded.effort ?? resumed.effort,
        });
      } catch {
        /* 静默，等待下次机会 */
      }
    })();
  }

  /** 低频读全量兜住“socket 还活着但某次通知没有到 UI”的情况（15s，对应 Android watchdog） */
  private startWatchdog(threadId: string) {
    if (this.watchdogTimer != null) clearInterval(this.watchdogTimer);
    this.watchdogTimer = window.setInterval(() => {
      if (!this.state.generating || this.state.threadId !== threadId) {
        this.stopWatchdog();
        return;
      }
      void client
        .readThread(threadId, true)
        .then((thread) => this.applyServerThread(thread))
        .catch(() => undefined);
    }, TURN_RECONCILE_INTERVAL_MS);
  }

  private stopWatchdog() {
    if (this.watchdogTimer != null) {
      clearInterval(this.watchdogTimer);
      this.watchdogTimer = null;
    }
  }

  /** §6 审批应答 */
  respondApproval = (decision: ApprovalDecision) => {
    const request = this.state.pendingApproval;
    if (!request) return;
    this.setState({ ...this.state, pendingApproval: null });
    void client.respondApproval(request.requestId, decision).catch((error) => {
      // 应答失败时恢复弹窗让用户重试；否则审批在服务端永远挂起。
      this.setState({
        ...this.state,
        pendingApproval: request,
        error: `审批应答失败：${error instanceof Error ? error.message : error}`,
      });
    });
  }

  reportErrorClear = () => {
    this.setState({ ...this.state, error: null });
  };

  /** §3.9② thread/settings/update：模型与推理档位同属会话级设置。 */
  switchConfiguration = (modelId: string, effort: string) => {
    const threadId = this.state.threadId;
    this.setState({ ...this.state, model: modelId, effort });
    if (threadId) {
      void client.updateThreadSettings(threadId, modelId, effort).catch((error) =>
        this.setState({ ...this.state, error: error instanceof Error ? error.message : String(error) }),
      );
    }
    // 新会话还没建：只记本地状态，send() 会在建立后写入 effort。
  }

  private appendItem(item: ThreadItem) {
    this.setState({ ...this.state, items: [...this.state.items, item] });
  }

  private appendDelta(itemId: string, delta: string) {
    this.setState((state) => {
      const index = state.items.findIndex((it) => it.id === itemId);
      const target = state.items[index];
      if (target && target.kind === "agentMessage") {
        const items = [...state.items];
        items[index] = { ...target, text: target.text + delta };
        return { ...state, items };
      }
      return { ...state, items: [...state.items, { kind: "agentMessage" as const, id: itemId, text: delta }] };
    });
  }

  /** 按 summaryIndex 为 reasoning 摘要分段，逐个 delta 追加 */
  private appendReasoningDelta(itemId: string, summaryIndex: number, delta: string) {
    this.setState((state) => {
      const index = state.items.findIndex((it) => it.id === itemId);
      const existing = state.items[index];
      const summary = [...(existing && existing.kind === "reasoning" ? existing.summary : [])];
      const safeIndex = Math.max(0, summaryIndex);
      while (summary.length <= safeIndex) summary.push("");
      summary[safeIndex] += delta;
      const updated: ThreadItem = { kind: "reasoning", id: itemId, summary };
      if (index >= 0) {
        const items = [...state.items];
        items[index] = updated;
        return { ...state, items };
      }
      return { ...state, items: [...state.items, updated] };
    });
  }

  private replaceItem(item: ThreadItem) {
    this.setState((state) => {
      const index = state.items.findIndex((it) => it.id === item.id);
      if (index >= 0) {
        const items = [...state.items];
        items[index] = item;
        return { ...state, items };
      }
      if (item.kind === "reasoning" && item.summary.length === 0) {
        // 默认未开启摘要时不会产生空的“思考过程”折叠项。
        return state;
      }
      if (item.kind === "userMessage") {
        // 某些服务端只发 item/completed，不发 userMessage 的 item/started。
        const localIndex = state.items.findIndex(
          (existing) =>
            existing.kind === "userMessage" &&
            existing.id.startsWith("local-") &&
            JSON.stringify(existing.content) === JSON.stringify(item.content),
        );
        if (localIndex >= 0) {
          const items = [...state.items];
          items[localIndex] = item;
          return { ...state, items };
        }
      }
      return { ...state, items: [...state.items, item] };
    });
  }

  /**
   * 服务端快照对账。轮次仍在进行时，本地流式增量比服务端快照新（delta 不落盘），
   * 只更新元数据，不覆盖 items；轮次已结束时快照是最终真相，全量替换。
   */
  private applyServerThread(thread: Thread) {
    this.setState((state) => {
      const activeTurn = thread.turns.filter((t) => t.status === "inProgress").at(-1);
      const lastTurn = thread.turns.at(-1);
      const lastError = lastTurn && lastTurn.status === "failed" ? lastTurn.error : undefined;
      if (activeTurn) {
        return {
          ...state,
          title: thread.name ?? (thread.preview || state.title),
          model: thread.model ?? state.model,
          effort: thread.effort ?? state.effort,
          generating: true,
          currentTurnId: activeTurn.id,
        };
      }
      return {
        ...state,
        title: thread.name ?? (thread.preview || state.title),
        model: thread.model ?? state.model,
        effort: thread.effort ?? state.effort,
        items: thread.turns.flatMap((turn) => turn.items),
        generating: false,
        currentTurnId: null,
        // 轮次结束时不可能有待应答审批（服务端在等应答就不会结束轮次），可安全清除。
        pendingApproval: null,
        error: lastError ?? null,
      };
    });
  }

  private setState(state: ChatUiState): void;
  private setState(updater: (state: ChatUiState) => ChatUiState): void;
  private setState(update: ChatUiState | ((state: ChatUiState) => ChatUiState)) {
    this.state = typeof update === "function" ? update(this.state) : update;
    this.listeners.forEach((fn) => fn());
  }
}

function localId(): string {
  return `local-${crypto.randomUUID()}`;
}
