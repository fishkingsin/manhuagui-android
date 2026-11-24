package com.fishkingsin.manhuagui.ui.detail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.fishkingsin.manhuagui.core.LibraryService
import com.fishkingsin.manhuagui.data.DownloadedChapterRecord
import com.fishkingsin.manhuagui.data.InMemoryLocalStore
import com.fishkingsin.manhuagui.data.LocalStore
import com.fishkingsin.manhuagui.data.ReadingProgress
import com.fishkingsin.manhuagui.downloads.ArchiveExporter
import com.fishkingsin.manhuagui.downloads.ChapterDownloadProgress
import com.fishkingsin.manhuagui.downloads.OfflineDownloadManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import uniffi.manhuagui_ffi.ChapterItem
import uniffi.manhuagui_ffi.ComicDetails

@Composable
fun DetailScreen(
    nativeId: Long,
    service: LibraryService,
    imageLoader: ImageLoader,
    offlineDownloadManager: OfflineDownloadManager,
    archiveExporter: ArchiveExporter,
    onBack: () -> Unit,
    onChapter: (Long, String) -> Unit,
    localStore: LocalStore = InMemoryLocalStore(),
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember(nativeId, service, localStore, offlineDownloadManager, archiveExporter, scope) {
        DetailViewModel(
            nativeId = nativeId,
            service = service,
            localStore = localStore,
            offlineDownloadManager = offlineDownloadManager,
            archiveExporter = archiveExporter,
            scope = scope,
        )
    }
    val uiState by viewModel.state.collectAsState()
    val downloadedChapters by viewModel.downloadedChapters.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var pendingChapterExport by remember { mutableStateOf<Long?>(null) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val comic = uiState.comic

    val createCbzDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.comicbook+zip"),
    ) { uri ->
        val chapterId = pendingChapterExport
        pendingChapterExport = null
        if (uri != null && chapterId != null) {
            viewModel.exportChapterCbz(chapterId) { context.contentResolver.openOutputStream(uri) }
        }
    }
    val createPdfDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        val chapterId = pendingChapterExport
        pendingChapterExport = null
        if (uri != null && chapterId != null) {
            viewModel.exportChapterPdf(chapterId) { context.contentResolver.openOutputStream(uri) }
        }
    }
    val chooseCbzDirectory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val directory = DocumentFile.fromTreeUri(context, uri)
            viewModel.exportComicCbz { fileName ->
                val document = directory?.createFile("application/vnd.comicbook+zip", fileName)
                document?.let { context.contentResolver.openOutputStream(it.uri) }
            }
        }
    }
    val choosePdfDirectory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val directory = DocumentFile.fromTreeUri(context, uri)
            viewModel.exportComicPdf { fileName ->
                val document = directory?.createFile("application/pdf", fileName)
                document?.let { context.contentResolver.openOutputStream(it.uri) }
            }
        }
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshProgress()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(viewModel) {
        viewModel.exportResult.collectLatest { message ->
            exportMessage = message
            delay(3_000)
            exportMessage = null
        }
    }
    LaunchedEffect(viewModel) {
        val current = viewModel.state.value
        if (current.comic == null && !current.loading && current.error == null) {
            viewModel.load()
        }
    }

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
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            EInkButton(label = "Back", onClick = onBack)
            EInkButton(
                label = if (uiState.isBookmarked) "Bookmarked" else "Bookmark",
                onClick = viewModel::toggleBookmark,
            )
        }
        exportMessage?.let { Text(text = it, color = MaterialTheme.colorScheme.onBackground) }
        when {
            uiState.loading -> Message(Modifier.weight(1f), "Loading…")
            uiState.error != null -> Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(uiState.error?.userMessage.orEmpty(), color = MaterialTheme.colorScheme.onBackground)
                EInkButton(label = "Retry", onClick = viewModel::load)
            }
            comic != null -> DetailContent(
                comic = comic,
                chapters = uiState.chapters,
                readingProgress = uiState.readingProgress,
                downloadedChapters = downloadedChapters,
                downloadProgress = downloadProgress,
                imageLoader = imageLoader,
                onChapter = onChapter,
                onDownloadUnread = viewModel::downloadUnreadChapters,
                onExportComicCbz = { chooseCbzDirectory.launch(null) },
                onExportComicPdf = { choosePdfDirectory.launch(null) },
                onDeleteAllDownloads = viewModel::deleteAllDownloads,
                onDownloadChapter = viewModel::downloadChapter,
                onCancelDownload = viewModel::cancelDownload,
                onDeleteDownloadedChapter = viewModel::deleteDownloadedChapter,
                onExportChapterCbz = { chapterId ->
                    pendingChapterExport = chapterId
                    createCbzDocument.launch("chapter-$chapterId.cbz")
                },
                onExportChapterPdf = { chapterId ->
                    pendingChapterExport = chapterId
                    createPdfDocument.launch("chapter-$chapterId.pdf")
                },
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
    readingProgress: ReadingProgress?,
    downloadedChapters: List<DownloadedChapterRecord>,
    downloadProgress: Map<Long, ChapterDownloadProgress>,
    imageLoader: ImageLoader,
    onChapter: (Long, String) -> Unit,
    onDownloadUnread: () -> Unit,
    onExportComicCbz: () -> Unit,
    onExportComicPdf: () -> Unit,
    onDeleteAllDownloads: () -> Unit,
    onDownloadChapter: (Long) -> Unit,
    onCancelDownload: (Long) -> Unit,
    onDeleteDownloadedChapter: (Long) -> Unit,
    onExportChapterCbz: (Long) -> Unit,
    onExportChapterPdf: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor = MaterialTheme.colorScheme.onBackground
    val downloadedIds = remember(downloadedChapters) { downloadedChapters.mapTo(mutableSetOf()) { it.chapterId } }
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
                Text(text = comic.title, color = contentColor, fontSize = 24.sp)
                comic.subtitle?.let { subtitle ->
                    Text(text = subtitle, color = contentColor, fontSize = 18.sp)
                }
                if (comic.authors.isNotEmpty()) {
                    Text(text = "Authors: ${comic.authors.joinToString(", ")}", color = contentColor)
                }
                if (comic.genres.isNotEmpty()) {
                    Text(text = "Genres: ${comic.genres.joinToString(", ")}", color = contentColor)
                }
                Text(text = "Status: ${comic.statusText}", color = contentColor)
                Text(text = "Updated: ${comic.updateText}", color = contentColor)
                if (comic.intro.isNotBlank()) {
                    Text(text = comic.intro, color = contentColor)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EInkButton(
                        label = "Download Unread",
                        onClick = onDownloadUnread,
                        modifier = Modifier.weight(1f),
                    )
                    EInkButton(
                        label = "Export CBZ",
                        enabled = downloadedIds.isNotEmpty(),
                        onClick = onExportComicCbz,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EInkButton(
                        label = "Export PDF",
                        enabled = downloadedIds.isNotEmpty(),
                        onClick = onExportComicPdf,
                        modifier = Modifier.weight(1f),
                    )
                    EInkButton(
                        label = "Delete Downloads",
                        enabled = downloadedIds.isNotEmpty(),
                        onClick = onDeleteAllDownloads,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        readingProgress?.let { progress ->
            item(key = "continue-reading") {
                EInkButton(
                    label = "Continue: ${progress.chapterTitle} (p.${progress.pageIndex + 1})",
                    onClick = { onChapter(progress.chapterId, progress.chapterTitle) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item(key = "chapters-heading") {
            Text(text = "Chapters", color = contentColor, fontSize = 22.sp)
        }
        if (chapters.isEmpty()) {
            item(key = "empty-chapters") {
                Text(text = "No chapters available.", color = contentColor)
            }
        } else {
            items(chapters, key = { it.chapterId }) { chapter ->
                ChapterRow(
                    chapter = chapter,
                    isDownloaded = chapter.chapterId in downloadedIds,
                    progress = downloadProgress[chapter.chapterId],
                    onChapter = { onChapter(chapter.chapterId, chapter.chapterTitle) },
                    onDownload = { onDownloadChapter(chapter.chapterId) },
                    onCancel = { onCancelDownload(chapter.chapterId) },
                    onDelete = { onDeleteDownloadedChapter(chapter.chapterId) },
                    onExportCbz = { onExportChapterCbz(chapter.chapterId) },
                    onExportPdf = { onExportChapterPdf(chapter.chapterId) },
                )
            }
        }
    }
}

@Composable
private fun ChapterRow(
    chapter: ChapterItem,
    isDownloaded: Boolean,
    progress: ChapterDownloadProgress?,
    onChapter: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onExportCbz: () -> Unit,
    onExportPdf: () -> Unit,
) {
    val contentColor = MaterialTheme.colorScheme.onBackground
    val presentation = chapterDownloadPresentation(isDownloaded, progress)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, contentColor, RectangleShape)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onChapter,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(text = chapter.groupName, color = contentColor)
                Text(text = chapter.chapterTitle, color = contentColor)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(text = "${chapter.chapterSize}p", color = contentColor)
                Box(
                    modifier = Modifier
                        .border(1.dp, contentColor, RectangleShape)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(text = presentation.statusLabel, color = contentColor)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EInkButton(
                label = presentation.action.label,
                onClick = when (presentation.action) {
                    ChapterDownloadAction.DOWNLOAD -> onDownload
                    ChapterDownloadAction.CANCEL -> onCancel
                    ChapterDownloadAction.DELETE -> onDelete
                },
                modifier = Modifier.weight(1f),
            )
            EInkButton(
                label = "Export CBZ",
                enabled = presentation.canExport,
                onClick = onExportCbz,
                modifier = Modifier.weight(1f),
            )
            EInkButton(
                label = "Export PDF",
                enabled = presentation.canExport,
                onClick = onExportPdf,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun EInkButton(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = MaterialTheme.colorScheme.background
    val contentColor = MaterialTheme.colorScheme.onBackground
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .background(backgroundColor)
            .border(1.dp, contentColor, RectangleShape)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = contentColor, fontSize = 14.sp)
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
