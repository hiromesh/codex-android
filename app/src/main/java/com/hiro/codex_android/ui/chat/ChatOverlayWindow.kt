package com.hiro.codex_android.ui.chat

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.hiro.codex_android.data.model.ThreadItem
import com.hiro.codex_android.ui.theme.CodexandroidTheme

/** The model is deliberately a mirror of the active ChatViewModel, not a second chat session. */
data class ChatOverlayModel(
    val state: ChatUiState,
    val douyinPaused: Boolean,
    val onSend: (String) -> Unit,
    val onInterrupt: () -> Unit,
    val onSelectConfiguration: (String, String) -> Unit,
    val onApproval: (com.hiro.codex_android.data.model.ApprovalDecision) -> Unit,
)

/**
 * A touchable system overlay which mirrors the current chat above an external PiP/pop-up window.
 *
 * Android cannot lower our overlay below another app's window on demand, so during generation the
 * surface becomes intentionally translucent; when the turn finishes it becomes an opaque glass
 * reading surface and the external player's media session is paused by ChatScreen.
 */
object ChatOverlayWindow {
    private var windowManager: WindowManager? = null
    private var root: ComposeView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private val model = mutableStateOf<ChatOverlayModel?>(null)

    fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun showOrUpdate(context: Context, lifecycleOwner: LifecycleOwner, value: ChatOverlayModel) {
        if (!canDraw(context)) return
        model.value = value
        if (root != null) {
            updateTouchMode(generating = value.state.generating)
            return
        }

        val appContext = context.applicationContext
        val composeView = ComposeView(appContext).also { view ->
            // A ComposeView attached straight to WindowManager has no Activity decor-view tree.
            // Supplying the owner keeps effects, dialogs, and text input lifecycle-aware.
            view.setViewTreeLifecycleOwner(lifecycleOwner)
            // Compose 1.10 also requires a saved-state owner when it attaches.  The screen's
            // ComponentActivity supplies it; without this, a system overlay crashes on attach.
            (lifecycleOwner as? SavedStateRegistryOwner)?.let { savedStateOwner ->
                view.setViewTreeSavedStateRegistryOwner(savedStateOwner)
            }
            view.setContent {
                CodexandroidTheme {
                    model.value?.let { overlayModel ->
                        ChatOverlaySurface(model = overlayModel)
                    }
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            overlayFlags(generating = value.state.generating),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            // Android 12+ allows touch to pass a TYPE_APPLICATION_OVERLAY only below its
            // obscuring-opacity threshold.  This keeps the visual layer transparent to input.
            alpha = if (value.state.generating) 0.79f else 1f
        }
        val manager = appContext.getSystemService(WindowManager::class.java)
        runCatching {
            manager.addView(composeView, params)
            root = composeView
            windowManager = manager
            layoutParams = params
        }
    }

    /**
     * During generation this is a visual-only layer: swipes pass through to the video PiP (and
     * taps outside it pass through to the real chat below).  The completed glass becomes
     * touchable again so its input field and history can be used normally.
     */
    private fun updateTouchMode(generating: Boolean) {
        val view = root ?: return
        val manager = windowManager ?: return
        val params = layoutParams ?: return
        val flags = overlayFlags(generating)
        val alpha = if (generating) 0.79f else 1f
        if (params.flags == flags && params.alpha == alpha) return
        params.flags = flags
        params.alpha = alpha
        runCatching { manager.updateViewLayout(view, params) }
    }

    private fun overlayFlags(generating: Boolean): Int =
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            if (generating) {
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            } else {
                0
            }

    fun hide() {
        val view = root ?: return
        val manager = windowManager ?: return
        root = null
        windowManager = null
        layoutParams = null
        model.value = null
        runCatching { manager.removeViewImmediate(view) }
    }

    fun openPermissionSettings(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

@Composable
private fun ChatOverlaySurface(model: ChatOverlayModel) {
    val state = model.state
    // Generation leaves enough of the third-party video visible to be useful.  At rest this is
    // deliberately almost opaque: Android cannot blur pixels belonging to a different app.
    // Keep the video genuinely readable while Codex works; the chat cards still supply their
    // own contrast, so only the surrounding canvas needs to become this transparent.
    val backdrop = if (state.generating) Color(0x360D121A) else Color(0xF00D121A)
    val listState = rememberLazyListState()
    val entries = state.items
    var followLatest by remember(state.threadId) { mutableStateOf(true) }
    var initiallyPositioned by remember(state.threadId) { mutableStateOf(false) }

    LaunchedEffect(state.loading, entries.size) {
        if (!initiallyPositioned && !state.loading && entries.isNotEmpty()) {
            withFrameNanos { }
            listState.scrollToItem(entries.lastIndex)
            listState.scrollBy(100_000f)
            initiallyPositioned = true
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val lastIndex = layout.totalItemsCount - 1
            val last = layout.visibleItemsInfo.lastOrNull { it.index == lastIndex }
            last != null && last.offset + last.size <= layout.viewportEndOffset + 12
        }.collect { atBottom -> followLatest = atBottom }
    }
    val lastContentLength = when (val item = entries.lastOrNull()) {
        is ThreadItem.AgentMessage -> item.text.length
        is ThreadItem.Reasoning -> item.summary.sumOf(String::length)
        else -> 0
    }
    LaunchedEffect(entries.size, lastContentLength) {
        if (followLatest && !listState.isScrollInProgress && entries.isNotEmpty()) {
            listState.scrollBy(100_000f)
        }
    }

    Scaffold(
        containerColor = backdrop,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            ChatInputBar(
                generating = state.generating,
                model = state.model,
                effort = state.effort,
                models = state.availableModels,
                onSelectConfiguration = model.onSelectConfiguration,
                tokenUsage = state.tokenUsage,
                onSend = model.onSend,
                onInterrupt = model.onInterrupt,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                state = listState,
                // The message cards are intentionally secondary to the moving picture while a
                // turn is in flight.  Applying alpha to the complete list also softens cards
                // emitted by tools, without changing their normal in-app appearance.
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .alpha(if (state.generating) 0.52f else 1f),
                // Reserve the upper part of the overlay for the video.  The live reasoning
                // stream begins lower, where it no longer sits directly on top of the picture.
                contentPadding = PaddingValues(
                    start = 12.dp,
                    top = if (state.generating) 120.dp else 48.dp,
                    end = 12.dp,
                    bottom = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(items = entries, key = { it.id }) { item ->
                    when (item) {
                        is ThreadItem.UserMessage -> UserMessageBubble(item)
                        is ThreadItem.AgentMessage -> AgentMessageItem(
                            item = item,
                            streaming = state.generating && item.id == entries.lastOrNull()?.id,
                        )
                        is ThreadItem.CommandExecution -> CommandExecutionCard(item)
                        is ThreadItem.FileChange -> FileChangeCard(item)
                        is ThreadItem.Plan -> PlanCard(item)
                        is ThreadItem.WebSearch -> WebSearchCard(item)
                        is ThreadItem.Reasoning -> ReasoningItem(item)
                    }
                }
            }
            Surface(
                color = if (state.generating) Color(0xB51D2A39) else Color(0xED1D2A39),
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 8.dp)
                    .alpha(if (state.generating) 0.62f else 1f),
            ) {
                Text(
                    text = when {
                        state.generating -> "Codex 正在推理 · 上滑切换视频"
                        model.douyinPaused -> "Codex 已完成 · 抖音已暂停"
                        else -> "Codex 已完成"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
            // A system overlay is not on the Activity back stack.  Give it an explicit escape
            // hatch so it can never trap navigation if the user no longer wants the PiP layout.
            IconButton(
                onClick = ChatOverlayWindow::hide,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 4.dp, end = 6.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "关闭聊天浮层",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }

    state.pendingApproval?.let { request ->
        ApprovalDialog(request = request, onDecision = model.onApproval)
    }
}
