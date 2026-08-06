import { Router, type Request, type Response } from "express";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { RepositoryRegistry } from "../src/protocol/registry";
import {
  AgentProfile,
  ApprovalDecision,
  AgentTypeId,
  Thread,
  profileDisplayName,
} from "../src/types";
import { parseChatAction } from "../src/slashCommands";

/**
 * 业务 API：把 web 的完整能力（多 Agent 会话、发消息、审批、斜杠命令）暴露为 REST，
 * 供 CLI / 脚本 / 未来的开放接口使用。服务端持有自己的 RepositoryRegistry 与
 * 配置文件（~/.codex-web/profiles.json）——profile 配置以服务端文件为权威，
 * web 前端通过 PUT /api/profiles 同步。
 *
 * 鉴权：设置 API_TOKEN 环境变量后，本组路由需要 Authorization: Bearer <API_TOKEN>。
 */

const CONFIG_DIR = path.join(os.homedir(), ".codex-web");
const PROFILES_FILE = path.join(CONFIG_DIR, "profiles.json");

const apiBase = `http://127.0.0.1:${process.env.PORT ?? "3000"}`;

/* ---------------- 配置（文件存储，服务端权威） ---------------- */

function loadProfiles(): AgentProfile[] {
  try {
    const raw = JSON.parse(fs.readFileSync(PROFILES_FILE, "utf8")) as { profiles?: AgentProfile[] };
    return Array.isArray(raw.profiles) ? raw.profiles : [];
  } catch {
    return [];
  }
}

function saveProfiles(profiles: AgentProfile[]) {
  fs.mkdirSync(CONFIG_DIR, { recursive: true });
  fs.writeFileSync(PROFILES_FILE, JSON.stringify({ profiles }, null, 2), { mode: 0o600 });
}

const registry = new RepositoryRegistry(
  { getProfiles: () => loadProfiles() },
  { apiBase },
);

/* ---------------- 待审批收集（事件驱动 → CLI 轮询） ---------------- */

interface PendingApproval {
  requestId: number;
  profileId: string;
  profileName: string;
  agentType: AgentTypeId;
  threadId: string;
  turnId: string;
  command: string;
  cwd: string;
  reason: string;
  receivedAt: number;
}

const pendingApprovals = new Map<number, PendingApproval>();

registry.subscribeAll(({ profileId, event }) => {
  if (event.type === "approvalRequest") {
    const profile = loadProfiles().find((p) => p.id === profileId);
    pendingApprovals.set(event.requestId, {
      requestId: event.requestId,
      profileId,
      profileName: profile ? profileDisplayName(profile) : profileId,
      agentType: profile?.type ?? "codex",
      threadId: event.threadId,
      turnId: event.turnId,
      command: event.command,
      cwd: event.cwd,
      reason: event.reason,
      receivedAt: Date.now(),
    });
    // 防止无应答审批无限堆积（超过 100 条丢最旧的）
    while (pendingApprovals.size > 100) {
      const oldest = [...pendingApprovals.keys()].sort((a, b) => (pendingApprovals.get(a)?.receivedAt ?? 0) - (pendingApprovals.get(b)?.receivedAt ?? 0))[0];
      pendingApprovals.delete(oldest);
    }
    return;
  }
  // 活跃轮次跟踪：CLI 轮询用（claude 的 readThread 不包含进行中的 turn，无法按 turnId 查）
  if (event.type === "turnStarted") {
    activeTurns.set(event.threadId, { threadId: event.threadId, turnId: event.turnId, status: "inProgress", error: undefined, doneAt: 0 });
  } else if (event.type === "turnCompleted") {
    const t = activeTurns.get(event.threadId);
    if (t) {
      t.status = event.status;
      t.error = event.error;
      t.doneAt = Date.now();
    }
  }
});

/** 活跃轮次表：threadId → 最近一轮状态（事件驱动；CLI 轮询接口读它）。 */
const activeTurns = new Map<string, { threadId: string; turnId: string; status: string; error?: string; doneAt: number }>();

/* ---------------- 工具函数 ---------------- */

