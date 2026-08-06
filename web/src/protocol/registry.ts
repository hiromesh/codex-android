import { AgentProfile, profileConnectionKey } from "../types";
import { CodexClient } from "./codexClient";
import { ClaudeClient } from "./claudeClient";
import { KimiClient } from "./kimiClient";
import { ProfiledEvent } from "./events";
import { Repository } from "./repository";

/** 配置源抽象：浏览器端由 settingsStore（localStorage）实现，服务端由文件存储实现。 */
export interface ProfileProvider {
  getProfiles(): AgentProfile[];
  /** 配置变化时回调（浏览器侧用于即时重建连接）；服务端在 PUT /api/profiles 后手动调用 reconcile。 */
  subscribeProfiles?(fn: () => void): () => void;
}

/**
 * 按 AgentProfile 管理 repository 的创建与销毁（对应 Android RepositoryRegistry）。
 *
 * repository 按需创建并缓存；profile 被删除、停用或连接信息（类型/地址/token/cwd）
 * 变化时，旧实例 close 后丢弃，下次使用时按新配置重建。事件流合并为
 * [allEvents]，每个事件都带来源 profileId。
 *
 * 与 UI 无关：浏览器 UI 与服务端 API（REST 暴露）共用同一实现。
 */
export class RepositoryRegistry {
  private repositories = new Map<string, Repository>();
  private connectionKeys = new Map<string, string>();
  private listeners = new Set<(event: ProfiledEvent) => void>();

  constructor(
    private readonly provider: ProfileProvider,
    private readonly options: { apiBase?: string } = {},
  ) {
    if (provider.subscribeProfiles) {
      provider.subscribeProfiles(() => this.reconcile());
    }
  }

  /** 订阅聚合事件流（带来源 profileId）。 */
  subscribeAll(fn: (event: ProfiledEvent) => void): () => void {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }

  /**
   * 取某个 profile 的 repository，不存在则按类型创建。
   * @throws 配置不存在/已停用，或该 Agent 类型暂未支持。
   */
  repositoryFor(profileId: string): Repository {
    const profile = this.provider.getProfiles().find((p) => p.id === profileId && p.enabled);
    if (!profile) throw new Error("配置不存在或已停用");
    const existing = this.repositories.get(profileId);
    if (existing) return existing;
    const repo = this.createRepository(profile);
    this.repositories.set(profileId, repo);
    this.connectionKeys.set(profileId, profileConnectionKey(profile));
    repo.events.subscribe((event) => {
      for (const fn of [...this.listeners]) fn({ profileId, event });
    });
    return repo;
  }

  /** 聚合所有启用配置的会话列表，按更新时间倒序混排；失败项单独收进 errors。 */
  async listAllThreads(): Promise<{ entries: { profile: AgentProfile; thread: import("../types").Thread }[]; errors: Map<string, string> }> {
    const profiles = this.provider.getProfiles().filter((p) => p.enabled);
    const entries: { profile: AgentProfile; thread: import("../types").Thread }[] = [];
    const errors = new Map<string, string>();
    await Promise.all(
      profiles.map(async (profile) => {
        try {
          const page = await this.repositoryFor(profile.id).listThreads(undefined, 20);
          for (const thread of page.data) entries.push({ profile, thread });
        } catch (error) {
          errors.set(profile.id, error instanceof Error ? error.message : String(error));
        }
      }),
    );
    entries.sort((a, b) => b.thread.updatedAt - a.thread.updatedAt);
    return { entries, errors };
  }

  /** 配置变更后重建连接（服务端 PUT /api/profiles 后调用；浏览器由 subscribeProfiles 自动触发）。 */
  reconcile() {
    const active = new Map(
      this.provider
        .getProfiles()
        .filter((p) => p.enabled)
        .map((p) => [p.id, p] as const),
    );
    for (const id of [...this.repositories.keys()]) {
      const profile = active.get(id);
      if (profile && profileConnectionKey(profile) === this.connectionKeys.get(id)) continue;
      const stale = this.repositories.get(id);
      this.repositories.delete(id);
      this.connectionKeys.delete(id);
      if (stale) {
        try {
          stale.close();
        } catch {
          /* close 失败不阻断重连 */
        }
      }
    }
  }

  private createRepository(profile: AgentProfile): Repository {
    switch (profile.type) {
      case "codex":
        return new CodexClient(profile, this.options);
      case "kimi":
        return new KimiClient(profile, this.options);
      case "claude":
        return new ClaudeClient(profile, this.options);
      default:
        throw new Error(`${profile.type} 暂未支持`);
    }
  }
}
