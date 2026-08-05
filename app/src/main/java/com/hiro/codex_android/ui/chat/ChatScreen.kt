package com.hiro.codex_android.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hiro.codex_android.data.ServiceLocator
import com.hiro.codex_android.data.model.ThreadItem
import com.hiro.codex_android.ui.components.AgentBadge
import com.hiro.codex_android.ui.theme.GlassFill

/** @param threadIdArg "new" 表示新会话（发第一条消息时才真正 thread/start） */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ChatScreen(
    profileIdArg: String,
    threadIdArg: String,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onBack: () -> Unit,
    onOpenThread: (threadId: String) -> Unit = {},
) {
    val vm: ChatViewModel = viewModel(
        factory = ChatViewModel.factory(
            threadId = threadIdArg.takeIf { it != "new" },
            repo = ServiceLocator.registry.repositoryFor(profileIdArg),
            settingsStore = ServiceLocator.settingsStore,
            streamingAsrClient = ServiceLocator.streamingAsrClient,
            ttsManager = ServiceLocator.ttsManager,
        ),
    )
    val profiles by ServiceLocator.settingsStore.profiles.collectAsState()
    val agentType = profiles.firstOrNull { it.id == profileIdArg }?.type
    val state by vm.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) vm.startAsr()
        else vm.reportError("需要麦克风权限才能进行语音识别")
    }

    DisposableEffect(lifecycleOwner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> vm.reconcileAfterForeground()
                // 不在后台继续占用麦克风，保护隐私并避免后台录音限制导致异常。
                Lifecycle.Event.ON_PAUSE -> vm.stopAsr()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.stopAsr()
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let { snackbarHostState.showSnackbar(it) }
    }

    // 让整个聊天页（含输入框）作为卡片的展开目标，避免两套文字在过渡中重叠。
    val cardExpansionModifier = if (threadIdArg == "new") {
        Modifier
    } else {
        with(sharedTransitionScope) {
            Modifier.sharedBounds(
                sharedContentState = rememberSharedContentState(key = "thread-card-$profileIdArg:$threadIdArg"),
                animatedVisibilityScope = animatedVisibilityScope,
                enter = androidx.compose.animation.fadeIn(tween(durationMillis = 160, delayMillis = 400)),
                exit = androidx.compose.animation.fadeOut(tween(durationMillis = 150)),
                boundsTransform = { _, _ -> tween(durationMillis = 480, easing = FastOutSlowInEasing) },
            )
        }
    }

    Scaffold(
        modifier = cardExpansionModifier.blur(if (sharedTransitionScope.isTransitionActive) 14.dp else 0.dp),
        // 与任务卡片同一块半透明玻璃底，不让正文直接透出并和卡片文字重叠。
        containerColor = if (threadIdArg == "new") Color.Transparent else GlassFill,
        // 顶部只由消息列表和返回按钮各自消费状态栏安全区，避免空 TopAppBar 留白。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            ChatInputBar(
                generating = state.generating,
                actionBusy = state.actionBusy,
                agentType = agentType,
                model = state.model,
                effort = state.effort,
                models = state.availableModels,
                onSelectConfiguration = vm::switchConfiguration,
                tokenUsage = state.tokenUsage,
                asrTranscript = state.asrTranscript,
                asrRecording = state.asrRecording,
                onToggleAsr = {
                    if (state.asrRecording) {
                        vm.stopAsr()
                    } else if (
                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        vm.startAsr()
                    } else {
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onStopAsr = vm::stopAsr,
                onSend = vm::submit,
                onInterrupt = vm::interrupt,
            )
        },
    ) { padding ->
        val listState = rememberLazyListState()
        val items = state.items
        var followLatest by remember { mutableStateOf(true) }
        var initialScrollCompleted by remember(threadIdArg) { mutableStateOf(false) }

        // 从任务卡片展开已有会话时，初始落点固定在最新消息底部。
        // 这是一次性定位；完成后仍由 followLatest 完全交还滚动控制给用户。
        LaunchedEffect(state.loading, items.size) {
            if (!initialScrollCompleted && !state.loading && items.isNotEmpty()) {
                withFrameNanos { }
                listState.scrollToItem(items.lastIndex)
                listState.scrollBy(100_000f)
                followLatest = true
                initialScrollCompleted = true
            }
        }

        // 用户一旦向上翻阅历史，就不再让流式 delta 抢走滚动控制权。
        LaunchedEffect(listState) {
            snapshotFlow {
                val layout = listState.layoutInfo
                val lastIndex = layout.totalItemsCount - 1
                val lastItem = layout.visibleItemsInfo.lastOrNull { it.index == lastIndex }
                // 推理卡片可能比视口还高。仅“能看到最后一个 item”不代表用户在它的底部。
                lastItem != null && lastItem.offset + lastItem.size <= layout.viewportEndOffset + 12
            }.collect { atBottom -> followLatest = atBottom }
        }

        // 新 item 或流式文本增长时，仅在用户原本位于底部的情况下跟随最新消息。
        val lastContentLength = when (val item = items.lastOrNull()) {
            is ThreadItem.AgentMessage -> item.text.length
            is ThreadItem.Reasoning -> item.summary.sumOf(String::length)
            else -> 0
        }
        LaunchedEffect(items.size, lastContentLength) {
            if (followLatest && !listState.isScrollInProgress && items.isNotEmpty()) {
                // scrollToItem(last) 会把高卡片的顶部贴到视口，故向末尾滚一个足够大的距离。
                listState.scrollBy(100_000f)
            }
        }
        
        Box(Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().statusBarsPadding(),
                // 为悬浮返回按钮留出空间；比原空 TopAppBar 更紧凑。
                // 与底部输入框的 12dp 外边距共用同一条对齐基线。
                contentPadding = PaddingValues(start = 12.dp, top = 48.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                itemsIndexed(
                    items = items,
                    key = { index, item -> "${item.id}#$index" },
                ) { _, item ->
                    when (item) {
                        is ThreadItem.UserMessage -> UserMessageBubble(item)
                        is ThreadItem.AgentMessage -> AgentMessageItem(
                            item = item,
                            streaming = state.generating && item.id == items.lastOrNull()?.id,
                        )
                        is ThreadItem.CommandExecution -> CommandExecutionCard(item)
                        is ThreadItem.FileChange -> FileChangeCard(item)
                        is ThreadItem.Plan -> PlanCard(item)
                        is ThreadItem.WebSearch -> WebSearchCard(item)
                        is ThreadItem.Reasoning -> ReasoningItem(item)
                        is ThreadItem.ContextCompaction -> ContextCompactionCard(item)
                    }
                }
            }
            Row(
                modifier = Modifier.statusBarsPadding().padding(start = 4.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                agentType?.let { AgentBadge(it, size = 22.dp) }
            }
        }
    }

    // §6 审批：必须应答
    state.pendingApproval?.let { request ->
        ApprovalDialog(request = request, onDecision = vm::respondApproval)
    }

    ActionPromptDialogs(
        prompt = state.pendingActionPrompt,
        onDismiss = vm::dismissActionPrompt,
        onConfirmReview = vm::confirmReview,
        onConfirmUndo = vm::confirmUndo,
        onConfirmShell = vm::confirmShell,
    )

    LaunchedEffect(vm) {
        vm.openThread.collect(onOpenThread)
    }
}
