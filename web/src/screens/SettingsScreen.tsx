import { useEffect, useState } from "react";
import { settingsStore } from "../settings";
import {
  AGENT_TYPE_LIST,
  AgentProfile,
  AgentTypeId,
  agentTypeFromWireValue,
  DEFAULT_SETTINGS,
  profileDisplayName,
} from "../types";
import { ArrowLeftIcon, MenuIcon } from "../components/icons";
import { AgentBadge } from "../components/AgentBadge";

/** 新增/编辑中的配置草稿；id 为 null 表示新建。 */
interface ProfileDraft {
  id: string | null;
  type: AgentTypeId;
  name: string;
  serverUrl: string;
  token: string;
  defaultCwd: string;
}

export function SettingsScreen({ onBack, onOpenSidebar }: { onBack: () => void; onOpenSidebar: () => void }) {
  const initial = settingsStore.get();
  const [asrUrl, setAsrUrl] = useState(initial.asrUrl);
  const [asrAppKey, setAsrAppKey] = useState(initial.asrAppKey);
  const [asrAccessKey, setAsrAccessKey] = useState(initial.asrAccessKey);
  const [asrResourceId, setAsrResourceId] = useState(initial.asrResourceId);
  const [ttsEnabled, setTtsEnabled] = useState(initial.ttsEnabled);
  const [ttsUrl, setTtsUrl] = useState(initial.ttsUrl);
  const [ttsApiKey, setTtsApiKey] = useState(initial.ttsApiKey);
  const [ttsResourceId, setTtsResourceId] = useState(initial.ttsResourceId);
  const [ttsSpeaker, setTtsSpeaker] = useState(initial.ttsSpeaker);
  const [ttsSpeechRate, setTtsSpeechRate] = useState(initial.ttsSpeechRate);
  const [profiles, setProfiles] = useState<AgentProfile[]>(() => settingsStore.getProfiles());
  const [draft, setDraft] = useState<ProfileDraft | null>(null);
  const [draftError, setDraftError] = useState<string | null>(null);

  useEffect(() => settingsStore.subscribeProfiles(() => setProfiles(settingsStore.getProfiles())), []);

  const save = () => {
    settingsStore.save({
      asrUrl: asrUrl.trim() || DEFAULT_SETTINGS.asrUrl,
      asrAppKey: asrAppKey.trim(),
      asrAccessKey: asrAccessKey.trim(),
      asrResourceId: asrResourceId.trim() || DEFAULT_SETTINGS.asrResourceId,
      ttsEnabled,
      ttsUrl: ttsUrl.trim() || DEFAULT_SETTINGS.ttsUrl,
      ttsApiKey: ttsApiKey.trim(),
      ttsResourceId: ttsResourceId.trim() || DEFAULT_SETTINGS.ttsResourceId,
      ttsSpeaker: ttsSpeaker.trim() || DEFAULT_SETTINGS.ttsSpeaker,
      ttsSpeechRate,
    });
    onBack();
  };

  const startAdd = () => {
    setDraft({
      id: null,
      type: "codex",
      name: "",
      serverUrl: agentTypeFromWireValue("codex").defaultUrl,
      token: "",
      defaultCwd: "",
    });
    setDraftError(null);
  };

  const startEdit = (profile: AgentProfile) => {
    setDraft({
      id: profile.id,
      type: profile.type,
      name: profile.name,
      serverUrl: profile.serverUrl,
      token: profile.token,
      defaultCwd: profile.defaultCwd,
    });
    setDraftError(null);
  };

  const saveDraft = () => {
    if (!draft) return;
    const url = draft.serverUrl.trim();
    if (!url) {
      setDraftError("服务器地址不能为空");
      return;
    }
    settingsStore.saveProfile({
      id: draft.id ?? crypto.randomUUID(),
      name: draft.name.trim(),
      type: draft.type,
      serverUrl: url,
      token: draft.token.trim(),
      defaultCwd: draft.defaultCwd.trim(),
      enabled: true,
    });
    setDraft(null);
    setDraftError(null);
  };

  const deleteDraft = () => {
    if (draft?.id) settingsStore.deleteProfile(draft.id);
    setDraft(null);
    setDraftError(null);
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
        <div className="settings-section-title">Agent 服务器</div>
        {profiles.map((profile) => (
          <div key={profile.id} className="profile-card" onClick={() => startEdit(profile)}>
            <AgentBadge type={profile.type} size={30} />
            <div className="profile-card-body">
              <div className="profile-card-name">{profileDisplayName(profile)}</div>
              <div className="profile-card-url">{profile.serverUrl}</div>
            </div>
            <label className="switch" onClick={(e) => e.stopPropagation()}>
              <input
                type="checkbox"
                checked={profile.enabled}
                onChange={() => settingsStore.saveProfile({ ...profile, enabled: !profile.enabled })}
              />
              <span className="switch-track" />
            </label>
          </div>
        ))}
        <button className="btn btn-add-profile" onClick={startAdd}>
          添加 Agent 服务器
        </button>

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

        <div className="settings-section-title">语音合成（TTS）</div>
        <div className="field">
          <label className="switch-row">
            <div className="switch-row-text">
              <span>启用语音播报</span>
              <span className="switch-row-sub">流式朗读 agent 的回答（工具调用等不读）</span>
            </div>
            <input type="checkbox" checked={ttsEnabled} onChange={(e) => setTtsEnabled(e.target.checked)} />
            <span className="switch-track" />
          </label>
        </div>
        {ttsEnabled && (
          <>
            <label className="field">
              <span>TTS 地址 (WebSocket)</span>
              <input value={ttsUrl} onChange={(e) => setTtsUrl(e.target.value)} spellCheck={false} />
            </label>
            <label className="field">
              <span>API Key</span>
              <input type="password" value={ttsApiKey} onChange={(e) => setTtsApiKey(e.target.value)} />
            </label>
            <label className="field">
              <span>TTS 资源 ID</span>
              <input value={ttsResourceId} onChange={(e) => setTtsResourceId(e.target.value)} spellCheck={false} />
            </label>
            <label className="field">
              <span>音色（发音人 ID）</span>
              <input value={ttsSpeaker} onChange={(e) => setTtsSpeaker(e.target.value)} spellCheck={false} />
            </label>
            <div className="field">
              <span>语速：{ttsSpeechRate}（-50 ~ 100，0 为原速）</span>
              <input
                type="range"
                min={-50}
                max={100}
                value={ttsSpeechRate}
                onChange={(e) => setTtsSpeechRate(Number(e.target.value))}
              />
            </div>
          </>
        )}

        <button className="btn btn-primary btn-save" onClick={save}>
          保存
        </button>
      </div>

      {draft && (
        <ProfileEditDialog
          draft={draft}
          error={draftError}
          onChange={(next) => {
            setDraft(next);
            setDraftError(null);
          }}
          onSave={saveDraft}
          onDelete={deleteDraft}
          onDismiss={() => setDraft(null)}
        />
      )}
    </div>
  );
}

