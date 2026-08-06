import { AgentProfile, AppSettings, DEFAULT_SETTINGS, AGENT_TYPES, profileConnectionKey } from "./types";
import { RepositoryRegistry } from "./protocol/registry";

const SETTINGS_KEYS: Record<Exclude<keyof AppSettings, "ttsSpeechRate">, string> = {
  asrUrl: "asrUrl",
  asrAppKey: "asrAppKey",
  asrAccessKey: "asrAccessKey",
  asrResourceId: "asrResourceId",
  ttsEnabled: "ttsEnabled",
  ttsUrl: "ttsUrl",
  ttsApiKey: "ttsApiKey",
  ttsResourceId: "ttsResourceId",
  ttsSpeaker: "ttsSpeaker",
};
const KEY_TTS_SPEECH_RATE = "ttsSpeechRate";
const KEY_PROFILES = "agentProfiles";
/** 旧版单一服务器配置；首次启动时迁移为一个 codex profile。 */
const KEY_LEGACY_URL = "serverUrl";
const KEY_LEGACY_TOKEN = "token";

/** 与 Android SharedPreferences 同键的本地持久化，Token 不出浏览器。 */
class SettingsStore {
  private value: AppSettings = this.load();
  private profilesValue: AgentProfile[] = this.loadProfiles();
  private listeners = new Set<() => void>();
  private profileListeners = new Set<() => void>();

  get(): AppSettings {
    return this.value;
  }

  getProfiles(): AgentProfile[] {
    return this.profilesValue;
  }

  save(settings: AppSettings) {
    this.value = settings;
    for (const key of Object.keys(SETTINGS_KEYS) as (keyof typeof SETTINGS_KEYS)[]) {
      try {
        localStorage.setItem(SETTINGS_KEYS[key], String(settings[key]));
      } catch {
        /* 隐私模式等场景忽略 */
      }
    }
    try {
      localStorage.setItem(KEY_TTS_SPEECH_RATE, String(settings.ttsSpeechRate));
    } catch {
      /* ignore */
    }
    this.listeners.forEach((fn) => fn());
  }

  /** 按 id upsert；与 Android saveProfile 一致。 */
  saveProfile(profile: AgentProfile) {
    this.persistProfiles(this.profilesValue.filter((p) => p.id !== profile.id).concat(profile));
  }

  deleteProfile(profileId: string) {
    this.persistProfiles(this.profilesValue.filter((p) => p.id !== profileId));
  }

  /** 全量覆盖（服务端同步拉取时用）。 */
  saveProfiles(profiles: AgentProfile[]) {
    this.persistProfiles(profiles);
  }

