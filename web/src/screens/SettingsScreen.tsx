import { useState } from "react";
import { settingsStore } from "../settings";
import { DEFAULT_SETTINGS } from "../types";
import { ArrowLeftIcon, MenuIcon } from "../components/icons";

export function SettingsScreen({ onBack, onOpenSidebar }: { onBack: () => void; onOpenSidebar: () => void }) {
  const initial = settingsStore.get();
  const [serverUrl, setServerUrl] = useState(initial.serverUrl);
  const [token, setToken] = useState(initial.token);
  const [asrUrl, setAsrUrl] = useState(initial.asrUrl);
  const [asrAppKey, setAsrAppKey] = useState(initial.asrAppKey);
  const [asrAccessKey, setAsrAccessKey] = useState(initial.asrAccessKey);
  const [asrResourceId, setAsrResourceId] = useState(initial.asrResourceId);

  const save = () => {
    settingsStore.save({
      serverUrl: serverUrl.trim() || DEFAULT_SETTINGS.serverUrl,
      token: token.trim(),
      asrUrl: asrUrl.trim() || DEFAULT_SETTINGS.asrUrl,
      asrAppKey: asrAppKey.trim(),
      asrAccessKey: asrAccessKey.trim(),
      asrResourceId: asrResourceId.trim() || DEFAULT_SETTINGS.asrResourceId,
    });
    onBack();
  };

  return (
    <div className="settings">
      <header className="chat-header">
        <button className="icon-btn chat-menu-btn" title="会话列表" onClick={onOpenSidebar}>
          <MenuIcon size={18} />
        </button>
        <button className="icon-btn chat-back" title="返回" onClick={onBack}>
          <ArrowLeftIcon size={18} />
        </button>
        <div className="chat-title-wrap">
          <div className="chat-title">设置</div>
        </div>
      </header>
      <div className="settings-body">
        <div className="settings-section-title">连接</div>
        <label className="field">
          <span>服务器地址 (WebSocket)</span>
          <input value={serverUrl} onChange={(e) => setServerUrl(e.target.value)} spellCheck={false} />
        </label>
        <label className="field">
          <span>Token</span>
          <input type="password" value={token} onChange={(e) => setToken(e.target.value)} />
        </label>

        <div className="settings-section-title">语音识别（火山引擎）</div>
        <label className="field">
          <span>ASR 地址 (WebSocket)</span>
          <input value={asrUrl} onChange={(e) => setAsrUrl(e.target.value)} spellCheck={false} />
        </label>
        <label className="field">
          <span>App ID</span>
          <input type="password" value={asrAppKey} onChange={(e) => setAsrAppKey(e.target.value)} />
        </label>
        <label className="field">
          <span>Access Token</span>
          <input type="password" value={asrAccessKey} onChange={(e) => setAsrAccessKey(e.target.value)} />
        </label>
        <label className="field">
          <span>ASR 资源 ID</span>
          <input value={asrResourceId} onChange={(e) => setAsrResourceId(e.target.value)} spellCheck={false} />
        </label>

        <button className="btn btn-primary btn-save" onClick={save}>
          保存
        </button>
      </div>
    </div>
  );
}
