// claude_server.js — Claude Code 套皮服务（本地/云服务器均可）
//
// 协议（对齐官方 direct-connect / CLAUDE_DEPLOY.md）：
//   - 控制面：REST  GET /healthz、POST /sessions、GET /sessions、GET /sessions/{id}、
//              GET /sessions/{id}/messages、DELETE /sessions/{id}
//   - 事件面：WebSocket /sessions/{id}/ws，NDJSON 一行一个 JSON
//     → 客户端发：user / control_response / control_request(interrupt)
//     → 服务端发：system/init / stream_event / control_request(can_use_tool) / result / error / keep_alive
//   - 鉴权：Bearer token（AUTH_TOKEN env > ~/.claude/server-token > 自动生成持久化）
//
// 会话：SDK query() 每次拉起一个 claude 子进程；claude 会话落盘 ~/.claude/projects/<cwd>/，
// 按 claude session id resume。serverId（App 看到的 threadId）与 claude session id 的映射
// 持久化到 ~/.claude/server-sessions.json，服务重启后 App 可继续 resume。
//
// 历史会话（GET /sessions 列表 / messages）直接扫 ~/.claude/projects/*/*.jsonl——
// 因此 App 能看到本机 CLI 开过的所有 claude 会话。

import http from 'node:http'
import crypto from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { WebSocketServer } from 'ws'
import { query } from '@anthropic-ai/claude-agent-sdk'

const PORT = parseInt(process.env.PORT || '58628')
const HOST = process.env.HOST || '127.0.0.1'
const WORKSPACE = process.env.WORKSPACE || os.homedir()
const MAX_SESSIONS = parseInt(process.env.MAX_SESSIONS || '4')
const QUERY_TIMEOUT_MS = parseInt(process.env.QUERY_TIMEOUT_MS || (30 * 60 * 1000))  // SDK 默认 10min 太短
const TOKEN_FILE = path.join(os.homedir(), '.claude', 'server-token')
const SESSIONS_FILE = path.join(os.homedir(), '.claude', 'server-sessions.json')
const PROJECTS_DIR = path.join(os.homedir(), '.claude', 'projects')

// ---- token：环境变量 > 文件 > 自动生成并持久化 ----
let token = process.env.AUTH_TOKEN
if (!token && fs.existsSync(TOKEN_FILE)) token = fs.readFileSync(TOKEN_FILE, 'utf8').trim()
if (!token) {
  token = 'sk-ant-cc-' + crypto.randomBytes(16).toString('base64url')
  fs.mkdirSync(path.dirname(TOKEN_FILE), { recursive: true })
  fs.writeFileSync(TOKEN_FILE, token, { mode: 0o600 })
}

// ---- 会话表：serverId -> { claudeSessionId, cwd, dsp, ws, abort, pending, createdAt } ----
// claudeSessionId 在首轮 system/init 后补上；映射持久化到 SESSIONS_FILE。
const sessions = new Map()
const persisted = loadPersisted()

function loadPersisted() {
  try {
    return JSON.parse(fs.readFileSync(SESSIONS_FILE, 'utf8'))
  } catch {
    return {}
  }
}

function persist() {
  const out = {}
  for (const [serverId, s] of sessions) {
    if (s.claudeSessionId || persisted[serverId]) {
      out[serverId] = {
        claude_session_id: s.claudeSessionId || serverId,
        cwd: s.cwd,
        created_at: s.createdAt,
      }
    }
  }
  for (const [serverId, meta] of Object.entries(persisted)) {
    if (!out[serverId]) out[serverId] = meta
  }
  try {
    fs.mkdirSync(path.dirname(SESSIONS_FILE), { recursive: true })
    fs.writeFileSync(SESSIONS_FILE, JSON.stringify(out, null, 2), { mode: 0o600 })
  } catch (e) {
    console.error('[claude-web] persist sessions failed:', e.message)
  }
}

function authOk(req) {
  const a = req.headers.authorization
  return a === `Bearer ${token}`
}

// ---- jsonl 会话文件：~/.claude/projects/<cwd编码>/<sessionId>.jsonl ----

function findJsonl(sessionId) {
  // 会话映射：serverId → claudeSessionId（jsonl 文件名是 claude session id）
  const mapped = persisted[sessionId]?.claude_session_id || sessions.get(sessionId)?.claudeSessionId
  const candidates = [...new Set([sessionId, mapped].filter(Boolean))]
  for (const candidate of candidates) {
    const direct = path.join(PROJECTS_DIR, candidate + '.jsonl')
    if (fs.existsSync(direct)) return direct
  }
  // serverId 可能不在 projects 根：扫描所有子目录按文件名匹配
  let dirs = []
  try { dirs = fs.readdirSync(PROJECTS_DIR) } catch { return null }
  for (const dir of dirs) {
    for (const candidate of candidates) {
      const file = path.join(PROJECTS_DIR, dir, candidate + '.jsonl')
      if (fs.existsSync(file)) return file
    }
  }
  return null
}

