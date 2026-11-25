package com.fishkingsin.manhuagui.ui.bookmarks

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.fishkingsin.manhuagui.bookmarks.BookmarkUpdateChecker
import com.fishkingsin.manhuagui.data.LocalStore

@Composable
fun BookmarksScreen(
    localStore: LocalStore,
    checker: BookmarkUpdateChecker,
    onComic: (Long) -> Unit,
    imageLoader: ImageLoader,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    var ignoringBattery by remember {
        mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName))
    }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ignoringBattery = powerManager.isIgnoringBatteryOptimizations(context.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val scope = rememberCoroutineScope()
    val viewModel = remember(localStore, checker, scope) {
        BookmarksViewModel(localStore, checker, scope)
    }
    val uiState by viewModel.state.collectAsState()
    val backgroundColor = MaterialTheme.colorScheme.background
    val contentColor = MaterialTheme.colorScheme.onBackground

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .safeDrawingPadding()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "Bookmarks", color = contentColor, fontSize = 24.sp)
            EInkButton(
                label = "Check updates",
                enabled = !uiState.checking,
                onClick = viewModel::checkUpdatesNow,
            )
        }
        if (!ignoringBattery) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, contentColor, RectangleShape)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Battery optimization may stop background update checks.",
                    modifier = Modifier.weight(1f),
                    color = contentColor,
                )
                EInkButton(
                    label = "Battery settings",
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    },
                )
            }
        }
        if (uiState.checking) {
            Text(text = "Checking…", color = contentColor)
        }
        if (uiState.checkFailed) {
            Text(text = "Couldn't check updates.", color = contentColor)
        }
        uiState.lastUpdatedCount?.let { count ->
            Text(text = "Updated $count manga.", color = contentColor)
        }
        if (uiState.items.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "No bookmarks yet.", color = contentColor)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(uiState.items, key = { it.comicId }) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RectangleShape)
                            .background(backgroundColor)
                            .border(1.dp, contentColor, RectangleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onComic(item.comicId) },
                            )
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = item.coverUrl,
                            imageLoader = imageLoader,
                            contentDescription = item.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(width = 80.dp, height = 112.dp),
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = item.title,
                                color = contentColor,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = item.latestChapterTitle,
                                color = contentColor,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (item.hasUnreadUpdate) {
                                Box(
                                    modifier = Modifier.border(1.dp, contentColor, RectangleShape),
                                ) {
                                    Text(
                                        text = "Updated (+${item.newChapterCount})",
                                        color = contentColor,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                }
                            }
                            EInkButton(
                                label = "Remove",
                                onClick = { viewModel.removeBookmark(item.comicId) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EInkButton(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val backgroundColor = MaterialTheme.colorScheme.background
    val contentColor = MaterialTheme.colorScheme.onBackground
    Box(
        modifier = Modifier
            .height(48.dp)
            .clip(RectangleShape)
            .background(backgroundColor)
            .border(1.dp, contentColor, RectangleShape)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = contentColor, fontSize = 14.sp)
    }
}
