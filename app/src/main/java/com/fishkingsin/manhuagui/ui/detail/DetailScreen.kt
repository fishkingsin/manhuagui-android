package com.fishkingsin.manhuagui.ui.detail

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.fishkingsin.manhuagui.core.LibraryService
import uniffi.manhuagui_ffi.ChapterItem
import uniffi.manhuagui_ffi.ComicDetails

@Composable
fun DetailScreen(
    nativeId: Long,
    service: LibraryService,
    imageLoader: ImageLoader,
    onBack: () -> Unit,
    onChapter: (Long) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember(nativeId, service, scope) { DetailViewModel(nativeId, service, scope) }
    val uiState by viewModel.state.collectAsState()
    val comic = uiState.comic

    LaunchedEffect(viewModel) {
        val current = viewModel.state.value
        if (current.comic == null && !current.loading && current.error == null) {
            viewModel.load()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
        ) {
            EInkButton(label = "Back", onClick = onBack)
        }
        when {
            uiState.loading -> Message(Modifier.weight(1f), "Loading…")
            uiState.error != null -> Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(uiState.error?.userMessage.orEmpty(), color = Color.Black)
                EInkButton(label = "Retry", onClick = viewModel::load)
            }
            comic != null -> DetailContent(
                comic = comic,
                chapters = uiState.chapters,
                imageLoader = imageLoader,
                onChapter = onChapter,
                modifier = Modifier.weight(1f),
            )
            else -> Message(Modifier.weight(1f), "Loading…")
        }
    }
}

@Composable
private fun DetailContent(
    comic: ComicDetails,
    chapters: List<ChapterItem>,
    imageLoader: ImageLoader,
    onChapter: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "comic-details") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AsyncImage(
                    model = comic.coverUrl,
                    imageLoader = imageLoader,
                    contentDescription = comic.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 120.dp, height = 168.dp)
                        .align(Alignment.CenterHorizontally),
                )
                Text(text = comic.title, color = Color.Black, fontSize = 24.sp)
                comic.subtitle?.let { subtitle ->
                    Text(text = subtitle, color = Color.Black, fontSize = 18.sp)
                }
                if (comic.authors.isNotEmpty()) {
                    Text(text = "Authors: ${comic.authors.joinToString(", ")}", color = Color.Black)
                }
                if (comic.genres.isNotEmpty()) {
                    Text(text = "Genres: ${comic.genres.joinToString(", ")}", color = Color.Black)
                }
                Text(text = "Status: ${comic.statusText}", color = Color.Black)
                Text(text = "Updated: ${comic.updateText}", color = Color.Black)
                if (comic.intro.isNotBlank()) {
                    Text(text = comic.intro, color = Color.Black)
                }
            }
        }
        item(key = "chapters-heading") {
            Text(text = "Chapters", color = Color.Black, fontSize = 22.sp)
        }
        items(chapters, key = { it.chapterId }) { chapter ->
            ChapterRow(chapter = chapter, onClick = { onChapter(chapter.chapterId) })
        }
    }
}

@Composable
private fun ChapterRow(chapter: ChapterItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .border(1.dp, Color.Black, RectangleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = chapter.groupName, color = Color.Black)
            Text(text = chapter.chapterTitle, color = Color.Black)
        }
        Text(text = "${chapter.chapterSize}p", color = Color.Black)
    }
}

@Composable
private fun EInkButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(48.dp)
            .background(Color.White)
            .border(1.dp, Color.Black, RectangleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = Color.Black, fontSize = 16.sp)
    }
}

@Composable
private fun Message(modifier: Modifier, text: String) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = text, color = Color.Black)
    }
}
