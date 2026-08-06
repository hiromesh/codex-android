/**
 * 协议数据模型，与 Android 端 Models.kt / AgentProfile.kt 一一对应（字段为 camelCase JSON）。
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
  | { kind: "reasoning"; id: string; summary: string[] }
  | { kind: "contextCompaction"; id: string; status: string };

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

/** review/start 的 target（tagged union，`type` 判别）。见 docs/CODEX_ACTIONS_API.md §2。 */
export type ReviewTarget =
  | { type: "uncommittedChanges" }
  | { type: "baseBranch"; branch: string }
  | { type: "commit"; sha: string; title?: string }
  | { type: "custom"; instructions: string };

export interface ReviewStartResult {
  turn: Turn;
  /** inline 时 = 原会话；detached 时 = 新审查会话 */
  reviewThreadId: string;
}

/* ---------------- Agent 类型与配置（对应 Android AgentProfile.kt） ---------------- */

export type AgentTypeId = "codex" | "kimi" | "claude" | "opencode";

export interface AgentTypeInfo {
  wireValue: AgentTypeId;
  displayName: string;
  /** 列表卡片/徽章上的单字母标识 */
  badgeLetter: string;
  /** 徽章底色（CSS 色值） */
  badgeColor: string;
  /** 是否已有可用的协议实现 */
  supported: boolean;
  /** §0 生产地址（8443 规避未备案域名 80/443 拦截）。 */
  defaultUrl: string;
}

export const AGENT_TYPES: Record<AgentTypeId, AgentTypeInfo> = {
  codex: {
    wireValue: "codex",
    displayName: "Codex",
    badgeLetter: "C",
    badgeColor: "#10A37F",
    supported: true,
    defaultUrl: "wss://codex.waibozishu.com:8443",
  },
  kimi: {
    wireValue: "kimi",
    displayName: "Kimi Code",
    badgeLetter: "K",
    badgeColor: "#3E63DD",
    supported: true,
    defaultUrl: "https://kimi.waibozishu.com:8443",
  },
  claude: {
    wireValue: "claude",
    displayName: "Claude Code",
    badgeLetter: "A",
    badgeColor: "#D97757",
    supported: true,
    defaultUrl: "https://claude.waibozishu.com:8443",
  },
  opencode: {
    wireValue: "opencode",
    displayName: "OpenCode",
    badgeLetter: "O",
    badgeColor: "#8B5CF6",
    supported: false,
    defaultUrl: "",
  },
};

export const AGENT_TYPE_LIST: AgentTypeInfo[] = [
  AGENT_TYPES.codex,
  AGENT_TYPES.kimi,
  AGENT_TYPES.claude,
  AGENT_TYPES.opencode,
];

export function agentTypeFromWireValue(value: string): AgentTypeInfo {
  return AGENT_TYPES[value as AgentTypeId] ?? AGENT_TYPES.codex;
}

/** 一个 Agent 服务器配置。会话（Thread）归属某个 profile，列表聚合展示时凭 [id] 找到对应的连接。 */
export interface AgentProfile {
  id: string;
  /** 用户命名；留空时展示用类型显示名 */
  name: string;
  type: AgentTypeId;
  serverUrl: string;
  token: string;
  /** Kimi/Claude 建会话时的服务器工作目录（绝对路径）。 */
  defaultCwd: string;
  enabled: boolean;
}

export function profileDisplayName(profile: AgentProfile): string {
  return profile.name.trim() || AGENT_TYPES[profile.type].displayName;
}

/** 连接三元组：任一变化都需要重建 repository。 */
export function profileConnectionKey(profile: AgentProfile): string {
  return `${profile.type}|${profile.serverUrl}|${profile.token}|${profile.defaultCwd}`;
}

/** 跨服务器 threadId 可能冲突；列表展示与导航都以 composite key 为准。 */
export function threadCompositeKey(profileId: string, threadId: string): string {
  return `${profileId}:${threadId}`;
}

/* ---------------- App 级配置（对应 Android AppSettings；模型是会话级设置，不放这里） ---------------- */

export interface AppSettings {
  asrUrl: string;
  asrAppKey: string;
  asrAccessKey: string;
  asrResourceId: string;
  /** 是否启用 TTS：启用后 agent 的回答会流式语音播报（仅回答正文，工具等不读）。 */
  ttsEnabled: boolean;
  /** 豆包语音合成大模型 V3 双向流式（新版控制台鉴权：X-Api-Key）。 */
  ttsUrl: string;
  ttsApiKey: string;
  ttsResourceId: string;
  ttsSpeaker: string;
  /** 语速，取值 [-50, 100]，0 为原速。 */
  ttsSpeechRate: number;
}

export const DEFAULT_SETTINGS: AppSettings = {
  /** 文档推荐的双向流式优化接口，实时返回识别结果。 */
  asrUrl: "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async",
  asrAppKey: "",
  asrAccessKey: "",
  asrResourceId: "volc.seedasr.sauc.duration",
  ttsEnabled: false,
  /** 文本流式输入、音频流式输出的双向接口，适合直接对接 LLM 的流式回答。 */
  ttsUrl: "wss://openspeech.bytedance.com/api/v3/tts/bidirection",
  ttsApiKey: "",
  /** 豆包语音合成模型 2.0；1.0 音色（mars/moon 系列）需换成 seed-tts-1.0(-concurr)。 */
  ttsResourceId: "seed-tts-2.0",
  /** 2.0 音色为 uranus 系列；与资源 ID 不匹配会被服务端拒绝合成。 */
  ttsSpeaker: "zh_female_shuangkuaisisi_uranus_bigtts",
  ttsSpeechRate: 0,
};
