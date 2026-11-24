package com.fishkingsin.manhuagui.ui.detail

import com.fishkingsin.manhuagui.core.AppError
import com.fishkingsin.manhuagui.core.LibraryService
import com.fishkingsin.manhuagui.data.BookmarkRecord
import com.fishkingsin.manhuagui.data.InMemoryLocalStore
import com.fishkingsin.manhuagui.data.LocalStore
import com.fishkingsin.manhuagui.data.ReadingProgress
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
import uniffi.manhuagui_ffi.ChapterItem
import uniffi.manhuagui_ffi.ChapterPagesResult
import uniffi.manhuagui_ffi.ComicDetails
import uniffi.manhuagui_ffi.ComicListItem
import uniffi.manhuagui_ffi.DownloadedChapter
import uniffi.manhuagui_ffi.ExportedChapterFile
import uniffi.manhuagui_ffi.LocalChapterNavigation
import uniffi.manhuagui_ffi.SearchPage

@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModelTest {
    @Test
    fun toggleBookmarkSeedsKnownChaptersAndSecondToggleRemovesIt() = runTest {
        val chapters = listOf(chapter(5, "Group", 2, 1.0), chapter(8, "Group", 2, 2.0))
        val comic = comic(41, chapters)
        val store = InMemoryLocalStore()
        val library = FakeLibrary().apply { onGetComic = { comic } }
        val viewModel = viewModel(41, library, this, localStore = store, clockMs = { 50 })

        viewModel.load()
        runCurrent()
        viewModel.toggleBookmark()
        runCurrent()

        assertTrue(viewModel.state.value.isBookmarked)
        assertEquals(setOf(5L, 8L), store.getBookmark(41)?.lastKnownChapterIds)
        assertEquals("Chapter 8", store.getBookmark(41)?.latestChapterTitle)
        assertEquals(50L, store.getBookmark(41)?.lastCheckedEpochMs)
        assertEquals(50L, store.getBookmark(41)?.updatedAtEpochMs)

        viewModel.toggleBookmark()
        runCurrent()

        assertFalse(viewModel.state.value.isBookmarked)
        assertNull(store.getBookmark(41))
    }

    @Test
    fun quickBookmarkTogglesLeaveTheSecondTapAsTheFinalIntent() = runTest {
        val store = InMemoryLocalStore()
        val library = FakeLibrary().apply { onGetComic = { comic(41, emptyList()) } }
        val viewModel = viewModel(41, library, this, localStore = store)

        viewModel.load()
        runCurrent()
        viewModel.toggleBookmark()
        viewModel.toggleBookmark()
        runCurrent()

        assertFalse(viewModel.state.value.isBookmarked)
        assertNull(store.getBookmark(41))
    }

    @Test
    fun rapidTogglesPersistInTapOrder() = runTest {
        val pendingUpsert = CompletableDeferred<Unit>()
        val operations = mutableListOf<String>()
        var savedBookmark: BookmarkRecord? = null
        val store = object : LocalStore by InMemoryLocalStore() {
            override suspend fun upsertBookmark(record: BookmarkRecord) {
                pendingUpsert.await()
                savedBookmark = record
                operations += "upsert"
            }

            override suspend fun deleteBookmark(comicId: Long, sourceId: String) {
                savedBookmark = null
                operations += "delete"
            }
        }
        val library = FakeLibrary().apply { onGetComic = { comic(41, emptyList()) } }
        val viewModel = viewModel(41, library, this, localStore = store)

        viewModel.load()
        runCurrent()
        viewModel.toggleBookmark()
        viewModel.toggleBookmark()
        runCurrent()
        pendingUpsert.complete(Unit)
        runCurrent()

        assertEquals(listOf("upsert", "delete"), operations)
        assertFalse(viewModel.state.value.isBookmarked)
        assertNull(savedBookmark)
    }

    @Test
    fun olderToggleFailureDoesNotRollBackTheLatestSuccessfulToggle() = runTest {
        val base = InMemoryLocalStore()
        val firstWriteStarted = CompletableDeferred<Unit>()
        val releaseFirstWrite = CompletableDeferred<Unit>()
        var writeCount = 0
        val store = object : LocalStore by base {
            override suspend fun upsertBookmark(record: BookmarkRecord) {
                writeCount += 1
                if (writeCount == 1) {
                    firstWriteStarted.complete(Unit)
                    releaseFirstWrite.await()
                    throw IllegalStateException("first write failed")
                }
                base.upsertBookmark(record)
            }
        }
        val library = FakeLibrary().apply { onGetComic = { comic(41, emptyList()) } }
        val viewModel = viewModel(41, library, this, localStore = store)

        viewModel.load()
        runCurrent()
        viewModel.toggleBookmark()
        runCurrent()
        assertTrue(firstWriteStarted.isCompleted)

        viewModel.toggleBookmark()
        viewModel.toggleBookmark()
        releaseFirstWrite.complete(Unit)
        runCurrent()

        assertTrue(viewModel.state.value.isBookmarked)
        assertEquals("Comic 41", base.getBookmark(41)?.title)
    }

    @Test
    fun failedBookmarkWriteRevertsOptimisticState() = runTest {
        val store = object : LocalStore by InMemoryLocalStore() {
            override suspend fun upsertBookmark(record: BookmarkRecord) {
                throw IllegalStateException("write failed")
            }
        }
        val library = FakeLibrary().apply { onGetComic = { comic(41, emptyList()) } }
        val viewModel = viewModel(41, library, this, localStore = store)

        viewModel.load()
        runCurrent()
        viewModel.toggleBookmark()

        assertTrue(viewModel.state.value.isBookmarked)
        runCurrent()

        assertFalse(viewModel.state.value.isBookmarked)
        assertNull(store.getBookmark(41))
    }

    @Test
    fun loadSyncsAnExistingBookmarkAndClearsItsUnreadBadge() = runTest {
        val comic = comic(41, listOf(chapter(5, "Group", 2, 1.0)))
        val store = InMemoryLocalStore()
        store.upsertBookmark(
            BookmarkRecord(
                comicId = 41,
                title = "Old title",
                coverUrl = "old.jpg",
                lastKnownChapterIds = setOf(1),
                latestChapterTitle = "Old chapter",
                newChapterCount = 2,
                hasUnreadUpdate = true,
            ),
        )
        val library = FakeLibrary().apply { onGetComic = { comic } }
        val viewModel = viewModel(41, library, this, localStore = store)

        viewModel.load()
        runCurrent()

        assertTrue(viewModel.state.value.isBookmarked)
        assertFalse(store.getBookmark(41)?.hasUnreadUpdate ?: true)
        assertEquals(0, store.getBookmark(41)?.newChapterCount)
        assertEquals(comic.title, store.getBookmark(41)?.title)
        assertEquals(comic.coverUrl, store.getBookmark(41)?.coverUrl)
        assertEquals(setOf(5L), store.getBookmark(41)?.lastKnownChapterIds)
        assertEquals("Chapter 5", store.getBookmark(41)?.latestChapterTitle)
    }

    @Test
    fun loadSurfacesSavedReadingProgress() = runTest {
        val progress = ReadingProgress(
            comicId = 41,
            chapterId = 5,
            chapterTitle = "Chapter five",
            pageIndex = 3,
            updatedAtEpochMs = 20,
        )
        val store = InMemoryLocalStore()
        store.saveReadingProgress(progress)
        val library = FakeLibrary().apply { onGetComic = { comic(41, emptyList()) } }
        val viewModel = viewModel(41, library, this, localStore = store)

        viewModel.load()
        runCurrent()

        assertEquals(progress, viewModel.state.value.readingProgress)
    }

    @Test
    fun refreshProgressReadsUpdatesSavedAfterDetailLoaded() = runTest {
        val store = InMemoryLocalStore()
        val library = FakeLibrary().apply { onGetComic = { comic(41, emptyList()) } }
        val viewModel = viewModel(41, library, this, localStore = store)

        viewModel.load()
        runCurrent()
        val progress = ReadingProgress(comicId = 41, chapterId = 5, chapterTitle = "Chapter five", pageIndex = 3, updatedAtEpochMs = 20)
        store.saveReadingProgress(progress)
        store.upsertBookmark(
            BookmarkRecord(
                comicId = 41,
                title = "Comic 41",
                coverUrl = "cover.jpg",
                lastKnownChapterIds = emptySet(),
                latestChapterTitle = "",
            ),
        )
        viewModel.refreshProgress()
        runCurrent()

        assertEquals(progress, viewModel.state.value.readingProgress)
        assertTrue(viewModel.state.value.isBookmarked)
    }

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

    private fun viewModel(
        nativeId: Long,
        library: FakeLibrary,
        scope: TestScope,
        localStore: LocalStore = InMemoryLocalStore(),
        clockMs: () -> Long = { 1_234L },
    ) = DetailViewModel(
        nativeId = nativeId,
        service = LibraryService(library, StandardTestDispatcher(scope.testScheduler)),
        localStore = localStore,
        clockMs = clockMs,
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
