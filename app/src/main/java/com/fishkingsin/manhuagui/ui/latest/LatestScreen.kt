package com.fishkingsin.manhuagui.ui.latest

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import com.fishkingsin.manhuagui.core.LibraryService
import com.fishkingsin.manhuagui.ui.comic.ComicCard
import uniffi.manhuagui_ffi.AndroidUpdateWindow

@Composable
fun LatestScreen(
    service: LibraryService,
    imageLoader: ImageLoader,
    onSearch: () -> Unit,
    onBookmarks: () -> Unit,
    onComic: (Long) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember(service, scope) { LatestViewModel(service, scope) }
    val uiState by viewModel.state.collectAsState()
    val backgroundColor = MaterialTheme.colorScheme.background
    val contentColor = MaterialTheme.colorScheme.onBackground

    LaunchedEffect(viewModel) {
        val initialState = viewModel.state.value
        if (initialState.items.isEmpty() && !initialState.loading && initialState.error == null) {
            viewModel.refresh()
        }
    }

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
            Text(text = "Latest", color = contentColor, fontSize = 26.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EInkButton(label = "Bookmarks", onClick = onBookmarks)
                EInkButton(label = "Search", onClick = onSearch)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WindowChip(
                "7d", AndroidUpdateWindow.DAYS7, uiState.window, viewModel::setWindow, Modifier.weight(1f),
            )
            WindowChip(
                "15d", AndroidUpdateWindow.DAYS15, uiState.window, viewModel::setWindow, Modifier.weight(1f),
            )
            WindowChip(
                "30d", AndroidUpdateWindow.DAYS30, uiState.window, viewModel::setWindow, Modifier.weight(1f),
            )
            WindowChip(
                "All", AndroidUpdateWindow.ALL, uiState.window, viewModel::setWindow, Modifier.weight(1f),
            )
        }
        when {
            uiState.loading && uiState.items.isEmpty() -> Message(Modifier.weight(1f), "Loading…")
            uiState.error != null -> Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(uiState.error?.userMessage.orEmpty(), color = contentColor)
                EInkButton(label = "Retry", onClick = viewModel::refresh)
            }
            uiState.items.isEmpty() -> Message(Modifier.weight(1f), "No comics found.")
            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(uiState.items, key = { it.comicId }) { item ->
                    ComicCard(
                        item = item,
                        imageLoader = imageLoader,
                        onClick = { onComic(item.comicId) },
                    )
                }
                if (uiState.window == AndroidUpdateWindow.ALL && !uiState.endReached) {
                    item(key = "load-more") {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (uiState.loading) {
                                Text("Loading…", color = contentColor, modifier = Modifier.padding(12.dp))
                            } else {
                                EInkButton(label = "Load more", onClick = viewModel::loadMore)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WindowChip(
    label: String,
    window: AndroidUpdateWindow,
    selectedWindow: AndroidUpdateWindow,
    onSelect: (AndroidUpdateWindow) -> Unit,
    modifier: Modifier,
) {
    val selected = window == selectedWindow
    val backgroundColor = MaterialTheme.colorScheme.background
    val contentColor = MaterialTheme.colorScheme.onBackground
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RectangleShape)
            .background(if (selected) contentColor else backgroundColor)
            .border(1.dp, contentColor, RectangleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onSelect(window) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) backgroundColor else contentColor,
            fontSize = 16.sp,
        )
    }
}

@Composable
private fun EInkButton(label: String, onClick: () -> Unit) {
    val backgroundColor = MaterialTheme.colorScheme.background
    val contentColor = MaterialTheme.colorScheme.onBackground
    Box(
        modifier = Modifier
            .height(48.dp)
            .clip(RectangleShape)
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

@Composable
private fun Message(modifier: Modifier, text: String) {
    val contentColor = MaterialTheme.colorScheme.onBackground
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = text, color = contentColor)
    }
}
