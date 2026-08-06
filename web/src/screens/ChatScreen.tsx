import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import { ChatStore } from "../stores";
import { ThreadItem } from "../types";
import { ApprovalDialog } from "../components/ApprovalDialog";
import { ChatInputBar } from "../components/inputbar";
import { MessageItem } from "../components/items";
import { ActionPromptDialogs } from "../components/ActionPromptDialogs";
import { AgentBadge } from "../components/AgentBadge";
import { ArrowLeftIcon, CloseIcon, MenuIcon } from "../components/icons";

export function ChatScreen({
  profileId,
  threadId,
  onBack,
  onOpenSidebar,
  onOpenThread,
}: {
  profileId: string;
  threadId: string;
  onBack: () => void;
  onOpenSidebar: () => void;
  onOpenThread?: (threadId: string) => void;
}) {
  const [store] = useState(() => new ChatStore(profileId, threadId === "new" ? null : threadId));
  useEffect(() => () => store.dispose(), [store]);
  useEffect(
    () => (onOpenThread ? store.subscribeOpenThread(onOpenThread) : undefined),
    [store, onOpenThread],
  );
  const state = useSyncExternalStore(store.subscribe, store.getSnapshot);

  const listRef = useRef<HTMLDivElement>(null);
  const [followLatest, setFollowLatest] = useState(true);

  // 从任务卡片展开已有会话时，初始落点固定在最新消息底部。
  useEffect(() => {
    const el = listRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [threadId]);

  // 用户一旦向上翻阅历史，就不再让流式 delta 抢走滚动控制权。
  useEffect(() => {
    const el = listRef.current;
    if (!el) return;
    const onScroll = () => {
      const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 48;
      setFollowLatest(atBottom);
    };
    el.addEventListener("scroll", onScroll);
    onScroll();
    return () => el.removeEventListener("scroll", onScroll);
  }, []);

  // 新 item 或流式文本增长时，仅在用户原本位于底部的情况下跟随最新消息。
  const lastItem = state.items.at(-1);
  const lastLength =
    lastItem?.kind === "agentMessage"
      ? lastItem.text.length
      : lastItem?.kind === "reasoning"
        ? lastItem.summary.reduce((sum, line) => sum + line.length, 0)
        : 0;
  useEffect(() => {
    if (followLatest) {
      const el = listRef.current;
      if (el) el.scrollTop = el.scrollHeight;
    }
  }, [state.items.length, lastLength, followLatest]);

  return (
    <div className="chat">
      <header className="chat-header">
        <button className="icon-btn chat-menu-btn" title="会话列表" onClick={onOpenSidebar}>
          <MenuIcon size={18} />
        </button>
        <button className="icon-btn chat-back" title="返回" onClick={onBack}>
          <ArrowLeftIcon size={18} />
        </button>
        <AgentBadge type={state.agentType} size={22} />
        <div className="chat-title-wrap">
          <div className="chat-title">{state.title}</div>
          {state.generating && <div className="chat-title-status">生成中…</div>}
        </div>
      </header>

      {state.loading && <div className="chat-loading" />}
      {state.error && !state.loading && <ErrorToast store={store} message={state.error} />}

      <div className="chat-list" ref={listRef}>
        {state.items.length === 0 && !state.loading && (
          <div className="chat-empty">还没有消息，发一条试试</div>
        )}
        {state.items.map((item) => (
          <MessageRow key={item.id} item={item} streaming={state.generating && item.id === lastItem?.id} />
        ))}
      </div>

      <div className="chat-input-wrap">
        <ChatInputBar
          generating={state.generating}
          actionBusy={state.actionBusy}
          agentName={state.agentName}
          model={state.model}
          effort={state.effort}
          models={state.availableModels}
          onSelectConfiguration={store.switchConfiguration}
          tokenUsage={state.tokenUsage}
          asrTranscript={state.asrTranscript}
          asrRecording={state.asrRecording}
          onToggleAsr={() => (state.asrRecording ? store.stopAsr() : store.startAsr())}
          onStopAsr={store.stopAsr}
          onSend={store.submit}
          onInterrupt={store.interrupt}
        />
      </div>

      {state.pendingApproval && <ApprovalDialog request={state.pendingApproval} onDecision={store.respondApproval} />}
      <ActionPromptDialogs
        prompt={state.pendingActionPrompt}
        onDismiss={store.dismissActionPrompt}
        onConfirmReview={store.confirmReview}
        onConfirmUndo={store.confirmUndo}
        onConfirmShell={store.confirmShell}
      />
    </div>
  );
}

function MessageRow({ item, streaming }: { item: ThreadItem; streaming: boolean }) {
  return <MessageItem item={item} streaming={streaming} />;
}

function ErrorToast({ store, message }: { store: ChatStore; message: string }) {
  useEffect(() => {
    const timer = window.setTimeout(() => store.reportErrorClear(), 8_000);
    return () => clearTimeout(timer);
  }, [store, message]);
  return (
    <div className="toast">
      <span>{message}</span>
      <button className="icon-btn" onClick={() => store.reportErrorClear()}>
        <CloseIcon size={13} />
      </button>
    </div>
  );
}
