package com.fishkingsin.manhuagui.ui.search

import com.fishkingsin.manhuagui.core.AppError
import com.fishkingsin.manhuagui.core.LibraryService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.manhuagui_ffi.AndroidDownloadObserver
import uniffi.manhuagui_ffi.AndroidExportFormat
import uniffi.manhuagui_ffi.AndroidLibraryInterface
import uniffi.manhuagui_ffi.AndroidUpdateWindow
import uniffi.manhuagui_ffi.BrowsePage
import uniffi.manhuagui_ffi.ChapterPagesResult
import uniffi.manhuagui_ffi.ComicDetails
import uniffi.manhuagui_ffi.ComicListItem
import uniffi.manhuagui_ffi.DownloadedChapter
import uniffi.manhuagui_ffi.ExportedChapterFile
import uniffi.manhuagui_ffi.LocalChapterNavigation
import uniffi.manhuagui_ffi.SearchPage

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    @Test
    fun submitTrimsQueryAndLoadsFirstPage() = runTest {
        val item = comic(12)
        val page = SearchPage(items = listOf(item), current = 1, total = 4)
        val library = FakeLibrary().apply {
            onSearch = { _, _ -> page }
        }
        val viewModel = viewModel(library, this)
        viewModel.setQuery("  moonlight  ")

        viewModel.submit()
        runCurrent()

        assertEquals(listOf(SearchQuery("moonlight", 1)), library.searchQueries)
        assertEquals(page, viewModel.state.value.results)
        assertEquals("  moonlight  ", viewModel.state.value.query)
        assertFalse(viewModel.state.value.loading)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun submitWithBlankQueryDoesNotCallService() = runTest {
        val library = FakeLibrary()
        val viewModel = viewModel(library, this)
        viewModel.setQuery("  \t ")

        viewModel.submit()
        runCurrent()

        assertTrue(library.searchQueries.isEmpty())
        assertFalse(viewModel.state.value.loading)
        assertNull(viewModel.state.value.results)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun submitWhileLoadingDoesNotStartAnotherRequest() = runTest {
        val result = CompletableDeferred<SearchPage>()
        val library = FakeLibrary().apply {
            onSearch = { _, _ -> result.await() }
        }
        val viewModel = viewModel(library, this)
        viewModel.setQuery("first")

        viewModel.submit()
        runCurrent()
        assertTrue(viewModel.state.value.loading)

        viewModel.setQuery("second")
        viewModel.submit()
        runCurrent()
        assertEquals("first", viewModel.state.value.query)
        assertEquals(listOf(SearchQuery("first", 1)), library.searchQueries)

        val page = SearchPage(items = listOf(comic(1)), current = 1, total = 1)
        result.complete(page)
        runCurrent()

        assertEquals(page, viewModel.state.value.results)
        assertEquals("first", viewModel.state.value.query)
        assertFalse(viewModel.state.value.loading)
    }

    @Test
    fun submitFailureClearsResultsAndMapsError() = runTest {
        val page = SearchPage(items = listOf(comic(9)), current = 1, total = 1)
        var fail = false
        val library = FakeLibrary().apply {
            onSearch = { _, _ ->
                if (fail) throw IllegalStateException("search diagnostic")
                page
            }
        }
        val viewModel = viewModel(library, this)
        viewModel.setQuery("first")
        viewModel.submit()
        runCurrent()
        assertEquals(page, viewModel.state.value.results)

        fail = true
        viewModel.setQuery("second")
        viewModel.submit()
        runCurrent()

        assertEquals(AppError.Unknown("search diagnostic"), viewModel.state.value.error)
        assertNull(viewModel.state.value.results)
        assertFalse(viewModel.state.value.loading)
    }

    private fun viewModel(library: FakeLibrary, scope: TestScope) = SearchViewModel(
        service = LibraryService(library, StandardTestDispatcher(scope.testScheduler)),
        scope = scope.backgroundScope,
    )

    private fun comic(id: Long) = ComicListItem(
        comicId = id,
        title = "Comic $id",
        coverUrl = "https://example.test/$id.jpg",
        statusText = "Ongoing",
        updateText = "Updated today",
        rating = 9.0f,
    )

    private data class SearchQuery(val keyword: String, val page: Int)

    private class FakeLibrary : AndroidLibraryInterface {
        val searchQueries = mutableListOf<SearchQuery>()
        var onSearch: suspend (String, Int) -> SearchPage = { _, page ->
            SearchPage(emptyList(), current = page.toLong(), total = page.toLong())
        }

        override suspend fun browseWithin(
            window: AndroidUpdateWindow,
            startPage: Int,
            maxPages: Int,
        ): List<ComicListItem> = error("Not used by these tests")

        override suspend fun browseLatestPage(page: Int): BrowsePage = error("Not used by these tests")

        override suspend fun search(keyword: String, page: Int): SearchPage {
            searchQueries += SearchQuery(keyword, page)
            return onSearch(keyword, page)
        }

        override suspend fun getComic(nativeId: Long): ComicDetails = error("Not used by these tests")

        override suspend fun getChapterPages(nativeId: Long, chapterId: Long): ChapterPagesResult =
            error("Not used by these tests")

        override fun cancelDownload(chapterId: Long) = Unit

        override suspend fun deleteDownloadedChapter(
            nativeId: Long,
            chapterId: Long,
            downloadRoot: String,
        ) = error("Not used by these tests")

        override suspend fun downloadChapter(
            nativeId: Long,
            chapterId: Long,
            downloadRoot: String,
            observer: AndroidDownloadObserver,
        ): DownloadedChapter = error("Not used by these tests")

        override suspend fun exportChapters(
            nativeId: Long,
            chapterIds: List<Long>,
            downloadRoot: String,
            exportRoot: String,
            format: AndroidExportFormat,
        ): List<ExportedChapterFile> = error("Not used by these tests")

        override suspend fun readLocalChapterNavigation(
            nativeId: Long,
            chapterId: Long,
            downloadRoot: String,
        ): LocalChapterNavigation = error("Not used by these tests")

        override suspend fun scanDownloadedChapters(downloadRoot: String): List<DownloadedChapter> =
            error("Not used by these tests")
    }
}
