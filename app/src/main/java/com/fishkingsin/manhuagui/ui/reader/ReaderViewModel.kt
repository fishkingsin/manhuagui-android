package com.fishkingsin.manhuagui.ui.reader

import com.fishkingsin.manhuagui.core.AppError
import com.fishkingsin.manhuagui.core.LibraryService
import com.fishkingsin.manhuagui.core.toAppError
import com.fishkingsin.manhuagui.data.InMemoryLocalStore
import com.fishkingsin.manhuagui.data.LocalStore
import com.fishkingsin.manhuagui.data.ReadingProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uniffi.manhuagui_ffi.ImagePage

sealed interface ReaderEffect {
    data class OpenChapter(val chapterId: Long) : ReaderEffect
}

data class ReaderUiState(
    val nativeId: Long,
    val chapterId: Long,
    val pages: List<ImagePage> = emptyList(),
    val pageIndex: Int = 0,
    val lastTurnDirection: Int = 1,
    val prevChapterId: Long? = null,
    val nextChapterId: Long? = null,
    val chromeVisible: Boolean = false,
    val loading: Boolean = false,
    val error: AppError? = null,
)

class ReaderViewModel(
    private val nativeId: Long,
    private val chapterId: Long,
    private val service: LibraryService,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val localStore: LocalStore = InMemoryLocalStore(),
    private val chapterTitle: String = "",
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow(ReaderUiState(nativeId, chapterId))
    val state = mutableState.asStateFlow()

    private val mutableEffects = MutableSharedFlow<ReaderEffect>(extraBufferCapacity = 1)
    val effects = mutableEffects.asSharedFlow()

    fun load() {
        if (mutableState.value.loading) return

        mutableState.value = mutableState.value.copy(loading = true, error = null)
        scope.launch {
            service.getChapterPages(nativeId, chapterId).fold(
                onSuccess = { result ->
                    if (result.pages.isEmpty()) {
                        mutableState.value = mutableState.value.copy(
                            loading = false,
                            pages = emptyList(),
                            error = AppError.ParseFailed("Chapter has no pages"),
                        )
                    } else {
                        val savedProgress = localStore.getReadingProgress(nativeId)
                        val initialPageIndex = if (savedProgress?.chapterId == chapterId) {
                            savedProgress.pageIndex.coerceIn(0, result.pages.lastIndex)
                        } else {
                            0
                        }
                        localStore.saveReadingProgress(progressAt(initialPageIndex))
                        val bookmark = localStore.getBookmark(nativeId)
                        if (bookmark != null && (bookmark.hasUnreadUpdate || bookmark.newChapterCount > 0)) {
                            localStore.upsertBookmark(bookmark.copy(hasUnreadUpdate = false, newChapterCount = 0))
                        }
                        mutableState.value = mutableState.value.copy(
                            loading = false,
                            pages = result.pages,
                            pageIndex = initialPageIndex,
                            prevChapterId = result.prevChapterId,
                            nextChapterId = result.nextChapterId,
                            error = null,
                        )
                    }
                },
                onFailure = { throwable ->
                    mutableState.value = mutableState.value.copy(
                        loading = false,
                        pages = emptyList(),
                        error = throwable.toAppError(),
                    )
                },
            )
        }
    }

    fun retry() = load()

    fun onPageChanged(index: Int) {
        val current = mutableState.value
        if (current.pages.isNotEmpty()) {
            val newIndex = index.coerceIn(0, current.pages.lastIndex)
            if (newIndex != current.pageIndex) {
                val direction = if (newIndex < current.pageIndex) -1 else 1
                mutableState.value = current.copy(pageIndex = newIndex, lastTurnDirection = direction)
                saveProgress(newIndex)
            }
        }
    }

    private fun saveProgress(pageIndex: Int) {
        scope.launch { localStore.saveReadingProgress(progressAt(pageIndex)) }
    }

    private fun progressAt(pageIndex: Int) = ReadingProgress(
        comicId = nativeId,
        chapterId = chapterId,
        chapterTitle = chapterTitle.ifBlank { "Chapter $chapterId" },
        pageIndex = pageIndex,
        updatedAtEpochMs = clockMs(),
    )

    fun toggleChrome() {
        mutableState.value = mutableState.value.copy(chromeVisible = !mutableState.value.chromeVisible)
    }

    fun turnPage(delta: Int) {
        val current = mutableState.value
        if (current.pages.isEmpty() || delta == 0) return

        val target = current.pageIndex + delta
        when {
            target in 0..current.pages.lastIndex -> {
                val direction = if (target < current.pageIndex || delta < 0) -1 else 1
                mutableState.value = current.copy(pageIndex = target, lastTurnDirection = direction)
                saveProgress(target)
            }
            target > current.pages.lastIndex -> current.nextChapterId?.let { chapter ->
                mutableEffects.tryEmit(ReaderEffect.OpenChapter(chapter))
            }
            target < 0 -> current.prevChapterId?.let { chapter ->
                mutableEffects.tryEmit(ReaderEffect.OpenChapter(chapter))
            }
        }
    }
}
