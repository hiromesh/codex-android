package com.hiro.codex_android.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hiro.codex_android.data.ServiceLocator
import com.hiro.codex_android.data.model.ThreadItem

/** @param threadIdArg "new" 表示新会话（发第一条消息时才真正 thread/start） */
@Composable
fun ChatScreen(threadIdArg: String, onBack: () -> Unit) {
    val vm: ChatViewModel = viewModel(
        factory = ChatViewModel.factory(
            threadId = threadIdArg.takeIf { it != "new" },
            repo = ServiceLocator.repository,
        ),
    )
    val state by vm.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.reconcileAfterForeground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.error) {
        state.error?.let { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        containerColor = Color.Transparent,
        // 顶部只由消息列表和返回按钮各自消费状态栏安全区，避免空 TopAppBar 留白。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            ChatInputBar(
                generating = state.generating,
                model = state.model,
                effort = state.effort,
                models = state.availableModels,
                onSelectConfiguration = vm::switchConfiguration,
                tokenUsage = state.tokenUsage,
                onSend = vm::send,
                onInterrupt = vm::interrupt,
            )
        },
    ) { padding ->
        val listState = rememberLazyListState()
        val items = state.items
        var followLatest by remember { mutableStateOf(true) }

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
                items(items = items, key = { it.id }) { item ->
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
                    }
                }
            }
            IconButton(
                onClick = onBack,
                modifier = Modifier.statusBarsPadding().padding(start = 4.dp, top = 2.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // §6 审批：必须应答
    state.pendingApproval?.let { request ->
        ApprovalDialog(request = request, onDecision = vm::respondApproval)
    }
}
