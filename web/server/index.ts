import path from "node:path";
import { fileURLToPath } from "node:url";
import express from "express";
import { createServer } from "node:http";
import { attachWsProxies } from "./wsProxies.js";
import { registerRelay } from "./relay.js";
import { createApiRouter } from "./api.js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const distDir = path.resolve(__dirname, "../dist");

const app = express();
const server = createServer(app);

app.get("/api/health", (_req, res) => {
  res.json({ ok: true, name: "codex-web", time: Date.now() });
});

// Kimi / Claude 的 REST 控制面统一走同源转发（浏览器无法跨域带 Authorization 头）。
// 必须先于业务路由挂载：relay 自行读取请求体，不能被 express.json 消费。
registerRelay(app);

attachWsProxies(server);

// 业务 API：多 Agent 会话/发消息/审批/斜杠命令（供 CLI / 脚本使用）。
// express.json 只作用于业务路由，不影响 relay 的原始流读取。
app.use("/api", express.json(), createApiRouter());

// 生产模式：同一端口同时托管前端静态资源（前后端一体）。
if (process.env.NODE_ENV === "production") {
  app.use(express.static(distDir));
  app.get("*", (_req, res) => res.sendFile(path.join(distDir, "index.html")));
}

const port = Number(process.env.PORT ?? 3000);
server.listen(port, () => {
  console.log(`codex-web server listening on http://localhost:${port} (${process.env.NODE_ENV ?? "development"})`);
});
