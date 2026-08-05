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
    /** "$sessionId:$turnId" → 当前 step；同 turn 多段 assistant 文本靠 step 分气泡。 */
    private val stepByTurnKey = ConcurrentHashMap<String, Int>()
    /** 工具打断文本后，若服务端未发 turn.step.started，下一段 delta 需自行 +1 step。 */
    private val pendingAssistantStepBump = ConcurrentHashMap.newKeySet<String>()
    /** toolCallId → 启动时拼好的命令展示文案；result 事件常不带 name，靠这里补。 */
    private val toolCommandById = ConcurrentHashMap<String, String>()
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
        }
        // POST /sessions 会忽略 body.agent_config（服务端硬编码 model:''），
        // 模型 / yolo 必须紧接着用 /profile 写入，否则首条 prompt 会报 Model not set。
        val session = restPost("/sessions", body)
        val threadId = session.optString("id")
        if (threadId.isBlank()) throw IOException("Kimi 建会话响应缺少 id")

        val resolvedModel = model?.takeIf { it.isNotBlank() } ?: resolveDefaultModel()
        val agentConfig = JSONObject().put("permission_mode", "yolo").also { cfg ->
            if (!resolvedModel.isNullOrBlank()) cfg.put("model", resolvedModel)
        }
        restPost("/sessions/$threadId/profile", JSONObject().put("agent_config", agentConfig))

        val thread = parseSessionAsThread(restGet("/sessions/$threadId")).let { parsed ->
            // profile 后的 GET 若仍投影空 model，用我们刚写入的值补上，方便 UI。
            if (parsed.model.isNullOrBlank() && !resolvedModel.isNullOrBlank()) {
                parsed.copy(model = resolvedModel)
            } else {
                parsed
            }
        }
        subscribeSession(thread.id)
        return thread
    }

    /** 取服务端默认模型（providers.default_model，否则 models 列表第一项）。 */
    private suspend fun resolveDefaultModel(): String? {
        val fromProviders = runCatching {
            restGet("/providers").optJSONArray("items").objects()
                .mapNotNull { it.optString("default_model").takeIf(String::isNotBlank) }
                .firstOrNull()
        }.getOrNull()
        if (!fromProviders.isNullOrBlank()) return fromProviders
        return runCatching {
            restGet("/models").optJSONArray("items").objects()
                .firstOrNull()
                ?.optString("model")
                ?.takeIf(String::isNotBlank)
        }.getOrNull()
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
        // 只补 approvals / in-flight / 游标，不要再用残缺历史整表覆盖当前 UI。
        runCatching { applySnapshot(threadId, reloadMessages = false) }
        val busy = session.optBoolean("busy")
        return thread.copy(
            turns = listOf(
                Turn(
                    id = "history",
                    status = if (busy) "inProgress" else "completed",
                    items = messages,
                ),
            ),
        )
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
            val efforts = item.optJSONArray("support_efforts").strings()
            val caps = item.optJSONArray("capabilities").strings()
            ModelInfo(
                id = id,
                displayName = item.optString("display_name").ifBlank { id },
                description = item.optString("provider"),
                isDefault = id in defaultIds,
                supportedReasoningEfforts = when {
                    efforts.isNotEmpty() -> efforts
                    // 有 thinking 能力但未声明档位时，用 Kimi 常见集合，避免二级菜单空白。
                    "thinking" in caps -> listOf("off", "low", "medium", "high", "xhigh", "max")
                    else -> emptyList()
                },
                defaultReasoningEffort = item.optString("default_effort").ifBlank {
                    if ("thinking" in caps) "high" else "medium"
                },
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
                        applySnapshot(sessionId, reloadMessages = true)
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
                val key = turnKey(sessionId, turnId)
                promptByTurnKey[key] = promptId
                stepByTurnKey[key] = 0
                pendingAssistantStepBump.remove(key)
                startedAssistantItems.removeIf { it.startsWith("asst-$sessionId-$turnId-") }
                startedReasoningItems.removeIf { it.startsWith("think-$sessionId-$turnId-") }
                _events.emit(CodexEvent.TurnStarted(sessionId, promptId))
            }
            "turn.step.started" -> {
                val turnId = payload.optInt("turnId")
                val step = payload.optInt("step")
                val key = turnKey(sessionId, turnId)
                stepByTurnKey[key] = step
                // step 事件已切换气泡，取消工具触发的兜底 bump，避免 step+1 两次。
                pendingAssistantStepBump.remove(key)
            }
            "assistant.delta" -> {
                val turnId = payload.optInt("turnId")
                maybeBumpAssistantStep(sessionId, turnId)
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
                maybeBumpAssistantStep(sessionId, turnId)
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
                val turnId = payload.optInt("turnId")
                val toolCallId = payload.optString("toolCallId")
                val name = payload.optString("name")
                val args = payload.opt("args")
                val command = buildString {
                    append(name)
                    if (args != null && args != JSONObject.NULL) append(' ').append(args.toString())
                }.ifBlank { toolCallId }
                if (toolCallId.isNotBlank()) toolCommandById[toolCallId] = command
                // 工具打断当前 assistant 段；若之后没有 turn.step.started，下一段文本自行开新气泡。
                if (turnId >= 0) pendingAssistantStepBump.add(turnKey(sessionId, turnId))
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
                // result 帧经常只有 id；不要用 id 覆盖启动时写好的工具名。
                val command = toolCommandById.remove(toolCallId)
                    ?: payload.optString("name").takeIf { it.isNotBlank() }
                    ?: payload.optString("toolName").takeIf { it.isNotBlank() }
                    ?: ""
                _events.emit(
                    CodexEvent.ItemCompleted(
                        sessionId,
                        ThreadItem.CommandExecution(
                            id = toolCallId,
                            command = command,
                            status = if (isError) "failed" else "completed",
                            aggregatedOutput = output,
                            exitCode = if (isError) 1 else 0,
                        ),
                    ),
                )
            }
            "turn.ended" -> {
                val turnId = payload.optInt("turnId")
                val key = turnKey(sessionId, turnId)
                val promptId = promptByTurnKey.remove(key)
                    ?: activePromptBySession[sessionId]
                    ?: "turn-$turnId"
                if (activePromptBySession[sessionId] == promptId) {
                    activePromptBySession.remove(sessionId)
                }
                stepByTurnKey.remove(key)
                pendingAssistantStepBump.remove(key)
                val reason = payload.optString("reason", "completed")
                val status = when (reason) {
                    "cancelled" -> "interrupted"
                    "failed" -> "failed"
                    else -> "completed"
                }
                val error = payload.optJSONObject("error")?.optString("message")
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

    /**
     * @param reloadMessages true 时用 REST 历史整表对账（仅断线 resync）；
     *   日常 read/subscribe 必须为 false——服务端常把工具前的 thinking 落成空串，
     *   整表覆盖会把直播正确的「thinking→工具→thinking→回复」打成「工具→thinking→回复」。
     */
    private suspend fun applySnapshot(sessionId: String, reloadMessages: Boolean = false) {
        val snap = restGet("/sessions/$sessionId/snapshot")
        snap.optJSONObject("cursor")?.let { sessionCursors[sessionId] = it }
        if (snap.has("as_of_seq")) {
            val cursor = JSONObject().put("seq", snap.optLong("as_of_seq"))
            snap.optString("epoch").takeIf { it.isNotBlank() }?.let { cursor.put("epoch", it) }
            sessionCursors[sessionId] = cursor
        }
        snap.optJSONArray("pending_approvals").objects().forEach { emitApproval(sessionId, it) }
        val sessionObj = snap.optJSONObject("session")
        val busy = sessionObj?.optBoolean("busy") == true
        val inFlight = snap.optJSONObject("in_flight_turn")
        if (inFlight != null) {
            inFlight.optString("current_prompt_id").takeIf { it.isNotBlank() }?.let {
                activePromptBySession[sessionId] = it
            }
            val turnId = inFlight.optInt("turn_id")
            val step = inFlight.optInt("step", currentStep(sessionId, turnId))
            stepByTurnKey[turnKey(sessionId, turnId)] = step
            val thinking = inFlight.optString("thinking_text")
            if (thinking.isNotBlank()) {
                val itemId = reasoningItemId(sessionId, turnId, step)
                if (startedReasoningItems.add(itemId)) {
                    _events.emit(
                        CodexEvent.ItemStarted(sessionId, ThreadItem.Reasoning(itemId, listOf(thinking))),
                    )
                }
            }
            val asst = inFlight.optString("assistant_text")
            if (asst.isNotBlank()) {
                val itemId = assistantItemId(sessionId, turnId, step)
                if (startedAssistantItems.add(itemId)) {
                    _events.emit(CodexEvent.ItemStarted(sessionId, ThreadItem.AgentMessage(itemId, asst)))
                }
            }
        }
        if (!reloadMessages) {
            // 只同步会话元数据，items 留空表示「不要覆盖本地气泡」。
            _events.emit(
                CodexEvent.ThreadReconciled(
                    parseSessionAsThread(sessionObj ?: restGet("/sessions/$sessionId")).copy(
                        turns = listOf(
                            Turn(id = "meta", status = if (busy) "inProgress" else "completed", items = emptyList()),
                        ),
                    ),
                ),
            )
            return
        }
        _events.emit(
            CodexEvent.ThreadReconciled(
                parseSessionAsThread(sessionObj ?: restGet("/sessions/$sessionId")).copy(
                    turns = listOf(
                        Turn(
                            id = "history",
                            status = if (busy) "inProgress" else "completed",
                            items = loadAllMessages(sessionId),
                        ),
                    ),
                ),
            ),
        )
    }

    // ── 解析 ──────────────────────────────────────────────────────────────

    /**
     * Kimi `GET .../messages` 默认 **newest first**（见 kap-server messageHistory）。
     * 聊天 UI 要时间正序，所以整页拉完后 reverse；翻更早的页用 `before_id`（不是 after_id）。
     */
    private suspend fun loadAllMessages(sessionId: String): List<ThreadItem> {
        val newestFirst = mutableListOf<JSONObject>()
        var beforeId: String? = null
        for (i in 0 until 20) {
            val path = buildString {
                append("/sessions/$sessionId/messages?page_size=100")
                if (!beforeId.isNullOrBlank()) append("&before_id=").append(beforeId)
            }
            val page = restGet(path)
            val batch = page.optJSONArray("items").objects()
            if (batch.isEmpty()) break
            newestFirst += batch
            // 本页最后一条是当前页里最旧的，用它继续往更早翻。
            beforeId = batch.last().optString("id")
            if (!page.optBoolean("has_more")) break
        }
        return newestFirst.asReversed()
            .flatMap(::parseMessageItems)
            .let(::ensureUniqueItemIds)
    }

    private fun parseMessageItems(msg: JSONObject): List<ThreadItem> {
        val id = msg.optString("id").ifBlank { "msg-${msg.hashCode()}" }
        val role = msg.optString("role")
        val content = msg.optJSONArray("content").objects()
        return when (role) {
            "user" -> {
                if (!isDisplayableUserMessage(msg)) {
                    emptyList()
                } else {
                    val blocks = content.mapNotNull { block ->
                        when (block.optString("type")) {
                            "text" -> {
                                val raw = block.optString("text")
                                val cleaned = stripHiddenSystemMarkup(raw)
                                if (cleaned.isBlank() && raw.isNotBlank()) null
                                else Content("text", cleaned.ifBlank { raw })
                            }
                            else -> null
                        }
                    }
                    when {
                        blocks.isEmpty() -> emptyList()
                        blocks.all { it.text.isBlank() } -> emptyList()
                        isHiddenSystemUserText(blocks.joinToString("\n") { it.text }) -> emptyList()
                        else -> listOf(ThreadItem.UserMessage(id, blocks))
                    }
                }
            }
            "assistant" -> buildList {
                // 按 content 顺序展开，保留 thinking → text → tool → thinking → text。
                // 旧逻辑先抽全部 thinking 再拼全部 text，会导致后段 thinking 丢位置/被盖住。
                var thinkIndex = 0
                var textIndex = 0
                val pendingText = StringBuilder()
                fun flushText() {
                    if (pendingText.isEmpty()) return
                    val itemId = if (textIndex == 0) id else "$id-text-$textIndex"
                    add(ThreadItem.AgentMessage(itemId, pendingText.toString()))
                    textIndex++
                    pendingText.clear()
                }
                content.forEachIndexed { index, block ->
                    when (block.optString("type")) {
                        "thinking" -> {
                            flushText()
                            val think = block.optString("thinking")
                                .ifBlank { block.optString("text") }
                                .trim()
                            if (think.isEmpty()) return@forEachIndexed
                            val last = lastOrNull()
                            if (last is ThreadItem.Reasoning) {
                                // 连续 thinking 段合并（与 kimi-web 一致）
                                set(lastIndex, last.copy(summary = last.summary + think))
                            } else {
                                add(ThreadItem.Reasoning("$id-think-$thinkIndex", listOf(think)))
                                thinkIndex++
                            }
                        }
                        "text" -> {
                            val piece = block.optString("text")
                            if (piece.isNotEmpty()) pendingText.append(piece)
                        }
                        "tool_use" -> {
                            flushText()
                            val callId = block.optString("tool_call_id").ifBlank { "$id-tool-$index" }
                            add(
                                ThreadItem.CommandExecution(
                                    id = callId,
                                    command = buildString {
                                        append(block.optString("tool_name"))
                                        val input = block.opt("input")
                                        if (input != null && input != JSONObject.NULL) {
                                            append(' ').append(input.toString())
                                        }
                                    },
                                    status = "completed",
                                ),
                            )
                        }
                    }
                }
                flushText()
            }
            "tool" -> content.filter { it.optString("type") == "tool_result" }.mapIndexed { index, tool ->
                val callId = tool.optString("tool_call_id").ifBlank { "$id-result-$index" }
                // 单独 id，避免与 tool_use 的 callId 在 LazyColumn 里撞 key 闪退；
                // 下面 ensure 之前会先尝试合并进已有 CommandExecution。
                ThreadItem.CommandExecution(
                    id = "$callId-result",
                    command = callId,
                    status = if (tool.optBoolean("is_error")) "failed" else "completed",
                    aggregatedOutput = tool.opt("output")?.toString().orEmpty(),
                    exitCode = if (tool.optBoolean("is_error")) 1 else 0,
                )
            }
            else -> emptyList()
        }
    }

    /** 合并 tool_result 到对应 tool_use，并保证列表 item id 全局唯一（Compose key 要求）。 */
    private fun ensureUniqueItemIds(items: List<ThreadItem>): List<ThreadItem> {
        val merged = mutableListOf<ThreadItem>()
        for (item in items) {
            if (item is ThreadItem.CommandExecution && item.id.endsWith("-result")) {
                val callId = item.id.removeSuffix("-result")
                val idx = merged.indexOfLast { it is ThreadItem.CommandExecution && it.id == callId }
                if (idx >= 0) {
                    val existing = merged[idx] as ThreadItem.CommandExecution
                    merged[idx] = existing.copy(
                        status = item.status,
                        aggregatedOutput = item.aggregatedOutput.ifBlank { existing.aggregatedOutput },
                        exitCode = item.exitCode ?: existing.exitCode,
                    )
                    continue
                }
            }
            merged += item
        }
        val seen = mutableSetOf<String>()
        return merged.map { item ->
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
            model = agentConfig?.optString("model")?.takeIf { it.isNotBlank() },
            // optString 缺省是 ""，不能当 effort 用，否则会盖掉 UI 默认档位。
            effort = agentConfig?.optString("thinking")?.takeIf { it.isNotBlank() },
        )
    }

    private fun turnKey(sessionId: String, turnId: Int) = "$sessionId:$turnId"

    /**
     * 与 kimi-web `isDisplayableUserMessage` 对齐：只展示真人输入；
     * compaction / injection / hook / cron 等系统注入的 user 角色消息不展示。
     */
    private fun isDisplayableUserMessage(msg: JSONObject): Boolean {
        val origin = msg.optJSONObject("metadata")?.optJSONObject("origin") ?: return true
        val kind = origin.optString("kind")
        if (kind.isBlank() || kind == "user") return true
        if (kind == "skill_activation" || kind == "plugin_command") {
            return origin.optString("trigger") == "user-slash"
        }
        return false
    }

    /** `<system-reminder>…</system-reminder>` 等纯系统提示，历史里当 user 出现也不展示。 */
    private fun isHiddenSystemUserText(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        return stripHiddenSystemMarkup(trimmed).isEmpty() &&
            (trimmed.contains("<system-reminder>", ignoreCase = true) ||
                trimmed.contains("<system>", ignoreCase = true))
    }

    private fun stripHiddenSystemMarkup(text: String): String =
        text.replace(SYSTEM_REMINDER_REGEX, "").replace(SYSTEM_TAG_REGEX, "").trim()

    private fun currentStep(sessionId: String, turnId: Int): Int =
        stepByTurnKey[turnKey(sessionId, turnId)] ?: 0

    /** 工具打断后若没收到 step.started，在下一段文本/思考 delta 时 +1 step 开新气泡。 */
    private fun maybeBumpAssistantStep(sessionId: String, turnId: Int) {
        val key = turnKey(sessionId, turnId)
        if (!pendingAssistantStepBump.remove(key)) return
        stepByTurnKey[key] = currentStep(sessionId, turnId) + 1
    }

    private fun assistantItemId(sessionId: String, turnId: Int, step: Int = currentStep(sessionId, turnId)) =
        "asst-$sessionId-$turnId-$step"

    private fun reasoningItemId(sessionId: String, turnId: Int, step: Int = currentStep(sessionId, turnId)) =
        "think-$sessionId-$turnId-$step"

    private companion object {
        val SYSTEM_REMINDER_REGEX = Regex("(?is)<system-reminder\\b[^>]*>.*?</system-reminder>")
        val SYSTEM_TAG_REGEX = Regex("(?is)<system\\b[^>]*>.*?</system>")

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
