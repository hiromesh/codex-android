import { useEffect, useState } from "react";
import { ChatScreen } from "./screens/ChatScreen";
import { SettingsScreen } from "./screens/SettingsScreen";
import { Sidebar } from "./screens/Sidebar";
import { ThreadListStore } from "./stores";
import { PlusIcon } from "./components/icons";

type Route = { view: "threads" } | { view: "chat"; threadId: string } | { view: "settings" };

function parseHash(hash: string): Route {
  const match = hash.replace(/^#\/?/, "");
  if (match.startsWith("settings")) return { view: "settings" };
  const chatMatch = match.match(/^chat\/(.+)$/);
  if (chatMatch) return { view: "chat", threadId: decodeURIComponent(chatMatch[1]) };
  return { view: "threads" };
}

function navigate(hash: string) {
  window.location.hash = hash;
}

const SIDEBAR_DEFAULT = 320;
const SIDEBAR_MIN = 240;
const SIDEBAR_MAX = 560;

function loadSidebarWidth(): number {
  try {
    const raw = localStorage.getItem("sidebarWidth");
    if (raw != null) {
      const v = Number(raw);
      if (Number.isFinite(v)) return Math.min(SIDEBAR_MAX, Math.max(SIDEBAR_MIN, v));
    }
  } catch {
    /* ignore */
  }
  return SIDEBAR_DEFAULT;
}

export function App() {
  const [route, setRoute] = useState<Route>(() => parseHash(window.location.hash));
  const [threadListStore] = useState(() => new ThreadListStore());
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [sidebarWidth, setSidebarWidth] = useState(loadSidebarWidth);
  const [resizing, setResizing] = useState(false);

  useEffect(() => {
    const onChange = () => {
      setRoute(parseHash(window.location.hash));
      setSidebarOpen(false);
    };
    window.addEventListener("hashchange", onChange);
    return () => window.removeEventListener("hashchange", onChange);
  }, []);

  useEffect(() => {
    threadListStore.init();
    return () => threadListStore.dispose();
  }, [threadListStore]);

  useEffect(() => {
    try {
      localStorage.setItem("sidebarWidth", String(sidebarWidth));
    } catch {
      /* ignore */
    }
  }, [sidebarWidth]);

  const onDividerPointerDown = (e: React.PointerEvent) => {
    if (e.button !== 0) return;
    e.preventDefault();
    const startX = e.clientX;
    const startWidth = sidebarWidth;
    const onMove = (ev: PointerEvent) => {
      setSidebarWidth(Math.min(SIDEBAR_MAX, Math.max(SIDEBAR_MIN, startWidth + ev.clientX - startX)));
    };
    const onUp = () => {
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerup", onUp);
      document.body.classList.remove("resizing-cols");
      setResizing(false);
    };
    window.addEventListener("pointermove", onMove);
    window.addEventListener("pointerup", onUp);
    document.body.classList.add("resizing-cols");
    setResizing(true);
  };

  return (
    <div className={`app ${sidebarOpen ? "sidebar-open" : ""}`} style={{ "--sidebar-width": `${sidebarWidth}px` } as React.CSSProperties}>
      <Sidebar
        store={threadListStore}
        activeThreadId={route.view === "chat" && route.threadId !== "new" ? route.threadId : null}
        onOpenThread={(id) => navigate(`#/chat/${encodeURIComponent(id)}`)}
        onNewThread={() => navigate("#/chat/new")}
        onOpenSettings={() => navigate("#/settings")}
        onThreadDeleted={(id) => {
          // 删除的正是当前打开的会话时，返回列表
          if (route.view === "chat" && route.threadId === id) navigate("#/");
        }}
      />
      <div
        className={`resize-handle ${resizing ? "resizing" : ""}`}
        role="separator"
        aria-orientation="vertical"
        aria-label="调整侧栏宽度"
        onPointerDown={onDividerPointerDown}
      />
      <main className="main">
        {route.view === "settings" && (
          <SettingsScreen
            onBack={() => {
              navigate("#/");
              threadListStore.refresh();
            }}
            onOpenSidebar={() => setSidebarOpen(true)}
          />
        )}
        {route.view === "chat" && (
          <ChatScreen key={route.threadId} threadId={route.threadId} onBack={() => navigate("#/")} onOpenSidebar={() => setSidebarOpen(true)} />
        )}
        {route.view === "threads" && (
          <div className="empty-state">
            <div className="empty-state-title">Codex Web</div>
            <div className="empty-state-sub">选择一个会话，或开始新的对话</div>
            <button className="btn btn-primary empty-state-btn" onClick={() => navigate("#/chat/new")}>
              <PlusIcon size={15} />
              新会话
            </button>
          </div>
        )}
      </main>
    </div>
  );
}
