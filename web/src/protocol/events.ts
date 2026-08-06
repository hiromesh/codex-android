import { CodexEvent } from "../types";

/** 与 Android RepositoryRegistry 的 ProfiledEvent 对应：事件带上来源 profile。 */
export interface ProfiledEvent {
  profileId: string;
  event: CodexEvent;
}

export type EventListener = (event: CodexEvent) => void;

/** 极简发布/订阅，对应 Android SharedFlow（每个 repository 一个事件流）。 */
export class EventEmitter {
  private listeners = new Set<EventListener>();

  subscribe(fn: EventListener): () => void {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }

  emit(event: CodexEvent) {
    for (const fn of [...this.listeners]) {
      try {
        fn(event);
      } catch {
        /* 单个监听器抛错不影响其余监听器 */
      }
    }
  }
}
