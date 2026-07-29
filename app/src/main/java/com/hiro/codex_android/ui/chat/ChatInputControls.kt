package com.hiro.codex_android.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hiro.codex_android.data.model.ModelInfo
import com.hiro.codex_android.data.model.TokenUsage
import com.hiro.codex_android.ui.theme.GlassBorder
import com.hiro.codex_android.ui.theme.GlassPopup
import kotlin.math.roundToInt

@Composable
fun ModelSelector(
    model: String,
    effort: String,
    models: List<ModelInfo>,
    onSelect: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    var effortModelId by remember { mutableStateOf<String?>(null) }
    val selectedModel = models.firstOrNull { it.id == model }
    val label = selectedModel?.displayName ?: "GPT-5.6-Terra"

    Box(modifier) {
        ModelSelectorTrigger(label, effort, onClick = { open = true })
        SelectionMenu(
            open = open,
            model = model,
            effort = effort,
            models = models,
            effortModelId = effortModelId,
            onDismiss = {
                open = false
                effortModelId = null
            },
            onOpenEffort = { effortModelId = it },
            onBack = { effortModelId = null },
            onSelect = { modelId, selectedEffort ->
                onSelect(modelId, selectedEffort)
                open = false
                effortModelId = null
            },
        )
    }
}

@Composable
private fun ModelSelectorTrigger(label: String, effort: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(
            text = " · ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = effortLabel(effort),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            Icons.Default.KeyboardArrowDown,
            contentDescription = "切换模型和推理档位",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 3.dp).size(15.dp),
        )
    }
}

@Composable
private fun SelectionMenu(
    open: Boolean,
    model: String,
    effort: String,
    models: List<ModelInfo>,
    effortModelId: String?,
    onDismiss: () -> Unit,
    onOpenEffort: (String) -> Unit,
    onBack: () -> Unit,
    onSelect: (String, String) -> Unit,
) {
    DropdownMenu(
        expanded = open,
        onDismissRequest = onDismiss,
        modifier = Modifier.width(280.dp),
        shape = RoundedCornerShape(16.dp),
        containerColor = GlassPopup,
        tonalElevation = 0.dp,
        shadowElevation = 16.dp,
        border = BorderStroke(1.dp, GlassBorder),
    ) {
        val effortModel = models.firstOrNull { it.id == effortModelId }
        if (effortModel == null) {
            ModelMenu(models, model, onOpenEffort)
        } else {
            val currentEffort = if (effortModel.id == model) effort else effortModel.defaultReasoningEffort
            EffortMenu(effortModel, currentEffort, onBack) { selectedEffort ->
                onSelect(effortModel.id, selectedEffort)
            }
        }
    }
}

@Composable
private fun ModelMenu(
    models: List<ModelInfo>,
    selectedModelId: String,
    onOpenEffort: (String) -> Unit,
) {
    Column(Modifier.padding(horizontal = 6.dp, vertical = 5.dp)) {
        models.filter { !it.hidden }.forEach { item ->
            ModelMenuRow(
                model = item,
                selected = item.id == selectedModelId,
                onClick = { onOpenEffort(item.id) },
            )
        }
    }
}

@Composable
private fun ModelMenuRow(model: ModelInfo, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 12.dp),
    ) {
        Text(model.displayName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = "当前模型",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
        } else {
            Icon(
                Icons.Default.KeyboardArrowRight,
                contentDescription = "选择推理档位",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun EffortMenu(
    model: ModelInfo,
    selectedEffort: String,
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
) {
    Column(Modifier.padding(horizontal = 6.dp, vertical = 5.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onBack)
                .padding(horizontal = 8.dp, vertical = 10.dp),
        ) {
            Icon(
                Icons.Default.KeyboardArrowLeft,
                contentDescription = "返回模型列表",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(model.displayName, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 6.dp))
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        model.supportedReasoningEfforts.forEach { item ->
            EffortMenuRow(item, item == selectedEffort, onClick = { onSelect(item) })
        }
    }
}

@Composable
private fun EffortMenuRow(effort: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 11.dp),
    ) {
        Text(
            effortLabel(effort),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = "当前推理档位",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

@Composable
fun ContextUsageRing(usage: TokenUsage?) {
    if (usage == null || usage.contextWindow <= 0) return
    val fraction = (usage.usedTokens.toFloat() / usage.contextWindow.toFloat()).coerceIn(0f, 1f)
    val color = if (fraction >= 0.8f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(30.dp)) {
        CircularProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxSize(),
            color = color,
            trackColor = MaterialTheme.colorScheme.surface,
            strokeWidth = 2.5.dp,
        )
        Text(
            text = "${(fraction * 100).roundToInt()}",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = color,
        )
    }
}

private fun effortLabel(effort: String): String =
    effort.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
