package com.fishkingsin.manhuagui.ui.detail

import com.fishkingsin.manhuagui.core.AppError
import com.fishkingsin.manhuagui.core.LibraryService
import com.fishkingsin.manhuagui.core.toAppError
import com.fishkingsin.manhuagui.data.BookmarkRecord
import com.fishkingsin.manhuagui.data.InMemoryLocalStore
import com.fishkingsin.manhuagui.data.LocalStore
import com.fishkingsin.manhuagui.data.ReadingProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val localStore: LocalStore = InMemoryLocalStore(),
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow(DetailUiState())
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
                    val existingBookmark = localStore.getBookmark(nativeId)
                    val progress = localStore.getReadingProgress(nativeId)
                    if (existingBookmark != null) {
                        localStore.upsertBookmark(
                            existingBookmark.copy(
                                title = comic.title,
                                coverUrl = comic.coverUrl,
                                lastKnownChapterIds = comic.chapters.map { it.chapterId }.toSet(),
                                latestChapterTitle = sortedChapters.firstOrNull()?.chapterTitle.orEmpty(),
                                hasUnreadUpdate = false,
                                newChapterCount = 0,
                            ),
                        )
                    }
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
        scope.launch {
            if (current.isBookmarked) {
                localStore.deleteBookmark(nativeId)
                mutableState.value = mutableState.value.copy(isBookmarked = false)
            } else {
                val record = BookmarkRecord(
                    comicId = nativeId,
                    title = comic.title,
                    coverUrl = comic.coverUrl,
                    lastKnownChapterIds = comic.chapters.map { it.chapterId }.toSet(),
                    latestChapterTitle = current.chapters.firstOrNull()?.chapterTitle.orEmpty(),
                    newChapterCount = 0,
                    hasUnreadUpdate = false,
                    lastCheckedEpochMs = clockMs(),
                    updatedAtEpochMs = clockMs(),
                )
                localStore.upsertBookmark(record)
                mutableState.value = mutableState.value.copy(isBookmarked = true)
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
