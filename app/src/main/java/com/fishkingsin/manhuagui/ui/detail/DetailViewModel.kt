package com.fishkingsin.manhuagui.ui.detail

import com.fishkingsin.manhuagui.core.AppError
import com.fishkingsin.manhuagui.core.LibraryService
import com.fishkingsin.manhuagui.core.toAppError
import com.fishkingsin.manhuagui.data.BookmarkRecord
import com.fishkingsin.manhuagui.data.DownloadedChapterRecord
import com.fishkingsin.manhuagui.data.InMemoryLocalStore
import com.fishkingsin.manhuagui.data.LocalStore
import com.fishkingsin.manhuagui.data.ReadingProgress
import com.fishkingsin.manhuagui.downloads.ArchiveExporter
import com.fishkingsin.manhuagui.downloads.ChapterDownloadProgress
import com.fishkingsin.manhuagui.downloads.ChapterDownloadRequest
import com.fishkingsin.manhuagui.downloads.OfflineDownloadManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.OutputStream
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uniffi.manhuagui_ffi.AndroidExportFormat
import uniffi.manhuagui_ffi.ChapterItem
import uniffi.manhuagui_ffi.ComicDetails

data class DetailUiState(
    val comic: ComicDetails? = null,
    val chapters: List<ChapterItem> = emptyList(),
    val isBookmarked: Boolean = false,
    val readingProgress: ReadingProgress? = null,
    val loading: Boolean = false,
    val error: AppError? = null,
)

