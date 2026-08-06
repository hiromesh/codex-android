import { useEffect, useState } from "react";
import { useSyncExternalStore } from "react";
import { ThreadListStore, entryKey } from "../stores";
import { Thread } from "../types";
import { SettingsIcon, TrashIcon } from "../components/icons";
import { AgentBadge } from "../components/AgentBadge";
import { NewThreadMenu } from "../components/NewThreadMenu";
import { useRelativeTime } from "../components/items";

export function Sidebar({
  store,
  activeChat,
  onOpenThread,
  onNewThread,
  onOpenSettings,
  onThreadDeleted,
}: {
  store: ThreadListStore;
  activeChat: { profileId: string; threadId: string } | null;
  onOpenThread: (profileId: string, threadId: string) => void;
  onNewThread: (profileId: string) => void;
  onOpenSettings: () => void;
  onThreadDeleted?: (profileId: string, threadId: string) => void;
}) {
  const state = useSyncExternalStore(store.subscribe, store.getSnapshot);
  const [deleteTarget, setDeleteTarget] = useState<{ profileId: string; thread: Thread } | null>(null);

  // 与 Android Dialog 一致：点击外部或 Esc 关闭
  useEffect(() => {
    if (!deleteTarget) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setDeleteTarget(null);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [deleteTarget]);

  const confirmDelete = (profileId: string, thread: Thread) => {
    store.deleteThread(profileId, thread.id);
    onThreadDeleted?.(profileId, thread.id);
    setDeleteTarget(null);
  };

  return (
    <aside className="sidebar">
      <div className="sidebar-header">
        <span className="sidebar-title">Codex</span>
        <button className="icon-btn" title="设置" onClick={onOpenSettings}>
          <SettingsIcon size={18} />
        </button>
      </div>
      {/* 按钮放在列表容器内并 sticky 钉住：与卡片同属一个 flex 容器，
          滚动条占位出现/消失时按钮和卡片等宽伸缩，不会差一截。 */}
      <div className="sidebar-list">
        <NewThreadMenu
          onPick={onNewThread}
          onOpenSettings={onOpenSettings}
          className="new-thread-btn"
        />
        {state.loading && <div className="sidebar-loading" />}
        {state.error && !state.loading && (
          <div className="sidebar-error">{state.error}</div>
        )}
        {/* 部分配置连接失败时保留其他配置的卡片，失败项在列表顶部提示。 */}
        {state.entries.length > 0 &&
          [...state.profileErrors.entries()].map(([profileId, message]) => (
            <div key={`error-${profileId}`} className="sidebar-profile-error">
              {state.profiles.find((p) => p.id === profileId)?.name || "Agent"} 连接失败：{message}
            </div>
          ))}
        {!state.error && state.entries.length === 0 &&
          (state.profileErrors.size > 0 ? (
            <div className="sidebar-error">
              {[...state.profileErrors.entries()]
                .map(([profileId, message]) => {
                  const name = state.profiles.find((p) => p.id === profileId)?.name || "Agent";
                  return `${name} 无法连接：${message}`;
                })
                .join("\n")}
            </div>
          ) : (
            <div className="sidebar-empty">
              {state.profiles.length === 0 ? "先在设置里添加一个 Agent 服务器" : "还没有会话，点 + 开始"}
            </div>
          ))}
        {state.entries.map((entry) => (
          <ThreadCard
            key={entryKey(entry)}
            entry={entry}
            active={
              activeChat !== null &&
              activeChat.profileId === entry.profileId &&
              activeChat.threadId === entry.thread.id
            }
            onClick={() => onOpenThread(entry.profileId, entry.thread.id)}
            onDelete={() => setDeleteTarget({ profileId: entry.profileId, thread: entry.thread })}
          />
        ))}
      </div>
      {deleteTarget && (
        <div className="dialog-overlay" onClick={() => setDeleteTarget(null)}>
          <div className="dialog-card" role="alertdialog" aria-label="删除会话" onClick={(e) => e.stopPropagation()}>
            <div className="dialog-title">删除这个会话？</div>
            <div className="dialog-desc">会话记录将被永久删除，无法恢复。</div>
            <div className="dialog-subject">
              {deleteTarget.thread.name ?? (deleteTarget.thread.preview || "Untitled task")}
            </div>
            <div className="dialog-actions">
              <button className="btn" onClick={() => setDeleteTarget(null)}>
                取消
              </button>
              <button className="btn dialog-btn-danger" onClick={() => confirmDelete(deleteTarget.profileId, deleteTarget.thread)}>
                <TrashIcon size={14} />
                删除
              </button>
            </div>
          </div>
        </div>
      )}
    </aside>
  );
}

function ThreadCard({
  entry,
  active,
  onClick,
  onDelete,
}: {
  entry: { profileId: string; profileName: string; agentType: import("../types").AgentTypeId; thread: Thread };
  active: boolean;
  onClick: () => void;
  onDelete: () => void;
}) {
  const working = isWorking(entry.thread);
  const time = useRelativeTime(entry.thread.updatedAt);
  const title = entry.thread.name ?? (entry.thread.preview || "Untitled task");
  const subtitle = [entry.profileName, entry.thread.model, entry.thread.effort ? capitalize(entry.thread.effort) : undefined]
    .filter(Boolean)
    .join("  ·  ");
  return (
    <button
      className={`thread-card ${active ? "thread-card-active" : ""} ${working ? "thread-card-working" : ""}`}
      onClick={onClick}
      // 桌面端右键 = 安卓长按，呼出删除确认
      onContextMenu={(e) => {
        e.preventDefault();
        onDelete();
      }}
    >
      <div className="thread-card-top">
        <AgentBadge type={entry.agentType} size={24} />
        <span className="thread-card-title">{title}</span>
        {working && <span className="thread-pulse" />}
      </div>
      <div className="thread-card-bottom">
        <span className="thread-card-sub">{subtitle}</span>
        <span className="thread-card-time">{time}</span>
      </div>
    </button>
  );
}

function isWorking(thread: Thread): boolean {
  const type = thread.status.type.toLowerCase();
  return type === "busy" || type === "inprogress" || type === "working" || type === "active";
}

function capitalize(value: string): string {
  return value.charAt(0).toUpperCase() + value.slice(1);
}
