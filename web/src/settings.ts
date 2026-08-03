import { AppSettings, DEFAULT_SETTINGS } from "./types";

const KEYS: Record<keyof AppSettings, string> = {
  serverUrl: "serverUrl",
  token: "token",
  asrUrl: "asrUrl",
  asrAppKey: "asrAppKey",
  asrAccessKey: "asrAccessKey",
  asrResourceId: "asrResourceId",
};

/** 与 Android SharedPreferences 同键的本地持久化，Token 不出浏览器。 */
class SettingsStore {
  private value: AppSettings = this.load();
  private listeners = new Set<() => void>();

  get(): AppSettings {
    return this.value;
  }

  save(settings: AppSettings) {
    this.value = settings;
    for (const key of Object.keys(KEYS) as (keyof AppSettings)[]) {
      try {
        localStorage.setItem(KEYS[key], settings[key]);
      } catch {
        /* 隐私模式等场景忽略 */
      }
    }
    this.listeners.forEach((fn) => fn());
  }

  subscribe(fn: () => void): () => void {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }

  private load(): AppSettings {
    const out = { ...DEFAULT_SETTINGS };
    try {
      for (const key of Object.keys(KEYS) as (keyof AppSettings)[]) {
        const raw = localStorage.getItem(KEYS[key]);
        if (raw != null) out[key] = raw;
      }
    } catch {
      /* ignore */
    }
    return out;
  }
}

export const settingsStore = new SettingsStore();
