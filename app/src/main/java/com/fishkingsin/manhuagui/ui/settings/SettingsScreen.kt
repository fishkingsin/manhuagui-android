package com.fishkingsin.manhuagui.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fishkingsin.manhuagui.settings.AppSettings
import com.fishkingsin.manhuagui.settings.ReadingDirection
import uniffi.manhuagui_ffi.AndroidUpdateWindow

@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel) {
    val settings by viewModel.settings.collectAsState()
    val contentColor = MaterialTheme.colorScheme.onBackground

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EInkButton(label = "Back", onClick = onBack)
            Text(
                text = "Settings",
                modifier = Modifier.weight(1f),
                color = contentColor,
                fontSize = 24.sp,
                textAlign = TextAlign.Center,
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "reading-direction") {
                ChoiceSettingRow(
                    label = "Reading direction",
                    choices = listOf("RTL" to ReadingDirection.RTL, "LTR" to ReadingDirection.LTR),
                    selected = settings.readingDirection,
                    onSelect = viewModel::setReadingDirection,
                )
            }
            item(key = "update-window") {
                ChoiceSettingRow(
                    label = "Default update window",
                    choices = listOf(
                        "7d" to AndroidUpdateWindow.DAYS7,
                        "15d" to AndroidUpdateWindow.DAYS15,
                        "30d" to AndroidUpdateWindow.DAYS30,
                        "All" to AndroidUpdateWindow.ALL,
                    ),
                    selected = settings.defaultUpdateWindow,
                    onSelect = viewModel::setDefaultUpdateWindow,
                )
            }
            item(key = "check-interval") {
                ChoiceSettingRow(
                    label = "Check interval",
                    choices = AppSettings.ALLOWED_CHECK_INTERVAL_HOURS.map { "$it h" to it },
                    selected = settings.checkIntervalHours,
                    onSelect = viewModel::setCheckIntervalHours,
                )
            }
            item(key = "offline-only") {
                val backgroundColor = MaterialTheme.colorScheme.background
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .background(backgroundColor)
                        .border(1.dp, contentColor, RectangleShape)
                        .toggleable(
                            value = settings.offlineOnlyMode,
                            role = Role.Switch,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onValueChange = viewModel::setOfflineOnlyMode,
                        )
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(text = "Offline-only mode", color = contentColor)
                    Text(text = if (settings.offlineOnlyMode) "On" else "Off", color = contentColor)
                }
            }
            item(key = "prefetch-count") {
                ChoiceSettingRow(
                    label = "Prefetch count",
                    choices = AppSettings.ALLOWED_PREFETCH_COUNTS.map { it.toString() to it },
                    selected = settings.prefetchCount,
                    onSelect = viewModel::setPrefetchCount,
                )
            }
            item(key = "disk-cache") {
                ChoiceSettingRow(
                    label = "Disk cache size",
                    choices = AppSettings.ALLOWED_DISK_CACHE_SIZES_MB.map { "$it MB" to it },
                    selected = settings.diskCacheSizeMb,
                    onSelect = viewModel::setDiskCacheSizeMb,
                )
            }
        }
    }
}

@Composable
private fun <T> ChoiceSettingRow(
    label: String,
    choices: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    val contentColor = MaterialTheme.colorScheme.onBackground
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = label, color = contentColor, fontSize = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            choices.forEach { (text, value) ->
                ChoiceChip(
                    label = text,
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = MaterialTheme.colorScheme.background
    val contentColor = MaterialTheme.colorScheme.onBackground
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .background(if (selected) contentColor else backgroundColor)
            .border(1.dp, contentColor, RectangleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) backgroundColor else contentColor,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun EInkButton(label: String, onClick: () -> Unit) {
    val backgroundColor = MaterialTheme.colorScheme.background
    val contentColor = MaterialTheme.colorScheme.onBackground
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .background(backgroundColor)
            .border(1.dp, contentColor, RectangleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = contentColor, fontSize = 16.sp)
    }
}
