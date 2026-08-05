# Kimi Code 服务器部署指南（手机远程控制用）

> 目标：在云服务器上常驻运行 `kimi web`（kap-server），通过 Caddy TLS 反代暴露给自研 Android App。
> 与 codex app-server 部署（见 §9 of MOBILE_APP_API.md）同一套路，本文档可直接交给 agent 执行。
>
> 协议依据：kimi-code 仓库 `packages/kap-server`、`apps/kimi-code/src/cli/sub/web/`。

## 0. 架构

```
Android App ──HTTPS/WSS──> Caddy (kimi.waibozishu.com:8443) ──> 127.0.0.1:58627 (kimi web / kap-server)
```

- 控制面：**REST** `POST/GET /api/v1/*`（Fastify）
- 事件面：**WebSocket** `/api/v1/ws`（流式事件订阅，带 `{seq, epoch}` 游标可断线重放）
- 鉴权：bearer token，文件在 `~/.kimi-code/server.token`（首次启动自动生成，0600，重启不变）

## 1. 安装与登录

```bash
curl -fsSL https://code.kimi.com/kimi-code/install.sh | bash
# 新开 shell 或 source 后：
kimi --version

# 登录（必须，否则 server 无法调用模型）：交互式跑一次，选 OAuth 或 API key
kimi
# 在 TUI 里执行 /login，完成后退出
```

数据目录默认 `~/.kimi-code`（可用 `KIMI_CODE_HOME` 改）。

## 2. 首次启动，拿到 token

```bash
kimi web --no-open --port 58627
# 另开终端：
cat ~/.kimi-code/server.token        # 这就是 App 要用的密码，记下来
curl -s http://127.0.0.1:58627/api/v1/healthz   # 应返回 200（健康检查不需要鉴权）
# 验证后 Ctrl+C 停掉，下面交给 systemd
```

注意：token 是**首次启动自动生成并持久化**的，不需要手工创建；换机器/删了文件才会重新生成。

## 3. systemd 常驻

`/etc/systemd/system/kimi-web.service`：

```ini
[Unit]
Description=Kimi Code web server (kap-server)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=ubuntu
WorkingDirectory=/home/ubuntu
# --allowed-host 必须加：kap-server 有 DNS-rebinding Host 检查，
# 默认只放行 localhost/127.0.0.1，走 Caddy 域名进来会被 403。
ExecStart=/home/ubuntu/.local/bin/kimi web --no-open --port 58627 --allowed-host kimi.waibozishu.com
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
```

> `kimi` 的实际路径用 `which kimi` 确认后再写。实测（2026-08）：install.sh 装在 `~/.kimi-code/bin/kimi`。
> 也可以用环境变量代替 flag：`Environment=KIMI_CODE_ALLOWED_HOSTS=kimi.waibozishu.com`。
> **ExecStart 必须写成一行**：`--allowed-host` 换行或重复会导致 systemd 解析失败（daemon-reload 报错或服务异常）。改完用 `sudo systemctl daemon-reload && sudo systemctl restart kimi-web` 确认无报错。

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now kimi-web
sudo systemctl status kimi-web
journalctl -u kimi-web -f    # 看日志
```

## 4. Caddy 反代

在 `/etc/caddy/Caddyfile` 追加（与 codex 共用 8443 端口，SNI 区分域名）：

```
kimi.waibozishu.com:8443 {
    reverse_proxy 127.0.0.1:58627
}
```

```bash
# 域名解析：kimi.waibozishu.com A 记录指向服务器 IP（同 codex 子域名的配置方式）
sudo systemctl reload caddy
```

> 用非标准端口 8443 是因为腾讯云未备案域名会拦截 80/443（此前 codex 部署已踩过）。
> 云安全组确认 8443 已放行（codex 部署时应该已经开过，无需重复操作）。
> 实测：Caddy 会为 kimi 域名自动签发 Let's Encrypt 证书（CN=kimi.waibozishu.com），无需手动配置证书。
> 与 codex 共用 8443 不会冲突：Caddy 按 SNI（TLS 握手域名）区分路由到不同后端。

## 5. 端到端验证

```bash
TOKEN=$(cat ~/.kimi-code/server.token)

