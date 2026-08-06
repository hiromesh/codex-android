import { registry } from "./protocol/registry";
import { startAsr, AsrSession } from "./asr";
import { settingsStore } from "./settings";
import { ttsManager } from "./tts";
import { parseChatAction, ParsedChatAction } from "./slashCommands";
import {
  AgentProfile,
  AgentTypeId,
  agentTypeFromWireValue,
  ApprovalDecision,
  CodexEvent,
  ModelInfo,
  ReviewTarget,
  Thread,
  ThreadItem,
  TokenUsage,
} from "./types";

/* ---------------- TTS 单例（全局：agent 回答流式播报） ---------------- */

export { ttsManager };

/* ---------------- 列表项 ---------------- */

export interface ThreadEntry {
  profileId: string;
  profileName: string;
  agentType: AgentTypeId;
  thread: Thread;
}

/** 跨服务器 threadId 可能冲突；展示与导航都以 key 为准。 */
export function entryKey(entry: ThreadEntry): string {
  return `${entry.profileId}:${entry.thread.id}`;
}

/* ---------------- ThreadListStore（对应 ThreadListViewModel） ---------------- */

export interface ThreadListUiState {
  loading: boolean;
  entries: ThreadEntry[];
  /** 启用的配置，供新建会话时选择 */
  profiles: AgentProfile[];
  /** profileId -> 错误信息；部分配置失败不影响其他配置的卡片 */
  profileErrors: Map<string, string>;
  error: string | null;
}

export class ThreadListStore {
  private state: ThreadListUiState = { loading: false, entries: [], profiles: [], profileErrors: new Map(), error: null };
  private listeners = new Set<() => void>();
  private unsubEvents: (() => void) | null = null;
  private unsubProfiles: (() => void) | null = null;
  private pollTimer: number | null = null;
  private locallyWorkingKeys = new Set<string>();

  subscribe = (fn: () => void): (() => void) => {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  };

  getSnapshot = (): ThreadListUiState => {
    return this.state;
  };

  init = () => {
    if (this.unsubEvents) return;
    // 聊天页即使不在前台，仓库仍会分发 turn 事件；首页据此即时更新任务卡片状态。
    this.unsubEvents = registry.subscribeAll(({ profileId, event }) => {
      switch (event.type) {
        case "turnStarted":
          this.locallyWorkingKeys.add(`${profileId}:${event.threadId}`);
          this.updateThreadStatus(profileId, event.threadId, "busy");
          // 新建会话刚发第一条消息时列表里还没有它：立即拉一次让它出现，
          // 不用等 30s 轮询或手动刷新（后台刷新不闪 loading）。
          if (!this.state.entries.some((entry) => entryKey(entry) === `${profileId}:${event.threadId}`)) {
            this.refresh();
          }
          break;
        case "turnCompleted":
          this.locallyWorkingKeys.delete(`${profileId}:${event.threadId}`);
          this.updateThreadStatus(profileId, event.threadId, "idle");
          break;
        case "threadReconciled":
          this.replaceThread(profileId, event.thread);
          break;
        // 多端同步：web/其他设备删除或归档后，本机列表即时移除
        case "threadDeleted":
        case "threadArchived":
          this.removeLocally(profileId, event.threadId);
          break;
        default:
          break;
      }
    });
    // 配置增删/启停后重新聚合列表
    this.unsubProfiles = settingsStore.subscribeProfiles(() => this.refresh());
    this.setState({ ...this.state, profiles: settingsStore.getProfiles().filter((p) => p.enabled) });
    this.refresh();
    // 首页可见时保持轻量轮询（与 Android 30s 一致），让其他正在工作的任务也能及时显示状态。
    this.pollTimer = window.setInterval(() => this.refresh(), 30_000);
  }

  dispose = () => {
    this.unsubEvents?.();
    this.unsubEvents = null;
    this.unsubProfiles?.();
    this.unsubProfiles = null;
    if (this.pollTimer != null) clearInterval(this.pollTimer);
    this.pollTimer = null;
  };

  /** 聚合所有启用配置的 §3.4 thread/list，按更新时间倒序混排。 */
  refresh = () => {
    void (async () => {
      // 后台轮询（30s）不闪 loading：只有首次进入/列表为空时才显示加载条，避免侧栏周期性抽动。
      const showLoading = this.state.entries.length === 0;
      if (showLoading) this.setState({ ...this.state, loading: true, error: null });
      const { entries, errors } = await registry.listAllThreads();
      const effective = entries.map(({ profile, thread }) => ({
        profileId: profile.id,
        profileName: profile.name.trim() || agentTypeFromWireValue(profile.type).displayName,
        agentType: profile.type,
        thread: this.locallyWorkingKeys.has(`${profile.id}:${thread.id}`)
          ? { ...thread, status: { type: "busy" } }
          : thread,
      }));
      this.setState({
        ...this.state,
        loading: false,
        entries: effective,
        profileErrors: errors,
      });
    })();
  }

