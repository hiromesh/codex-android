package com.hiro.codex_android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hiro.codex_android.data.AgentType
import com.hiro.codex_android.data.ServiceLocator
import com.hiro.codex_android.ui.components.AgentBadge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val vm: SettingsViewModel = viewModel(
        factory = SettingsViewModel.factory(ServiceLocator.settingsStore),
    )
    val state by vm.uiState.collectAsState()

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // Agent 服务器：多个配置，新增/编辑即时生效，不经底部“保存”。
            Text(
                text = "Agent 服务器",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            state.profiles.forEach { profile ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.startEditProfile(profile) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AgentBadge(profile.type, size = 30.dp)
                        Spacer(Modifier.padding(start = 10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = profile.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = profile.serverUrl,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        Switch(
                            checked = profile.enabled,
                            onCheckedChange = { vm.toggleProfileEnabled(profile) },
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = vm::startAddProfile,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("添加 Agent 服务器")
            }

            Text(
                text = "语音识别（火山引擎）",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            OutlinedTextField(
                value = state.asrUrl,
                onValueChange = vm::setAsrUrl,
                label = { Text("ASR 地址 (WebSocket)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            )
            OutlinedTextField(
                value = state.asrAppKey,
                onValueChange = vm::setAsrAppKey,
                label = { Text("App ID") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            )
            OutlinedTextField(
                value = state.asrAccessKey,
                onValueChange = vm::setAsrAccessKey,
                label = { Text("Access Token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            )
            OutlinedTextField(
                value = state.asrResourceId,
                onValueChange = vm::setAsrResourceId,
                label = { Text("ASR 资源 ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            )

            Text(
                text = "语音合成（TTS）",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "启用语音播报",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "流式朗读 agent 的回答（工具调用等不读）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.ttsEnabled, onCheckedChange = vm::setTtsEnabled)
            }
            if (state.ttsEnabled) {
                OutlinedTextField(
                    value = state.ttsUrl,
                    onValueChange = vm::setTtsUrl,
                    label = { Text("TTS 地址 (WebSocket)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                )
                OutlinedTextField(
                    value = state.ttsApiKey,
                    onValueChange = vm::setTtsApiKey,
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                )
                OutlinedTextField(
                    value = state.ttsResourceId,
                    onValueChange = vm::setTtsResourceId,
                    label = { Text("TTS 资源 ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                )
                OutlinedTextField(
                    value = state.ttsSpeaker,
                    onValueChange = vm::setTtsSpeaker,
                    label = { Text("音色（发音人 ID）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                )
                Text(
                    text = "语速：${state.ttsSpeechRate}（-50 ~ 100，0 为原速）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Slider(
                    value = state.ttsSpeechRate.toFloat(),
                    onValueChange = { vm.setTtsSpeechRate(it.toInt()) },
                    valueRange = -50f..100f,
                    modifier = Modifier.fillMaxWidth(),
                )
                }

            Text(
                text = "显示与电源",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "使用期间屏幕常亮",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "仅在本 App 前台生效，退出后仍跟系统熄屏时间",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.keepScreenOn, onCheckedChange = vm::setKeepScreenOn)
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    // 只保存 ASR/TTS 等全局配置；Agent 服务器在弹窗里即时保存。
                    vm.save()
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存")
            }
        }
    }

    state.draft?.let { draft ->
        ProfileEditDialog(
            draft = draft,
            error = state.draftError,
            onChange = vm::updateDraft,
            onSave = vm::saveDraft,
            onDelete = vm::deleteDraftProfile,
            onDismiss = vm::dismissDraft,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileEditDialog(
    draft: ProfileDraft,
    error: String?,
    onChange: ((ProfileDraft) -> ProfileDraft) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.id == null) "添加 Agent 服务器" else "编辑 Agent 服务器") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AgentType.entries.forEach { type ->
                        FilterChip(
                            selected = draft.type == type,
                            enabled = type.supported,
                            onClick = {
                                onChange { d ->
                                    d.copy(
                                        type = type,
                                        // 地址未改过时跟随类型默认值；手动改过则保留。
                                        serverUrl = if (d.serverUrl.isBlank() ||
                                            d.serverUrl == AgentType.defaultUrl(d.type)
                                        ) {
                                            AgentType.defaultUrl(type)
                                        } else {
                                            d.serverUrl
                                        },
                                    )
                                }
                            },
                            label = {
                                Text(if (type.supported) type.displayName else "${type.displayName}（暂未支持）")
                            },
                        )
                    }
                }
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { name -> onChange { it.copy(name = name) } },
                    label = { Text("名称") },
                    placeholder = { Text(draft.type.displayName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.serverUrl,
                    onValueChange = { url -> onChange { it.copy(serverUrl = url) } },
                    label = {
                        Text(
                            if (draft.type == AgentType.KIMI) "服务器地址 (HTTPS)"
                            else "服务器地址 (WebSocket)",
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.token,
                    onValueChange = { token -> onChange { it.copy(token = token) } },
                    label = { Text("Token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (draft.type == AgentType.KIMI) {
                    OutlinedTextField(
                        value = draft.defaultCwd,
                        onValueChange = { cwd -> onChange { it.copy(defaultCwd = cwd) } },
                        label = { Text("默认工作目录（服务器绝对路径）") },
                        placeholder = { Text("/home/ubuntu/proj") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (draft.id != null) {
                    TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                        Text("删除此配置", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
