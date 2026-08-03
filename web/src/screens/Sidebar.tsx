import { useEffect, useState } from "react";
import { useSyncExternalStore } from "react";
import { ThreadListStore } from "../stores";
import { Thread } from "../types";
import { PlusIcon, SettingsIcon } from "../components/icons";
import { useRelativeTime } from "../components/items";

export function Sidebar({
  store,
  activeThreadId,
  onOpenThread,
  onNewThread,
  onOpenSettings,
  onThreadDeleted,
}: {
  store: ThreadListStore;
  activeThreadId: string | null;
  onOpenThread: (id: string) => void;
  onNewThread: () => void;
  onOpenSettings: () => void;
  onThreadDeleted?: (id: string) => void;
}) {
  const state = useSyncExternalStore(store.subscribe, store.getSnapshot);
  const [deleteTarget, setDeleteTarget] = useState<Thread | null>(null);

  // 与 Android Dialog 一致：点击外部或 Esc 关闭
  useEffect(() => {
    if (!deleteTarget) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setDeleteTarget(null);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [deleteTarget]);

  const confirmDelete = (thread: Thread) => {
    store.deleteThread(thread.id);
    onThreadDeleted?.(thread.id);
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
      <button className="new-thread-btn" onClick={onNewThread}>
        <PlusIcon size={16} />
        新会话
      </button>
      <div className="sidebar-list">
        {state.loading && <div className="sidebar-loading" />}
        {state.error && !state.loading && (
          <div className="sidebar-error">无法连接：{state.error}</div>
        )}
        {!state.error && !state.loading && state.threads.length === 0 && (
          <div className="sidebar-empty">还没有会话，点 + 开始</div>
        )}
        {state.threads.map((thread) => (
          <ThreadCard
            key={thread.id}
            thread={thread}
            active={thread.id === activeThreadId}
            onClick={() => onOpenThread(thread.id)}
            onDelete={() => setDeleteTarget(thread)}
          />
        ))}
      </div>
      {deleteTarget && (
        <div className="dialog-overlay" onClick={() => setDeleteTarget(null)}>
          <div className="dialog-card" role="alertdialog" aria-label="删除会话" onClick={(e) => e.stopPropagation()}>
            <div className="dialog-title">删除会话？</div>
            <div className="dialog-subject">
              {deleteTarget.name ?? (deleteTarget.preview || "Untitled task")}
            </div>
            <button className="btn dialog-btn dialog-btn-danger" onClick={() => confirmDelete(deleteTarget)}>
              删除
            </button>
            <button className="btn dialog-btn" onClick={() => setDeleteTarget(null)}>
              取消
            </button>
          </div>
        </div>
      )}
    </aside>
  );
}

function ThreadCard({
  thread,
  active,
  onClick,
  onDelete,
}: {
  thread: Thread;
  active: boolean;
  onClick: () => void;
  onDelete: () => void;
}) {
  const working = isWorking(thread);
  const time = useRelativeTime(thread.updatedAt);
  const title = thread.name ?? (thread.preview || "Untitled task");
  const subtitle = ["Codex", thread.model, thread.effort ? capitalize(thread.effort) : undefined]
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