function ProfileEditDialog({
  draft,
  error,
  onChange,
  onSave,
  onDelete,
  onDismiss,
}: {
  draft: ProfileDraft;
  error: string | null;
  onChange: (draft: ProfileDraft) => void;
  onSave: () => void;
  onDelete: () => void;
  onDismiss: () => void;
}) {
  const isHttpAgent = draft.type === "kimi" || draft.type === "claude";
  return (
    <div className="dialog-overlay" onClick={onDismiss}>
      <div className="dialog-card dialog-card-wide profile-edit-card" role="alertdialog" aria-label="Agent 服务器" onClick={(e) => e.stopPropagation()}>
        <div className="dialog-title">{draft.id ? "编辑 Agent 服务器" : "添加 Agent 服务器"}</div>
        <div className="agent-type-chips">
          {AGENT_TYPE_LIST.map((type) => (
            <button
              key={type.wireValue}
              className={`agent-type-chip ${draft.type === type.wireValue ? "agent-type-chip-selected" : ""}`}
              disabled={!type.supported}
              onClick={() => {
                const oldUrl = agentTypeFromWireValue(draft.type).defaultUrl;
                onChange({
                  ...draft,
                  type: type.wireValue,
                  // 地址未改过时跟随类型默认值；手动改过则保留。
                  serverUrl: !draft.serverUrl.trim() || draft.serverUrl.trim() === oldUrl ? type.defaultUrl : draft.serverUrl,
                });
              }}
            >
              {type.supported ? type.displayName : `${type.displayName}（暂未支持）`}
            </button>
          ))}
        </div>
        <label className="field">
          <span>名称</span>
          <input value={draft.name} onChange={(e) => onChange({ ...draft, name: e.target.value })} placeholder={agentTypeFromWireValue(draft.type).displayName} />
        </label>
        <label className="field">
          <span>{isHttpAgent ? "服务器地址 (HTTPS)" : "服务器地址 (WebSocket)"}</span>
          <input value={draft.serverUrl} onChange={(e) => onChange({ ...draft, serverUrl: e.target.value })} spellCheck={false} />
        </label>
        <label className="field">
          <span>Token</span>
          <input type="password" value={draft.token} onChange={(e) => onChange({ ...draft, token: e.target.value })} />
        </label>
        {isHttpAgent && (
          <label className="field">
            <span>默认工作目录（服务器绝对路径）</span>
            <input
              value={draft.defaultCwd}
              onChange={(e) => onChange({ ...draft, defaultCwd: e.target.value })}
              placeholder="/home/ubuntu/proj"
              spellCheck={false}
            />
          </label>
        )}
        {error && <div className="field-error">{error}</div>}
        {draft.id && (
          <button className="btn profile-delete-btn" onClick={onDelete}>
            删除此配置
          </button>
        )}
        <div className="dialog-actions">
          <button className="btn" onClick={onDismiss}>
            取消
          </button>
          <button className="btn btn-primary" onClick={onSave}>
            保存
          </button>
        </div>
      </div>
    </div>
  );
}