class DetailViewModel(
    private val nativeId: Long,
    private val service: LibraryService,
    private val offlineDownloadManager: OfflineDownloadManager,
    private val archiveExporter: ArchiveExporter,
    private val localStore: LocalStore = InMemoryLocalStore(),
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val mutableState = MutableStateFlow(DetailUiState())
    private val mutableExportResult = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private val toggleMutex = Mutex()
    private var toggleGeneration = 0L
    val state = mutableState.asStateFlow()
    val downloadedChapters: StateFlow<List<DownloadedChapterRecord>> = localStore
        .observeDownloadedChapters(nativeId)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    val downloadProgress: StateFlow<Map<Long, ChapterDownloadProgress>> = offlineDownloadManager.progress
        .map { progress -> progress.filterValues { it.comicId == nativeId } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())
    val exportResult: SharedFlow<String> = mutableExportResult.asSharedFlow()

    fun load() {
        if (mutableState.value.loading) return

        mutableState.value = mutableState.value.copy(loading = true, error = null)
        scope.launch {
            service.getComic(nativeId).fold(
                onSuccess = { comic ->
                    val sortedChapters = comic.chapters.sortedWith(
                        compareByDescending<ChapterItem> { it.groupSize }
                            .thenBy { it.groupName }
                            .thenByDescending { it.order },
                    )
                    val existingBookmark = localStore.updateBookmarkIfPresent(nativeId) { current ->
                        current.copy(
                            title = comic.title,
                            coverUrl = comic.coverUrl,
                            lastKnownChapterIds = comic.chapters.map { it.chapterId }.toSet(),
                            latestChapterTitle = sortedChapters.firstOrNull()?.chapterTitle.orEmpty(),
                            hasUnreadUpdate = false,
                            newChapterCount = 0,
                        )
                    }
                    val progress = localStore.getReadingProgress(nativeId)
                    mutableState.value = mutableState.value.copy(
                        loading = false,
                        comic = comic,
                        chapters = sortedChapters,
                        isBookmarked = existingBookmark != null,
                        readingProgress = progress,
                        error = null,
                    )
                },
                onFailure = { throwable ->
                    mutableState.value = mutableState.value.copy(
                        loading = false,
                        comic = null,
                        chapters = emptyList(),
                        error = throwable.toAppError(),
                    )
                },
            )
        }
    }

    fun toggleBookmark() {
        val current = mutableState.value
        val comic = current.comic ?: return
        val nextBookmarked = !current.isBookmarked
        val generation = ++toggleGeneration
        mutableState.value = current.copy(isBookmarked = nextBookmarked)
        scope.launch {
            toggleMutex.withLock {
                try {
                    if (nextBookmarked) {
                        localStore.upsertBookmark(
                            BookmarkRecord(
                                comicId = nativeId,
                                title = comic.title,
                                coverUrl = comic.coverUrl,
                                lastKnownChapterIds = comic.chapters.map { it.chapterId }.toSet(),
                                latestChapterTitle = current.chapters.firstOrNull()?.chapterTitle.orEmpty(),
                                newChapterCount = 0,
                                hasUnreadUpdate = false,
                                lastCheckedEpochMs = clockMs(),
                                updatedAtEpochMs = clockMs(),
                            ),
                        )
                    } else {
                        localStore.deleteBookmark(nativeId)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    if (generation == toggleGeneration) {
                        mutableState.value = mutableState.value.copy(isBookmarked = !nextBookmarked)
                    }
                }
            }
        }
    }

    fun downloadChapter(chapterId: Long) {
        offlineDownloadManager.enqueue(listOf(ChapterDownloadRequest(nativeId, chapterId)))
    }

    fun downloadUnreadChapters() {
        val chapters = mutableState.value.chapters
        val progress = mutableState.value.readingProgress
        val unread = if (progress == null) {
            chapters
        } else {
            val progressIndex = chapters.indexOfFirst { it.chapterId == progress.chapterId }
            if (progressIndex < 0) emptyList() else chapters.take(progressIndex)
        }
        scope.launch {
            val downloadedIds = localStore.getDownloadedChapters(nativeId)
                .mapTo(mutableSetOf()) { it.chapterId }
            offlineDownloadManager.enqueue(
                unread.filterNot { it.chapterId in downloadedIds }
                    .map { ChapterDownloadRequest(nativeId, it.chapterId) },
            )
        }
    }

    fun cancelDownload(chapterId: Long) {
        offlineDownloadManager.cancel(chapterId)
    }

    fun deleteDownloadedChapter(chapterId: Long) {
        scope.launch { offlineDownloadManager.deleteChapter(nativeId, chapterId) }
    }

    fun deleteAllDownloads() {
        scope.launch { offlineDownloadManager.deleteComic(nativeId) }
    }

    fun exportChapterCbz(chapterId: Long, openOutputStream: () -> OutputStream?) {
        exportChapter(chapterId, AndroidExportFormat.CBZ, openOutputStream)
    }

    fun exportChapterPdf(chapterId: Long, openOutputStream: () -> OutputStream?) {
        exportChapter(chapterId, AndroidExportFormat.PDF, openOutputStream)
    }

    fun exportComicCbz(openOutputStream: (String) -> OutputStream?) {
        exportComic(AndroidExportFormat.CBZ, openOutputStream)
    }

    fun exportComicPdf(openOutputStream: (String) -> OutputStream?) {
        exportComic(AndroidExportFormat.PDF, openOutputStream)
    }

    fun refreshProgress() {
        scope.launch {
            val bookmark = localStore.getBookmark(nativeId)
            val progress = localStore.getReadingProgress(nativeId)
            mutableState.value = mutableState.value.copy(
                isBookmarked = bookmark != null,
                readingProgress = progress,
            )
        }
    }

    private fun exportChapter(
        chapterId: Long,
        format: AndroidExportFormat,
        openOutputStream: () -> OutputStream?,
    ) {
        scope.launch {
            export {
                archiveExporter.exportChapterToStream(nativeId, chapterId, format) { file ->
                    val output = requireNotNull(openOutputStream())
                    output.use { destination ->
                        file.inputStream().use { input -> input.copyTo(destination) }
                    }
                }
            }
        }
    }

    private fun exportComic(format: AndroidExportFormat, openOutputStream: (String) -> OutputStream?) {
        val chapterIds = downloadedChapters.value.map { it.chapterId }
        scope.launch {
            if (chapterIds.isEmpty()) {
                mutableExportResult.emit("No downloaded chapters to export.")
                return@launch
            }
            export {
                archiveExporter.exportComicToDirectory(nativeId, chapterIds, format) { fileName, file ->
                    val output = requireNotNull(openOutputStream(fileName))
                    output.use { destination ->
                        file.inputStream().use { input -> input.copyTo(destination) }
                    }
                }
            }
        }
    }

    private suspend fun export(operation: suspend () -> Unit) {
        try {
            operation()
            mutableExportResult.emit("Export completed.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableExportResult.emit("Export failed.")
        }
    }
}
