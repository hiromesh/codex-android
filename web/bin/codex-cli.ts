#!/usr/bin/env node
/**
 * nex — 多 Agent（Codex / Kimi / Claude Code）命令行客户端。
 *
 * 调用 web 服务的业务 API（server/api.ts）。Node ≥24 可直接执行本文件（原生 TS）。
 *
 * 用法：
 *   codex ls [--profile <名>]                列出会话
 *   codex new [--profile <名>] [--model <m>] [--effort <e>]
 *   codex open <threadId>                    查看会话历史
 *   codex send <threadId> <文本>             发消息，轮询到完成并流式打印
 *   codex cmd  <threadId> <斜杠命令>          执行 /compact /fork /undo /review 等
 *   codex stop <threadId>                    中断当前轮次
 *   codex rm   <threadId>                    删除会话
 *   codex approvals                          列出待审批
 *   codex approve <requestId> <decision>     应答审批（accept/acceptForSession/decline/cancel）
 *   codex profiles                           列出 Agent 配置
 *   codex profiles add --type <codex|kimi|claude> --url <地址> --token <token>
 *                       [--name <名>] [--cwd <服务器路径>] [--disable]
 *   codex profiles rm <名称或id>
 *
 * 环境变量：
 *   CODEX_WEB_URL      服务地址，默认 http://localhost:3000
 *   CODEX_WEB_TOKEN    服务端 API_TOKEN（若有）
 */

