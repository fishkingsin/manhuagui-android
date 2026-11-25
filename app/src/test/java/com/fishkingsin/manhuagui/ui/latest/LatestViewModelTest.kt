package com.fishkingsin.manhuagui.ui.latest

import com.fishkingsin.manhuagui.core.AppError
import com.fishkingsin.manhuagui.core.LibraryService
import com.fishkingsin.manhuagui.settings.AppSettings
import com.fishkingsin.manhuagui.settings.InMemorySettingsStore
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
class LatestViewModelTest {
    @Test
    fun selectingDays30LoadsTenPagesAndEndsFilteredBrowsing() = runTest {
        val comic = comic(30)
        val library = FakeLibrary().apply {
            onBrowseWithin = { _, _, _ -> listOf(comic) }
        }
        val viewModel = viewModel(library, this)

        viewModel.setWindow(AndroidUpdateWindow.DAYS30)
        runCurrent()

        assertEquals(AndroidUpdateWindow.DAYS30, viewModel.state.value.window)
        assertEquals(listOf(comic), viewModel.state.value.items)
        assertEquals(listOf(FilteredQuery(AndroidUpdateWindow.DAYS30, 1, 10)), library.filteredQueries)
        assertTrue(viewModel.state.value.endReached)
        assertFalse(viewModel.state.value.loading)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun refreshWhileLoadingDoesNotStartAnotherRequest() = runTest {
        val result = CompletableDeferred<List<ComicListItem>>()
        val library = FakeLibrary().apply {
            onBrowseWithin = { _, _, _ -> result.await() }
        }
        val viewModel = viewModel(library, this)

        viewModel.refresh()
        runCurrent()
        assertTrue(viewModel.state.value.loading)

        viewModel.refresh()
        runCurrent()
        assertEquals(1, library.filteredQueries.size)

        val comic = comic(1)
        result.complete(listOf(comic))
        runCurrent()

        assertEquals(listOf(comic), viewModel.state.value.items)
        assertFalse(viewModel.state.value.loading)
    }

    @Test
    fun allModeAppendsNextPageAndUsesReturnedPageMetadata() = runTest {
        val first = comic(1)
        val second = comic(2)
        val library = FakeLibrary().apply {
            onBrowseLatestPage = { page ->
                when (page) {
                    1 -> BrowsePage(listOf(first), page = 1, totalPages = 2)
                    2 -> BrowsePage(listOf(second), page = 2, totalPages = 2)
                    else -> error("Unexpected page $page")
                }
            }
        }
        val viewModel = viewModel(library, this)

        viewModel.setWindow(AndroidUpdateWindow.ALL)
        runCurrent()
        assertEquals(listOf(first), viewModel.state.value.items)
        assertEquals(2, viewModel.state.value.nextPage)
        assertEquals(2, viewModel.state.value.totalPages)
        assertFalse(viewModel.state.value.endReached)

        viewModel.loadMore()
        runCurrent()

        assertEquals(listOf(first, second), viewModel.state.value.items)
        assertEquals(listOf(1, 2), library.latestPages)
        assertEquals(3, viewModel.state.value.nextPage)
        assertEquals(2, viewModel.state.value.totalPages)
        assertTrue(viewModel.state.value.endReached)
        assertFalse(viewModel.state.value.loading)
    }

    @Test
    fun changingWindowCancelsOldPageAndResetsPaging() = runTest {
        val firstAll = comic(1)
        val staleAll = comic(2)
        val days30 = comic(30)
        val pendingNextPage = CompletableDeferred<BrowsePage>()
        val library = FakeLibrary().apply {
            onBrowseLatestPage = { page ->
                if (page == 1) BrowsePage(listOf(firstAll), page = 1, totalPages = 3)
                else pendingNextPage.await()
            }
            onBrowseWithin = { _, _, _ -> listOf(days30) }
        }
        val viewModel = viewModel(library, this)

        viewModel.setWindow(AndroidUpdateWindow.ALL)
        runCurrent()
        viewModel.loadMore()
        runCurrent()
        assertTrue(viewModel.state.value.loading)

        viewModel.setWindow(AndroidUpdateWindow.DAYS30)
        runCurrent()
        pendingNextPage.complete(BrowsePage(listOf(staleAll), page = 2, totalPages = 3))
        runCurrent()

        assertEquals(AndroidUpdateWindow.DAYS30, viewModel.state.value.window)
        assertEquals(listOf(days30), viewModel.state.value.items)
        assertEquals(1, viewModel.state.value.nextPage)
        assertEquals(0, viewModel.state.value.totalPages)
        assertTrue(viewModel.state.value.endReached)
    }

    @Test
    fun loadMoreFailureClearsItemsAndResetsPaging() = runTest {
        val first = comic(1)
        val library = FakeLibrary().apply {
            onBrowseLatestPage = { page ->
                if (page == 1) BrowsePage(listOf(first), page = 1, totalPages = 3)
                else throw IllegalStateException("page diagnostic")
            }
        }
        val viewModel = viewModel(library, this)

        viewModel.setWindow(AndroidUpdateWindow.ALL)
        runCurrent()
        assertEquals(listOf(first), viewModel.state.value.items)
        assertEquals(2, viewModel.state.value.nextPage)
        assertEquals(3, viewModel.state.value.totalPages)

        viewModel.loadMore()
        runCurrent()

        assertEquals(AppError.Unknown("page diagnostic"), viewModel.state.value.error)
        assertEquals("Something went wrong.", viewModel.state.value.error?.userMessage)
        assertTrue(viewModel.state.value.items.isEmpty())
        assertEquals(1, viewModel.state.value.nextPage)
        assertEquals(0, viewModel.state.value.totalPages)
        assertFalse(viewModel.state.value.endReached)
        assertFalse(viewModel.state.value.loading)
        assertEquals(listOf(1, 2), library.latestPages)
    }

    @Test
    fun refreshFailureClearsItemsAndSurfacesMappedError() = runTest {
        val comic = comic(1)
        var fail = false
        val library = FakeLibrary().apply {
            onBrowseWithin = { _, _, _ ->
                if (fail) throw IllegalStateException("private diagnostic")
                listOf(comic)
            }
        }
        val viewModel = viewModel(library, this)
        viewModel.setWindow(AndroidUpdateWindow.DAYS15)
        runCurrent()
        assertEquals(listOf(comic), viewModel.state.value.items)

        fail = true
        viewModel.refresh()
        runCurrent()

        assertEquals(AppError.Unknown("private diagnostic"), viewModel.state.value.error)
        assertEquals("Something went wrong.", viewModel.state.value.error?.userMessage)
        assertTrue(viewModel.state.value.items.isEmpty())
        assertFalse(viewModel.state.value.loading)
    }

    @Test
    fun firstRefreshLoadsTheSavedDefaultUpdateWindow() = runTest {
        val library = FakeLibrary()
        val settingsStore = InMemorySettingsStore(
            AppSettings(defaultUpdateWindow = AndroidUpdateWindow.ALL),
        )
        val viewModel = viewModel(library, this, settingsStore)

        viewModel.refresh()
        runCurrent()

        assertEquals(AndroidUpdateWindow.ALL, viewModel.state.value.window)
        assertEquals(listOf(1), library.latestPages)
    }

    private fun viewModel(
        library: FakeLibrary,
        scope: TestScope,
        settingsStore: InMemorySettingsStore = InMemorySettingsStore(),
    ) = LatestViewModel(
        service = LibraryService(library, StandardTestDispatcher(scope.testScheduler)),
        settingsStore = settingsStore,
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

    private data class FilteredQuery(
        val window: AndroidUpdateWindow,
        val startPage: Int,
        val maxPages: Int,
    )

    private class FakeLibrary : AndroidLibraryInterface {
        val filteredQueries = mutableListOf<FilteredQuery>()
        val latestPages = mutableListOf<Int>()
        var onBrowseWithin: suspend (AndroidUpdateWindow, Int, Int) -> List<ComicListItem> =
            { _, _, _ -> emptyList() }
        var onBrowseLatestPage: suspend (Int) -> BrowsePage = { page ->
            BrowsePage(emptyList(), page = page.toLong(), totalPages = page.toLong())
        }

        override suspend fun browseWithin(
            window: AndroidUpdateWindow,
            startPage: Int,
            maxPages: Int,
        ): List<ComicListItem> {
            filteredQueries += FilteredQuery(window, startPage, maxPages)
            return onBrowseWithin(window, startPage, maxPages)
        }

        override suspend fun browseLatestPage(page: Int): BrowsePage {
            latestPages += page
            return onBrowseLatestPage(page)
        }

        override suspend fun search(keyword: String, page: Int): SearchPage =
            error("Not used by these tests")

        override suspend fun getComic(nativeId: Long): ComicDetails =
            error("Not used by these tests")

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