/** profile 按 id / 名称（不区分大小写）解析；找不到抛错。 */
function resolveProfile(profileRef: string): AgentProfile {
  const profiles = loadProfiles().filter((p) => p.enabled);
  const key = profileRef.trim().toLowerCase();
  const found =
    profiles.find((p) => p.id === profileRef) ??
    profiles.find((p) => p.name.trim().toLowerCase() === key) ??
    profiles.find((p) => profileDisplayName(p).toLowerCase() === key);
  if (!found) {
    const names = profiles.map((p) => `${profileDisplayName(p)}(${p.type})`).join(", ");
    throw new Error(`找不到启用的 Agent 配置「${profileRef}」；可用：${names || "（无配置，请先配置）"}`);
  }
  return found;
}

function error(res: Response, status: number, message: string) {
  res.status(status).json({ error: message });
}

/** 异步路由包装：异常统一 500。 */
function wrap(fn: (req: Request, res: Response) => Promise<void>) {
  return (req: Request, res: Response) => {
    fn(req, res).catch((e: unknown) => {
      const message = e instanceof Error ? e.message : String(e);
      error(res, 500, message);
    });
  };
}

/** 新会话创建后写入 model/effort（与 web 端一致；claude 的「暂不支持」忽略）。 */
async function configureThread(repo: ReturnType<RepositoryRegistry["repositoryFor"]>, thread: Thread, model?: string, effort?: string) {
  try {
    await repo.updateThreadSettings(thread.id, model || thread.model || undefined, effort || undefined);
  } catch (e) {
    if (!(e instanceof Error) || !e.message.includes("暂不支持")) throw e;
  }
}

/** 不指定 profile 时按 threadId 遍历启用的配置定位（CLI 便利：不用记 profile）。 */
async function locateThread(threadId: string): Promise<{ profile: AgentProfile; repo: ReturnType<RepositoryRegistry["repositoryFor"]> }> {
  const profiles = loadProfiles().filter((p) => p.enabled);
  let firstError: unknown = null;
  for (const profile of profiles) {
    const repo = registry.repositoryFor(profile.id);
    try {
      await repo.readThread(threadId, false);
      return { profile, repo };
    } catch (e) {
      firstError ??= e;
    }
  }
  throw firstError instanceof Error ? firstError : new Error(`找不到会话 ${threadId}`);
}

/* ---------------- 路由 ---------------- */