  /** 归档（软删除，可恢复）：先本地移除，失败则刷新列表恢复 */
  archiveThread = (profileId: string, threadId: string) => {
    this.removeLocally(profileId, threadId);
    void registry
      .repositoryFor(profileId)
      .archiveThread(threadId)
      .catch((error) => {
        this.setState({ ...this.state, error: `归档失败：${error instanceof Error ? error.message : String(error)}` });
        this.refresh();
      });
  };

  /** 彻底删除（不可恢复）：先本地移除，失败则刷新列表恢复 */
  deleteThread = (profileId: string, threadId: string) => {
    this.removeLocally(profileId, threadId);
    void registry
      .repositoryFor(profileId)
      .deleteThread(threadId)
      .catch((error) => {
        this.setState({ ...this.state, error: `删除失败：${error instanceof Error ? error.message : String(error)}` });
        this.refresh();
      });
  };

  private removeLocally(profileId: string, threadId: string) {
    this.locallyWorkingKeys.delete(`${profileId}:${threadId}`);
    this.setState({ ...this.state, entries: this.state.entries.filter((entry) => entryKey(entry) !== `${profileId}:${threadId}`) });
  }

  private updateThreadStatus(profileId: string, threadId: string, status: string) {
    const key = `${profileId}:${threadId}`;
    this.setState({
      ...this.state,
      entries: this.state.entries.map((entry) =>
        entryKey(entry) === key ? { ...entry, thread: { ...entry.thread, status: { type: status } } } : entry,
      ),
    });
  }

  private replaceThread(profileId: string, updated: Thread) {
    const key = `${profileId}:${updated.id}`;
    this.setState({
      ...this.state,
      entries: this.state.entries.map((entry) => (entryKey(entry) === key ? { ...entry, thread: updated } : entry)),
    });
  }

  private setState(state: ThreadListUiState) {
    this.state = state;
    this.listeners.forEach((fn) => fn());
  }
}

/* ---------------- ChatStore（对应 ChatViewModel） ---------------- */

export type PendingActionPrompt =
  | { kind: "reviewTarget" }
  | { kind: "confirmUndo"; numTurns: number }
  | { kind: "confirmShell"; command: string };

export interface ChatUiState {
  profileId: string;
  agentType: AgentTypeId;
  /** 输入框占位文案，如 Codex / Kimi */
  agentName: string;
  /** null 表示新会话，发第一条消息时才真正 thread/start */
  threadId: string | null;
  title: string;
  model: string;
  effort: string;
  items: ThreadItem[];
  loading: boolean;
  generating: boolean;
  /** 斜杠动作进行中（compact/fork 等），禁用连点与发消息。 */
  actionBusy: boolean;
  currentTurnId: string | null;
  pendingApproval: (CodexEvent & { type: "approvalRequest" }) | null;
  pendingActionPrompt: PendingActionPrompt | null;
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
  private state: ChatUiState;
  private listeners = new Set<() => void>();
  private unsubEvents: () => void;
  private watchdogTimer: number | null = null;
  private asrSession: AsrSession | null = null;
  private asrSessionId: string | null = null;
  private openThreadListeners = new Set<(threadId: string) => void>();

