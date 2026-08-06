import { useEffect, useRef, useState } from "react";
import { profileDisplayName } from "../types";
import { settingsStore } from "../settings";
import { AgentBadge } from "./AgentBadge";
import { PlusIcon } from "./icons";

/**
 * 「新会话」入口（对应 Android 右下角 FAB）：
 * 0 个可用配置 → 提示先去设置；1 个 → 直接进入；多个 → 弹出配置选择。
 */
export function NewThreadMenu({
  onPick,
  onOpenSettings,
  className = "",
}: {
  onPick: (profileId: string) => void;
  onOpenSettings: () => void;
  className?: string;
}) {
  const [open, setOpen] = useState(false);
  const [showHint, setShowHint] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const profiles = settingsStore.getProfiles().filter((p) => p.enabled);

  useEffect(() => {
    if (!open) return;
    const onPointerDown = (e: PointerEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false);
    };
    window.addEventListener("pointerdown", onPointerDown);
    return () => window.removeEventListener("pointerdown", onPointerDown);
  }, [open]);

  const onClick = () => {
    if (profiles.length === 0) setShowHint(true);
    else if (profiles.length === 1) onPick(profiles[0].id);
    else setOpen((v) => !v);
  };

  return (
    <>
      <div className="new-thread-menu" ref={rootRef}>
        <button className={className} onClick={onClick}>
          <PlusIcon size={15} />
          新会话
        </button>
        {open && (
          <div className="profile-picker">
            {profiles.map((profile) => (
              <button
                key={profile.id}
                className="profile-picker-row"
                onClick={() => {
                  setOpen(false);
                  onPick(profile.id);
                }}
              >
                <AgentBadge type={profile.type} size={22} />
                <span>{profileDisplayName(profile)}</span>
              </button>
            ))}
          </div>
        )}
      </div>
      {showHint && (
        <div className="dialog-overlay" onClick={() => setShowHint(false)}>
          <div className="dialog-card" role="alertdialog" aria-label="还没有可用的 Agent 服务器" onClick={(e) => e.stopPropagation()}>
            <div className="dialog-title">还没有可用的 Agent 服务器</div>
            <div className="dialog-desc">先在设置里添加一个服务器配置，再回来新建会话。</div>
            <div className="dialog-actions">
              <button className="btn" onClick={() => setShowHint(false)}>
                取消
              </button>
              <button
                className="btn btn-primary"
                onClick={() => {
                  setShowHint(false);
                  onOpenSettings();
                }}
              >
                去设置
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
