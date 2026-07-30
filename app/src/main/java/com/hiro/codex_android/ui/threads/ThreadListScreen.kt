package com.hiro.codex_android.ui.threads

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hiro.codex_android.data.ServiceLocator
import com.hiro.codex_android.data.model.Thread
import com.hiro.codex_android.ui.theme.GlassBorder
import com.hiro.codex_android.ui.theme.GlassFill
import com.hiro.codex_android.ui.theme.GlassFillStrong
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun ThreadListScreen(
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onOpenThread: (String) -> Unit,
    onNewThread: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val vm: ThreadListViewModel = viewModel(factory = ThreadListViewModel.factory(ServiceLocator.repository))
    val state by vm.uiState.collectAsState()

    // 首页可见时保持轻量轮询，让其他正在工作的任务也能及时显示状态。
    LaunchedEffect(Unit) {
        while (isActive) {
            vm.refresh()
            delay(8_000)
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        // 内容自行消费安全区，避免 Scaffold 与 statusBarsPadding 叠加产生双倍顶部留白。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().statusBarsPadding(),
                // 列表延伸到悬浮按钮下方，但末项仍可完整滚出按钮区域。
                contentPadding = PaddingValues(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.threads, key = { it.id }) { thread ->
                    ThreadCard(
                        thread = thread,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        onClick = { onOpenThread(thread.id) },
                    )
                }
            }

            if (state.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth().statusBarsPadding())
            }
            if (!state.loading && state.error != null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "无法连接：${state.error}",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            } else if (!state.loading && state.threads.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "还没有会话，点右下角 + 开始",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 两个独立的悬浮控件代替整条底栏，不再用黑色区域遮住历史列表。
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 8.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder),
                ) {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "设置",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                FloatingActionButton(
                    onClick = onNewThread,
                    modifier = Modifier.size(52.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp),
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "新会话",
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun ThreadCard(
    thread: Thread,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onClick: () -> Unit,
) {
    val working = thread.isWorking()
    val pulseTransition = rememberInfiniteTransition(label = "task-pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_050, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "task-pulse-alpha",
    )
    val sharedModifier = with(sharedTransitionScope) {
        Modifier.sharedBounds(
            sharedContentState = rememberSharedContentState(key = "thread-card-${thread.id}"),
            animatedVisibilityScope = animatedVisibilityScope,
            // 内容分段切换，避免卡片与聊天正文在同一帧重叠。
            enter = androidx.compose.animation.fadeIn(tween(durationMillis = 160, delayMillis = 400)),
            exit = androidx.compose.animation.fadeOut(tween(durationMillis = 150)),
            boundsTransform = { _, _ -> tween(durationMillis = 480, easing = FastOutSlowInEasing) },
        )
    }
    Card(
        onClick = onClick,
        // 过渡层轻微模糊，形成玻璃缓冲，静止后立即恢复清晰。
        modifier = sharedModifier
            .blur(if (sharedTransitionScope.isTransitionActive) 14.dp else 0.dp)
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (working) GlassFillStrong else GlassFill,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, if (working) MaterialTheme.colorScheme.primary.copy(alpha = 0.42f) else GlassBorder),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = thread.name ?: thread.preview.ifBlank { "Untitled task" },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (working) {
                    Spacer(Modifier.size(8.dp))
                    TaskStatus(pulseAlpha = pulseAlpha)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = listOfNotNull("Codex", thread.model, thread.effort?.replaceFirstChar { it.uppercase() })
                        .joinToString("  ·  "),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = relativeTime(thread.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TaskStatus(pulseAlpha: Float) = Box(
    modifier = Modifier
        .size(8.dp)
        .alpha(pulseAlpha)
        .background(Color(0xFF69D596), CircleShape),
)

private fun Thread.isWorking(): Boolean =
    status.type.equals("busy", ignoreCase = true) ||
        status.type.equals("inProgress", ignoreCase = true) ||
        status.type.equals("working", ignoreCase = true) ||
        status.type.equals("active", ignoreCase = true)

private fun relativeTime(epochSeconds: Long): String =
    DateUtils.getRelativeTimeSpanString(
        epochSeconds * 1000,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()
