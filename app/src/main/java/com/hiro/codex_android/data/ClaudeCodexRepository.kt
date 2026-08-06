package com.hiro.codex_android.data

import com.hiro.codex_android.data.model.ApprovalDecision
import com.hiro.codex_android.data.model.Content
import com.hiro.codex_android.data.model.ModelInfo
import com.hiro.codex_android.data.model.ReviewStartResult
import com.hiro.codex_android.data.model.ReviewTarget
import com.hiro.codex_android.data.model.Thread
import com.hiro.codex_android.data.model.ThreadItem
import com.hiro.codex_android.data.model.ThreadStatus
import com.hiro.codex_android.data.model.TokenUsage
import com.hiro.codex_android.data.model.Turn
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/**
 * Claude Code 套皮服务适配：REST 控制面（POST/GET /sessions）+ 每会话一条 WS（NDJSON），
 * 对外仍实现 [CodexRepository]，让聊天/列表 UI 无需感知协议差异。
 *
 * 协议依据：claude-server/README.md、~/codex/CLAUDE_DEPLOY.md（wire 形状对齐官方 direct-connect）。
 * 与服务端（claude_server.js）的差异点：
 * - 会话是 per-WS 的：每个打开的会话各自一条 WebSocket，事件统一汇入 [events]。
 * - Claude 无模型/推理档位概念：listModels 返回空、updateThreadSettings 不支持。
 * - 工具执行无输出流（SDK 只转发 assistant 消息），工具卡片在下一段文本或 result 时收尾。
 */
