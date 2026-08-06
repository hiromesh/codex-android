import {
  ApprovalDecision,
  Content,
  ModelInfo,
  ReviewStartResult,
  ReviewTarget,
  Thread,
  ThreadPage,
  Turn,
} from "../types";
import { EventEmitter } from "./events";

/**
 * Agent 后端仓库接口（对应 Android CodexRepository）。方法名刻意对应各协议的
 * RPC method / REST 端点；UI / Store 层只依赖此接口。这是 per-agent 连接抽象：
 * 每个 AgentProfile 由 Registry 创建一个实例；codex/kimi/claude 各自实现同一接口，
 * 便于后续把协议层直接暴露为 HTTP API（list sessions / get session 等）。
 */
export interface Repository {
  /** 全局事件流：单连接 + reader 分发，所有事件都从这里出来 */
  readonly events: EventEmitter;

  /** 释放连接与协程；profile 被删除/修改后由 Registry 调用，实例此后不可再用。 */
  close(): void;

  /** §3.1 initialize + initialized（codex）；其余协议为连接就绪保证 */
  initialize(): Promise<void>;

  /** §3.4 thread/list */
  listThreads(cursor?: string, limit?: number): Promise<ThreadPage>;

  /** §3.2 thread/start，可选 model */
  startThread(model?: string): Promise<Thread>;

  /** §3.3 thread/resume */
  resumeThread(threadId: string, model?: string): Promise<Thread>;

  /** §3.5 thread/read，includeTurns=true 返回每轮 items 用于重建聊天界面 */
  readThread(threadId: string, includeTurns?: boolean): Promise<Thread>;

  /** §3.10 thread/archive：归档会话（软删除，列表默认隐藏，可 unarchive 恢复） */
  archiveThread(threadId: string): Promise<void>;

  /** §3.10 thread/delete：彻底删除会话（不可恢复） */
  deleteThread(threadId: string): Promise<void>;

  /** §3.6 turn/start，发消息 */
  startTurn(threadId: string, input: Content[]): Promise<Turn>;

  /** §3.7 turn/interrupt */
  interruptTurn(threadId: string, turnId: string): Promise<void>;

  /** §6 审批应答 */
  respondApproval(requestId: number, decision: ApprovalDecision): Promise<void>;

  /** §3.8 model/list */
  listModels(): Promise<ModelInfo[]>;

  /** §3.9② thread/settings/update：会话中途切换模型/推理档位 */
  updateThreadSettings(threadId: string, model?: string, effort?: string): Promise<void>;

  // ── 动作类接口（docs/CODEX_ACTIONS_API.md）：斜杠命令对应物，不走 turn/start 发消息 ──

  /** thread/compact/start：压缩上下文（/compact）；立即返回，压缩在后台进行 */
  startCompact(threadId: string): Promise<void>;

  /** review/start：发起代码审查（/review） */
  startReview(threadId: string, target: ReviewTarget, delivery?: string): Promise<ReviewStartResult>;

  /** thread/fork：分叉会话（/fork）；可选 lastTurnId 截断到某一轮（含） */
  forkThread(threadId: string, lastTurnId?: string): Promise<Thread>;

  /** thread/rollback：砍掉末尾 N 轮（/undo）；只删对话历史，不回滚文件改动 */
  rollbackThread(threadId: string, numTurns?: number): Promise<Thread>;

  /** thread/shellCommand：在会话上下文跑 shell（!cmd）；不受沙箱限制 */
  shellCommand(threadId: string, command: string): Promise<void>;
}