/** 读 jsonl 前几行提取 cwd（首行可能是 mode/permission-mode，无 cwd 字段）；失败返回 null。 */
function cwdOfJsonl(file) {
  try {
    const handle = fs.openSync(file, 'r')
    try {
      const buf = Buffer.alloc(1 << 20)
      const n = fs.readSync(handle, buf, 0, buf.length, 0)
      for (const line of buf.subarray(0, n).toString('utf8').split('\n')) {
        if (!line.trim()) continue
        let obj
        try { obj = JSON.parse(line) } catch { continue }
        if (obj.cwd) return obj.cwd
      }
    } finally {
      fs.closeSync(handle)
    }
  } catch { /* 读失败忽略 */ }
  return null
}

/** 提取首个非 meta 的 user 文本（会话标题用）；最多扫 500 行。 */
function firstUserPrompt(file) {
  try {
    const handle = fs.openSync(file, 'r')
    try {
      const buf = Buffer.alloc(1 << 20) // 1MB 足够绝大多数会话找到首条用户消息
      const n = fs.readSync(handle, buf, 0, buf.length, 0)
      for (const line of buf.subarray(0, n).toString('utf8').split('\n')) {
        if (!line.trim()) continue
        let obj
        try { obj = JSON.parse(line) } catch { continue }
        if (obj.type !== 'user' || obj.isMeta) continue
        const text = extractUserText(obj.message)
        if (text && !text.startsWith('<')) return text.slice(0, 60)
      }
    } finally {
      fs.closeSync(handle)
    }
  } catch { /* 读失败忽略 */ }
  return null
}

function extractUserText(message) {
  if (!message) return ''
  const c = message.content
  if (typeof c === 'string') return c
  if (Array.isArray(c)) {
    return c.filter(b => b && b.type === 'text').map(b => b.text).join('')
  }
  return ''
}

/** 会话列表：扫全部 jsonl，按文件 mtime 倒序；过滤已删除的。 */
function listSessionFiles() {
  const out = []
  let dirs = []
  try { dirs = fs.readdirSync(PROJECTS_DIR) } catch { return out }
  for (const dir of dirs) {
    let files = []
    try { files = fs.readdirSync(path.join(PROJECTS_DIR, dir)).filter(f => f.endsWith('.jsonl')) } catch { continue }
    for (const file of files) {
      const full = path.join(PROJECTS_DIR, dir, file)
      const id = file.slice(0, -'.jsonl'.length)
      if (persisted[id]?.deleted) continue
      let stat
      try { stat = fs.statSync(full) } catch { continue }
      out.push({
        session_id: id,
        cwd: cwdOfJsonl(full),
        updated_at: Math.floor(stat.mtimeMs / 1000),
        created_at: Math.floor((stat.birthtimeMs || stat.mtimeMs) / 1000),
        last_prompt: firstUserPrompt(full),
      })
    }
  }
  out.sort((a, b) => b.updated_at - a.updated_at)
  return out
}

/** 解析 jsonl 为消息列表：过滤非 user/assistant 行与 isMeta 注入行。 */
function readMessages(file) {
  const items = []
  try {
    for (const line of fs.readFileSync(file, 'utf8').split('\n')) {
      if (!line.trim()) continue
      let obj
      try { obj = JSON.parse(line) } catch { continue }
      if (obj.type !== 'user' && obj.type !== 'assistant') continue
      if (obj.isMeta) continue
      const message = obj.message || {}
      const content = typeof message.content === 'string'
        ? [{ type: 'text', text: message.content }]
        : (Array.isArray(message.content) ? message.content : [])
      items.push({
        type: obj.type,
        role: message.role || obj.type,
        content,
        timestamp: obj.timestamp || '',
        session_id: obj.sessionId || obj.session_id || '',
      })
    }
  } catch { /* 解析失败返回已读部分 */ }
  return items
}

