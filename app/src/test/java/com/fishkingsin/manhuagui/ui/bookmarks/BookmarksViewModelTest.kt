package com.fishkingsin.manhuagui.ui.bookmarks

import com.fishkingsin.manhuagui.bookmarks.BookmarkUpdateChecker
import com.fishkingsin.manhuagui.core.LibraryService
import com.fishkingsin.manhuagui.data.BookmarkRecord
import com.fishkingsin.manhuagui.data.DEFAULT_SOURCE_ID
import com.fishkingsin.manhuagui.data.InMemoryLocalStore
import com.fishkingsin.manhuagui.data.LocalStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.ContinuationInterceptor
import uniffi.manhuagui_ffi.AndroidLibraryInterface
import uniffi.manhuagui_ffi.AndroidUpdateWindow
import uniffi.manhuagui_ffi.BrowsePage
import uniffi.manhuagui_ffi.ChapterItem
import uniffi.manhuagui_ffi.ChapterPagesResult
import uniffi.manhuagui_ffi.ComicDetails
import uniffi.manhuagui_ffi.ComicListItem
import uniffi.manhuagui_ffi.SearchPage

@OptIn(ExperimentalCoroutinesApi::class)
class BookmarksViewModelTest {
    @Test
    fun stateObservesBookmarkChanges() = runTest {
        val store = InMemoryLocalStore()
        val viewModelScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val viewModel = viewModel(store, FakeLibrary(), viewModelScope)
            runCurrent()
            assertEquals(emptyList<BookmarkRecord>(), viewModel.state.value.items)

            val item = bookmark(7)
            store.upsertBookmark(item)
            runCurrent()

            assertEquals(listOf(item), viewModel.state.value.items)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun checkUpdatesNowPublishesUpdatedCountAndUnreadBadge() = runTest {
        val store = InMemoryLocalStore()
        store.upsertBookmark(bookmark(7, lastKnownChapterIds = setOf(1L)))
        val library = FakeLibrary().apply {
            onGetComic = { comic(7, listOf(1L, 2L)) }
        }
        val viewModelScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val viewModel = viewModel(store, library, viewModelScope)

            viewModel.checkUpdatesNow()
            runCurrent()

            assertFalse(viewModel.state.value.checking)
            assertEquals(1, viewModel.state.value.lastUpdatedCount)
            assertTrue(viewModel.state.value.items.single().hasUnreadUpdate)
            assertEquals(1, viewModel.state.value.items.single().newChapterCount)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun checkUpdatesNowIgnoresDuplicateWhileCheckIsRunning() = runTest {
        val store = InMemoryLocalStore()
        store.upsertBookmark(bookmark(7, lastKnownChapterIds = setOf(1L)))
        val response = CompletableDeferred<ComicDetails>()
        val library = FakeLibrary().apply {
            onGetComic = { response.await() }
        }
        val viewModelScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val viewModel = viewModel(store, library, viewModelScope)

            viewModel.checkUpdatesNow()
            runCurrent()
            assertTrue(viewModel.state.value.checking)

            viewModel.checkUpdatesNow()
            assertEquals(1, library.comicRequestCount)

            response.complete(comic(7, listOf(1L, 2L)))
            runCurrent()

            assertEquals(1, library.comicRequestCount)
            assertEquals(1, viewModel.state.value.lastUpdatedCount)
            assertFalse(viewModel.state.value.checking)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun removeBookmarkDeletesItemFromObservedState() = runTest {
        val store = InMemoryLocalStore()
        store.upsertBookmark(bookmark(7))
        val viewModelScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val viewModel = viewModel(store, FakeLibrary(), viewModelScope)
            runCurrent()

            viewModel.removeBookmark(7)
            runCurrent()

            assertTrue(viewModel.state.value.items.isEmpty())
            assertNull(store.getBookmark(7))
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun failedCheckClearsCheckingAndPreservesLastUpdatedCount() = runTest {
        val store = FailingGetBookmarksStore(InMemoryLocalStore())
        store.upsertBookmark(bookmark(7, lastKnownChapterIds = setOf(1L)))
        val library = FakeLibrary().apply {
            onGetComic = { comic(7, listOf(1L, 2L)) }
        }
        val viewModelScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val viewModel = viewModel(store, library, viewModelScope)
            viewModel.checkUpdatesNow()
            runCurrent()
            assertEquals(1, viewModel.state.value.lastUpdatedCount)

            store.failGetBookmarks = true
            viewModel.checkUpdatesNow()
            runCurrent()

            assertFalse(viewModel.state.value.checking)
            assertEquals(1, viewModel.state.value.lastUpdatedCount)
        } finally {
            viewModelScope.cancel()
        }
    }

    private fun viewModel(
        store: LocalStore,
        library: FakeLibrary,
        scope: CoroutineScope,
    ) = BookmarksViewModel(
        localStore = store,
        checker = BookmarkUpdateChecker(
            localStore = store,
            service = LibraryService(
                library,
                scope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher,
            ),
        ),
        scope = scope,
    )

    private fun bookmark(
        comicId: Long,
        lastKnownChapterIds: Set<Long> = emptySet(),
    ) = BookmarkRecord(
        sourceId = DEFAULT_SOURCE_ID,
        comicId = comicId,
        title = "Comic $comicId",
        coverUrl = "https://example.test/$comicId.jpg",
        lastKnownChapterIds = lastKnownChapterIds,
        latestChapterTitle = "Chapter",
    )

    private fun comic(comicId: Long, chapterIds: List<Long>) = ComicDetails(
        comicId = comicId,
        title = "Comic $comicId",
        subtitle = "Subtitle $comicId",
        coverUrl = "https://example.test/$comicId.jpg",
        statusText = "Ongoing",
        updateText = "Updated today",
        year = 2020,
        region = "Japan",
        genres = listOf("Action"),
        authors = listOf("Author"),
        aliases = listOf("Alias"),
        intro = "Introduction",
        chapters = chapterIds.map { id ->
            ChapterItem(
                chapterId = id,
                chapterTitle = "Chapter $id",
                chapterSize = 20,
                groupName = "Group",
                groupSize = 2,
                order = id.toDouble(),
            )
        },
    )

    private class FailingGetBookmarksStore(
        private val store: InMemoryLocalStore,
    ) : LocalStore by store {
        var failGetBookmarks = false

        override suspend fun getBookmarks(sourceId: String): List<BookmarkRecord> {
            if (failGetBookmarks) error("Bookmark storage unavailable")
            return store.getBookmarks(sourceId)
        }
    }

    private class FakeLibrary : AndroidLibraryInterface {
        var comicRequestCount = 0
        var onGetComic: suspend (Long) -> ComicDetails = { error("Not configured") }

        override suspend fun browseWithin(
            window: AndroidUpdateWindow,
            startPage: Int,
            maxPages: Int,
        ): List<ComicListItem> = error("Not used by these tests")

        override suspend fun browseLatestPage(page: Int): BrowsePage = error("Not used by these tests")

        override suspend fun search(keyword: String, page: Int): SearchPage = error("Not used by these tests")

        override suspend fun getComic(nativeId: Long): ComicDetails {
            comicRequestCount += 1
            return onGetComic(nativeId)
        }

        override suspend fun getChapterPages(nativeId: Long, chapterId: Long): ChapterPagesResult =
            error("Not used by these tests")
    }
}
