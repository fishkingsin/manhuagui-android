package com.fishkingsin.manhuagui.ui.reader

import com.fishkingsin.manhuagui.core.AppError
import com.fishkingsin.manhuagui.core.LibraryService
import com.fishkingsin.manhuagui.data.BookmarkRecord
import com.fishkingsin.manhuagui.data.DownloadedChapterRecord
import com.fishkingsin.manhuagui.data.InMemoryLocalStore
import com.fishkingsin.manhuagui.data.LocalStore
import com.fishkingsin.manhuagui.data.ReadingProgress
import com.fishkingsin.manhuagui.settings.AppSettings
import com.fishkingsin.manhuagui.settings.InMemorySettingsStore
import com.fishkingsin.manhuagui.settings.ReadingDirection
import com.fishkingsin.manhuagui.settings.SettingsStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
import uniffi.manhuagui_ffi.AndroidException
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
import uniffi.manhuagui_ffi.ImagePage
import uniffi.manhuagui_ffi.LocalChapterNavigation
import uniffi.manhuagui_ffi.SearchPage

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {
    @Test
    fun loadResumesTheSavedPageInTheSameChapter() = runTest {
        val store = InMemoryLocalStore()
        store.saveReadingProgress(
            ReadingProgress(
                comicId = 42,
                chapterId = 7,
                chapterTitle = "Old title",
                pageIndex = 2,
                updatedAtEpochMs = 10,
            ),
        )
        val library = FakeLibrary().apply {
            onGetChapterPages = { _, _ -> ChapterPagesResult((1..3).map(::imagePage), null, null) }
        }
        val viewModel = viewModel(library, this, localStore = store, chapterTitle = "Chapter seven", clockMs = { 20 })

        viewModel.load()
        runCurrent()

        assertEquals(2, viewModel.state.value.pageIndex)
        assertEquals(
            ReadingProgress(comicId = 42, chapterId = 7, chapterTitle = "Chapter seven", pageIndex = 2, updatedAtEpochMs = 20),
            store.getReadingProgress(42),
        )
    }

    @Test
    fun reopeningSavedChapterWithoutTitlePreservesItsRealTitle() = runTest {
        val store = InMemoryLocalStore()
        store.saveReadingProgress(
            ReadingProgress(
                comicId = 42,
                chapterId = 7,
                chapterTitle = "A Real Chapter Title",
                pageIndex = 1,
                updatedAtEpochMs = 10,
            ),
        )
        val library = FakeLibrary().apply {
            onGetChapterPages = { _, _ -> ChapterPagesResult((1..3).map(::imagePage), null, null) }
        }
        val viewModel = viewModel(library, this, localStore = store, clockMs = { 20 })

        viewModel.load()
        runCurrent()

        assertEquals("A Real Chapter Title", store.getReadingProgress(42)?.chapterTitle)
    }

    @Test
    fun loadClampsSavedPageToTheLastAvailablePage() = runTest {
        val store = InMemoryLocalStore()
        store.saveReadingProgress(
            ReadingProgress(comicId = 42, chapterId = 7, chapterTitle = "Chapter 7", pageIndex = 99, updatedAtEpochMs = 10),
        )
        val library = FakeLibrary().apply {
            onGetChapterPages = { _, _ -> ChapterPagesResult((1..3).map(::imagePage), null, null) }
        }
        val viewModel = viewModel(library, this, localStore = store)

        viewModel.load()
        runCurrent()

        assertEquals(2, viewModel.state.value.pageIndex)
    }

    @Test
    fun loadStartsAtFirstPageWhenSavedProgressIsForAnotherChapter() = runTest {
        val store = InMemoryLocalStore()
        store.saveReadingProgress(
            ReadingProgress(comicId = 42, chapterId = 8, chapterTitle = "Chapter 8", pageIndex = 2, updatedAtEpochMs = 10),
        )
        val library = FakeLibrary().apply {
            onGetChapterPages = { _, _ -> ChapterPagesResult((1..3).map(::imagePage), null, null) }
        }
        val viewModel = viewModel(library, this, localStore = store)

        viewModel.load()
        runCurrent()

        assertEquals(0, viewModel.state.value.pageIndex)
    }

    @Test
    fun turningPagePersistsTheNewProgress() = runTest {
        val store = InMemoryLocalStore()
        val library = FakeLibrary().apply {
            onGetChapterPages = { _, _ -> ChapterPagesResult((1..3).map(::imagePage), null, null) }
        }
        val viewModel = viewModel(library, this, localStore = store, clockMs = { 30 })

        viewModel.load()
        runCurrent()
        viewModel.turnPage(1)
        runCurrent()

        assertEquals(1, store.getReadingProgress(42)?.pageIndex)
        assertEquals("Chapter 7", store.getReadingProgress(42)?.chapterTitle)
        assertEquals(30L, store.getReadingProgress(42)?.updatedAtEpochMs)
    }

    @Test
    fun pagerPageChangePersistsTheNewProgress() = runTest {
        val store = InMemoryLocalStore()
        val library = FakeLibrary().apply {
            onGetChapterPages = { _, _ -> ChapterPagesResult((1..3).map(::imagePage), null, null) }
        }
        val viewModel = viewModel(library, this, localStore = store)

        viewModel.load()
        runCurrent()
        viewModel.onPageChanged(2)
        runCurrent()

        assertEquals(2, store.getReadingProgress(42)?.pageIndex)
    }

    @Test
    fun loadingChapterClearsAnUnreadBookmarkBadge() = runTest {
        for ((hasUnreadUpdate, newChapterCount) in listOf(true to 0, false to 3)) {
            val store = InMemoryLocalStore()
            store.upsertBookmark(
                BookmarkRecord(
                    comicId = 42,
                    title = "Comic 42",
                    coverUrl = "cover.jpg",
                    lastKnownChapterIds = setOf(7),
                    latestChapterTitle = "Chapter 7",
                    newChapterCount = newChapterCount,
                    hasUnreadUpdate = hasUnreadUpdate,
                ),
            )
            val library = FakeLibrary().apply {
                onGetChapterPages = { _, _ -> ChapterPagesResult(listOf(imagePage(1)), null, null) }
            }
            val viewModel = viewModel(library, this, localStore = store)

            viewModel.load()
            runCurrent()

            assertEquals(false, store.getBookmark(42)?.hasUnreadUpdate)
            assertEquals(0, store.getBookmark(42)?.newChapterCount)
        }
    }

    @Test
    fun samePageNotificationPreservesTheLastTurnDirection() = runTest {
        val library = FakeLibrary().apply {
            onGetChapterPages = { _, _ ->
                ChapterPagesResult((1..3).map(::imagePage), null, null)
            }
        }
        val viewModel = viewModel(library, this)

        viewModel.load()
        runCurrent()
        viewModel.turnPage(2)
        viewModel.turnPage(-1)

        assertEquals(1, viewModel.state.value.pageIndex)
        assertEquals(-1, viewModel.state.value.lastTurnDirection)

        viewModel.onPageChanged(1)

        assertEquals(-1, viewModel.state.value.lastTurnDirection)
        viewModel.onPageChanged(0)
        assertEquals(-1, viewModel.state.value.lastTurnDirection)
        viewModel.onPageChanged(2)
        assertEquals(1, viewModel.state.value.lastTurnDirection)
    }

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
    fun loadUsesSortedLocalJpgPagesAndLocalChapterNavigation() = runTest {
        val chapterDirectory = Files.createTempDirectory("reader-local-pages").toFile()
        try {
            File(chapterDirectory, "002.jpg").createNewFile()
            File(chapterDirectory, "001.jpg").createNewFile()
            File(chapterDirectory, "003.png").createNewFile()
            val store = InMemoryLocalStore()
            store.upsertDownloadedChapter(downloadedChapter(chapterDirectory))
            store.saveReadingProgress(
                ReadingProgress(
                    comicId = 42,
                    chapterId = 7,
                    chapterTitle = "Saved title",
                    pageIndex = 99,
                    updatedAtEpochMs = 10,
                ),
            )
            val library = FakeLibrary().apply {
                onReadLocalChapterNavigation = { _, _, _ ->
                    LocalChapterNavigation(prevChapterId = 6, nextChapterId = 8)
                }
            }
            val viewModel = viewModel(
                library,
                this,
                localStore = store,
                settingsStore = InMemorySettingsStore(
                    AppSettings(readingDirection = ReadingDirection.LTR, prefetchCount = 5),
                ),
                downloadRoot = "/offline",
            )

            viewModel.load()
            viewModel.state.first { !it.loading }

            assertEquals(
                listOf(
                    "file://${File(chapterDirectory, "001.jpg").absolutePath}",
                    "file://${File(chapterDirectory, "002.jpg").absolutePath}",
                ),
                viewModel.state.value.pages.map { it.url },
            )
            assertTrue(viewModel.state.value.isOfflineChapter)
            assertEquals(1, viewModel.state.value.pageIndex)
            assertEquals(6L, viewModel.state.value.prevChapterId)
            assertEquals(8L, viewModel.state.value.nextChapterId)
            assertEquals(ReadingDirection.LTR, viewModel.state.value.readingDirection)
            assertEquals(5, viewModel.state.value.prefetchCount)
            assertEquals(emptyList<Pair<Long, Long>>(), library.chapterQueries)
            assertEquals(listOf(Triple(42L, 7L, "/offline")), library.navigationQueries)
            assertEquals("Saved title", store.getReadingProgress(42)?.chapterTitle)
            assertEquals(1, store.getReadingProgress(42)?.pageIndex)
            assertFalse(viewModel.state.value.loading)
        } finally {
            chapterDirectory.deleteRecursively()
        }
    }

    @Test
    fun loadFallsBackToStreamingWhenDownloadedDirectoryHasNoJpgPages() = runTest {
        val chapterDirectory = Files.createTempDirectory("reader-empty-local-pages").toFile()
        try {
            val store = InMemoryLocalStore()
            store.upsertDownloadedChapter(downloadedChapter(chapterDirectory))
            val page = imagePage(1)
            val library = FakeLibrary().apply {
                onGetChapterPages = { _, _ -> ChapterPagesResult(listOf(page), null, null) }
            }
            val viewModel = viewModel(library, this, localStore = store)

            viewModel.load()
            viewModel.state.first { !it.loading }

            assertEquals(listOf(page), viewModel.state.value.pages)
            assertFalse(viewModel.state.value.isOfflineChapter)
            assertEquals(listOf(42L to 7L), library.chapterQueries)
            assertEquals(emptyList<Triple<Long, Long, String>>(), library.navigationQueries)
        } finally {
            chapterDirectory.deleteRecursively()
        }
    }

    @Test
    fun offlineOnlyModePromptsWhenDownloadedRecordHasNoUsableDirectory() = runTest {
        val root = Files.createTempDirectory("reader-missing-local-pages").toFile()
        try {
            val store = InMemoryLocalStore()
            store.upsertDownloadedChapter(downloadedChapter(File(root, "missing")))
            val library = FakeLibrary()
            val viewModel = viewModel(
                library,
                this,
                localStore = store,
                settingsStore = InMemorySettingsStore(AppSettings(offlineOnlyMode = true)),
            )

            viewModel.load()
            viewModel.state.first { !it.loading }

            assertTrue(viewModel.state.value.awaitingOfflineStreamConfirmation)
            assertEquals(emptyList<Pair<Long, Long>>(), library.chapterQueries)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun offlineOnlyModePromptsUntilStreamingIsConfirmed() = runTest {
        val page = imagePage(1)
        val library = FakeLibrary().apply {
            onGetChapterPages = { _, _ -> ChapterPagesResult(listOf(page), null, null) }
        }
        val viewModel = viewModel(
            library,
            this,
            settingsStore = InMemorySettingsStore(
                AppSettings(offlineOnlyMode = true, readingDirection = ReadingDirection.LTR, prefetchCount = 2),
            ),
        )

        viewModel.load()
        runCurrent()

        assertTrue(viewModel.state.value.awaitingOfflineStreamConfirmation)
        assertFalse(viewModel.state.value.loading)
        assertEquals(ReadingDirection.LTR, viewModel.state.value.readingDirection)
        assertEquals(2, viewModel.state.value.prefetchCount)
        assertEquals(emptyList<Pair<Long, Long>>(), library.chapterQueries)

        viewModel.confirmStreaming()
        assertFalse(viewModel.state.value.awaitingOfflineStreamConfirmation)
        assertTrue(viewModel.state.value.loading)
        runCurrent()

        assertEquals(listOf(page), viewModel.state.value.pages)
        assertEquals(listOf(42L to 7L), library.chapterQueries)
        assertFalse(viewModel.state.value.loading)
    }

    @Test
    fun localChapterNavigationFailureDoesNotPreventOfflineReading() = runTest {
        val chapterDirectory = Files.createTempDirectory("reader-local-navigation-failure").toFile()
        try {
            File(chapterDirectory, "001.jpg").createNewFile()
            val store = InMemoryLocalStore()
            store.upsertDownloadedChapter(downloadedChapter(chapterDirectory))
            val library = FakeLibrary().apply {
                onReadLocalChapterNavigation = { _, _, _ -> error("navigation unavailable") }
            }
            val viewModel = viewModel(library, this, localStore = store)

            viewModel.load()
            viewModel.state.first { !it.loading }

            assertEquals(1, viewModel.state.value.pages.size)
            assertTrue(viewModel.state.value.isOfflineChapter)
            assertNull(viewModel.state.value.prevChapterId)
            assertNull(viewModel.state.value.nextChapterId)
            assertFalse(viewModel.state.value.loading)
        } finally {
            chapterDirectory.deleteRecursively()
        }
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
    fun parseFailedMapsToUserErrorAndRetryLoadsPages() = runTest {
        val page = imagePage(1)
        var shouldFail = true
        val library = FakeLibrary().apply {
            onGetChapterPages = { _, _ ->
                if (shouldFail) throw AndroidException.ParseFailed(detail = "decrypt failed")
                ChapterPagesResult(listOf(page), null, null)
            }
        }
        val viewModel = viewModel(library, this)

        viewModel.load()
        runCurrent()

        assertEquals(AppError.ParseFailed("decrypt failed"), viewModel.state.value.error)
        assertEquals("The site format changed. Update the app.", viewModel.state.value.error?.userMessage)

        shouldFail = false
        viewModel.retry()

        assertNull(viewModel.state.value.error)
        assertTrue(viewModel.state.value.loading)
        runCurrent()

        assertEquals(listOf(page), viewModel.state.value.pages)
        assertNull(viewModel.state.value.error)
        assertFalse(viewModel.state.value.loading)
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

    private fun viewModel(
        library: FakeLibrary,
        scope: TestScope,
        localStore: LocalStore = InMemoryLocalStore(),
        chapterTitle: String = "",
        clockMs: () -> Long = { 1_234L },
        settingsStore: SettingsStore = InMemorySettingsStore(),
        downloadRoot: String = "",
    ) = ReaderViewModel(
        nativeId = 42,
        chapterId = 7,
        service = LibraryService(library, StandardTestDispatcher(scope.testScheduler)),
        localStore = localStore,
        chapterTitle = chapterTitle,
        settingsStore = settingsStore,
        downloadRoot = downloadRoot,
        clockMs = clockMs,
        scope = scope.backgroundScope,
    )

    private fun downloadedChapter(chapterDirectory: File) = DownloadedChapterRecord(
        comicId = 42,
        comicTitle = "Comic 42",
        chapterId = 7,
        chapterTitle = "Chapter 7",
        groupName = "Group",
        order = 1.0,
        pageCount = 2,
        chapterDir = chapterDirectory.absolutePath,
        downloadedAtEpochMs = 10,
    )

    private fun imagePage(id: Int) = ImagePage(
        url = "https://example.test/$id.jpg",
        headers = mapOf("Referer" to "https://example.test/"),
    )

    private class FakeLibrary : AndroidLibraryInterface {
        val chapterQueries = mutableListOf<Pair<Long, Long>>()
        val navigationQueries = mutableListOf<Triple<Long, Long, String>>()
        var onGetChapterPages: suspend (Long, Long) -> ChapterPagesResult = { _, _ ->
            ChapterPagesResult(emptyList(), null, null)
        }
        var onReadLocalChapterNavigation: suspend (Long, Long, String) -> LocalChapterNavigation = { _, _, _ ->
            LocalChapterNavigation(null, null)
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
        ): LocalChapterNavigation {
            navigationQueries += Triple(nativeId, chapterId, downloadRoot)
            return onReadLocalChapterNavigation(nativeId, chapterId, downloadRoot)
        }

        override suspend fun scanDownloadedChapters(downloadRoot: String): List<DownloadedChapter> =
            error("Not used by these tests")
    }
}