class ClaudeCodexRepository(
    private val profile: AgentProfile,
) : CodexRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val httpBase: String
    /** profile.serverUrl 的 WS scheme（ws / wss），用于把服务端返回的 ws_url 对齐。 */
    private val wsScheme: String

    private val _events = MutableSharedFlow<CodexEvent>(extraBufferCapacity = 128)
    override val events: SharedFlow<CodexEvent> = _events.asSharedFlow()

    /** serverSessionId → 打开的会话（各自独立 WS 连接）。 */
    private val sessions = ConcurrentHashMap<String, Session>()
    /** 本地审批 key → (serverSessionId, 服务端 request_id)。 */
    private val approvalIdByKey = ConcurrentHashMap<Int, Pair<String, String>>()
    private val nextApprovalKey = AtomicInteger(1)

    private val incoming = Channel<Pair<Session, JSONObject>>(Channel.UNLIMITED)

    init {
        val (http, ws) = deriveBases(profile.serverUrl)
        httpBase = http
        wsScheme = ws.substringBefore("://")
        scope.launch {
            for ((session, frame) in incoming) handleFrame(session, frame)
        }
    }

    override fun close() {
        sessions.values.forEach { it.close() }
        sessions.clear()
        approvalIdByKey.clear()
        scope.cancel()
    }

    // ── CodexRepository 接口 ──────────────────────────────────────────────

    override suspend fun initialize(clientName: String, version: String) {
        require(profile.serverUrl.isNotBlank()) { "请先在设置中填写 Claude 服务器地址" }
        require(profile.token.isNotBlank()) { "请先在设置中填写 Claude Token" }
        val ok = withContext(Dispatchers.IO) {
            val request = Request.Builder().url("$httpBase/healthz").build()
            runCatching { client.newCall(request).execute().use { it.isSuccessful } }
                .getOrDefault(false)
        }
        if (!ok) throw IOException("Claude 服务器连接失败，请检查地址与 Token（healthz）")
    }

    override suspend fun listThreads(cursor: String?, limit: Int): ThreadPage {
        val data = restGet("/sessions?limit=${limit.coerceIn(1, 100)}")
        val items = data.optJSONArray("items").objects().map(::parseSession)
        return ThreadPage(items, null)
    }

    override suspend fun startThread(model: String?): Thread {
        val body = JSONObject()
            .put("dangerously_skip_permissions", false)
            .also { json ->
                val cwd = profile.defaultCwd.trim()
                if (cwd.isNotBlank()) json.put("cwd", cwd)
            }
        val resp = restPost("/sessions", body)
        val id = resp.optString("session_id")
        if (id.isBlank()) throw IOException("Claude 建会话响应缺少 session_id")
        registerSession(id, resp)
        return Thread(id = id, status = ThreadStatus("idle"), cwd = resp.optString("work_dir"))
    }

    override suspend fun resumeThread(threadId: String, model: String?): Thread {
        val resp = restGet("/sessions/$threadId")
        registerSession(threadId, resp)
        return parseSession(resp)
    }

    override suspend fun readThread(threadId: String, includeTurns: Boolean): Thread {
        val meta = restGet("/sessions/$threadId")
        registerSession(threadId, meta)
        val items = if (includeTurns) loadMessages(threadId) else emptyList()
        return parseSession(meta).copy(
            turns = listOf(Turn(id = "history", status = "completed", items = items)),
        )
    }

    override suspend fun archiveThread(threadId: String) {
        // Claude 无归档概念：等同删除（服务端移除映射，本地 jsonl 保留）。
        deleteThread(threadId)
    }

    override suspend fun deleteThread(threadId: String) {
        runCatching { restDelete("/sessions/$threadId") }
        sessions.remove(threadId)?.close()
    }

    override suspend fun startTurn(threadId: String, input: List<Content>): Turn {
        val session = sessions[threadId] ?: throw IOException("会话未打开，请先进入会话")
        val text = input.filter { it.type == "text" }.joinToString("") { it.text }
        if (text.isBlank()) throw IOException("空消息")
        session.connect()
        val turnId = "turn-${session.nextTurnId.getAndIncrement()}"
        session.activeTurnId = turnId
        val frame = JSONObject()
            .put("type", "user")
            .put(
                "message",
                JSONObject()
                    .put("role", "user")
                    .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text))),
            )
            .put("parent_tool_use_id", JSONObject.NULL)
            .put("session_id", "")
        if (session.socket?.send(frame.toString()) != true) {
            session.activeTurnId = null
            throw IOException("WebSocket 未连接，无法发送消息")
        }
        _events.emit(CodexEvent.TurnStarted(threadId, turnId))
        return Turn(id = turnId, status = "inProgress")
    }

    override suspend fun interruptTurn(threadId: String, turnId: String) {
        val session = sessions[threadId] ?: return
        if (!session.wsReady) return
        session.socket?.send(
            JSONObject()
                .put("type", "control_request")
                .put("request_id", UUID.randomUUID().toString())
                .put("request", JSONObject().put("subtype", "interrupt"))
                .toString(),
        )
    }

    override suspend fun respondApproval(requestId: Int, decision: ApprovalDecision) {
        val (sessionId, claudeRequestId) = approvalIdByKey.remove(requestId)
            ?: throw IOException("未知的审批请求：$requestId")
        val session = sessions[sessionId] ?: throw IOException("会话已关闭")
        val behavior = when (decision) {
            ApprovalDecision.Accept, ApprovalDecision.AcceptForSession -> "allow"
            ApprovalDecision.Decline, ApprovalDecision.Cancel -> "deny"
        }
        val frame = JSONObject()
            .put("type", "control_response")
            .put(
                "response",
                JSONObject()
                    .put("subtype", "success")
                    .put("request_id", claudeRequestId)
                    .put("response", JSONObject().put("behavior", behavior)),
            )
        if (session.socket?.send(frame.toString()) != true) {
            throw IOException("WebSocket 未连接，无法应答审批")
        }
        if (decision == ApprovalDecision.Cancel) {
            interruptTurn(sessionId, session.activeTurnId.orEmpty())
        }
    }

    override suspend fun listModels(): List<ModelInfo> = emptyList()

    override suspend fun updateThreadSettings(threadId: String, model: String?, effort: String?) {
        throw IOException("Claude 暂不支持切换模型/推理档位")
    }

    override suspend fun startCompact(threadId: String) {
        throw IOException("Claude 暂不支持 /compact")
    }

    override suspend fun startReview(
        threadId: String,
        target: ReviewTarget,
        delivery: String,
    ): ReviewStartResult {
        throw IOException("Claude 暂不支持 /review")
    }

    override suspend fun forkThread(threadId: String, lastTurnId: String?): Thread {
        throw IOException("Claude 暂不支持 /fork")
    }

    override suspend fun rollbackThread(threadId: String, numTurns: Int): Thread {
        throw IOException("Claude 暂不支持 /undo")
    }

    override suspend fun shellCommand(threadId: String, command: String) {
        throw IOException("Claude 暂不支持 !shell（请直接发消息让 agent 执行）")
    }

    // ── REST ──────────────────────────────────────────────────────────────

    private fun registerSession(serverId: String, resp: JSONObject) {
        if (sessions.containsKey(serverId)) return
        val rawWs = resp.optString("ws_url")
        val wsUrl = if (rawWs.isNotBlank()) {
            normalizeWsUrl(rawWs)
        } else {
            // 历史会话（GET /sessions/{id}）不带 ws_url：按同一模式拼。
            "$wsScheme://${httpBase.substringAfter("://")}/sessions/$serverId/ws"
        }
        sessions[serverId] = Session(serverId, resp.optString("work_dir").ifBlank { resp.optString("cwd") }, wsUrl)
    }

    private fun normalizeWsUrl(raw: String): String = when {
        wsScheme == "wss" && raw.startsWith("ws://") -> "wss://" + raw.removePrefix("ws://")
        wsScheme == "ws" && raw.startsWith("wss://") -> "ws://" + raw.removePrefix("wss://")
        else -> raw
    }

    private suspend fun loadMessages(threadId: String): List<ThreadItem> {
        val data = restGet("/sessions/$threadId/messages")
        return ensureUniqueIds(parseMessageItems(data.optJSONArray("items")))
    }

    private suspend fun restGet(path: String): JSONObject = rest("GET", path, null)

    private suspend fun restPost(path: String, body: JSONObject): JSONObject = rest("POST", path, body)

    private suspend fun restDelete(path: String): JSONObject = rest("DELETE", path, null)

    private suspend fun rest(method: String, path: String, body: JSONObject?): JSONObject =
        withContext(Dispatchers.IO) {
            val requestBody = body?.toString()?.toRequestBody(jsonMedia)
            val request = Request.Builder()
                .url(httpBase + path)
                .header("Authorization", "Bearer ${profile.token}")
                .method(method, if (method == "GET" || method == "DELETE") null else (requestBody ?: "{}".toRequestBody(jsonMedia)))
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                when {
                    !response.isSuccessful -> {
                        val hint = when (response.code) {
                            401 -> "Token 无效或已过期，请检查设置"
                            404 -> "会话不存在或已过期，请新建会话"
                            429 -> "服务器会话数已达上限，稍后再试"
                            else -> "HTTP ${response.code}"
                        }
                        throw IOException("Claude 错误（$hint）：${text.take(200)}")
                    }
                    text.isBlank() -> JSONObject()
                    else -> runCatching { JSONObject(text) }.getOrElse {
                        throw IOException("Claude 响应解析失败：${text.take(200)}")
                    }
                }
            }
        }

    // ── WS ────────────────────────────────────────────────────────────────

    /**
     * 一个打开中的会话。Claude 协议是每会话一条 WS：建会话/恢复时注册，
     * 首次发消息时才真正连接（惰性），断线后下一次 startTurn 自动重连。
     */
    private inner class Session(
        val serverId: String,
        val cwd: String,
        val wsUrl: String,
    ) {
        @Volatile var socket: WebSocket? = null
        @Volatile var wsReady = false
        val nextTurnId = AtomicInteger(1)
        val nextThinkId = AtomicInteger(1)
        val nextToolId = AtomicInteger(1)
        @Volatile var activeTurnId: String? = null
        val startedAssistant = ConcurrentHashMap.newKeySet<String>()
        val startedReasoning = ConcurrentHashMap.newKeySet<String>()
        val startedTools = ConcurrentHashMap.newKeySet<String>()
        val activeTools = ConcurrentHashMap.newKeySet<String>()
        private val connectMutex = Mutex()

        suspend fun connect() {
            connectMutex.withLock {
                if (wsReady && socket != null) return
                socket?.cancel()
                socket = null
                val opened = CompletableDeferred<Unit>()
                val request = Request.Builder()
                    .url(wsUrl)
                    .header("Authorization", "Bearer ${profile.token}")
                    .build()
                socket = client.newWebSocket(request, listener(opened))
                try {
                    withTimeout(CONNECT_TIMEOUT_MS) { opened.await() }
                    wsReady = true
                } catch (e: Exception) {
                    socket?.cancel()
                    socket = null
                    wsReady = false
                    throw IOException("连接 Claude 失败：${e.message}", e)
                }
            }
        }

        fun close() {
            socket?.close(1000, "profile changed")
            socket = null
            wsReady = false
        }

        private fun listener(opened: CompletableDeferred<Unit>) = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                for (line in text.split('\n')) {
                    if (line.isBlank()) continue
                    runCatching { JSONObject(line) }
                        .onSuccess { incoming.trySend(this@Session to it) }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                wsReady = false
                opened.completeExceptionally(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                wsReady = false
            }
        }
    }

    private suspend fun handleFrame(session: Session, frame: JSONObject) {
        when (frame.optString("type")) {
            // SDK ≥0.3.223：流式 assistant 消息是顶层事件，message 即 SDKAssistantMessage
            // （content 数组为 text/thinking/tool_use 块，与 0.3.221 的 stream_event 同构）。
            "assistant" -> handleAssistant(session, frame)
            "control_request" -> {
                val request = frame.optJSONObject("request") ?: return
                if (request.optString("subtype") != "can_use_tool") return
                val key = nextApprovalKey.getAndIncrement()
                val requestId = frame.optString("request_id")
                approvalIdByKey[key] = session.serverId to requestId
                val command = buildString {
                    append(request.optString("tool_name"))
                    request.opt("input")?.let { input ->
                        if (input != JSONObject.NULL) append(' ').append(input.toString().take(200))
                    }
                }.trim()
                _events.emit(
                    CodexEvent.ApprovalRequest(
                        requestId = key,
                        threadId = session.serverId,
                        turnId = session.activeTurnId.orEmpty(),
                        itemId = requestId,
                        command = command,
                        cwd = "",
                        reason = "需要批准工具调用",
                    ),
                )
            }
            "result" -> {
                completeActiveTools(session)
                val subtype = frame.optString("subtype")
                val status = when (subtype) {
                    "success" -> "completed"
                    "interrupted" -> "interrupted"
                    else -> "failed"
                }
                val error = frame.optJSONObject("error")?.optString("message")
                session.activeTurnId?.let { turnId ->
                    _events.emit(CodexEvent.TurnCompleted(session.serverId, turnId, status, error))
                }
                session.activeTurnId = null
            }
            "error" -> {
                completeActiveTools(session)
                val error = frame.optJSONObject("error")?.optString("message")
                    ?: frame.optString("error").takeIf { it.isNotBlank() }
                session.activeTurnId?.let { turnId ->
                    _events.emit(CodexEvent.TurnCompleted(session.serverId, turnId, "failed", error))
                }
                session.activeTurnId = null
            }
            // system/init（claude 会话 id，服务端自管 resume）、keep_alive：忽略
        }
    }

    private suspend fun handleAssistant(session: Session, frame: JSONObject) {
        val message = frame.optJSONObject("message") ?: return
        val blocks = message.optJSONArray("content")?.objects() ?: emptyList()
        for (block in blocks) {
            when (block.optString("type")) {
                "text" -> {
                    // 文本块出现说明此前的工具已执行完，先收尾工具卡片。
                    completeActiveTools(session)
                    val delta = block.optString("text")
                    if (delta.isEmpty()) continue
                    val itemId = assistantItemId(session)
                    if (session.startedAssistant.add(itemId)) {
                        _events.emit(CodexEvent.ItemStarted(session.serverId, ThreadItem.AgentMessage(itemId, "")))
                    }
                    _events.emit(CodexEvent.AgentMessageDelta(session.serverId, itemId, delta))
                }
                "thinking" -> {
                    val delta = block.optString("thinking").ifBlank { block.optString("text") }
                    if (delta.isEmpty()) continue
                    val itemId = reasoningItemId(session)
                    if (session.startedReasoning.add(itemId)) {
                        _events.emit(
                            CodexEvent.ItemStarted(session.serverId, ThreadItem.Reasoning(itemId, listOf(""))),
                        )
                    }
                    _events.emit(CodexEvent.ReasoningSummaryDelta(session.serverId, itemId, 0, delta))
                }
                "tool_use" -> {
                    val id = block.optString("id").ifBlank { "tool-${session.nextToolId.getAndIncrement()}" }
                    if (session.startedTools.add(id)) {
                        val command = buildString {
                            append(block.optString("name"))
                            block.opt("input")?.let { input ->
                                if (input != JSONObject.NULL) append(' ').append(input.toString())
                            }
                        }.trim()
                        session.activeTools.add(id)
                        _events.emit(
                            CodexEvent.ItemStarted(
                                session.serverId,
                                ThreadItem.CommandExecution(id, command, status = "inProgress"),
                            ),
                        )
                    }
                }
            }
        }
    }

    /** 工具卡片收尾：claude 无工具输出流，下一段文本或 result 时统一标记 completed。 */
    private suspend fun completeActiveTools(session: Session) {
        for (id in session.activeTools) {
            _events.emit(
                CodexEvent.ItemCompleted(
                    session.serverId,
                    ThreadItem.CommandExecution(id, "", status = "completed"),
                ),
            )
        }
        session.activeTools.clear()
    }

    private fun assistantItemId(session: Session) = "asst-${session.activeTurnId ?: "x"}"

    private fun reasoningItemId(session: Session) =
        "think-${session.activeTurnId ?: "x"}-${session.nextThinkId.getAndIncrement()}"

    // ── 历史解析（服务端已过滤 isMeta 注入行，结构见 claude-server/README.md）──

    private fun parseMessageItems(items: JSONArray?): List<ThreadItem> {
        val arr = items ?: return emptyList()
        val out = mutableListOf<ThreadItem>()
        var userIndex = 0
        var asstIndex = 0
        for (i in 0 until arr.length()) {
            val msg = arr.optJSONObject(i) ?: continue
            when (msg.optString("type")) {
                "user" -> {
                    val content = msg.optJSONArray("content").objects().mapNotNull { block ->
                        if (block.optString("type") == "text") {
                            Content("text", block.optString("text"))
                        } else {
                            null
                        }
                    }
                    if (content.isEmpty() || content.all { it.text.isBlank() }) continue
                    out += ThreadItem.UserMessage("hist-u-${userIndex++}", content)
                }
                "assistant" -> {
                    var textIndex = 0
                    var thinkIndex = 0
                    val pendingText = StringBuilder()
                    fun flushText() {
                        if (pendingText.isEmpty()) return
                        out += ThreadItem.AgentMessage("hist-a-$asstIndex-t${textIndex++}", pendingText.toString())
                        pendingText.clear()
                    }
                    msg.optJSONArray("content").objects().forEach { block ->
                        when (block.optString("type")) {
                            "thinking" -> {
                                flushText()
                                val think = block.optString("thinking")
                                    .ifBlank { block.optString("text") }
                                    .trim()
                                if (think.isNotEmpty()) {
                                    out += ThreadItem.Reasoning("hist-a-$asstIndex-r${thinkIndex++}", listOf(think))
                                }
                            }
                            "text" -> pendingText.append(block.optString("text"))
                            "tool_use" -> {
                                flushText()
                                val id = block.optString("id").ifBlank { "hist-a-$asstIndex-tool-$i" }
                                val command = buildString {
                                    append(block.optString("name"))
                                    block.opt("input")?.let { input ->
                                        if (input != JSONObject.NULL) append(' ').append(input.toString())
                                    }
                                }
                                out += ThreadItem.CommandExecution(id, command, status = "completed")
                            }
                        }
                    }
                    flushText()
                    asstIndex++
                }
            }
        }
        return out
    }

    /** Compose LazyColumn key 要求 id 全局唯一：重复的补后缀。 */
    private fun ensureUniqueIds(items: List<ThreadItem>): List<ThreadItem> {
        val seen = mutableSetOf<String>()
        return items.map { item ->
            var id = item.id.ifBlank { "item" }
            if (seen.add(id)) return@map item
            var n = 2
            while (!seen.add("$id#$n")) n++
            rewriteItemId(item, "$id#$n")
        }
    }

    private fun rewriteItemId(item: ThreadItem, newId: String): ThreadItem = when (item) {
        is ThreadItem.UserMessage -> item.copy(id = newId)
        is ThreadItem.AgentMessage -> item.copy(id = newId)
        is ThreadItem.CommandExecution -> item.copy(id = newId)
        is ThreadItem.FileChange -> item.copy(id = newId)
        is ThreadItem.Plan -> item.copy(id = newId)
        is ThreadItem.WebSearch -> item.copy(id = newId)
        is ThreadItem.Reasoning -> item.copy(id = newId)
        is ThreadItem.ContextCompaction -> item.copy(id = newId)
    }

    private fun parseSession(json: JSONObject): Thread = Thread(
        id = json.optString("session_id"),
        preview = json.optString("last_prompt").ifBlank { "Claude 会话" },
        createdAt = json.optLong("created_at"),
        updatedAt = json.optLong("updated_at"),
        status = ThreadStatus("idle"),
        cwd = json.optString("cwd").ifBlank { json.optString("work_dir") },
    )

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000L

        fun deriveBases(serverUrl: String): Pair<String, String> {
            val raw = serverUrl.trim().removeSuffix("/")
            return when {
                raw.startsWith("wss://") -> {
                    val host = raw.removePrefix("wss://")
                    "https://$host" to "wss://$host"
                }
                raw.startsWith("ws://") -> {
                    val host = raw.removePrefix("ws://")
                    "http://$host" to "ws://$host"
                }
                raw.startsWith("https://") -> {
                    val host = raw.removePrefix("https://")
                    raw to "wss://$host"
                }
                raw.startsWith("http://") -> {
                    val host = raw.removePrefix("http://")
                    raw to "ws://$host"
                }
                else -> "https://$raw" to "wss://$raw"
            }
        }

        fun JSONArray?.objects(): List<JSONObject> = buildList {
            this@objects?.let { array ->
                for (i in 0 until array.length()) array.optJSONObject(i)?.let(::add)
            }
        }
    }
}
