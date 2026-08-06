import type { NextFunction, Request, Response } from "express";
import { originAllowed } from "./wsProxies.js";

/**
 * Kimi / Claude 的控制面是 REST，而浏览器跨域 fetch 无法携带 Authorization
 * 头（会触发 preflight，目标服务器普遍不处理 CORS），因此 REST 也统一走本服务
 * 同源转发：`/api/relay/{agent}/{path}`，url/token 以 query 传入，由服务端注入
 * Bearer 头后转发到上游，响应原样回传。
 *
 * 与 /ws/* 代理同一安全模型：只允许本站点/本机来源使用，避免被任意网页借用成开放代理。
 */
const RELAY_AGENTS: Record<string, { basePath(url: string): string }> = {
  /** kimi REST 全部挂在 {base}/api/v1 下。 */
  kimi: { basePath: (url) => `${url.replace(/\/+$/, "")}/api/v1` },
  /** claude-server 的 REST 就在根路径。 */
  claude: { basePath: (url) => url.replace(/\/+$/, "") },
};

const RELAY_TIMEOUT_MS = 60_000;

export function createRelay() {
  return async (req: Request, res: Response, _next: NextFunction) => {
    if (!originAllowed(req)) {
      res.status(403).json({ code: -1, msg: "来源不被允许" });
      return;
    }
    const match = req.path.match(/^\/([a-z]+)(\/.*)?$/);
    const agent = match?.[1] ?? "";
    const spec = RELAY_AGENTS[agent];
    if (!spec) {
      res.status(404).json({ code: -1, msg: `未知的转发目标：${agent}` });
      return;
    }
    const { url, token } = req.query as { url?: string; token?: string };
    if (typeof url !== "string" || !/^https?:\/\//.test(url)) {
      res.status(400).json({ code: -1, msg: "缺少合法的目标地址（url）" });
      return;
    }
    const rest = match?.[2] || "/";

    // 除 url/token 外的 query 原样转发（kimi 的 page_size / after_id 等）。
    const params = new URLSearchParams();
    for (const [key, value] of Object.entries(req.query)) {
      if (key === "url" || key === "token") continue;
      if (Array.isArray(value)) value.forEach((v) => params.append(key, String(v)));
      else if (value != null) params.append(key, String(value));
    }
    const query = params.toString();
    const target = `${spec.basePath(url)}${rest}${query ? `?${query}` : ""}`;

    const body = await readBody(req);
    const headers: Record<string, string> = { Authorization: `Bearer ${token ?? ""}` };
    if (body !== undefined) headers["Content-Type"] = "application/json; charset=utf-8";

    try {
      const upstream = await fetch(target, {
        method: req.method,
        headers,
        body,
        signal: AbortSignal.timeout(RELAY_TIMEOUT_MS),
      });
      const text = await upstream.text();
      res.status(upstream.status);
      const contentType = upstream.headers.get("content-type");
      if (contentType) res.type(contentType);
      // claude-server 用 req.headers.host 生成建会话响应的 ws_url；经 relay 转发后
      // host 会变成 web 服务器自身（如 localhost:3000），必须改写回上游地址的 host，
      // 否则浏览器拿到的 WS 地址连错目标（安卓直连服务器没有这个问题）。
      res.send(agent === "claude" ? rewriteClaudeWsUrl(text, url) : text);
    } catch (error) {
      res.status(502).json({
        code: -1,
        msg: `转发失败：${error instanceof Error ? error.message : String(error)}`,
      });
    }
  };
}

function readBody(req: Request): Promise<string | undefined> {
  if (req.method === "GET" || req.method === "HEAD") return Promise.resolve(undefined);
  return new Promise((resolve, reject) => {
    let data = "";
    req.setEncoding("utf8");
    req.on("data", (chunk: string) => (data += chunk));
    req.on("end", () => resolve(data));
    req.on("error", reject);
  });
}

/**
 * 改写 claude 建会话响应里的 ws_url：host 与协议对齐上游地址（profile 配置的服务器）。
 * 只在响应含 ws_url 时重写，其余原样透传。
 */
function rewriteClaudeWsUrl(text: string, upstreamUrl: string): string {
  try {
    const json: unknown = JSON.parse(text);
    if (!json || typeof json !== "object") return text;
    const wsUrl = (json as Record<string, unknown>).ws_url;
    if (typeof wsUrl !== "string" || !wsUrl) return text;
    const upstream = new URL(upstreamUrl);
    const parsed = new URL(wsUrl);
    parsed.protocol = upstream.protocol === "https:" ? "wss:" : "ws:";
    parsed.host = upstream.host;
    (json as Record<string, unknown>).ws_url = parsed.toString();
    return JSON.stringify(json);
  } catch {
    return text;
  }
}

export function registerRelay(app: { use: (path: string, handler: (req: Request, res: Response, next: NextFunction) => void) => void }) {
  app.use("/api/relay", createRelay());
}
