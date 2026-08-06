import type { IncomingMessage, Server } from "node:http";
import { WebSocket, WebSocketServer, type RawData } from "ws";

/**
 * 浏览器 WebSocket 无法自定义请求头（Authorization / X-Api-*），
 * 所以所有上游连接都经由本服务端代理：
 *  - /ws/codex : codex app-server，Bearer token 由服务端注入
 *  - /ws/kimi  : kimi-web kap-server（/api/v1/ws），Bearer token 由服务端注入
 *  - /ws/claude: claude-server 的 per-session 事件面（NDJSON），Bearer token 由服务端注入
 *  - /ws/asr   : 火山引擎流式 ASR，App Key/Access Key/Resource Id 由服务端注入
 *  - /ws/tts   : 火山引擎双向流式 TTS，X-Api-Key/Resource Id 由服务端注入
 *
 * 同一个 http.Server 上不能挂多个带 path 的 WebSocketServer（非匹配路径的实例
 * 会向已升级的 socket 写 400），因此用 noServer 模式自行按 pathname 路由。
 */
export function attachWsProxies(server: Server) {
  const wss = new WebSocketServer({ noServer: true });

  server.on("upgrade", (req, socket, head) => {
    const url = new URL(req.url ?? "/", "http://localhost");
    if (!originAllowed(req)) {
      socket.destroy();
      return;
    }
    const upstreamUrl = url.searchParams.get("url") ?? "";
    if (!/^wss?:\/\//.test(upstreamUrl)) {
      socket.destroy();
      return;
    }
    const headers = routeHeaders(url.pathname, url);
    if (!headers) {
      socket.destroy();
      return;
    }
    wss.handleUpgrade(req, socket, head, (downstream) => {
      relay(downstream, upstreamUrl, headers);
    });
  });
}

/** 按 pathname 返回注入的请求头；未知路径返回 null（连接直接销毁）。 */
function routeHeaders(pathname: string, url: URL): Record<string, string> | null {
  switch (pathname) {
    case "/ws/codex":
    case "/ws/kimi":
    case "/ws/claude":
      return { Authorization: `Bearer ${url.searchParams.get("token") ?? ""}` };
    case "/ws/asr":
      return {
        "X-Api-App-Key": url.searchParams.get("appKey") ?? "",
        "X-Api-Access-Key": url.searchParams.get("accessKey") ?? "",
        "X-Api-Resource-Id": url.searchParams.get("resourceId") ?? "",
        "X-Api-Sequence": "-1",
        "X-Api-Request-Id": crypto.randomUUID(),
        "X-Api-Connect-Id": crypto.randomUUID(),
      };
    case "/ws/tts":
      return {
        "X-Api-Key": url.searchParams.get("apiKey") ?? "",
        "X-Api-Resource-Id": url.searchParams.get("resourceId") ?? "",
        "X-Api-Connect-Id": crypto.randomUUID(),
      };
    default:
      return null;
  }
}

/** 双向转发；上游未就绪时先缓冲浏览器侧消息，避免首包丢失。 */
function relay(downstream: WebSocket, upstreamUrl: string, headers: Record<string, string>) {
  const upstream = new WebSocket(upstreamUrl, { headers, perMessageDeflate: false });
  const pending: { data: RawData; binary: boolean }[] = [];

  // 与 Android OkHttp 的 pingInterval(20s) 对齐，保持上游连接存活。
  const heartbeat = setInterval(() => {
    if (upstream.readyState === WebSocket.OPEN) upstream.ping();
  }, 20_000);

  const flush = () => {
    while (pending.length > 0) {
      const message = pending.shift()!;
      if (upstream.readyState !== WebSocket.OPEN) return;
      upstream.send(message.data, { binary: message.binary });
    }
  };

  downstream.on("message", (data, isBinary) => {
    if (upstream.readyState === WebSocket.OPEN) {
      upstream.send(data, { binary: isBinary });
    } else {
      pending.push({ data, binary: isBinary });
    }
  });
  downstream.on("close", () => {
    clearInterval(heartbeat);
    if (upstream.readyState === WebSocket.OPEN) upstream.close(1000, "client closed");
  });
  downstream.on("error", () => {
    clearInterval(heartbeat);
    upstream.terminate();
  });

  upstream.on("open", flush);
  upstream.on("message", (data, isBinary) => {
    if (downstream.readyState === WebSocket.OPEN) downstream.send(data, { binary: isBinary });
  });
  upstream.on("close", (code, reason) => {
    clearInterval(heartbeat);
    if (downstream.readyState === WebSocket.OPEN) downstream.close(safeCloseCode(code), reason.toString());
  });
  upstream.on("error", () => {
    clearInterval(heartbeat);
    if (downstream.readyState === WebSocket.OPEN) downstream.close(1011, "upstream error");
  });
}

/** ws 的 close() 只能发送合法状态码；上游异常断开(1006 等)不能原样转发。 */
function safeCloseCode(code: number): number {
  if (Number.isInteger(code) && code >= 1000 && code <= 1014 && code !== 1004 && code !== 1005 && code !== 1006) return code;
  if (Number.isInteger(code) && code >= 3000 && code <= 4999) return code;
  return 1011;
}

/** 只允许本站点/本机来源，避免被任意网页借用代理。 */
export function originAllowed(req: IncomingMessage): boolean {
  const origin = req.headers.origin;
  if (!origin) return true;
  const host = Array.isArray(req.headers.host) ? req.headers.host[0] : req.headers.host;
  try {
    const originHost = new URL(origin).host;
    const [originName] = originHost.split(":");
    return originHost === host || originName === "localhost" || originName === "127.0.0.1";
  } catch {
    return false;
  }
}
