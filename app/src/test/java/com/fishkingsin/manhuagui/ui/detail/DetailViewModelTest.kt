package com.fishkingsin.manhuagui.ui.detail

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
import uniffi.manhuagui_ffi.AndroidLibraryInterface
import uniffi.manhuagui_ffi.AndroidUpdateWindow
import uniffi.manhuagui_ffi.BrowsePage
import uniffi.manhuagui_ffi.ChapterItem
import uniffi.manhuagui_ffi.ChapterPagesResult
import uniffi.manhuagui_ffi.ComicDetails
import uniffi.manhuagui_ffi.ComicListItem
import uniffi.manhuagui_ffi.SearchPage

@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModelTest {
    @Test
    fun loadShowsLoadingThenComicWithDeterministicallySortedChapters() = runTest {
        val chapters = listOf(
            chapter(1, "Z Group", 5, 1.0),
            chapter(2, "Z Group", 5, 2.0),
            chapter(3, "A Group", 5, 1.0),
            chapter(4, "A Group", 5, 3.0),
            chapter(5, "A Group", 8, 1.0),
        )
        val comic = comic(41, chapters)
        val result = CompletableDeferred<ComicDetails>()
        val library = FakeLibrary().apply {
            onGetComic = { result.await() }
        }
        val viewModel = viewModel(nativeId = 41, library = library, scope = this)

        viewModel.load()
        runCurrent()
        assertTrue(viewModel.state.value.loading)
        assertEquals(listOf(41L), library.comicIds)

        result.complete(comic)
        runCurrent()

        assertEquals(comic, viewModel.state.value.comic)
        assertEquals(
            listOf(chapters[4], chapters[3], chapters[2], chapters[1], chapters[0]),
            viewModel.state.value.chapters,
        )
        assertFalse(viewModel.state.value.loading)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun loadWhileLoadingDoesNotStartAnotherRequest() = runTest {
        val result = CompletableDeferred<ComicDetails>()
        val library = FakeLibrary().apply {
            onGetComic = { result.await() }
        }
        val viewModel = viewModel(nativeId = 18, library = library, scope = this)

        viewModel.load()
        runCurrent()
        viewModel.load()
        runCurrent()

        assertEquals(listOf(18L), library.comicIds)
        result.complete(comic(18, emptyList()))
        runCurrent()
        assertFalse(viewModel.state.value.loading)
    }

    @Test
    fun loadFailureClearsComicAndChaptersAndMapsError() = runTest {
        val initialComic = comic(25, listOf(chapter(8, "Group", 2, 1.0)))
        var fail = false
        val library = FakeLibrary().apply {
            onGetComic = {
                if (fail) throw IllegalStateException("detail diagnostic")
                initialComic
            }
        }
        val viewModel = viewModel(nativeId = 25, library = library, scope = this)

        viewModel.load()
        runCurrent()
        assertEquals(initialComic, viewModel.state.value.comic)
        assertEquals(initialComic.chapters, viewModel.state.value.chapters)

        fail = true
        viewModel.load()
        runCurrent()

        assertEquals(AppError.Unknown("detail diagnostic"), viewModel.state.value.error)
        assertNull(viewModel.state.value.comic)
        assertTrue(viewModel.state.value.chapters.isEmpty())
        assertFalse(viewModel.state.value.loading)
    }

    private fun viewModel(nativeId: Long, library: FakeLibrary, scope: TestScope) = DetailViewModel(
        nativeId = nativeId,
        service = LibraryService(library, StandardTestDispatcher(scope.testScheduler)),
        scope = scope.backgroundScope,
    )

    private fun chapter(id: Long, groupName: String, groupSize: Long, order: Double) = ChapterItem(
        chapterId = id,
        chapterTitle = "Chapter $id",
        chapterSize = 20,
        groupName = groupName,
        groupSize = groupSize,
        order = order,
    )

    private fun comic(id: Long, chapters: List<ChapterItem>) = ComicDetails(
        comicId = id,
        title = "Comic $id",
        subtitle = "Subtitle $id",
        coverUrl = "https://example.test/$id.jpg",
        statusText = "Ongoing",
        updateText = "Updated today",
        year = 2020,
        region = "Japan",
        genres = listOf("Action"),
        authors = listOf("Author"),
        aliases = listOf("Alias"),
        intro = "Introduction",
        chapters = chapters,
    )

    private class FakeLibrary : AndroidLibraryInterface {
        val comicIds = mutableListOf<Long>()
        var onGetComic: suspend () -> ComicDetails = { error("Not used by these tests") }

        override suspend fun browseWithin(
            window: AndroidUpdateWindow,
            startPage: Int,
            maxPages: Int,
        ): List<ComicListItem> = error("Not used by these tests")

        override suspend fun browseLatestPage(page: Int): BrowsePage = error("Not used by these tests")

        override suspend fun search(keyword: String, page: Int): SearchPage = error("Not used by these tests")

        override suspend fun getComic(nativeId: Long): ComicDetails {
            comicIds += nativeId
            return onGetComic()
        }

        override suspend fun getChapterPages(nativeId: Long, chapterId: Long): ChapterPagesResult =
            error("Not used by these tests")
    }
}