  private persistProfiles(profiles: AgentProfile[]) {
    this.profilesValue = profiles;
    try {
      localStorage.setItem(KEY_PROFILES, JSON.stringify(profiles));
    } catch {
      /* ignore */
    }
    this.profileListeners.forEach((fn) => fn());
    // 同步到服务端（API/CLI 的配置源）；不可达时静默，下次变更重试。
    void fetch("/api/profiles", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ profiles }),
    }).catch(() => undefined);
  }

  subscribe(fn: () => void): () => void {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }

  subscribeProfiles(fn: () => void): () => void {
    this.profileListeners.add(fn);
    return () => this.profileListeners.delete(fn);
  }

  private load(): AppSettings {
    const out = { ...DEFAULT_SETTINGS };
    try {
      for (const key of Object.keys(SETTINGS_KEYS) as (keyof typeof SETTINGS_KEYS)[]) {
        const raw = localStorage.getItem(SETTINGS_KEYS[key]);
        if (raw != null) {
          // ttsEnabled 是 boolean，localStorage 只能存字符串，不能直接塞回。
          if (key === "ttsEnabled") out.ttsEnabled = raw === "true";
          else out[key] = raw as never;
        }
      }
      const rate = localStorage.getItem(KEY_TTS_SPEECH_RATE);
      if (rate != null) {
        const v = Number(rate);
        if (Number.isFinite(v)) out.ttsSpeechRate = Math.max(-50, Math.min(100, Math.round(v)));
      }
    } catch {
      /* ignore */
    }
    return out;
  }

  /** 首次升级时把旧的单一 serverUrl/token 迁移为一个 codex profile。 */
  private loadProfiles(): AgentProfile[] {
    let json: string | null = null;
    let legacyUrl: string | null = null;
    let legacyToken: string | null = null;
    try {
      json = localStorage.getItem(KEY_PROFILES);
      legacyUrl = localStorage.getItem(KEY_LEGACY_URL);
      legacyToken = localStorage.getItem(KEY_LEGACY_TOKEN);
    } catch {
      /* ignore */
    }
    if (json != null) return parseProfiles(json);
    if (legacyUrl == null && legacyToken == null) return [];
    const migrated: AgentProfile = {
      id: crypto.randomUUID(),
      name: "",
      type: "codex",
      serverUrl: legacyUrl?.trim() || AGENT_TYPES.codex.defaultUrl,
      token: legacyToken?.trim() ?? "",
      defaultCwd: "",
      enabled: true,
    };
    try {
      localStorage.removeItem(KEY_LEGACY_URL);
      localStorage.removeItem(KEY_LEGACY_TOKEN);
      localStorage.setItem(KEY_PROFILES, JSON.stringify([migrated]));
    } catch {
      /* ignore */
    }
    return [migrated];
  }
}

function parseProfiles(json: string): AgentProfile[] {
  try {
    const array: unknown = JSON.parse(json);
    if (!Array.isArray(array)) return [];
    const out: AgentProfile[] = [];
    for (const raw of array) {
      if (!raw || typeof raw !== "object") continue;
      const obj = raw as Record<string, unknown>;
      const id = typeof obj.id === "string" ? obj.id : "";
      if (!id) continue;
      out.push({
        id,
        name: typeof obj.name === "string" ? obj.name : "",
        type: (["codex", "kimi", "claude", "opencode"] as const).includes(obj.type as never)
          ? (obj.type as AgentProfile["type"])
          : "codex",
        serverUrl: typeof obj.serverUrl === "string" ? obj.serverUrl : "",
        token: typeof obj.token === "string" ? obj.token : "",
        defaultCwd: typeof obj.defaultCwd === "string" ? obj.defaultCwd : "",
        enabled: typeof obj.enabled === "boolean" ? obj.enabled : true,
      });
    }
    return out;
  } catch {
    return [];
  }
}

export const settingsStore = new SettingsStore();

/** 浏览器侧 repository 注册表：配置源为 localStorage 的 settingsStore（服务端 API 用文件存储版）。 */
export const registry = new RepositoryRegistry({
  getProfiles: () => settingsStore.getProfiles(),
  subscribeProfiles: (fn) => settingsStore.subscribeProfiles(fn),
});

/**
 * 启动时与服务端配置对账（服务端文件为权威，API/CLI 依赖它）：
 * 服务端空且本地有 → 推送本地；两边都有 → 合并（服务端优先，按 type+serverUrl 去重），
 * 避免覆盖掉任一侧独有的配置。
 */
export function initServerProfileSync() {
  void fetch("/api/profiles")
    .then((r) => (r.ok ? (r.json() as Promise<{ profiles?: AgentProfile[] }>) : null))
    .then((data) => {
      const serverProfiles = data?.profiles;
      if (Array.isArray(serverProfiles) && serverProfiles.length > 0) {
        const merged = [...serverProfiles];
        for (const local of settingsStore.getProfiles()) {
          const dup = merged.some((p) => p.type === local.type && p.serverUrl === local.serverUrl);
          if (!dup) merged.push(local);
        }
        settingsStore.saveProfiles(merged);
      } else if (settingsStore.getProfiles().length > 0) {
        settingsStore.saveProfiles(settingsStore.getProfiles());
      }
    })
    .catch(() => undefined);
}

export { profileConnectionKey };