export function createApiRouter(): Router {
  const router = Router();

  // API_TOKEN 可选鉴权
  const apiToken = process.env.API_TOKEN;
  if (apiToken) {
    router.use((req, res, next) => {
      const auth = req.headers.authorization;
      if (auth === `Bearer ${apiToken}`) return next();
      res.status(401).json({ error: "需要 API_TOKEN" });
    });
  }

  /** 配置：服务端文件为权威；web 前端 / CLI 都通过这里读写。 */
  router.get("/profiles", (_req, res) => {
    res.json({ profiles: loadProfiles() });
  });
  router.put("/profiles", (req, res) => {
    const profiles = (req.body as { profiles?: unknown })?.profiles;
    if (!Array.isArray(profiles)) return error(res, 400, "body 需要 {profiles: [...]}");
    saveProfiles(profiles as AgentProfile[]);
    registry.reconcile();
    res.json({ ok: true });
  });

  /** 聚合会话列表（全部启用配置）。 */
  router.get("/sessions", wrap(async (_req, res) => {
    const { entries, errors } = await registry.listAllThreads();
    res.json({
      items: entries.map(({ profile, thread }) => ({
        profileId: profile.id,
        profileName: profileDisplayName(profile),
        agentType: profile.type,
        thread,
      })),
      errors: Object.fromEntries(errors),
    });
  }));

  /** 新建会话。body: {profile?, model?, effort?} */
  router.post("/sessions", wrap(async (req, res) => {
    const body = (req.body ?? {}) as { profile?: string; model?: string; effort?: string };
    const profile = body.profile ? resolveProfile(body.profile) : loadProfiles().find((p) => p.enabled);
    if (!profile) return error(res, 400, "没有启用的 Agent 配置，请先配置");
    const repo = registry.repositoryFor(profile.id);
    const thread = await repo.startThread(body.model?.trim() || undefined);
    await configureThread(repo, thread, body.model?.trim(), body.effort?.trim());
    res.json({ profileId: profile.id, profileName: profileDisplayName(profile), agentType: profile.type, thread });
  }));

  /** 会话详情（含 turns）。 */
  router.get("/sessions/:profile/:threadId", wrap(async (req, res) => {
    const profile = resolveProfile(req.params.profile);
    const repo = registry.repositoryFor(profile.id);
    const thread = await repo.readThread(req.params.threadId, true);
    res.json({ profileId: profile.id, profileName: profileDisplayName(profile), agentType: profile.type, thread });
  }));

  /** 不指定 profile 的会话详情（自动定位）。 */
  router.get("/sessions/:threadId", wrap(async (req, res) => {
    const { profile, repo } = await locateThread(req.params.threadId);
    const thread = await repo.readThread(req.params.threadId, true);
    res.json({ profileId: profile.id, profileName: profileDisplayName(profile), agentType: profile.type, thread });
  }));

  /**
   * 发消息 / 斜杠命令。body: {text}。
   * 返回：{kind:"turn", turnId}（普通消息/审查/压缩等已开启一轮）
   *      {kind:"forked", threadId}（/fork 产生新会话）
   *      {kind:"ok"}（/compact、!shell 等立即返回的动作）
   */
  router.post("/sessions/:profile/:threadId/messages", wrap(async (req, res) => {
    const profile = resolveProfile(req.params.profile);
    const threadId = req.params.threadId;
    const repo = registry.repositoryFor(profile.id);
    const text = String((req.body as { text?: unknown })?.text ?? "").trim();
    if (!text) return error(res, 400, "text 不能为空");

    const action = parseChatAction(text);
    if (!action) {
      const turn = await repo.startTurn(threadId, [{ type: "text", text }]);
      res.json({ kind: "turn", turnId: turn.id, status: turn.status });
      return;
    }

    // Claude：/compact 等交互命令透传给 CLI 执行（stream-json 模式 CLI 会拦截斜杠命令）
    if (profile.type === "claude" && action.kind === "compact") {
      const turn = await repo.startTurn(threadId, [{ type: "text", text }]);
      res.json({ kind: "turn", turnId: turn.id, status: turn.status });
      return;
    }

    switch (action.kind) {
      case "compact": {
        await repo.startCompact(threadId);
        res.json({ kind: "ok" });
        return;
      }
      case "fork": {
        const forked = await repo.forkThread(threadId);
        res.json({ kind: "forked", threadId: forked.id });
        return;
      }
      case "undo": {
        const thread = await repo.rollbackThread(threadId, action.numTurns);
        res.json({ kind: "ok", thread });
        return;
      }
      case "review": {
        const result = await repo.startReview(threadId, action.target);
        res.json({ kind: "turn", turnId: result.turn.id, reviewThreadId: result.reviewThreadId, status: result.turn.status });
        return;
      }
      case "reviewNeedTarget":
        return error(res, 400, "/review 需要目标：/review uncommittedChanges | baseBranch <分支> | commit <sha> | custom <指令>");
      case "shell": {
        await repo.shellCommand(threadId, action.command);
        res.json({ kind: "ok" });
        return;
      }
    }
  }));

  /** 不指定 profile 的斜杠命令/发消息（自动定位）。 */
  router.post("/sessions/:threadId/messages", wrap(async (req, res) => {
    const { profile, repo } = await locateThread(req.params.threadId);
    const text = String((req.body as { text?: unknown })?.text ?? "").trim();
    if (!text) return error(res, 400, "text 不能为空");
    const action = parseChatAction(text);
    if (!action || (profile.type === "claude" && action.kind === "compact")) {
      const turn = await repo.startTurn(req.params.threadId, [{ type: "text", text }]);
      res.json({ kind: "turn", turnId: turn.id, status: turn.status, profileId: profile.id, profileName: profileDisplayName(profile) });
      return;
    }
    switch (action!.kind) {
      case "compact":
        await repo.startCompact(req.params.threadId);
        res.json({ kind: "ok", profileId: profile.id });
        return;
      case "fork": {
        const forked = await repo.forkThread(req.params.threadId);
        res.json({ kind: "forked", threadId: forked.id, profileId: profile.id });
        return;
      }
      case "undo": {
        const thread = await repo.rollbackThread(req.params.threadId, action!.numTurns);
        res.json({ kind: "ok", thread, profileId: profile.id });
        return;
      }
      case "review": {
        const result = await repo.startReview(req.params.threadId, action!.target);
        res.json({ kind: "turn", turnId: result.turn.id, reviewThreadId: result.reviewThreadId, status: result.turn.status, profileId: profile.id });
        return;
      }
      case "reviewNeedTarget":
        return error(res, 400, "/review 需要目标：/review uncommittedChanges | baseBranch <分支> | commit <sha> | custom <指令>");
      case "shell": {
        await repo.shellCommand(req.params.threadId, action!.command);
        res.json({ kind: "ok", profileId: profile.id });
        return;
      }
    }
  }));

  /** 中断当前轮次。 */
  router.post("/sessions/:profile/:threadId/interrupt", wrap(async (req, res) => {
    const profile = resolveProfile(req.params.profile);
    const repo = registry.repositoryFor(profile.id);
    // 轮询场景拿不到 turnId：服务端对 codex/kimi 支持空 turnId 中断（会话级），claude 同样。
    await repo.interruptTurn(req.params.threadId, "");
    res.json({ ok: true });
  }));

  /** 不指定 profile 的中断（自动定位）。 */
  router.post("/sessions/:threadId/interrupt", wrap(async (req, res) => {
    const { repo } = await locateThread(req.params.threadId);
    await repo.interruptTurn(req.params.threadId, "");
    res.json({ ok: true });
  }));

  /** 删除会话。 */
  router.delete("/sessions/:profile/:threadId", wrap(async (req, res) => {
    const profile = resolveProfile(req.params.profile);
    const repo = registry.repositoryFor(profile.id);
    await repo.deleteThread(req.params.threadId);
    res.json({ ok: true });
  }));

  /** 不指定 profile 的删除（自动定位）。 */
  router.delete("/sessions/:threadId", wrap(async (req, res) => {
    const { repo } = await locateThread(req.params.threadId);
    await repo.deleteThread(req.params.threadId);
    res.json({ ok: true });
  }));

  /** 轮次状态（CLI 轮询用；claude 的 readThread 不含进行中 turn，不能按 turnId 查会话）。 */
  router.get("/turns/:threadId", wrap(async (req, res) => {
    const t = activeTurns.get(req.params.threadId);
    if (!t) {
      res.json({ status: "unknown" });
      return;
    }
    res.json({ status: t.status, turnId: t.turnId, error: t.error });
  }));

  /** 待审批列表。 */
  router.get("/approvals/pending", (_req, res) => {
    res.json({ items: [...pendingApprovals.values()].sort((a, b) => a.receivedAt - b.receivedAt) });
  });

  /** 审批应答。body: {decision: accept|acceptForSession|decline|cancel} */
  router.post("/approvals/:requestId", wrap(async (req, res) => {
    const requestId = Number(req.params.requestId);
    const approval = pendingApprovals.get(requestId);
    if (!approval) return error(res, 404, `没有待审批的请求 ${requestId}`);
    const decision = String((req.body as { decision?: unknown })?.decision ?? "") as ApprovalDecision;
    if (!["accept", "acceptForSession", "decline", "cancel"].includes(decision)) {
      return error(res, 400, "decision 必须是 accept / acceptForSession / decline / cancel");
    }
    const repo = registry.repositoryFor(approval.profileId);
    await repo.respondApproval(requestId, decision);
    pendingApprovals.delete(requestId);
    res.json({ ok: true });
  }));

  return router;
}
