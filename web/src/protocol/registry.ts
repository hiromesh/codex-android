import { AgentProfile, profileConnectionKey } from "../types";
import { settingsStore } from "../settings";
import { CodexClient } from "./codexClient";
import { ClaudeClient } from "./claudeClient";
import { KimiClient } from "./kimiClient";
import { ProfiledEvent } from "./events";
import { Repository } from "./repository";

/**
 * 按 AgentProfile 管理 repository 的创建与销毁（对应 Android RepositoryRegistry）。
 *
 * repository 按需创建并缓存；profile 被删除、停用或连接信息（类型/地址/token/cwd）
 * 变化时，旧实例 close 后丢弃，下次使用时按新配置重建。事件流合并为
 * [allEvents]，每个事件都带来源 profileId。
 *
 * 与 UI 解耦：后续把协议层暴露为 HTTP API（list 所有 session / 取某个 session）时，
 * 直接复用本模块的 repositoryFor / listAllThreads 即可。
 */
class RepositoryRegistry {
  private repositories = new Map<string, Repository>();
  private connectionKeys = new Map<string, string>();
  private listeners = new Set<(event: ProfiledEvent) => void>();

  constructor() {
    // 停用/删除/改连接的 profile，其 repository 立即释放（单例，应用生命周期内不取消）。
    settingsStore.subscribeProfiles(() => this.reconcile());
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
    const profile = settingsStore.getProfiles().find((p) => p.id === profileId && p.enabled);
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
    const profiles = settingsStore.getProfiles().filter((p) => p.enabled);
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

  /** 停用/删除/改连接的 profile，其 repository 立即释放。 */
  private reconcile() {
    const active = new Map(
      settingsStore
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
        return new CodexClient(profile);
      case "kimi":
        return new KimiClient(profile);
      case "claude":
        return new ClaudeClient(profile);
      default:
        throw new Error(`${profile.type} 暂未支持`);
    }
  }
}

export const registry = new RepositoryRegistry();
