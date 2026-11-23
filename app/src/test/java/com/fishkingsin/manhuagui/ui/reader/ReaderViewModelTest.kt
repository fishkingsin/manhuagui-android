package com.fishkingsin.manhuagui.ui.reader

import com.fishkingsin.manhuagui.core.AppError
import com.fishkingsin.manhuagui.core.LibraryService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.manhuagui_ffi.AndroidLibraryInterface
import uniffi.manhuagui_ffi.AndroidUpdateWindow
import uniffi.manhuagui_ffi.BrowsePage
import uniffi.manhuagui_ffi.ChapterPagesResult
import uniffi.manhuagui_ffi.ComicDetails
import uniffi.manhuagui_ffi.ComicListItem
import uniffi.manhuagui_ffi.ImagePage
import uniffi.manhuagui_ffi.SearchPage

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {
    @Test
    fun loadPublishesPagesAndChapterLinks() = runTest {
        val pages = listOf(imagePage(1), imagePage(2))
        val library = FakeLibrary().apply {
            onGetChapterPages = { nativeId, chapterId ->
                assertEquals(42L, nativeId)
                assertEquals(7L, chapterId)
                ChapterPagesResult(pages, prevChapterId = 6, nextChapterId = 8)
            }
        }
        val viewModel = viewModel(library, this)

        viewModel.load()
        assertTrue(viewModel.state.value.loading)
        runCurrent()

        assertEquals(pages, viewModel.state.value.pages)
        assertEquals(0, viewModel.state.value.pageIndex)
        assertEquals(6L, viewModel.state.value.prevChapterId)
        assertEquals(8L, viewModel.state.value.nextChapterId)
        assertFalse(viewModel.state.value.loading)
        assertNull(viewModel.state.value.error)
        assertEquals(listOf(42L to 7L), library.chapterQueries)
    }

    @Test
    fun emptyChapterBecomesParseErrorInsteadOfBlankPages() = runTest {
        val library = FakeLibrary().apply {
            onGetChapterPages = { _, _ -> ChapterPagesResult(emptyList(), null, null) }
        }
        val viewModel = viewModel(library, this)

        viewModel.load()
        runCurrent()

        assertTrue(viewModel.state.value.pages.isEmpty())
        assertEquals(AppError.ParseFailed("Chapter has no pages"), viewModel.state.value.error)
        assertFalse(viewModel.state.value.loading)
    }

    @Test
    fun failureSurfacesErrorAndRetryClearsItBeforeLoadingAgain() = runTest {
        val page = imagePage(1)
        var shouldFail = true
        val library = FakeLibrary().apply {
            onGetChapterPages = { _, _ ->
                if (shouldFail) throw IllegalStateException("temporary failure")
                ChapterPagesResult(listOf(page), null, null)
            }
        }
        val viewModel = viewModel(library, this)

        viewModel.load()
        runCurrent()
        assertEquals(AppError.Unknown("temporary failure"), viewModel.state.value.error)
        assertFalse(viewModel.state.value.loading)

        shouldFail = false
        viewModel.retry()

        assertTrue(viewModel.state.value.loading)
        assertNull(viewModel.state.value.error)
        runCurrent()

        assertEquals(listOf(page), viewModel.state.value.pages)
        assertNull(viewModel.state.value.error)
        assertFalse(viewModel.state.value.loading)
        assertEquals(2, library.chapterQueries.size)
    }

    @Test
    fun repeatedLoadWhileLoadingDoesNotStartAnotherRequest() = runTest {
        val pending = CompletableDeferred<ChapterPagesResult>()
        val library = FakeLibrary().apply {
            onGetChapterPages = { _, _ -> pending.await() }
        }
        val viewModel = viewModel(library, this)

        viewModel.load()
        runCurrent()
        viewModel.load()
        runCurrent()

        assertEquals(1, library.chapterQueries.size)
        assertTrue(viewModel.state.value.loading)

        pending.complete(ChapterPagesResult(listOf(imagePage(1)), null, null))
        runCurrent()

        assertFalse(viewModel.state.value.loading)
        assertEquals(1, viewModel.state.value.pages.size)
    }

    @Test
    fun turningPastChapterEdgesEmitsOpenChapterEffects() = runTest {
        val library = FakeLibrary().apply {
            onGetChapterPages = { _, _ ->
                ChapterPagesResult(listOf(imagePage(1), imagePage(2)), prevChapterId = 9, nextChapterId = 11)
            }
        }
        val viewModel = viewModel(library, this)
        val effects = mutableListOf<ReaderEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.effects.collect(effects::add)
        }
        viewModel.load()
        runCurrent()

        viewModel.turnPage(1)
        assertEquals(1, viewModel.state.value.pageIndex)
        viewModel.turnPage(1)
        viewModel.turnPage(-1)
        assertEquals(0, viewModel.state.value.pageIndex)
        viewModel.turnPage(-1)

        assertEquals(
            listOf(ReaderEffect.OpenChapter(11), ReaderEffect.OpenChapter(9)),
            effects,
        )
    }

    private fun viewModel(library: FakeLibrary, scope: TestScope) = ReaderViewModel(
        nativeId = 42,
        chapterId = 7,
        service = LibraryService(library, StandardTestDispatcher(scope.testScheduler)),
        scope = scope.backgroundScope,
    )

    private fun imagePage(id: Int) = ImagePage(
        url = "https://example.test/$id.jpg",
        headers = mapOf("Referer" to "https://example.test/"),
    )

    private class FakeLibrary : AndroidLibraryInterface {
        val chapterQueries = mutableListOf<Pair<Long, Long>>()
        var onGetChapterPages: suspend (Long, Long) -> ChapterPagesResult = { _, _ ->
            ChapterPagesResult(emptyList(), null, null)
        }

        override suspend fun browseWithin(
            window: AndroidUpdateWindow,
            startPage: Int,
            maxPages: Int,
        ): List<ComicListItem> = error("Not used by these tests")

        override suspend fun browseLatestPage(page: Int): BrowsePage = error("Not used by these tests")

        override suspend fun search(keyword: String, page: Int): SearchPage = error("Not used by these tests")

        override suspend fun getComic(nativeId: Long): ComicDetails = error("Not used by these tests")

        override suspend fun getChapterPages(nativeId: Long, chapterId: Long): ChapterPagesResult {
            chapterQueries += nativeId to chapterId
            return onGetChapterPages(nativeId, chapterId)
        }
    }
}
