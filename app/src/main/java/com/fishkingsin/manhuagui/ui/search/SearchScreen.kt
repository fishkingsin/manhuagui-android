package com.fishkingsin.manhuagui.ui.search

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import com.fishkingsin.manhuagui.core.LibraryService
import com.fishkingsin.manhuagui.ui.comic.ComicCard

@Composable
fun SearchScreen(
    service: LibraryService,
    imageLoader: ImageLoader,
    onBack: () -> Unit,
    onComic: (Long) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember(service, scope) { SearchViewModel(service, scope) }
    val uiState by viewModel.state.collectAsState()
    val results = uiState.results

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
        Text(text = "Search", color = Color.Black, fontSize = 26.sp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = uiState.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .border(1.dp, Color.Black, RectangleShape)
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                singleLine = true,
                enabled = !uiState.loading,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.submit() }),
                textStyle = TextStyle(color = Color.Black),
                cursorBrush = SolidColor(Color.Black),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (uiState.query.isEmpty()) {
                            Text(text = "Search comics", color = Color.Black)
                        }
                        innerTextField()
                    }
                },
            )
            EInkButton(label = "Search", onClick = viewModel::submit)
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
                EInkButton(label = "Retry", onClick = viewModel::submit)
            }
            results?.items?.isEmpty() == true -> Message(Modifier.weight(1f), "No comics found.")
            results != null -> LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(results.items, key = { it.comicId }) { item ->
                    ComicCard(
                        item = item,
                        imageLoader = imageLoader,
                        onClick = { onComic(item.comicId) },
                    )
                }
            }
            else -> Message(
                Modifier.weight(1f),
                if (uiState.query.isBlank()) "Enter a comic title." else "Press Search to find comics.",
            )
        }
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
