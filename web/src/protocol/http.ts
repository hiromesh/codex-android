import { AgentProfile } from "../types";

/**
 * 与 Android 两个 repository 的 deriveBases 一致的 URL 推导：
 * wss/https → wss/https；ws/http → ws/http；裸 host → https/wss。
 * 返回 HTTP base（不带尾部斜杠）与 WS scheme。
 */
export function deriveBases(serverUrl: string): { httpBase: string; wsScheme: "ws" | "wss" } {
  const normalized = serverUrl.trim().replace(/\/+$/, "");
  if (normalized.startsWith("wss://")) return { httpBase: `https://${normalized.slice(6)}`, wsScheme: "wss" };
  if (normalized.startsWith("ws://")) return { httpBase: `http://${normalized.slice(5)}`, wsScheme: "ws" };
  if (normalized.startsWith("https://")) return { httpBase: normalized, wsScheme: "wss" };
  if (normalized.startsWith("http://")) return { httpBase: normalized, wsScheme: "ws" };
  return { httpBase: `https://${normalized}`, wsScheme: "wss" };
}

/** 校验 profile 连接参数；不合法直接抛错（对应 Android toConnectionConfig 的 require）。 */
export function requireValidProfile(profile: AgentProfile): { url: string; token: string } {
  const url = profile.serverUrl.trim().replace(/\/+$/, "");
  if (!/^https?:\/\//.test(url) && !/^wss?:\/\//.test(url)) {
    throw new Error("服务器地址必须以 http(s):// 或 ws(s):// 开头");
  }
  if (!profile.token.trim()) throw new Error("请先在设置中填写 Token");
  return { url, token: profile.token.trim() };
}

/** 校验 WS 连接参数（codex 用）。 */
export function requireValidWsProfile(profile: AgentProfile): { url: string; token: string } {
  const url = profile.serverUrl.trim().replace(/\/+$/, "");
  if (!/^ws:\/\//.test(url) && !/^wss:\/\//.test(url)) {
    throw new Error("服务器地址必须以 ws:// 或 wss:// 开头");
  }
  if (!profile.token.trim()) throw new Error("请先在设置中填写 Token");
  return { url, token: profile.token.trim() };
}

/**
 * Kimi REST 统一信封：{code, msg, data}。
 * code 必须为 0；data 为对象时原样返回，为 null 时返回 {}，其余包一层 {"value": data}。
 */
export async function parseKimiEnvelope(response: Response, context: string): Promise<Record<string, unknown>> {
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`Kimi 错误（${context}）：HTTP ${response.status} ${text.slice(0, 200)}`);
  }
  let json: unknown;
  try {
    json = JSON.parse(text);
  } catch {
    throw new Error(`Kimi 错误（${context}）：响应不是 JSON`);
  }
  const obj = (json ?? {}) as Record<string, unknown>;
  if (obj.code !== 0) {
    throw new Error(`Kimi 错误（${context}）：${String(obj.msg ?? obj.code)}`);
  }
  const data = obj.data;
  if (data == null) return {};
  if (typeof data === "object" && !Array.isArray(data)) return data as Record<string, unknown>;
  return { value: data };
}

/** Claude REST 错误提示（对应 Android 的 HTTP 状态码 hint）。 */
export function claudeHttpHint(status: number): string {
  if (status === 401) return "Token 无效或已过期，请检查设置";
  if (status === 404) return "会话不存在或已过期，请新建会话";
  if (status === 429) return "服务器会话数已达上限，稍后再试";
  return `HTTP ${status}`;
}

export async function parseClaudeResponse(response: Response, context: string): Promise<Record<string, unknown>> {
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`Claude 错误（${claudeHttpHint(response.status)}）：${text.slice(0, 200)}`);
  }
  try {
    const json: unknown = JSON.parse(text);
    if (json && typeof json === "object" && !Array.isArray(json)) return json as Record<string, unknown>;
    return {};
  } catch {
    throw new Error(`Claude 错误（${context}）：响应不是 JSON`);
  }
}

/** 通用 JSON body 读取。 */
export function jsonBody(data: Record<string, unknown> | null): string {
  return JSON.stringify(data ?? {});
}
