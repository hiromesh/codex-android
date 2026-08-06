import { ReviewTarget } from "./types";

/** 输入框 `/` 弹出的可选命令（对应 docs/CODEX_ACTIONS_API.md）。 */
export interface SlashCommandSpec {
  trigger: string;
  title: string;
}

export const SLASH_COMMANDS: SlashCommandSpec[] = [
  { trigger: "/compact", title: "压缩上下文" },
  { trigger: "/review", title: "代码审查" },
  { trigger: "/fork", title: "分叉会话" },
  { trigger: "/undo", title: "撤销末轮" },
];

/**
 * 解析输入是否为动作命令。命中则不应再走 turn/start 发消息。
 * `!cmd` 走 shell；未知 `/xxx` 返回 null，仍按普通消息发送。
 */
export type ParsedChatAction =
  | { kind: "compact" }
  | { kind: "reviewNeedTarget" }
  | { kind: "review"; target: ReviewTarget }
  | { kind: "fork" }
  | { kind: "undo"; numTurns: number }
  | { kind: "shell"; command: string };

export function parseChatAction(raw: string): ParsedChatAction | null {
  const text = raw.trim();
  if (!text) return null;
  if (text.startsWith("!")) {
    const command = text.slice(1).trim();
    return command ? { kind: "shell", command } : null;
  }
  if (!text.startsWith("/")) return null;
  const parts = text.split(/\s+/, 2);
  const head = parts[0].toLowerCase();
  const rest = (parts[1] ?? "").trim();
  switch (head) {
    case "/compact":
      return { kind: "compact" };
    case "/fork":
      return { kind: "fork" };
    case "/undo": {
      const num = rest ? Math.max(1, Number.parseInt(rest, 10) || 1) : 1;
      return { kind: "undo", numTurns: num };
    }
    case "/review": {
      if (!rest) return { kind: "reviewNeedTarget" };
      if (rest.toLowerCase().startsWith("commit ")) {
        const sha = rest.slice("commit ".length).trim().split(/\s+/)[0] ?? "";
        if (!sha) return { kind: "reviewNeedTarget" };
        return { kind: "review", target: { type: "commit", sha } };
      }
      if (rest.toLowerCase().startsWith("custom ") || rest.startsWith(":")) {
        const instructions = rest.toLowerCase().startsWith("custom ")
          ? rest.slice("custom ".length).trim()
          : rest.startsWith(":")
            ? rest.slice(1).trim()
            : rest;
        if (!instructions) return { kind: "reviewNeedTarget" };
        return { kind: "review", target: { type: "custom", instructions } };
      }
      return { kind: "review", target: { type: "baseBranch", branch: rest } };
    }
    default:
      return null;
  }
}

export function filterSlashCommands(query: string): SlashCommandSpec[] {
  const q = query.trim();
  if (!q.startsWith("/")) return [];
  // 已输入空格说明命令已选定，不再弹菜单（参数由用户继续敲）。
  if (q.includes(" ")) return [];
  return SLASH_COMMANDS.filter((command) => command.trigger.toLowerCase().startsWith(q.toLowerCase()));
}