// ---- HTTP：健康检查 + 会话 CRUD（与官方 createDirectConnectSession 同构）----
const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost')
  const sendJson = (code, obj) => {
    res.writeHead(code, { 'content-type': 'application/json' })
    res.end(JSON.stringify(obj))
  }

  if (req.method === 'GET' && url.pathname === '/healthz') {
    res.writeHead(200); res.end('ok'); return
  }

  if (!authOk(req)) { res.writeHead(401); res.end('unauthorized'); return }

  if (req.method === 'POST' && url.pathname === '/sessions') {
    let body = ''
    req.on('data', c => (body += c))
    req.on('end', () => {
      let cwd = WORKSPACE, dsp = false
      try {
        const j = JSON.parse(body || '{}')
        cwd = j.cwd || WORKSPACE
        dsp = !!j.dangerously_skip_permissions
      } catch { /* 默认值 */ }
      if (sessions.size >= MAX_SESSIONS) { res.writeHead(429); res.end('too many sessions'); return }
      const id = crypto.randomUUID()
      sessions.set(id, { claudeSessionId: null, cwd, dsp, ws: null, abort: null, pending: new Map(), createdAt: Math.floor(Date.now() / 1000) })
      persist()
      // 返回的 ws_url 用 Host 头（Caddy 反代后即公网域名）；App 端把 ws:// 换成 wss://
      sendJson(200, {
        session_id: id,
        ws_url: `ws://${req.headers.host}/sessions/${id}/ws`,
        work_dir: cwd,
      })
    })
    return
  }

  // GET /sessions：历史会话列表（含 CLI 直接开过的）
  if (req.method === 'GET' && url.pathname === '/sessions') {
    const limit = Math.min(parseInt(url.searchParams.get('limit') || '100') || 100, 500)
    const all = listSessionFiles()
    // 当前活跃会话也补进去（jsonl 可能还没落盘）
    for (const [serverId, s] of sessions) {
      if (all.some(i => i.session_id === serverId)) continue
      all.push({
        session_id: serverId,
        cwd: s.cwd,
        updated_at: Math.floor(Date.now() / 1000),
        created_at: s.createdAt,
        last_prompt: '',
      })
    }
    all.sort((a, b) => b.updated_at - a.updated_at)
    sendJson(200, { items: all.slice(0, limit) })
    return
  }

  const m = url.pathname.match(/^\/sessions\/([^/]+)$/)
  if (m && req.method === 'GET') {
    const id = m[1]
    const active = sessions.get(id)
    const file = findJsonl(id)
    if (!active && !file) { res.writeHead(404); res.end('session not found'); return }
    const cwd = active?.cwd || cwdOfJsonl(file) || ''
    sendJson(200, {
      session_id: id,
      cwd,
      work_dir: cwd || WORKSPACE,
      active: !!active,
      claude_session_id: active?.claudeSessionId || persisted[id]?.claude_session_id || id,
      updated_at: active ? Math.floor(Date.now() / 1000) : (file ? Math.floor(fs.statSync(file).mtimeMs / 1000) : 0),
      last_prompt: file ? (firstUserPrompt(file) || '') : '',
    })
    return
  }

  if (m && req.method === 'DELETE') {
    sessions.delete(m[1])
    // 标记删除（不删 jsonl，本地历史保留）；列表过滤用
    persisted[m[1]] = { ...(persisted[m[1]] || {}), deleted: true }
    persist()
    sendJson(200, { ok: true })
    return
  }

  const hm = url.pathname.match(/^\/sessions\/([^/]+)\/messages$/)
  if (hm && req.method === 'GET') {
    const id = hm[1]
    const active = sessions.get(id)
    const file = findJsonl(id)
    if (!active && !file) { res.writeHead(404); res.end('session not found'); return }
    const cwd = active?.cwd || cwdOfJsonl(file) || ''
    const items = file ? readMessages(file) : []
    sendJson(200, { session_id: id, cwd, items })
    return
  }

  res.writeHead(404); res.end('not found')
})

// ---- WebSocket：NDJSON 消息（对齐官方 directConnectManager 的 wire 协议）----
const wss = new WebSocketServer({ server })

