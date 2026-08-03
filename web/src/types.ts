/**
 * 协议数据模型，与 Android 端 Models.kt 一一对应（字段为 camelCase JSON）。
 */

export interface ThreadStatus {
  type: string;
}

export interface Thread {
  id: string;
  preview: string;
  name?: string;
  ephemeral: boolean;
  /** epoch 秒 */
  createdAt: number;
  updatedAt: number;
  status: ThreadStatus;
  cwd: string;
  model?: string;
  /** 会话级推理档位 */
  effort?: string;
  turns: Turn[];
}

export interface Turn {
  id: string;
  /** inProgress / completed / interrupted / failed */
  status: string;
  items: ThreadItem[];
  error?: string;
}

export type ThreadItem =
  | { kind: "userMessage"; id: string; content: Content[] }
  | { kind: "agentMessage"; id: string; text: string }
  | {
      kind: "commandExecution";
      id: string;
      command: string;
      cwd: string;
      status: string;
      output: string;
      exitCode?: number;
      durationMs?: number;
    }
  | { kind: "fileChange"; id: string; changes: string[]; status: string }
  | { kind: "plan"; id: string; text: string }
  | { kind: "webSearch"; id: string; query: string; status: string }
  | { kind: "reasoning"; id: string; summary: string[] };

export interface Content {
  type: string; // text / image / localImage
  text: string;
  url?: string;
}

export interface ModelInfo {
  id: string;
  displayName: string;
  description: string;
  isDefault: boolean;
  hidden: boolean;
  supportedReasoningEfforts: string[];
  defaultReasoningEffort: string;
}

/**
 * thread/tokenUsage/updated：与 TUI 口径一致。
 * usedTokens = last.totalTokens - 12000 基线；contextWindow = modelContextWindow - 12000。
 */
export interface TokenUsage {
  usedTokens: number;
  contextWindow: number;
}

export type ApprovalDecision = "accept" | "acceptForSession" | "decline" | "cancel";

export type CodexEvent =
  | { type: "turnStarted"; threadId: string; turnId: string }
  | { type: "itemStarted"; threadId: string; item: ThreadItem }
  | { type: "agentMessageDelta"; threadId: string; itemId: string; delta: string }
  | { type: "reasoningSummaryDelta"; threadId: string; itemId: string; summaryIndex: number; delta: string }
  | { type: "itemCompleted"; threadId: string; item: ThreadItem }
  | {
      type: "turnCompleted";
      threadId: string;
      turnId: string;
      status: string;
      error?: string;
    }
  | { type: "tokenUsageUpdated"; threadId: string; usage: TokenUsage }
  | { type: "threadReconciled"; threadId: string; thread: Thread }
  | { type: "threadDeleted"; threadId: string }
  | { type: "threadArchived"; threadId: string }
  | {
      type: "approvalRequest";
      requestId: number;
      threadId: string;
      turnId: string;
      itemId: string;
      command: string;
      cwd: string;
      reason: string;
    };

export interface ThreadPage {
  data: Thread[];
  nextCursor?: string;
}

/** App 级配置，对应 Android SettingsStore；模型是会话级设置，不放这里。 */
export interface AppSettings {
  serverUrl: string;
  token: string;
  asrUrl: string;
  asrAppKey: string;
  asrAccessKey: string;
  asrResourceId: string;
}

export const DEFAULT_SETTINGS: AppSettings = {
  /** 与 Android 默认一致 */
  serverUrl: "wss://codex.waibozishu.com:8443",
  token: "",
  asrUrl: "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async",
  asrAppKey: "",
  asrAccessKey: "",
  asrResourceId: "volc.seedasr.sauc.duration",
};
