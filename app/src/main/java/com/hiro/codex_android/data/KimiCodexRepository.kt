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
 * Kimi Code kap-server 适配：REST 控制面 + WS `/api/v1/ws` 事件面，
 * 对外仍实现 [CodexRepository]，让聊天/列表 UI 无需感知协议差异。
 *
 * 协议依据：docs/KIMI_DEPLOY.md、packages/protocol（REST envelope + WS v2）。
 */
class KimiCodexRepository(
    private val profile: AgentProfile,
) : CodexRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
    private val connectMutex = Mutex()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val _events = MutableSharedFlow<CodexEvent>(extraBufferCapacity = 128)
    override val events: SharedFlow<CodexEvent> = _events.asSharedFlow()

    private val incoming = Channel<JSONObject>(Channel.UNLIMITED)
    private val pendingAcks = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val nextControlId = AtomicInteger(1)
    private val nextApprovalKey = AtomicInteger(1)
    private val approvalIdByKey = ConcurrentHashMap<Int, Pair<String, String>>() // key → sessionId, approvalId

    /** sessionId → 当前活跃 prompt_id（interrupt 用） */
    private val activePromptBySession = ConcurrentHashMap<String, String>()
    /** "$sessionId:$turnId" → prompt_id */
    private val promptByTurnKey = ConcurrentHashMap<String, String>()
    /** 已为该 turn 发出过 AgentMessage ItemStarted */
    private val startedAssistantItems = ConcurrentHashMap.newKeySet<String>()
    private val startedReasoningItems = ConcurrentHashMap.newKeySet<String>()
    private val subscribedSessions = ConcurrentHashMap.newKeySet<String>()
    private val sessionCursors = ConcurrentHashMap<String, JSONObject>()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var wsReady = false
    @Volatile private var openWaiter: CompletableDeferred<Unit>? = null
    @Volatile private var helloWaiter: CompletableDeferred<Unit>? = null

    private val httpBase: String
    private val wsUrl: String

    init {
        val (http, ws) = deriveBases(profile.serverUrl)
        httpBase = "$http/api/v1"
        wsUrl = "$ws/api/v1/ws"
        scope.launch {
            for (frame in incoming) handleFrame(frame)
        }
    }

    override fun close() {
        socket?.close(1000, "profile changed")
        socket = null
        wsReady = false
        openWaiter?.completeExceptionally(IOException("连接已关闭"))
        helloWaiter?.completeExceptionally(IOException("连接已关闭"))
        pendingAcks.values.forEach { it.completeExceptionally(IOException("连接已关闭")) }
        pendingAcks.clear()
        scope.cancel()
    }

    override suspend fun initialize(clientName: String, version: String) {
        ensureConnected()
    }

    override suspend fun listThreads(cursor: String?, limit: Int): ThreadPage {
        ensureConnected()
        val path = buildString {
            append("/sessions?page_size=").append(limit.coerceIn(1, 100))
            append("&exclude_empty=true")
            if (!cursor.isNullOrBlank()) append("&after_id=").append(cursor)
        }
        val data = restGet(path)
        val items = data.optJSONArray("items").objects().map { parseSessionAsThread(it) }
        val next = if (data.optBoolean("has_more") && items.isNotEmpty()) items.last().id else null
        return ThreadPage(items, next)
    }

    override suspend fun startThread(model: String?): Thread {
        ensureConnected()
        val body = JSONObject().also { json ->
            // Kimi 要求 workspace_id 或 metadata.cwd 二选一；优先用配置的工作目录。
            val cwd = profile.defaultCwd.trim()
            if (cwd.isNotBlank()) {
                json.put("metadata", JSONObject().put("cwd", cwd))
            } else {
                val workspace = pickWorkspace()
                    ?: throw IOException(
                        "请先在设置中填写 Kimi「默认工作目录」（服务器上的绝对路径，如 /home/ubuntu/proj），" +
                            "或先在服务器用 kimi 打开过一个项目",
                    )
                json.put("workspace_id", workspace.optString("id"))
            }
            if (!model.isNullOrBlank()) {
                json.put("agent_config", JSONObject().put("model", model))
            }
        }
        val session = restPost("/sessions", body)
        val thread = parseSessionAsThread(session)
        subscribeSession(thread.id)
        return thread
    }

    /** 取最近打开的 workspace；没有已注册工作区时返回 null。 */
    private suspend fun pickWorkspace(): JSONObject? {
        val items = restGet("/workspaces").optJSONArray("items").objects()
        if (items.isEmpty()) return null
        return items.maxByOrNull { it.optString("last_opened_at") } ?: items.first()
    }

    override suspend fun resumeThread(threadId: String, model: String?): Thread {
        ensureConnected()
        val session = restGet("/sessions/$threadId")
        subscribeSession(threadId)
        return parseSessionAsThread(session)
    }

    override suspend fun readThread(threadId: String, includeTurns: Boolean): Thread {
        ensureConnected()
        val session = restGet("/sessions/$threadId")
        val thread = parseSessionAsThread(session)
        if (!includeTurns) return thread
        val messages = loadAllMessages(threadId)
        subscribeSession(threadId)
        // 用 snapshot 补上进行中的 turn 文本与 pending approvals。
        runCatching { applySnapshot(threadId) }
        return thread.copy(turns = listOf(Turn(id = "history", status = "completed", items = messages)))
    }

    override suspend fun archiveThread(threadId: String) {
        ensureConnected()
        restPost("/sessions/$threadId:archive", JSONObject())
    }

    override suspend fun deleteThread(threadId: String) {
        // Kimi 无硬删除，归档等价于从默认列表移除。
        archiveThread(threadId)
    }

    override suspend fun startTurn(threadId: String, input: List<Content>): Turn {
        ensureConnected()
        subscribeSession(threadId)
        val content = JSONArray().also { array ->
            input.forEach { block ->
                when (block.type) {
                    "text" -> array.put(JSONObject().put("type", "text").put("text", block.text))
                    else -> array.put(JSONObject().put("type", "text").put("text", block.text))
                }
            }
        }
        val result = restPost(
            "/sessions/$threadId/prompts",
            JSONObject().put("content", content),
        )
        val promptId = result.optString("prompt_id")
        if (promptId.isBlank()) throw IOException("Kimi prompt 响应缺少 prompt_id")
        activePromptBySession[threadId] = promptId
        val status = result.optString("status", "running")
        if (status == "blocked") {
            throw IOException(result.optString("msg").ifBlank { "prompt 被拒绝（blocked）" })
        }
        return Turn(id = promptId, status = "inProgress")
    }

    override suspend fun interruptTurn(threadId: String, turnId: String) {
        ensureConnected()
        val promptId = turnId.ifBlank { activePromptBySession[threadId].orEmpty() }
        if (promptId.isBlank()) throw IOException("没有可中断的 prompt")
        restPost("/sessions/$threadId/prompts/$promptId:abort", JSONObject())
    }

    override suspend fun respondApproval(requestId: Int, decision: ApprovalDecision) {
        ensureConnected()
        val (sessionId, approvalId) = approvalIdByKey.remove(requestId)
            ?: throw IOException("未知的审批请求：$requestId")
        val kimiDecision = when (decision) {
            ApprovalDecision.Accept, ApprovalDecision.AcceptForSession -> "approved"
            ApprovalDecision.Decline -> "rejected"
            ApprovalDecision.Cancel -> "cancelled"
        }
        val body = JSONObject().put("decision", kimiDecision).also {
            if (decision == ApprovalDecision.AcceptForSession) it.put("scope", "session")
        }
        restPost("/sessions/$sessionId/approvals/$approvalId", body)
    }

    override suspend fun listModels(): List<ModelInfo> {
        ensureConnected()
        val data = restGet("/models")
        val defaultIds = runCatching {
            restGet("/providers").optJSONArray("items").objects()
                .mapNotNull { it.optString("default_model").takeIf(String::isNotBlank) }
                .toSet()
        }.getOrDefault(emptySet())
        val items = data.optJSONArray("items").objects().map { item ->
            val id = item.optString("model")
            ModelInfo(
                id = id,
                displayName = item.optString("display_name").ifBlank { id },
                description = item.optString("provider"),
                isDefault = id in defaultIds,
                supportedReasoningEfforts = item.optJSONArray("support_efforts").strings(),
                defaultReasoningEffort = item.optString("default_effort").ifBlank { "medium" },
            )
        }
        // 若 providers 没标 default，把第一项当作默认，方便新会话选中。
        return if (items.none { it.isDefault } && items.isNotEmpty()) {
            listOf(items.first().copy(isDefault = true)) + items.drop(1)
        } else {
            items
        }
    }

    override suspend fun updateThreadSettings(threadId: String, model: String?, effort: String?) {
        ensureConnected()
        val agentConfig = JSONObject().also { cfg ->
            model?.takeIf { it.isNotBlank() }?.let { cfg.put("model", it) }
            effort?.takeIf { it.isNotBlank() }?.let { cfg.put("thinking", it) }
        }
        if (agentConfig.length() == 0) return
        restPost("/sessions/$threadId/profile", JSONObject().put("agent_config", agentConfig))
    }

    override suspend fun startCompact(threadId: String) {
        ensureConnected()
        restPost("/sessions/$threadId:compact", JSONObject())
    }

    override suspend fun startReview(
        threadId: String,
        target: ReviewTarget,
        delivery: String,
    ): ReviewStartResult {
        throw IOException("Kimi 暂不支持 /review")
    }

    override suspend fun forkThread(threadId: String, lastTurnId: String?): Thread {
        ensureConnected()
        val session = restPost("/sessions/$threadId:fork", JSONObject())
        val thread = parseSessionAsThread(session)
        subscribeSession(thread.id)
        return thread
    }

    override suspend fun rollbackThread(threadId: String, numTurns: Int): Thread {
        ensureConnected()
        restPost(
            "/sessions/$threadId:undo",
            JSONObject().put("count", numTurns.coerceAtLeast(1)),
        )
        return readThread(threadId, includeTurns = true)
    }

    override suspend fun shellCommand(threadId: String, command: String) {
        throw IOException("Kimi 暂不支持 !shell（请直接发消息让 agent 执行）")
    }

    // ── REST ──────────────────────────────────────────────────────────────

    private suspend fun ensureConnected() {
        connectMutex.withLock {
            if (wsReady && socket != null) return
            require(profile.token.isNotBlank()) { "请先在设置中填写 Kimi Token" }
            require(profile.serverUrl.isNotBlank()) { "请先在设置中填写 Kimi 服务器地址" }

            socket?.cancel()
            socket = null
            wsReady = false
            subscribedSessions.clear()

            val opened = CompletableDeferred<Unit>()
            val helloed = CompletableDeferred<Unit>()
            openWaiter = opened
            helloWaiter = helloed

            val request = Request.Builder()
                .url(wsUrl)
                .header("Authorization", "Bearer ${profile.token}")
                .build()
            socket = client.newWebSocket(request, listener)

            try {
                withTimeout(CONNECT_TIMEOUT_MS) { opened.await() }
                // client_hello
                control("client_hello", JSONObject().put("client_id", "codex-android-${UUID.randomUUID()}"))
                withTimeout(CONNECT_TIMEOUT_MS) { helloed.await() }
                wsReady = true
            } catch (e: Exception) {
                socket?.cancel()
                socket = null
                wsReady = false
                throw IOException("连接 Kimi 失败：${e.message}", e)
            }
        }
        // 顺带探活 REST（失败不阻断已连上的 WS，但能尽早暴露 token 问题）
        runCatching { restGet("/healthz") }
    }

    private suspend fun subscribeSession(sessionId: String) {
        if (!subscribedSessions.add(sessionId)) return
        val payload = JSONObject().put("session_ids", JSONArray().put(sessionId))
        sessionCursors[sessionId]?.let { cursor ->
            payload.put("cursors", JSONObject().put(sessionId, cursor))
        }
        runCatching { control("subscribe", payload) }
            .onFailure { subscribedSessions.remove(sessionId) }
    }

    private suspend fun control(type: String, payload: JSONObject): JSONObject {
        val id = "c-${nextControlId.getAndIncrement()}"
        val deferred = CompletableDeferred<JSONObject>()
        pendingAcks[id] = deferred
        val frame = JSONObject().put("type", type).put("id", id).put("payload", payload)
        if (socket?.send(frame.toString()) != true) {
            pendingAcks.remove(id)
            throw IOException("WebSocket 未连接，无法发送 $type")
        }
        return try {
            withTimeout(RPC_TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            pendingAcks.remove(id)
            throw IOException("$type 超时/失败：${e.message}", e)
        }
    }

    private suspend fun restGet(path: String): JSONObject = rest("GET", path, null)

    private suspend fun restPost(path: String, body: JSONObject): JSONObject = rest("POST", path, body)

    private suspend fun rest(method: String, path: String, body: JSONObject?): JSONObject =
        withContext(Dispatchers.IO) {
            val requestBody = body?.toString()?.toRequestBody(jsonMedia)
            val request = Request.Builder()
                .url(httpBase + path)
                .header("Authorization", "Bearer ${profile.token}")
                .method(method, if (method == "GET") null else (requestBody ?: "{}".toRequestBody(jsonMedia)))
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrElse {
                    throw IOException("Kimi HTTP ${response.code}：${text.take(200)}")
                }
                val code = json.optInt("code", if (response.isSuccessful) 0 else -1)
                if (!response.isSuccessful || code != 0) {
                    val msg = json.optString("msg").ifBlank { "HTTP ${response.code}" }
                    throw IOException("Kimi 错误（$code）：$msg")
                }
                when (val data = json.opt("data")) {
                    is JSONObject -> data
                    JSONObject.NULL, null -> JSONObject()
                    else -> JSONObject().put("value", data)
                }
            }
        }

    // ── WS ────────────────────────────────────────────────────────────────

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            openWaiter?.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching { JSONObject(text) }
                .onSuccess { incoming.trySend(it) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            wsReady = false
            openWaiter?.completeExceptionally(t)
            helloWaiter?.completeExceptionally(t)
            pendingAcks.values.forEach { it.completeExceptionally(t) }
            pendingAcks.clear()
            subscribedSessions.clear()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            wsReady = false
            subscribedSessions.clear()
        }
    }

    private suspend fun handleFrame(frame: JSONObject) {
        when (val type = frame.optString("type")) {
            "server_hello" -> helloWaiter?.complete(Unit)
            "ack" -> {
                val id = frame.optString("id")
                val code = frame.optInt("code")
                val waiter = pendingAcks.remove(id) ?: return
                if (code == 0) {
                    waiter.complete(frame.optJSONObject("payload") ?: JSONObject())
                } else {
                    waiter.completeExceptionally(
                        IOException("WS ack 失败（$code）：${frame.optString("msg")}"),
                    )
                }
                // client_hello 的 ack 也算握手完成（部分服务端不发 server_hello 字段齐全时）
                if (helloWaiter?.isCompleted == false) helloWaiter?.complete(Unit)
            }
            "resync_required" -> {
                val sessionId = frame.optString("session_id")
                    .ifBlank { frame.optJSONObject("payload")?.optString("session_id").orEmpty() }
                if (sessionId.isNotBlank()) {
                    subscribedSessions.remove(sessionId)
                    runCatching {
                        applySnapshot(sessionId)
                        subscribeSession(sessionId)
                    }
                }
            }
            else -> handleSessionEvent(type, frame)
        }
    }

    private suspend fun handleSessionEvent(type: String, frame: JSONObject) {
        val sessionId = frame.optString("session_id").ifBlank {
            frame.optJSONObject("payload")?.optString("sessionId").orEmpty()
        }
        if (sessionId.isBlank()) return

        // 记录游标，便于重连重放。
        if (!frame.optBoolean("volatile", false) && frame.has("seq")) {
            val cursor = JSONObject().put("seq", frame.optLong("seq"))
            frame.optString("epoch").takeIf { it.isNotBlank() }?.let { cursor.put("epoch", it) }
            sessionCursors[sessionId] = cursor
        }

        val payload = frame.optJSONObject("payload") ?: JSONObject()
        when (type) {
            "turn.started" -> {
                val turnId = payload.optInt("turnId")
                val promptId = activePromptBySession[sessionId] ?: "turn-$turnId"
                promptByTurnKey["$sessionId:$turnId"] = promptId
                startedAssistantItems.remove(assistantItemId(sessionId, turnId))
                startedReasoningItems.remove(reasoningItemId(sessionId, turnId))
                _events.emit(CodexEvent.TurnStarted(sessionId, promptId))
            }
            "assistant.delta" -> {
                val turnId = payload.optInt("turnId")
                val itemId = assistantItemId(sessionId, turnId)
                val delta = payload.optString("delta")
                if (startedAssistantItems.add(itemId)) {
                    _events.emit(CodexEvent.ItemStarted(sessionId, ThreadItem.AgentMessage(itemId, "")))
                }
                if (delta.isNotEmpty()) {
                    _events.emit(CodexEvent.AgentMessageDelta(sessionId, itemId, delta))
                }
            }
            "thinking.delta" -> {
                val turnId = payload.optInt("turnId")
                val itemId = reasoningItemId(sessionId, turnId)
                val delta = payload.optString("delta")
                if (startedReasoningItems.add(itemId)) {
                    _events.emit(CodexEvent.ItemStarted(sessionId, ThreadItem.Reasoning(itemId, listOf(""))))
                }
                if (delta.isNotEmpty()) {
                    _events.emit(CodexEvent.ReasoningSummaryDelta(sessionId, itemId, 0, delta))
                }
            }
            "tool.call.started" -> {
                val toolCallId = payload.optString("toolCallId")
                val name = payload.optString("name")
                val args = payload.opt("args")
                val command = buildString {
                    append(name)
                    if (args != null && args != JSONObject.NULL) append(' ').append(args.toString())
                }
                _events.emit(
                    CodexEvent.ItemStarted(
                        sessionId,
                        ThreadItem.CommandExecution(toolCallId, command, status = "inProgress"),
                    ),
                )
            }
            "tool.result", "shell.completed" -> {
                val toolCallId = payload.optString("toolCallId")
                    .ifBlank { payload.optString("commandId") }
                if (toolCallId.isBlank()) return
                val output = payload.opt("output")?.toString().orEmpty()
                val isError = payload.optBoolean("isError") || payload.optBoolean("is_error")
                _events.emit(
                    CodexEvent.ItemCompleted(
                        sessionId,
                        ThreadItem.CommandExecution(
                            id = toolCallId,
                            command = toolCallId,
                            status = if (isError) "failed" else "completed",
                            aggregatedOutput = output,
                            exitCode = if (isError) 1 else 0,
                        ),
                    ),
                )
            }
            "turn.ended" -> {
                val turnId = payload.optInt("turnId")
                val promptId = promptByTurnKey.remove("$sessionId:$turnId")
                    ?: activePromptBySession[sessionId]
                    ?: "turn-$turnId"
                if (activePromptBySession[sessionId] == promptId) {
                    activePromptBySession.remove(sessionId)
                }
                val reason = payload.optString("reason", "completed")
                val status = when (reason) {
                    "cancelled" -> "interrupted"
                    "failed" -> "failed"
                    else -> "completed"
                }
                val error = payload.optJSONObject("error")?.optString("message")
                // 校对完整 assistant 文本：用 ItemCompleted 收尾（若已有流式内容）
                val asstId = assistantItemId(sessionId, turnId)
                if (startedAssistantItems.contains(asstId)) {
                    // 不覆盖文本，仅靠 ChatViewModel 已有 delta；这里不发空 completed。
                }
                _events.emit(CodexEvent.TurnCompleted(sessionId, promptId, status, error))
            }
            "compaction.started" -> {
                val id = "compact-$sessionId-${frame.optLong("seq")}"
                _events.emit(
                    CodexEvent.ItemStarted(sessionId, ThreadItem.ContextCompaction(id, "inProgress")),
                )
            }
            "compaction.completed" -> {
                val id = "compact-$sessionId-${frame.optLong("seq")}"
                _events.emit(
                    CodexEvent.ItemCompleted(sessionId, ThreadItem.ContextCompaction(id, "completed")),
                )
            }
            "agent.status.updated" -> {
                val used = payload.optLong("contextTokens", -1L)
                val window = payload.optLong("maxContextTokens", -1L)
                if (used >= 0 && window > 0) {
                    _events.emit(CodexEvent.TokenUsageUpdated(sessionId, TokenUsage(used, window)))
                }
                val phase = payload.optJSONObject("phase")
                if (phase?.optString("kind") == "awaiting_approval") {
                    emitApprovalFromPhase(sessionId, phase)
                }
            }
            "event.session.status_changed" -> {
                if (frame.optString("status") == "awaiting_approval" ||
                    payload.optString("status") == "awaiting_approval"
                ) {
                    runCatching { pollPendingApprovals(sessionId) }
                }
            }
        }
    }

    private suspend fun emitApprovalFromPhase(sessionId: String, phase: JSONObject) {
        val approval = phase.optJSONObject("approval")
        if (approval != null) {
            emitApproval(sessionId, approval)
        } else {
            pollPendingApprovals(sessionId)
        }
    }

    private suspend fun pollPendingApprovals(sessionId: String) {
        val data = restGet("/sessions/$sessionId/approvals?status=pending")
        data.optJSONArray("items").objects().forEach { emitApproval(sessionId, it) }
    }

    private suspend fun emitApproval(sessionId: String, approval: JSONObject) {
        val approvalId = approval.optString("approval_id").ifBlank { approval.optString("approvalId") }
        if (approvalId.isBlank()) return
        val key = nextApprovalKey.getAndIncrement()
        approvalIdByKey[key] = sessionId to approvalId
        val toolInput = approval.opt("tool_input_display") ?: approval.opt("toolInputDisplay")
        val command = buildString {
            append(approval.optString("tool_name").ifBlank { approval.optString("toolName") })
            append(' ')
            append(approval.optString("action"))
            if (toolInput != null && toolInput != JSONObject.NULL) append(' ').append(toolInput.toString())
        }.trim()
        _events.emit(
            CodexEvent.ApprovalRequest(
                requestId = key,
                threadId = sessionId,
                turnId = approval.opt("turn_id")?.toString()
                    ?: approval.opt("turnId")?.toString()
                    ?: activePromptBySession[sessionId].orEmpty(),
                itemId = approval.optString("tool_call_id")
                    .ifBlank { approval.optString("toolCallId") },
                command = command,
                cwd = "",
                reason = "需要批准工具调用",
            ),
        )
    }

    private suspend fun applySnapshot(sessionId: String) {
        val snap = restGet("/sessions/$sessionId/snapshot")
        snap.optJSONObject("cursor")?.let { sessionCursors[sessionId] = it }
        // as_of_seq / epoch 也可能在顶层
        if (snap.has("as_of_seq")) {
            val cursor = JSONObject().put("seq", snap.optLong("as_of_seq"))
            snap.optString("epoch").takeIf { it.isNotBlank() }?.let { cursor.put("epoch", it) }
            sessionCursors[sessionId] = cursor
        }
        snap.optJSONArray("pending_approvals").objects().forEach { emitApproval(sessionId, it) }
        val inFlight = snap.optJSONObject("in_flight_turn")
        if (inFlight != null) {
            inFlight.optString("current_prompt_id").takeIf { it.isNotBlank() }?.let {
                activePromptBySession[sessionId] = it
            }
            val turnId = inFlight.optInt("turn_id")
            val asst = inFlight.optString("assistant_text")
            if (asst.isNotBlank()) {
                val itemId = assistantItemId(sessionId, turnId)
                startedAssistantItems.add(itemId)
                _events.emit(CodexEvent.ItemStarted(sessionId, ThreadItem.AgentMessage(itemId, asst)))
            }
        }
        _events.emit(
            CodexEvent.ThreadReconciled(
                parseSessionAsThread(snap.optJSONObject("session") ?: restGet("/sessions/$sessionId")).copy(
                    turns = listOf(
                        Turn(
                            id = "history",
                            status = if (snap.optJSONObject("session")?.optBoolean("busy") == true) {
                                "inProgress"
                            } else {
                                "completed"
                            },
                            items = loadAllMessages(sessionId),
                        ),
                    ),
                ),
            ),
        )
    }

    // ── 解析 ──────────────────────────────────────────────────────────────

    private suspend fun loadAllMessages(sessionId: String): List<ThreadItem> {
        val items = mutableListOf<ThreadItem>()
        var afterId: String? = null
        repeat(20) {
            val path = buildString {
                append("/sessions/$sessionId/messages?page_size=100")
                if (!afterId.isNullOrBlank()) append("&after_id=").append(afterId)
            }
            val page = restGet(path)
            val batch = page.optJSONArray("items").objects()
            if (batch.isEmpty()) return items
            batch.forEach { msg -> items += parseMessageItems(msg) }
            afterId = batch.last().optString("id")
            if (!page.optBoolean("has_more")) return items
        }
        return items
    }

    private fun parseMessageItems(msg: JSONObject): List<ThreadItem> {
        val id = msg.optString("id")
        val role = msg.optString("role")
        val content = msg.optJSONArray("content").objects()
        return when (role) {
            "user" -> listOf(
                ThreadItem.UserMessage(
                    id,
                    content.mapNotNull { block ->
                        when (block.optString("type")) {
                            "text" -> Content("text", block.optString("text"))
                            else -> null
                        }
                    }.ifEmpty { listOf(Content("text", "")) },
                ),
            )
            "assistant" -> buildList {
                val texts = content.filter { it.optString("type") == "text" }.map { it.optString("text") }
                val thinking = content.filter { it.optString("type") == "thinking" }
                    .map { it.optString("thinking") }
                if (thinking.isNotEmpty()) add(ThreadItem.Reasoning("$id-think", thinking))
                if (texts.isNotEmpty()) add(ThreadItem.AgentMessage(id, texts.joinToString("")))
                content.filter { it.optString("type") == "tool_use" }.forEach { tool ->
                    add(
                        ThreadItem.CommandExecution(
                            id = tool.optString("tool_call_id").ifBlank { "$id-tool" },
                            command = buildString {
                                append(tool.optString("tool_name"))
                                val input = tool.opt("input")
                                if (input != null && input != JSONObject.NULL) append(' ').append(input.toString())
                            },
                            status = "completed",
                        ),
                    )
                }
            }
            "tool" -> content.filter { it.optString("type") == "tool_result" }.map { tool ->
                ThreadItem.CommandExecution(
                    id = tool.optString("tool_call_id").ifBlank { id },
                    command = tool.optString("tool_call_id"),
                    status = if (tool.optBoolean("is_error")) "failed" else "completed",
                    aggregatedOutput = tool.opt("output")?.toString().orEmpty(),
                )
            }
            else -> emptyList()
        }
    }

    private fun parseSessionAsThread(session: JSONObject): Thread {
        val created = parseIsoEpochSeconds(session.optString("created_at"))
        val updated = parseIsoEpochSeconds(session.optString("updated_at"))
        val busy = session.optBoolean("busy")
        val agentConfig = session.optJSONObject("agent_config")
        return Thread(
            id = session.optString("id"),
            preview = session.optString("last_prompt").ifBlank { session.optString("title") },
            name = session.optString("title").takeIf { it.isNotBlank() },
            createdAt = created,
            updatedAt = updated,
            status = ThreadStatus(if (busy) "busy" else "idle"),
            cwd = session.optJSONObject("metadata")?.optString("cwd").orEmpty(),
            model = agentConfig?.optString("model"),
            effort = agentConfig?.optString("thinking"),
        )
    }

    private fun assistantItemId(sessionId: String, turnId: Int) = "asst-$sessionId-$turnId"
    private fun reasoningItemId(sessionId: String, turnId: Int) = "think-$sessionId-$turnId"

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val RPC_TIMEOUT_MS = 30_000L

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

        fun parseIsoEpochSeconds(iso: String): Long = runCatching {
            // 粗解析：取 epoch millis / 1000；失败则 0。
            java.time.Instant.parse(iso).epochSecond
        }.getOrDefault(0L)

        fun JSONArray?.objects(): List<JSONObject> = buildList {
            this@objects?.let { array ->
                for (i in 0 until array.length()) array.optJSONObject(i)?.let(::add)
            }
        }

        fun JSONArray?.strings(): List<String> = buildList {
            this@strings?.let { array ->
                for (i in 0 until array.length()) {
                    array.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }
    }
}
