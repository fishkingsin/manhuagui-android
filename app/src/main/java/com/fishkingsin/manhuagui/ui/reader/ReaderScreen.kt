package com.fishkingsin.manhuagui.ui.reader

import android.app.Activity
import android.util.Log
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeometrySize
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Size as CoilSize
import com.fishkingsin.manhuagui.core.LibraryService
import com.fishkingsin.manhuagui.core.PageImages
import com.fishkingsin.manhuagui.data.InMemoryLocalStore
import com.fishkingsin.manhuagui.data.LocalStore
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.min
import uniffi.manhuagui_ffi.ImagePage

@Composable
fun ReaderScreen(
    nativeId: Long,
    chapterId: Long,
    service: LibraryService,
    imageLoader: ImageLoader,
    onBack: () -> Unit,
    onOpenChapter: (Long) -> Unit,
    localStore: LocalStore = InMemoryLocalStore(),
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember(nativeId, chapterId, service, localStore, scope) {
        ReaderViewModel(
            nativeId = nativeId,
            chapterId = chapterId,
            service = service,
            localStore = localStore,
            scope = scope,
        )
    }
    val uiState by viewModel.state.collectAsState()
    var readerState by remember(viewModel) { mutableStateOf(ReaderState()) }
    val context = LocalContext.current
    val prefetcher = remember(context, imageLoader) { PagePrefetcher.create(context, imageLoader) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    val focusRequester = remember { FocusRequester() }
    val backgroundColor = MaterialTheme.colorScheme.background
    val contentColor = MaterialTheme.colorScheme.onBackground
    val window = (context as? Activity)?.window

    DisposableEffect(lifecycleOwner, view, window) {
        val keepScreenOnFlag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        fun setKeepScreenOn(enabled: Boolean) {
            view.keepScreenOn = enabled
            if (enabled) {
                window?.addFlags(keepScreenOnFlag)
            } else {
                window?.clearFlags(keepScreenOnFlag)
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> setKeepScreenOn(true)
                Lifecycle.Event.ON_PAUSE -> setKeepScreenOn(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        setKeepScreenOn(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            setKeepScreenOn(false)
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
    LaunchedEffect(viewModel) {
        viewModel.load()
    }
    LaunchedEffect(uiState.pages, uiState.pageIndex, uiState.lastTurnDirection) {
        prefetcher.onPageTurn(uiState.pages, uiState.pageIndex, uiState.lastTurnDirection)
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
            .background(backgroundColor)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                Log.i("ReaderKey", "action=${event.nativeKeyEvent.action} keyCode=${event.nativeKeyEvent.keyCode}")
                val keyResult = handleReaderKey(
                    event.nativeKeyEvent.keyCode,
                    event.nativeKeyEvent.action,
                    rtl = true,
                )
                if (keyResult.pageDelta != null) viewModel.turnPage(keyResult.pageDelta)
                keyResult.consumed
            },
    ) {
        when {
            uiState.error != null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(uiState.error?.userMessage.orEmpty(), color = contentColor)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReaderButton("Retry", viewModel::retry)
                    ReaderButton("Back", onBack)
                }
            }
            uiState.loading || uiState.pages.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                contentAlignment = Alignment.Center,
            ) {
                Text("Loading…", color = contentColor)
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
                        userScrollEnabled = readerState.zoom <= 1f,
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
    val backgroundColor = MaterialTheme.colorScheme.background
    val contentColor = MaterialTheme.colorScheme.onBackground
    var intrinsicSize by remember(page.url) { mutableStateOf(GeometrySize.Unspecified) }
    var retryCount by remember(page.url) { mutableStateOf(0) }
    var imageFailed by remember(page.url) { mutableStateOf(false) }
    val currentReaderState by rememberUpdatedState(readerState)
    val currentIntrinsicSize by rememberUpdatedState(intrinsicSize)
    val updateReaderState by rememberUpdatedState(onReaderStateChange)
    val turnPage by rememberUpdatedState(onTurnPage)
    val toggleChrome by rememberUpdatedState(onToggleChrome)
    val request = remember(context, page, retryCount) {
        ImageRequest.Builder(context)
            .data(page.url)
            .size(CoilSize.ORIGINAL)
            .apply(PageImages.modelFor(page))
            .apply {
                if (retryCount > 0) memoryCachePolicy(CachePolicy.WRITE_ONLY)
            }
            .build()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(page.url) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressedChanges = event.changes.filter { it.pressed }
                        if (pressedChanges.size > 1 || currentReaderState.zoom > 1f) {
                            val state = currentReaderState
                            val zoom = (state.zoom * event.calculateZoom()).coerceIn(1f, 4f)
                            val pan = event.calculatePan()
                            val viewportWidth = size.width.toFloat()
                            val viewportHeight = size.height.toFloat()
                            val intrinsic = currentIntrinsicSize
                            val (fittedWidth, fittedHeight) = if (
                                intrinsic.width > 0f && intrinsic.height > 0f
                            ) {
                                val fitScale = min(
                                    viewportWidth / intrinsic.width,
                                    viewportHeight / intrinsic.height,
                                )
                                intrinsic.width * fitScale to intrinsic.height * fitScale
                            } else {
                                viewportWidth to viewportHeight
                            }
                            val maxX = ((fittedWidth * zoom - viewportWidth).coerceAtLeast(0f)) / 2f
                            val maxY = ((fittedHeight * zoom - viewportHeight).coerceAtLeast(0f)) / 2f
                            val offset = Offset(
                                x = (state.offset.x + pan.x).coerceIn(-maxX, maxX),
                                y = (state.offset.y + pan.y).coerceIn(-maxY, maxY),
                            )
                            event.changes.forEach { it.consume() }
                            updateReaderState(state.withTransform(zoom, offset))
                        }
                    } while (event.changes.any { it.pressed })
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
    ) {
        AsyncImage(
            model = request,
            imageLoader = imageLoader,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            onLoading = { imageFailed = false },
            onSuccess = { state ->
                imageFailed = false
                intrinsicSize = state.painter.intrinsicSize
            },
            onError = { imageFailed = true },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = readerState.zoom
                    scaleY = readerState.zoom
                    translationX = readerState.offset.x
                    translationY = readerState.offset.y
                },
        )
        if (imageFailed) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(backgroundColor)
                    .border(1.dp, contentColor, RectangleShape)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Couldn't load page.", color = contentColor)
                ReaderButton("Retry page") {
                    imageFailed = false
                    imageLoader.memoryCache?.remove(MemoryCache.Key(page.url))
                    retryCount += 1
                }
            }
        }
    }
}

@Composable
private fun ReaderChrome(
    chapterId: Long,
    pageIndex: Int,
    pageCount: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = MaterialTheme.colorScheme.background
    val contentColor = MaterialTheme.colorScheme.onBackground
    Row(
        modifier = modifier
            .fillMaxWidth()
            .safeDrawingPadding()
            .background(backgroundColor)
            .border(1.dp, contentColor, RectangleShape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReaderButton("Back", onBack)
        Text("Chapter $chapterId", color = contentColor)
        Text("${pageIndex + 1} / $pageCount", color = contentColor)
    }
}

@Composable
private fun ReaderButton(label: String, onClick: () -> Unit) {
    val backgroundColor = MaterialTheme.colorScheme.background
    val contentColor = MaterialTheme.colorScheme.onBackground
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .background(backgroundColor, RectangleShape)
            .border(1.dp, contentColor, RectangleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = contentColor)
    }
}
