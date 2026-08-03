import { useMemo, useState } from "react";
import { ThreadItem } from "../types";
import { MarkdownMessage } from "./markdown";
import { ChevronDownIcon, ChevronUpIcon, PlayIcon, SearchIcon } from "./icons";

/* ---------------- 用户消息：右对齐深灰气泡 ---------------- */

export function UserMessageBubble({ item }: { item: Extract<ThreadItem, { kind: "userMessage" }> }) {
  const text = item.content.find((c) => c.type === "text")?.text ?? "";
  return (
    <div className="msg-row msg-row-user">
      <div className="bubble-user">{text}</div>
    </div>
  );
}

/* ---------------- AI 回复 ---------------- */

export function AgentMessageItem({
  item,
  streaming,
}: {
  item: Extract<ThreadItem, { kind: "agentMessage" }>;
  streaming: boolean;
}) {
  return (
    <div className="msg-row">
      <div className="bubble-agent">
        <MarkdownMessage markdown={item.text} streaming={streaming} />
      </div>
    </div>
  );
}

/* ---------------- 命令执行卡片：header 常驻，输出可展开 ---------------- */

export function CommandExecutionCard({ item }: { item: Extract<ThreadItem, { kind: "commandExecution" }> }) {
  const [expanded, setExpanded] = useState(false);
  const expandable = item.output.trim().length > 0;
  return (
    <div className="card" onClick={() => expandable && setExpanded(!expanded)}>
      <div className="cmd-header">
        <PlayIcon size={14} />
        <code className="cmd-command">{item.command || "…"}</code>
        <CommandStatus item={item} />
        {expandable && (expanded ? <ChevronUpIcon size={16} /> : <ChevronDownIcon size={16} />)}
      </div>
      {expanded && expandable && (
        <pre className="cmd-output">{item.output}</pre>
      )}
    </div>
  );
}

function CommandStatus({ item }: { item: Extract<ThreadItem, { kind: "commandExecution" }> }) {
  switch (item.status) {
    case "inProgress":
      return (
        <span className="cmd-status cmd-status-running">
          <span className="spinner" />
          执行中
        </span>
      );
    case "completed": {
      const exit = item.exitCode ?? 0;
      const text = `exit ${exit}${item.durationMs != null ? ` · ${item.durationMs}ms` : ""}`;
      return <span className={`cmd-status ${exit === 0 ? "" : "cmd-status-error"}`}>{text}</span>;
    }
    default:
      return <span className="cmd-status cmd-status-error">{item.status}</span>;
  }
}

/* ---------------- 文件改动卡片 ---------------- */

export function FileChangeCard({ item }: { item: Extract<ThreadItem, { kind: "fileChange" }> }) {
  const [expanded, setExpanded] = useState(false);
  return (
    <div className="card card-narrow" onClick={() => item.changes.length > 0 && setExpanded(!expanded)}>
      <div className="filechange-title">
        {item.changes.length === 0 ? "已修改文件" : `已修改 ${item.changes.length} 个文件`}
      </div>
      {expanded &&
        item.changes.map((change, index) => (
          <code key={index} className="filechange-item">{change}</code>
        ))}
    </div>
  );
}

/* ---------------- 计划卡片 ---------------- */

export function PlanCard({ item }: { item: Extract<ThreadItem, { kind: "plan" }> }) {
  return (
    <div className="card">
      <div className="card-label">计划</div>
      <div className="card-text">{item.text}</div>
    </div>
  );
}

/* ---------------- 内置联网搜索的过程卡片 ---------------- */

export function WebSearchCard({ item }: { item: Extract<ThreadItem, { kind: "webSearch" }> }) {
  return (
    <div className="card card-narrow">
      <div className="websearch-row">
        <SearchIcon size={15} />
        <div className="websearch-body">
          <div className="card-label">联网搜索</div>
          {item.query && <div className="websearch-query">{item.query}</div>}
        </div>
        {item.status === "inProgress" && <span className="spinner" />}
      </div>
    </div>
  );
}

/* ---------------- 推理摘要：默认折叠 ---------------- */

export function ReasoningItem({ item }: { item: Extract<ThreadItem, { kind: "reasoning" }> }) {
  const [expanded, setExpanded] = useState(false);
  return (
    <div className="reasoning" onClick={() => setExpanded(!expanded)}>
      <div className="reasoning-toggle">{expanded ? "▾" : "▸"} thinking</div>
      {expanded && item.summary.map((line, index) => (
        <div key={index} className="reasoning-line">{line}</div>
      ))}
    </div>
  );
}

/* ---------------- 消息分派 ---------------- */

export function MessageItem({ item, streaming }: { item: ThreadItem; streaming: boolean }) {
  switch (item.kind) {
    case "userMessage":
      return <UserMessageBubble item={item} />;
    case "agentMessage":
      return <AgentMessageItem item={item} streaming={streaming} />;
    case "commandExecution":
      return <CommandExecutionCard item={item} />;
    case "fileChange":
      return <FileChangeCard item={item} />;
    case "plan":
      return <PlanCard item={item} />;
    case "webSearch":
      return <WebSearchCard item={item} />;
    case "reasoning":
      return <ReasoningItem item={item} />;
  }
}

/* ---------------- 时间 ---------------- */

export function useRelativeTime(epochSeconds: number): string {
  return useMemo(() => relativeTime(epochSeconds), [epochSeconds]);
}

function relativeTime(epochSeconds: number): string {
  const diffMs = Date.now() - epochSeconds * 1000;
  const minutes = Math.floor(diffMs / 60_000);
  if (minutes < 1) return "刚刚";
  if (minutes < 60) return `${minutes} 分钟前`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} 小时前`;
  const days = Math.floor(hours / 24);
  if (days < 7) return `${days} 天前`;
  const date = new Date(epochSeconds * 1000);
  return `${date.getMonth() + 1}月${date.getDate()}日`;
}

