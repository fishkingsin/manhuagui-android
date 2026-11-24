package com.fishkingsin.manhuagui.ui.reader

import com.fishkingsin.manhuagui.core.AppError
import com.fishkingsin.manhuagui.core.LibraryService
import com.fishkingsin.manhuagui.core.toAppError
import com.fishkingsin.manhuagui.data.InMemoryLocalStore
import com.fishkingsin.manhuagui.data.LocalStore
import com.fishkingsin.manhuagui.data.ReadingProgress
import com.fishkingsin.manhuagui.settings.InMemorySettingsStore
import com.fishkingsin.manhuagui.settings.ReadingDirection
import com.fishkingsin.manhuagui.settings.SettingsStore
import java.io.File
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
    val awaitingOfflineStreamConfirmation: Boolean = false,
    val isOfflineChapter: Boolean = false,
    val readingDirection: ReadingDirection = ReadingDirection.RTL,
    val prefetchCount: Int = 3,
)

class ReaderViewModel(
    private val nativeId: Long,
    private val chapterId: Long,
    private val service: LibraryService,
    private val localStore: LocalStore = InMemoryLocalStore(),
    private val settingsStore: SettingsStore = InMemorySettingsStore(),
    private val downloadRoot: String = "",
    private val chapterTitle: String = "",
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val mutableState = MutableStateFlow(ReaderUiState(nativeId, chapterId))
    private var resolvedChapterTitle = chapterTitle.ifBlank { "Chapter $chapterId" }
    val state = mutableState.asStateFlow()

    private val mutableEffects = MutableSharedFlow<ReaderEffect>(extraBufferCapacity = 1)
    val effects = mutableEffects.asSharedFlow()

    fun load() {
        if (mutableState.value.loading) return

        mutableState.value = mutableState.value.copy(
            loading = true,
            error = null,
            awaitingOfflineStreamConfirmation = false,
            isOfflineChapter = false,
        )
        scope.launch {
            val downloadedChapter = localStore.getDownloadedChapter(nativeId, chapterId)
            val settings = settingsStore.getSettings()
            mutableState.value = mutableState.value.copy(
                readingDirection = settings.readingDirection,
                prefetchCount = settings.prefetchCount,
            )
            val localPages = downloadedChapter?.let { chapter ->
                File(chapter.chapterDir).listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".jpg") }
                    ?.sortedBy { it.name }
                    ?.map { file -> ImagePage(url = "file://${file.absolutePath}", headers = emptyMap()) }
                    .orEmpty()
            }.orEmpty()

            if (localPages.isNotEmpty()) {
                val navigation = service.readLocalChapterNavigation(nativeId, chapterId, downloadRoot).getOrNull()
                publishPages(
                    pages = localPages,
                    prevChapterId = navigation?.prevChapterId,
                    nextChapterId = navigation?.nextChapterId,
                    isOfflineChapter = true,
                )
                return@launch
            }
            if (downloadedChapter == null && settings.offlineOnlyMode) {
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    awaitingOfflineStreamConfirmation = true,
                )
                return@launch
            }
            streamChapter()
        }
    }

    fun confirmStreaming() {
        val current = mutableState.value
        if (!current.awaitingOfflineStreamConfirmation || current.loading) return

        mutableState.value = current.copy(
            awaitingOfflineStreamConfirmation = false,
            loading = true,
            error = null,
        )
        scope.launch { streamChapter() }
    }

    private suspend fun streamChapter() {
        service.getChapterPages(nativeId, chapterId).fold(
            onSuccess = { result ->
                if (result.pages.isEmpty()) {
                    mutableState.value = mutableState.value.copy(
                        loading = false,
                        pages = emptyList(),
                        isOfflineChapter = false,
                        error = AppError.ParseFailed("Chapter has no pages"),
                    )
                } else {
                    publishPages(
                        pages = result.pages,
                        prevChapterId = result.prevChapterId,
                        nextChapterId = result.nextChapterId,
                        isOfflineChapter = false,
                    )
                }
            },
            onFailure = { throwable ->
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    pages = emptyList(),
                    isOfflineChapter = false,
                    error = throwable.toAppError(),
                )
            },
        )
    }

    private suspend fun publishPages(
        pages: List<ImagePage>,
        prevChapterId: Long?,
        nextChapterId: Long?,
        isOfflineChapter: Boolean,
    ) {
        val savedProgress = localStore.getReadingProgress(nativeId)
        resolvedChapterTitle = chapterTitle.ifBlank {
            savedProgress
                ?.takeIf { it.chapterId == chapterId && it.chapterTitle.isNotBlank() }
                ?.chapterTitle ?: "Chapter $chapterId"
        }
        val initialPageIndex = if (savedProgress?.chapterId == chapterId) {
            savedProgress.pageIndex.coerceIn(0, pages.lastIndex)
        } else {
            0
        }
        localStore.saveReadingProgress(progressAt(initialPageIndex))
        localStore.updateBookmarkIfPresent(nativeId) { bookmark ->
            if (bookmark.hasUnreadUpdate || bookmark.newChapterCount > 0) {
                bookmark.copy(hasUnreadUpdate = false, newChapterCount = 0)
            } else {
                bookmark
            }
        }
        mutableState.value = mutableState.value.copy(
            loading = false,
            pages = pages,
            pageIndex = initialPageIndex,
            prevChapterId = prevChapterId,
            nextChapterId = nextChapterId,
            awaitingOfflineStreamConfirmation = false,
            isOfflineChapter = isOfflineChapter,
            error = null,
        )
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
        chapterTitle = resolvedChapterTitle,
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