wss.on('connection', (ws, req) => {
  const m = req.url?.match(/^\/sessions\/([^/]+)\/ws$/)
  const s = m && sessions.get(m[1])
  if (!s) { ws.close(4004, 'unknown session'); return }
  // resume 目标（claude session id）：持久化映射 > jsonl 文件名（= claude session id）> 全新会话无
  if (!s.claudeSessionId) {
    const meta = persisted[m[1]]
    if (meta?.claude_session_id) {
      s.claudeSessionId = meta.claude_session_id
    } else if (findJsonl(m[1])) {
      s.claudeSessionId = m[1]
    }
  }
  if (s.ws && s.ws.readyState === 1) s.ws.close()   // 同会话重连：踢掉旧连接
  s.ws = ws

  const send = obj => { if (ws.readyState === 1) ws.send(JSON.stringify(obj)) }
  const ka = setInterval(() => send({ type: 'keep_alive' }), 30000)  // 防代理空闲超时

  // 把 App 的一条 user 消息跑一轮 SDK query；后续消息用 resume 续上下文
  const run = async text => {
    if (s.abort) { send({ type: 'error', error: 'turn already in progress' }); return }
    const abort = new AbortController()
    s.abort = abort
    const timeout = setTimeout(() => abort.abort(), QUERY_TIMEOUT_MS)
    try {
      // SDK ≥0.3.223：query({ prompt, options }) 单参数形状，
      // permissionMode/canUseTool/resume/abortController 都进 options（0.3.221 的顶层参数已废弃）。
      const messages = query({
        prompt: text,
        options: {
          cwd: s.cwd,
          permissionMode: s.dsp ? 'bypassPermissions' : 'default',
          // bypassPermissions 的安全开关，官方 SDK 必填
          allowDangerouslySkipPermissions: s.dsp,
          resume: s.claudeSessionId || undefined,
          abortController: abort,
          // 审批回调：把请求转发给 App，等 control_response
          canUseTool: (toolName, input, opts) => new Promise(resolve => {
            s.pending.set(opts.requestId, resolve)
            send({ type: 'control_request', request_id: opts.requestId, request: {
              subtype: 'can_use_tool', tool_name: toolName, input,
              title: opts.title, displayName: opts.displayName, description: opts.description,
            }})
            // 打断/轮次结束时未答的审批一律按拒绝处理，避免挂死
            opts.signal.addEventListener('abort', () => {
              if (s.pending.delete(opts.requestId)) resolve({ behavior: 'deny', message: 'interrupted' })
            })
          }),
        },
      })
      for await (const msg of messages) {
        // SDK ≥0.3.223 事件形状：system(init/thinking_tokens...) / assistant / result / error。
        // assistant 的 content 数组（text/thinking/tool_use 块）与 0.3.221 的 stream_event 同构，原样转发。
        if (msg.type === 'system' && msg.subtype === 'init') {
          if (msg.session_id) {
            s.claudeSessionId = msg.session_id
            persist()
          }
          send(msg)
        } else if (msg.type === 'assistant') {
          send(msg)
        } else if (msg.type === 'result') {
          if (msg.session_id) {
            s.claudeSessionId = msg.session_id
            persist()
          }
          send(msg)
          // 提前释放轮锁：客户端收到 result 后可能立刻发下一条消息，
          // 若等到 finally 再清 s.abort，会撞上 "turn already in progress" 竞态。
          s.abort = null
        } else if (msg.type === 'error') {
          send(msg)
        }
        // 其余 system 噪音（thinking_tokens 等）App 不需要，过滤
      }
    } catch (e) {
      if (abort.signal.aborted) {
        // 主动中止（interrupt 帧或 QUERY_TIMEOUT_MS 超时）：语义化收尾，App 端识别为 interrupted
        send({ type: 'result', subtype: 'interrupted', stop_reason: 'interrupted', is_error: false })
      } else {
        send({ type: 'error', error: { type: 'error', message: String(e?.message ?? e) } })
      }
    } finally {
      clearTimeout(timeout)
      s.abort = null
    }
  }

  ws.on('message', raw => {
    for (const line of String(raw).split('\n')) {
      if (!line.trim()) continue
      let msg
      try { msg = JSON.parse(line) } catch { continue }
      if (msg.type === 'user') {
        // 与官方 SDKUserMessage 格式一致：取 content 里所有 text block
        const text = (msg.message?.content ?? [])
          .filter(b => b.type === 'text')
          .map(b => b.text).join('')
        if (text) run(text)
      } else if (msg.type === 'control_response') {
        // 与官方 SDKControlResponse 格式一致：{subtype:'success', request_id, response:{behavior,...}}
        const outer = msg.response ?? {}
        const resolve = s.pending.get(outer.request_id)
        if (!resolve) continue
        s.pending.delete(outer.request_id)
        const r = outer.response ?? {}
        if (r.behavior === 'deny') {
          resolve({ behavior: 'deny', message: r.message || 'declined by user' })
        } else {
          resolve({ behavior: 'allow' })          // 当前 SDK 只支持 allow/deny，allowOnce 见文档 §7
        }
      } else if (msg.type === 'control_request' && msg.request?.subtype === 'interrupt') {
        s.abort?.abort()                          // 打断当前轮（= 官方 sendInterrupt）
      }
    }
  })

  ws.on('close', () => {
    clearInterval(ka)
    s.ws = null
    for (const [rid, resolve] of s.pending) { resolve({ behavior: 'deny', message: 'client disconnected' }) }
    s.pending.clear()
  })
  ws.on('error', () => clearInterval(ka))
})

server.listen(PORT, HOST, () => {
  console.log(`[claude-web] listening on ${HOST}:${PORT}`)
  console.log(`[claude-web] token: ${token}  (file: ${TOKEN_FILE})`)
  console.log(`[claude-web] workspace: ${WORKSPACE}  maxSessions: ${MAX_SESSIONS}  queryTimeout: ${QUERY_TIMEOUT_MS}ms`)
})
