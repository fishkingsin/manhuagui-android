package com.fishkingsin.manhuagui.ui.detail

import com.fishkingsin.manhuagui.core.AppError
import com.fishkingsin.manhuagui.core.LibraryService
import com.fishkingsin.manhuagui.core.toAppError
import com.fishkingsin.manhuagui.data.BookmarkRecord
import com.fishkingsin.manhuagui.data.InMemoryLocalStore
import com.fishkingsin.manhuagui.data.LocalStore
import com.fishkingsin.manhuagui.data.ReadingProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val localStore: LocalStore = InMemoryLocalStore(),
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val mutableState = MutableStateFlow(DetailUiState())
    private val toggleMutex = Mutex()
    private var toggleGeneration = 0L
    val state = mutableState.asStateFlow()

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
}