# 本机
curl -s http://127.0.0.1:58627/api/v1/healthz
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:58627/api/v1/sessions

# 公网（本地电脑执行）
curl -s https://kimi.waibozishu.com:8443/api/v1/healthz
curl -s -H "Authorization: Bearer $TOKEN" https://kimi.waibozishu.com:8443/api/v1/sessions

# 客户端本地 DNS 异常时的链路诊断：--resolve 绕过本地解析，直接打到服务器 IP
curl -v --resolve kimi.waibozishu.com:8443:<服务器IP> https://kimi.waibozishu.com:8443/api/v1/healthz

# 协议文档（给 App 开发者用，真理之源）
curl -s -H "Authorization: Bearer $TOKEN" https://kimi.waibozishu.com:8443/openapi.json   # REST
curl -s -H "Authorization: Bearer $TOKEN" https://kimi.waibozishu.com:8443/asyncapi.json  # WS
```

## 6. App 对接要点（给客户端开发）

与 codex 的核心差异：**控制面是 REST，不是 JSON-RPC over WS**。

| 操作 | 接口 |
|---|---|
| 健康检查 | `GET /api/v1/healthz`（无需鉴权） |
| 建会话 | `POST /api/v1/sessions` |
| 会话列表 | `GET /api/v1/sessions` |
| 读会话 | `GET /api/v1/sessions/{id}` |
| 归档/恢复 | `POST /api/v1/sessions/{id}:archive` / `:restore` |
| 发消息 | `POST /api/v1/sessions/{sid}/prompts` |
| 打断 | `POST /api/v1/sessions/{sid}/prompts/{pid}:abort` |
| 事件流 | `WS /api/v1/ws`：`client_hello` → `subscribe` → 收 `assistant.delta`/`thinking.delta`/`turn.started`/`turn.ended` |

认证（两种方式，**推荐第一种**）：
1. REST 和 WS 都直接带 `Authorization: Bearer <token>` 头（WS 升级请求也支持，OkHttp 可用）；
2. WS 备选：`Sec-WebSocket-Protocol: kimi-code.bearer.<token>` 子协议（给不能改 header 的浏览器用）。

WS 断线重连：`subscribe` 可带 `{seq, epoch}` 游标**重放漏掉的事件**——比 codex（不重放、需 read 对账）好处理。

## 7. 已知坑（部署时核对）

1. **Host 检查 403**：`--allowed-host <域名>` 没加，或域名写错。错误信息会提示正确的 flag 用法。
   快速定位：服务器上 `curl -s -H "Host: kimi.waibozishu.com" http://127.0.0.1:58627/api/v1/healthz`——返回 403（`code:40301`）即未放行，响应体会直接给出修复命令。
2. **401**：token 错了，或拿了旧 token（重装了 `~/.kimi-code`）。重新 `cat server.token`。
3. **它是前台进程**：必须用 systemd 包，别指望 `--daemon`（不存在；旧的 `kimi server` 已废弃）。
4. **WorkingDirectory 别忘**：不设的话 cwd 是 `/`（codex 部署时踩过）。
5. 非 loopback 直连暴露（不走 Caddy）需要额外的 `--insecure-no-tls` 等 flag——本指南全程 loopback + Caddy TLS，不涉及。
6. `POST /api/v1/shutdown` 和 PTY 终端路由在非 loopback 默认禁用（`--allow-remote-shutdown` / `--allow-remote-terminals`），**不要开**。
7. **客户端本地 DNS 缓存**（实测踩过）：`nslookup` 能解析但 `curl` 报 `Could not resolve host`——是 macOS 系统解析缓存（mDNSResponder）的问题，`nslookup` 走的是另一条路径。清缓存：`sudo dscacheutil -flushcache && sudo killall -HUP mDNSResponder`；不想清缓存就用 `curl --resolve` 绕过。
8. **curl 静默失败**：`curl -s` 出错时也要看 stderr（`curl -v` 看全链路：DNS → TCP → TLS → HTTP 状态码），空输出不代表请求成功。

## 8. 重启/维护

```bash
sudo systemctl restart kimi-web
journalctl -u kimi-web -f
```

注意：重启会断所有 WS 连接和正在进行的 prompt（与 codex 相同，重启前确认没有长任务在跑）。