import { readFileSync, writeFileSync, mkdirSync, chmodSync, existsSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

const API = process.env.CODEX_WEB_URL || "http://localhost:3000";
const TOKEN = process.env.CODEX_WEB_TOKEN || (() => {
  try { return readFileSync(join(homedir(), ".codex-web", "token"), "utf8").trim(); } catch { return ""; }
})();

type ThreadItem =
  | { kind: "userMessage"; id: string; content: { type: string; text: string }[] }
  | { kind: "agentMessage"; id: string; text: string }
  | { kind: "commandExecution"; id: string; command: string; cwd: string; status: string; output: string; exitCode?: number }
  | { kind: "fileChange"; id: string; changes: string[]; status: string }
  | { kind: "plan"; id: string; text: string }
  | { kind: "webSearch"; id: string; query: string; status: string }
  | { kind: "reasoning"; id: string; summary: string[] }
  | { kind: "contextCompaction"; id: string; status: string };
interface Turn { id: string; status: string; items: ThreadItem[]; error?: string }
interface Thread { id: string; preview: string; name?: string; status: { type: string }; updatedAt: number; turns: Turn[] }
interface ListEntry { profileId: string; profileName: string; agentType: string; thread: Thread }
interface Profile { id: string; name: string; type: string; serverUrl: string; token: string; defaultCwd: string; enabled: boolean }

/* ---------------- 会话 id 解析 ---------------- */

/** 列表展示用短 id：UUID 取前 8 位；kimi 的 session_ 前缀保留（否则所有 kimi 会话前缀相同）。 */
function shortId(id: string): string {
  if (id.startsWith("session_")) return "session_" + id.slice("session_".length, "session_".length + 8);
  return id.slice(0, 8);
}

/** 短 id（不足完整长度时）先查列表定位完整 id；支持 ls 里显示的前缀。 */
async function resolveThreadId(input: string): Promise<string> {
  if (input.length >= 36) return input;
  const data = (await api("/api/sessions")) as { items: ListEntry[] };
  const matches = data.items.filter((i) => i.thread.id.startsWith(input));
  if (matches.length === 0) throw new Error(`找不到以「${input}」开头的会话`);
  if (matches.length > 1) {
    throw new Error(`「${input}」匹配多个会话：\n` + matches.map((m) => `  ${m.thread.id}  ${(m.thread.name || m.thread.preview || "").slice(0, 24)}`).join("\n"));
  }
  return matches[0].thread.id;
}

/* ---------------- HTTP ---------------- */

async function api(path: string, options: { method?: string; body?: unknown } = {}): Promise<unknown> {
  const headers: Record<string, string> = {};
  if (TOKEN) headers.Authorization = `Bearer ${TOKEN}`;
  if (options.body !== undefined) headers["Content-Type"] = "application/json";
  const res = await fetch(`${API}${path}`, {
    method: options.method ?? "GET",
    headers,
    body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
  });
  const text = await res.text();
  let json: unknown = null;
  try { json = text ? JSON.parse(text) : null; } catch { /* 非 JSON */ }
  if (!res.ok) {
    const message = json && typeof json === "object" && "error" in json
      ? String((json as Record<string, unknown>).error)
      : `HTTP ${res.status} ${text.slice(0, 200)}`;
    throw new Error(message);
  }
  return json;
}

/* ---------------- 渲染 ---------------- */

function itemSummary(item: ThreadItem): string {
  switch (item.kind) {
    case "userMessage":
      return item.content.filter((c) => c.type === "text").map((c) => c.text).join("");
    case "agentMessage":
      return item.text;
    case "commandExecution": {
      const exit = item.exitCode ?? 0;
      return `[cmd ${exit === 0 ? "ok" : `exit ${exit}`}] ${item.command}`;
    }
    case "fileChange":
      return `[文件改动${item.changes.length ? ` ×${item.changes.length}` : ""}]`;
    case "plan":
      return `[计划] ${item.text}`;
    case "webSearch":
      return `[联网搜索] ${item.query}`;
    case "reasoning":
      return `[思考]`;
    case "contextCompaction":
      return item.status === "completed" ? "[上下文已压缩]" : "[正在压缩上下文…]";
  }
}

/** open 只显示 你/回答正文；工具调用与思考过程不展示。 */
function printThread(thread: Thread, profileName: string) {
  for (const turn of thread.turns) {
    for (const item of turn.items) {
      if (item.kind === "userMessage") {
        const text = item.content.filter((c) => c.type === "text").map((c) => c.text).join("");
        if (text) console.log(`你: ${text}`);
      } else if (item.kind === "agentMessage" && item.text.trim()) {
        console.log(item.text);
      }
    }
  }
  const last = thread.turns.at(-1);
  if (last?.status === "failed") console.error(`轮次失败: ${last.error ?? "未知错误"}`);
}

/** 增量打印一轮：按 itemId 记录已打印的文本长度，只输出新增部分。silent 用于预热（只标记不输出）。 */
/** 增量打印一轮：只输出回答正文（agentMessage）的增量，工具/思考/用户消息都不展示。 */
function makeIncrementalPrinter() {
  const printed = new Map<string, number>();
  return (turn: Turn, silent = false) => {
    for (const item of turn.items) {
      if (item.kind !== "agentMessage" || !item.text) continue;
      const prev = printed.get(item.id) ?? 0;
      if (item.text.length > prev) {
        if (!silent) process.stdout.write(item.text.slice(prev));
        printed.set(item.id, item.text.length);
      }
    }
  };
}

/* ---------------- 命令 ---------------- */

async function cmdLs(filterProfile?: string) {
  const data = (await api("/api/sessions")) as { items: ListEntry[]; errors?: Record<string, string> };
  const items = filterProfile
    ? data.items.filter((i) => i.profileName.toLowerCase().includes(filterProfile.toLowerCase()))
    : data.items;
  if (items.length === 0) {
    console.log("（没有会话）");
    if (data.errors && Object.keys(data.errors).length > 0) {
      for (const [k, v] of Object.entries(data.errors)) console.error(`[${k}] 连接失败: ${v}`);
    }
    return;
  }
  const rows = items.map((e) => {
    const t = e.thread;
    const title = (t.name || t.preview || "Untitled").slice(0, 34);
    const working = ["busy", "inprogress", "working", "active"].includes(t.status.type.toLowerCase());
    const time = relativeTime(t.updatedAt);
    const badge = e.agentType === "codex" ? "[X]" : `[${e.agentType[0].toUpperCase()}]`;
    return { id: shortId(t.id), agent: badge, title, state: working ? "●" : " ", time };
  });
  const w = { id: 20, agent: 6, title: 34, time: 10 };
  console.log(`${"ID".padEnd(w.id)}${"Agent".padEnd(w.agent)}${"标题".padEnd(w.title)}${"时间".padEnd(w.time)} 状态`);
  for (const r of rows) {
    console.log(`${r.id.padEnd(w.id)}${r.agent.padEnd(w.agent)}${r.title.padEnd(w.title)}${r.time.padEnd(w.time)} ${r.state}`);
  }
  if (filterProfile && data.errors) {
    for (const [k, v] of Object.entries(data.errors)) console.error(`[${k}] 连接失败: ${v}`);
  }
}

async function cmdNew(profile?: string, model?: string, effort?: string) {
  const data = (await api("/api/sessions", { method: "POST", body: { profile, model, effort } })) as { thread: Thread; profileName: string; agentType: string };
  console.log(`已创建会话 ${data.thread.id}（${data.profileName}）`);
}

async function cmdOpen(threadId: string) {
  const data = (await api(`/api/sessions/${threadId}`)) as { thread: Thread; profileName: string };
  printThread(data.thread, data.profileName);
}

async function cmdSend(threadId: string, text: string, opts: { noWait?: boolean; timeoutSec?: number }) {
  const data = (await api(`/api/sessions/${threadId}/messages`, { method: "POST", body: { text } })) as {
    kind: string; turnId?: string; threadId?: string; status?: string;
  };
  if (data.kind === "forked") {
    console.log(`已分叉，新会话: ${data.threadId}`);
    return;
  }
  if (data.kind !== "turn" || !data.turnId) {
    console.log("命令已执行");
    return;
  }
  const turnId = data.turnId;
  if (opts.noWait) {
    console.log(`已发送，轮次 ${turnId}（不等待）`);
    return;
  }
  const printer = makeIncrementalPrinter();
  // 预热：把发送前的全部内容标记为已打印（静默），只输出本次发送产生的新内容
  try {
    const pre = (await api(`/api/sessions/${threadId}`)) as { thread: Thread };
    const preTurn = pre.thread.turns.at(-1);
    if (preTurn) printer(preTurn, true);
  } catch { /* 预热失败忽略，最多多打印一遍历史 */ }
  const deadline = Date.now() + (opts.timeoutSec ?? 600) * 1000;
  let printedSomething = false;
  while (Date.now() < deadline) {
    // 打印增量内容（claude 的进行中内容实时落盘 jsonl，readThread 能拿到）
    try {
      const detail = (await api(`/api/sessions/${threadId}`)) as { thread: Thread };
      const lastTurn = detail.thread.turns.at(-1);
      if (lastTurn) {
        printer(lastTurn);
        printedSomething = true;
      }
    } catch { /* 快照读取失败不阻断状态轮询 */ }
    // 轮次状态（事件驱动；claude 的 turn 不在会话快照里，必须走这个接口）
    const turnState = (await api(`/api/turns/${threadId}`)) as { status?: string; error?: string };
    if (turnState.status && turnState.status !== "inProgress") {
      if (turnState.status === "failed") {
        console.error(`\n轮次失败: ${turnState.error ?? "未知错误"}`);
        process.exit(1);
      }
      if (turnState.status === "interrupted") console.error("\n（已中断）");
      if (!printedSomething) console.log(); // 无内容也留一个换行
      process.exit(0);
    }
    if (turnState.status === "unknown") {
      // 没有活跃轮次：POST 已成功即消息已入队，视为完成
      if (!printedSomething) console.log();
      process.exit(0);
    }
    await sleep(1000);
  }
  console.error(`\n等待超时（${opts.timeoutSec ?? 600}s）`);
  process.exit(1);
}

async function cmdCmd(threadId: string, command: string) {
  // 斜杠命令统一走 messages 接口（服务端按 agent 类型分发）
  await cmdSend(threadId, command, { noWait: false });
}

async function cmdStop(threadId: string) {
  await api(`/api/sessions/${threadId}/interrupt`, { method: "POST", body: {} });
  console.log("已请求中断");
}

async function cmdRm(threadId: string) {
  await api(`/api/sessions/${threadId}`, { method: "DELETE" });
  console.log("已删除");
}

async function cmdApprovals() {
  const data = (await api("/api/approvals/pending")) as {
    items: { requestId: number; profileName: string; threadId: string; command: string; cwd: string; reason: string }[];
  };
  if (data.items.length === 0) {
    console.log("（没有待审批）");
    return;
  }
  for (const a of data.items) {
    console.log(`#${a.requestId} [${a.profileName}] ${a.command}`);
    if (a.cwd) console.log(`    cwd: ${a.cwd}`);
    if (a.reason) console.log(`    ${a.reason}`);
  }
}

async function cmdApprove(requestId: string, decision: string) {
  if (!["accept", "acceptForSession", "decline", "cancel"].includes(decision)) {
    console.error("decision 必须是 accept / acceptForSession / decline / cancel");
    process.exit(2);
  }
  await api(`/api/approvals/${requestId}`, { method: "POST", body: { decision } });
  console.log("已应答");
}

async function cmdProfiles() {
  const data = (await api("/api/profiles")) as { profiles: Profile[] };
  if (data.profiles.length === 0) {
    console.log("（没有配置，用 codex profiles add 添加，或在 web 设置里添加）");
    return;
  }
  for (const p of data.profiles) {
    console.log(`${p.enabled ? "✓" : "✗"} ${p.name || p.type}  [${p.type}]  ${p.serverUrl}${p.defaultCwd ? `  cwd: ${p.defaultCwd}` : ""}`);
  }
}

async function cmdProfilesAdd(args: Record<string, string>) {
  const type = args.type;
  const url = args.url;
  const token = args.token;
  if (!type || !url || !token) {
    console.error("需要 --type <codex|kimi|claude> --url <地址> --token <token>");
    process.exit(2);
  }
  if (!["codex", "kimi", "claude"].includes(type)) {
    console.error("type 必须是 codex / kimi / claude");
    process.exit(2);
  }
  const data = (await api("/api/profiles")) as { profiles: Profile[] };
  const profiles = data.profiles;
  profiles.push({
    id: crypto.randomUUID(),
    name: args.name ?? "",
    type,
    serverUrl: url,
    token,
    defaultCwd: args.cwd ?? "",
    enabled: args.disable ? false : true,
  });
  await api("/api/profiles", { method: "PUT", body: { profiles } });
  console.log("已添加");
}

async function cmdProfilesRm(ref: string) {
  const data = (await api("/api/profiles")) as { profiles: Profile[] };
  const key = ref.toLowerCase();
  const profiles = data.profiles.filter(
    (p) => p.id !== ref && p.name.toLowerCase() !== key && p.type.toLowerCase() !== key,
  );
  if (profiles.length === data.profiles.length) {
    console.error(`找不到配置「${ref}」`);
    process.exit(1);
  }
  await api("/api/profiles", { method: "PUT", body: { profiles } });
  console.log("已删除");
}

/* ---------------- 入口 ---------------- */

const args = process.argv.slice(2);
const [sub, ...rest] = args;

function sleep(ms: number) {
  return new Promise((r) => setTimeout(r, ms));
}

function relativeTime(epochSeconds: number): string {
  const diffMs = Date.now() - epochSeconds * 1000;
  const minutes = Math.floor(diffMs / 60_000);
  if (minutes < 1) return "刚刚";
  if (minutes < 60) return `${minutes}分钟前`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}小时前`;
  return `${Math.floor(hours / 24)}天前`;
}

/** 解析 --flag value 与位置参数。 */
function parseArgs(argv: string[]): { flags: Record<string, string>; positional: string[] } {
  const flags: Record<string, string> = {};
  const positional: string[] = [];
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith("--")) {
      const key = a.slice(2);
      const next = argv[i + 1];
      if (next !== undefined && !next.startsWith("--")) {
        flags[key] = next;
        i++;
      } else {
        flags[key] = "true";
      }
    } else {
      positional.push(a);
    }
  }
  return { flags, positional };
}

async function main() {
  switch (sub) {
    case "ls": {
      const { flags } = parseArgs(rest);
      await cmdLs(flags.profile);
      return;
    }
    case "new": {
      const { flags } = parseArgs(rest);
      await cmdNew(flags.profile, flags.model, flags.effort);
      return;
    }
    case "open": {
      const [threadId] = rest;
      if (!threadId) return usage("open 需要会话 id");
      await cmdOpen(await resolveThreadId(threadId));
      return;
    }
    case "send": {
      const [threadId, ...textParts] = rest;
      const text = textParts.join(" ").trim();
      if (!threadId || !text) return usage("send 需要会话 id 和消息文本");
      await cmdSend(await resolveThreadId(threadId), text, { noWait: rest.includes("--no-wait"), timeoutSec: flagNum(rest, "timeout") });
      return;
    }
    case "cmd": {
      const [threadId, ...textParts] = rest;
      const text = textParts.join(" ").trim();
      if (!threadId || !text) return usage("cmd 需要会话 id 和命令（如 /compact、/fork）");
      await cmdCmd(await resolveThreadId(threadId), text);
      return;
    }
    case "stop": {
      const [threadId] = rest;
      if (!threadId) return usage("stop 需要会话 id");
      await cmdStop(await resolveThreadId(threadId));
      return;
    }
    case "rm": {
      const [threadId] = rest;
      if (!threadId) return usage("rm 需要会话 id");
      await cmdRm(await resolveThreadId(threadId));
      return;
    }
    case "approvals":
      await cmdApprovals();
      return;
    case "approve": {
      const [id, decision] = rest;
      if (!id || !decision) return usage("approve 需要 requestId 和 decision");
      await cmdApprove(id, decision);
      return;
    }
    case "profiles": {
      const action = rest[0];
      if (!action || action === "ls") {
        await cmdProfiles();
        return;
      }
      if (action === "add") {
        const { flags } = parseArgs(rest.slice(1));
        await cmdProfilesAdd(flags);
        return;
      }
      if (action === "rm") {
        const [, ref] = rest;
        if (!ref) return usage("profiles rm 需要名称或 id");
        await cmdProfilesRm(ref);
        return;
      }
      return usage("profiles 子命令：ls / add / rm");
    }
    case "help":
    case "-h":
    case "--help":
      printHelp();
      return;
    default:
      usage();
  }
}

function flagNum(argv: string[], name: string): number | undefined {
  const idx = argv.indexOf(`--${name}`);
  if (idx >= 0 && argv[idx + 1] !== undefined) {
    const v = Number(argv[idx + 1]);
    if (Number.isFinite(v)) return v;
  }
  return undefined;
}

/** 完整帮助：全部支持的操作与说明。 */
function printHelp() {
  console.log(
    `nex — 多 Agent 枢纽（Codex / Kimi / Claude Code 统一命令行）\n\n` +
      `服务: ${API}\n\n` +
      "用法: nex <命令> [参数]\n\n" +
      "会话管理:\n" +
      "  nex ls [--profile <名>]                 列出所有会话（跨全部 agent）\n" +
      "  nex new [--profile <名>] [--model <m>] [--effort <e>]\n" +
      "                                           新建会话（默认第一个启用的配置）\n" +
      "  nex open <id>                           查看会话历史\n" +
      "  nex rm <id>                             删除会话\n\n" +
      "对话:\n" +
      "  nex send <id> <文本>                    发消息，流式打印到完成\n" +
      "                                            --no-wait 不等待直接返回\n" +
      "                                            --timeout <秒> 改等待超时（默认 600）\n" +
      "  nex cmd <id> <斜杠命令>                 执行斜杠命令（见下方列表）\n" +
      "  nex stop <id>                           中断当前轮次\n\n" +
      "斜杠命令（通过 nex cmd <id> 传入，服务端按 agent 类型自动分发）:\n" +
      "  /compact          压缩上下文\n" +
      "  /fork             分叉会话（返回新会话 id）\n" +
      "  /undo [n]         撤销末尾 n 轮（默认 1）\n" +
      "  /review <目标>    代码审查（uncommittedChanges | baseBranch <分支> | commit <sha> | custom <指令>）\n" +
      "  /effort <档位>    切换推理档位（Claude：CLI 拦截执行）\n" +
      "  /model <模型>     切换模型（Claude：CLI 拦截执行）\n" +
      "  /goal <目标>      设定目标（Claude：CLI 拦截执行）\n" +
      "  !cmd              在会话上下文执行 shell（codex，不受沙箱限制）\n\n" +
      "审批:\n" +
      "  nex approvals                           列出待审批\n" +
      "  nex approve <requestId> <decision>      应答审批\n" +
      "                                            decision: accept / acceptForSession / decline / cancel\n\n" +
      "配置:\n" +
      "  nex profiles                            列出 Agent 配置\n" +
      "  nex profiles add --type <codex|kimi|claude> --url <地址> --token <token>\n" +
      "                       [--name <名>] [--cwd <服务器路径>] [--disable]\n" +
      "  nex profiles rm <名称或id>              删除配置\n\n" +
      "其他:\n" +
      "  nex help / nex --help / nex -h         显示本帮助\n\n" +
      "说明:\n" +
      "  - 会话 id 用 8 位前缀即可（nex ls 显示的 id 就是可直接用的前缀）\n" +
      "  - 服务地址: CODEX_WEB_URL（默认 http://localhost:3000）\n" +
      "  - 鉴权: CODEX_WEB_TOKEN 环境变量或 ~/.codex-web/token 文件\n",
  );
}

function usage(extra?: string) {
  if (extra) console.error(extra);
  console.error(`nex — 多 Agent 枢纽（服务: ${API}）。用 nex help 查看全部操作。`);
  process.exit(extra ? 2 : 0);
}

main().catch((e: unknown) => {
  console.error(e instanceof Error ? e.message : String(e));
  process.exit(1);
});