  constructor(profileId: string, threadId: string | null) {
    const profile = settingsStore.getProfiles().find((p) => p.id === profileId && p.enabled);
    const type = profile ? profile.type : "codex";
    this.state = {
      profileId,
      agentType: type,
      agentName: profile?.name.trim() || agentTypeFromWireValue(type).displayName,
      threadId,
      title: "新会话",
      model: DEFAULT_MODEL,
      effort: DEFAULT_EFFORT,
      items: [],
      loading: false,
      generating: false,
      actionBusy: false,
      currentTurnId: null,
      pendingApproval: null,
      pendingActionPrompt: null,
      availableModels: [],
      tokenUsage: null,
      asrTranscript: null,
      asrRecording: false,
      error: null,
    };
    const repo = registry.repositoryFor(profileId);
    if (threadId) this.loadThread(threadId);
    // §8.2：订阅全局事件流，按 threadId 过滤
    this.unsubEvents = repo.events.subscribe((event) => this.handleEvent(event));
    // §3.8：模型选择器数据；当前模型不在列表里时改用服务端默认（避免新会话显示 Codex 占位名）。
    // 已有会话的 effort 以会话为准（loadThread 已读取），listModels 只喂菜单数据，
    // 不再用默认模型的档位列表去夹会话 effort，避免「打开会话 effort 变了」。
    void repo
      .listModels()
      .then((models) => this.setState((state) => {
        const preferred = models.find((m) => m.isDefault) ?? models[0];
        const modelId = models.some((m) => m.id === state.model) ? state.model : (preferred?.id ?? state.model);
        const modelInfo = models.find((m) => m.id === modelId) ?? preferred;
        return {
          ...state,
          availableModels: models,
          model: modelId,
          effort: state.threadId == null ? resolveEffort(modelInfo, state.effort) : state.effort,
        };
      }))
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

  /** /fork 成功后通知 UI 跳到新会话（一次性）。 */
  subscribeOpenThread = (fn: (threadId: string) => void): (() => void) => {
    this.openThreadListeners.add(fn);
    return () => this.openThreadListeners.delete(fn);
  };

  dispose = () => {
    this.unsubEvents();
    this.stopAsr();
    // 离开会话页即停掉语音播报（对应 Android ChatViewModel.onCleared）。
    ttsManager.stop();
    if (this.watchdogTimer != null) clearInterval(this.watchdogTimer);
    document.removeEventListener("visibilitychange", this.onVisibilityChange);
  };

  private onVisibilityChange = () => {
    if (!document.hidden) this.reconcileAfterForeground();
  };

  /** 进入已有会话时，先 §3.3 thread/resume 再 §3.5 thread/read 拉完整历史 */
  private loadThread(threadId: string) {
    const repo = registry.repositoryFor(this.state.profileId);
    void (async () => {
      this.setState({ ...this.state, loading: true });
      try {
        const resumed = await repo.resumeThread(threadId);
        const loaded = await repo.readThread(threadId, true);
        // 少数旧服务端的 read 响应不带会话设置，保留 resume 的返回。
        const thread: Thread = {
          ...loaded,
          model: loaded.model ?? resumed.model,
          effort: loaded.effort ?? resumed.effort,
        };
        this.setState((state) => {
          const modelId = thread.model ?? state.model;
          const modelInfo = state.availableModels.find((m) => m.id === modelId);
          return {
            ...state,
            loading: false,
            title: thread.name ?? (thread.preview || "会话"),
            model: modelId,
            effort: resolveEffort(modelInfo, thread.effort ?? state.effort),
            items: thread.turns.flatMap((turn) => turn.items),
          };
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
      case "itemStarted": {
        const item =
          event.item.kind === "contextCompaction"
            ? // 以事件为准：started = 压缩进行中，避免服务端缺 status 时误显示「已压缩」。
              { ...event.item, status: "inProgress" }
            : event.item;
        this.appendItem(item);
        break;
      }
      // TTS 只朗读回答正文：正文 delta 边到边合成，工具/思考等其他事件不喂。
      case "agentMessageDelta":
        this.appendDelta(event.itemId, event.delta);
        ttsManager.onAgentDelta(event.delta);
        break;
      case "reasoningSummaryDelta":
        this.appendReasoningDelta(event.itemId, event.summaryIndex, event.delta);
        break;
      // item/completed 里是完整 item，直接替换以校对
      case "itemCompleted": {
        const item =
          event.item.kind === "contextCompaction"
            ? { ...event.item, status: "completed" }
            : event.item;
        this.replaceItem(item);
        if (item.kind === "agentMessage") ttsManager.onAgentMessageFinished();
        break;
      }
      case "turnCompleted": {
        if (event.status === "completed") {
          // 兜底：个别服务端可能漏发 agentMessage 的 item/completed。
          ttsManager.onAgentMessageFinished();
        } else {
          ttsManager.stop();
        }
        this.setState({
          ...this.state,
          generating: false,
          currentTurnId: null,
          pendingApproval: null,
          error: event.error ?? null,
        });
        this.stopWatchdog();
        break;
      }
      case "tokenUsageUpdated":
        this.setState({ ...this.state, tokenUsage: event.usage });
        break;
      case "threadReconciled":
        this.applyServerThread(event.thread);
        break;
      // 多端同步：当前会话被 web/其他设备删除或归档
      case "threadDeleted":
        ttsManager.stop();
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
        ttsManager.stop();
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

  /**
   * 输入框发送入口：命中 `/compact` `/review` `/fork` `/undo` 或 `!cmd` 时走动作接口，
   * 不插入用户气泡、不触发 turn/start；其余仍按普通对话发送。
   */
  submit = (text: string) => {
    const trimmed = text.trim();
    if (!trimmed) return;
    // 一点发送就停掉全部 TTS（含上一段还在播的尾音），与能否真正发出无关。
    ttsManager.stop();
    const action = parseChatAction(trimmed);
    if (action) {
      // Claude：/compact 等交互命令由 CLI 在 stream-json 模式自行拦截执行，
      // 不能走 codex 的 thread/compact/start（Claude 不支持），直接透传为普通消息。
      // /model /effort /goal 本就不在动作列表里，天然走普通消息透传。
      if (this.state.agentType === "claude" && action.kind === "compact") {
        this.send(trimmed);
        return;
      }
      this.dispatchAction(action);
    } else {
      this.send(trimmed);
    }
  };

  dismissActionPrompt = () => {
    this.setState({ ...this.state, pendingActionPrompt: null });
  };

  confirmReview = (target: ReviewTarget) => {
    this.setState({ ...this.state, pendingActionPrompt: null });
    this.runAction("审查", (threadId) =>
      registry
        .repositoryFor(this.state.profileId)
        .startReview(threadId, target)
        .then((result) => {
          this.setState({ ...this.state, generating: true, currentTurnId: result.turn.id, error: null });
          this.startWatchdog(result.reviewThreadId);
        }),
    );
  };

  confirmUndo = (numTurns: number) => {
    this.setState({ ...this.state, pendingActionPrompt: null });
    this.runAction("撤销", (threadId) =>
      registry
        .repositoryFor(this.state.profileId)
        .rollbackThread(threadId, numTurns)
        .then((thread) => this.applyServerThread(thread)),
    );
  };

  confirmShell = (command: string) => {
    this.setState({ ...this.state, pendingActionPrompt: null });
    this.runAction("Shell", (threadId) => registry.repositoryFor(this.state.profileId).shellCommand(threadId, command));
  };

  private dispatchAction(action: ParsedChatAction) {
    const state = this.state;
    if (state.generating || state.actionBusy) return;
    switch (action.kind) {
      case "compact":
        this.runAction("压缩", (threadId) => registry.repositoryFor(this.state.profileId).startCompact(threadId));
        break;
      case "reviewNeedTarget":
        this.setState({ ...this.state, pendingActionPrompt: { kind: "reviewTarget" } });
        break;
      case "review":
        this.confirmReview(action.target);
        break;
      case "fork":
        this.runAction("分叉", (threadId) =>
          registry
            .repositoryFor(this.state.profileId)
            .forkThread(threadId)
            .then((forked) => {
              for (const fn of [...this.openThreadListeners]) fn(forked.id);
            }),
        );
        break;
      case "undo":
        this.setState({ ...this.state, pendingActionPrompt: { kind: "confirmUndo", numTurns: action.numTurns } });
        break;
      case "shell":
        this.setState({ ...this.state, pendingActionPrompt: { kind: "confirmShell", command: action.command } });
        break;
    }
  }

  private runAction(label: string, block: (threadId: string) => Promise<void>) {
    const threadId = this.state.threadId;
    if (!threadId) {
      this.setState({ ...this.state, error: `请先发送一条消息创建会话，再使用 ${label}` });
      return;
    }
    if (this.state.actionBusy || this.state.generating) return;
    ttsManager.stop();
    this.setState({ ...this.state, actionBusy: true, error: null });
    void block(threadId)
      .catch((error) => {
        this.setState({ ...this.state, error: `${label} 失败：${error instanceof Error ? error.message : error}` });
      })
      .finally(() => this.setState({ ...this.state, actionBusy: false }));
  }

  /** §3.6 turn/start */
  send = (text: string) => {
    const trimmed = text.trim();
    if (!trimmed || this.state.generating || this.state.actionBusy) return;
    ttsManager.stop();
    const repo = registry.repositoryFor(this.state.profileId);
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
          // 新会话：第一条消息时才真正 thread/start（§3.2）；
          // thread/start 只带模型；effort 在建会话后、首条 turn 前写入设置。
          const selection = this.state;
          const thread = await repo.startThread(selection.model || undefined);
          // 建会话后把当前 UI 上的 model + effort 一并写入；Kimi 的 create 本身不生效。
          try {
            await repo.updateThreadSettings(thread.id, selection.model || thread.model || undefined, selection.effort || undefined);
          } catch (settingsError) {
            // Claude 不支持模型/档位切换：建会话后写设置会抛「暂不支持」，
            // 不能让它阻断发消息；其余（codex/kimi）写失败仍按真实错误上抛。
            if (!(settingsError instanceof Error) || !settingsError.message.includes("暂不支持")) throw settingsError;
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
        const turn = await repo.startTurn(threadId, [{ type: "text", text: trimmed }]);
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
    const { threadId, generating, currentTurnId } = this.state;
    if (!threadId) return;
    // generating 即可停；不强制 currentTurnId（Kimi 对账时可能是 meta 占位）。
    if (!generating && currentTurnId == null) return;
    ttsManager.stop();
    void registry
      .repositoryFor(this.state.profileId)
      .interruptTurn(threadId, currentTurnId ?? "")
      .catch((error) => this.setState({ ...this.state, error: `中断失败：${error instanceof Error ? error.message : error}` }));
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
    const repo = registry.repositoryFor(this.state.profileId);
    void (async () => {
      try {
        const resumed = await repo.resumeThread(threadId);
        const loaded = await repo.readThread(threadId, true);
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
      void registry
        .repositoryFor(this.state.profileId)
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
    void registry
      .repositoryFor(this.state.profileId)
      .respondApproval(request.requestId, decision)
      .catch((error) => {
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
      void registry
        .repositoryFor(this.state.profileId)
        .updateThreadSettings(threadId, modelId, effort)
        .catch((error) => this.setState({ ...this.state, error: error instanceof Error ? error.message : String(error) }));
    }
    // 新会话还没建：只记本地状态，send() 会在建立后写入 effort。
  }

  private appendItem(item: ThreadItem) {
    this.setState((state) => {
      // reasoning 的 item/started 通常没有摘要；仅在服务端真的给出内容后展示。
      if (item.kind === "reasoning" && item.summary.length === 0) return state;
      // 发送时先插入 local-* 气泡；服务端随后会回传同一 userMessage。
      // 用内容匹配并替换为服务端 itemId，防止同一条消息显示两遍。
      if (item.kind === "userMessage") {
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
        return { ...state, items: [...state.items, item] };
      }
      return { ...state, items: [...state.items, item] };
    });
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
        const existing = state.items[index];
        const merged: ThreadItem =
          item.kind === "commandExecution" && existing.kind === "commandExecution"
            ? {
                // completed 可能只带 output/status；保留 started 时的工具名，避免被 id 盖掉。
                ...item,
                command: item.command && item.command !== item.id ? item.command : existing.command,
                cwd: item.cwd || existing.cwd,
                output: item.output || existing.output,
                exitCode: item.exitCode ?? existing.exitCode,
                durationMs: item.durationMs ?? existing.durationMs,
              }
            : item;
        const items = [...state.items];
        items[index] = merged;
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
      const incomingItems = thread.turns.flatMap((turn) => turn.items);
      // 对账若未带 items（Kimi snapshot 元数据同步），保留本地气泡，避免直播被残缺历史盖掉。
      const replaceItems = incomingItems.length > 0;
      const modelId = thread.model ?? state.model;
      const modelInfo = state.availableModels.find((m) => m.id === modelId);
      if (activeTurn) {
        return {
          ...state,
          title: thread.name ?? (thread.preview || state.title),
          model: modelId,
          effort: resolveEffort(modelInfo, thread.effort ?? state.effort),
          generating: true,
          currentTurnId: activeTurn.id,
        };
      }
      return {
        ...state,
        title: thread.name ?? (thread.preview || state.title),
        model: modelId,
        effort: resolveEffort(modelInfo, thread.effort ?? state.effort),
        items: replaceItems ? incomingItems : state.items,
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

/**
 * 把当前 effort 对齐到模型支持的档位：空值、或不在 support 列表里时，改用模型默认/第一项。
 * Kimi 常见 support 不含 medium，而 UI 初始是 medium，不夹会显示对不上勾选。
 */
export function resolveEffort(model: ModelInfo | undefined, effort: string | undefined): string {
  const supported = model?.supportedReasoningEfforts ?? [];
  const current = effort?.trim() || undefined;
  if (current != null && (supported.length === 0 || supported.includes(current))) return current;
  const fallback = model?.defaultReasoningEffort?.trim() || undefined;
  if (fallback != null && (supported.length === 0 || supported.includes(fallback))) return fallback;
  return supported[0] ?? current ?? "medium";
}
