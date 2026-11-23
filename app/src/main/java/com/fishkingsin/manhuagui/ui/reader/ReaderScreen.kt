package com.fishkingsin.manhuagui.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Size
import com.fishkingsin.manhuagui.core.LibraryService
import com.fishkingsin.manhuagui.core.PageImages
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import uniffi.manhuagui_ffi.ImagePage

@Composable
fun ReaderScreen(
    nativeId: Long,
    chapterId: Long,
    service: LibraryService,
    imageLoader: ImageLoader,
    onBack: () -> Unit,
    onOpenChapter: (Long) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember(nativeId, chapterId, service) {
        ReaderViewModel(nativeId, chapterId, service, scope)
    }
    val uiState by viewModel.state.collectAsState()
    var readerState by remember(viewModel) { mutableStateOf(ReaderState()) }
    val view = LocalView.current

    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(viewModel) {
        viewModel.load()
    }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ReaderEffect.OpenChapter -> onOpenChapter(effect.chapterId)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
    ) {
        when {
            uiState.error != null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(uiState.error?.userMessage.orEmpty(), color = Color.Black)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReaderButton("Retry", viewModel::retry)
                    ReaderButton("Back", onBack)
                }
            }
            uiState.loading || uiState.pages.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text("Loading…", color = Color.Black)
            }
            else -> {
                val pagerState = rememberPagerState(pageCount = { uiState.pages.size })

                LaunchedEffect(pagerState, viewModel) {
                    snapshotFlow { pagerState.currentPage }
                        .distinctUntilChanged()
                        .collect { page ->
                            readerState = readerState.onPagerPage(page)
                            viewModel.onPageChanged(page)
                        }
                }
                LaunchedEffect(uiState.pageIndex, uiState.pages.size) {
                    if (uiState.pages.isNotEmpty() && pagerState.currentPage != uiState.pageIndex) {
                        pagerState.scrollToPage(uiState.pageIndex)
                    }
                }

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    HorizontalPager(
                        state = pagerState,
                        beyondViewportPageCount = 0,
                        modifier = Modifier.fillMaxSize(),
                    ) { pageIndex ->
                        ReaderPage(
                            page = uiState.pages[pageIndex],
                            imageLoader = imageLoader,
                            readerState = readerState,
                            onReaderStateChange = { readerState = it },
                            onTurnPage = viewModel::turnPage,
                            onToggleChrome = viewModel::toggleChrome,
                        )
                    }
                }

                if (uiState.chromeVisible) {
                    ReaderChrome(
                        chapterId = uiState.chapterId,
                        pageIndex = uiState.pageIndex,
                        pageCount = uiState.pages.size,
                        onBack = onBack,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderPage(
    page: ImagePage,
    imageLoader: ImageLoader,
    readerState: ReaderState,
    onReaderStateChange: (ReaderState) -> Unit,
    onTurnPage: (Int) -> Unit,
    onToggleChrome: () -> Unit,
) {
    val context = LocalContext.current
    val currentReaderState by rememberUpdatedState(readerState)
    val updateReaderState by rememberUpdatedState(onReaderStateChange)
    val turnPage by rememberUpdatedState(onTurnPage)
    val toggleChrome by rememberUpdatedState(onToggleChrome)
    val request = remember(context, page) {
        ImageRequest.Builder(context)
            .data(page.url)
            .size(Size.ORIGINAL)
            .apply(PageImages.modelFor(page))
            .build()
    }

    AsyncImage(
        model = request,
        imageLoader = imageLoader,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = readerState.zoom
                scaleY = readerState.zoom
                translationX = readerState.offset.x
                translationY = readerState.offset.y
            }
            .pointerInput(page.url) {
                detectTransformGestures { _, pan, zoomChange, _ ->
                    val state = currentReaderState
                    val zoom = (state.zoom * zoomChange).coerceIn(1f, 4f)
                    val maxX = size.width * (zoom - 1f) / 2f
                    val maxY = size.height * (zoom - 1f) / 2f
                    val offset = Offset(
                        x = (state.offset.x + pan.x).coerceIn(-maxX, maxX),
                        y = (state.offset.y + pan.y).coerceIn(-maxY, maxY),
                    )
                    updateReaderState(state.withTransform(zoom, offset))
                }
            }
            .pointerInput(page.url) {
                detectTapGestures(
                    onDoubleTap = {
                        updateReaderState(currentReaderState.toggleDoubleTapZoom())
                    },
                    onTap = { point ->
                        when (tapZoneAction(point.x, size.width.toFloat())) {
                            TapAction.PrevPage -> turnPage(-1)
                            TapAction.NextPage -> turnPage(1)
                            TapAction.ToggleChrome -> toggleChrome()
                        }
                    },
                )
            },
    )
}

@Composable
private fun ReaderChrome(
    chapterId: Long,
    pageIndex: Int,
    pageCount: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.White)
            .border(1.dp, Color.Black, RectangleShape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReaderButton("Back", onBack)
        Text("Chapter $chapterId", color = Color.Black)
        Text("${pageIndex + 1} / $pageCount", color = Color.Black)
    }
}

@Composable
private fun ReaderButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .background(Color.White, RectangleShape)
            .border(1.dp, Color.Black, RectangleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.Black)
    }
}
