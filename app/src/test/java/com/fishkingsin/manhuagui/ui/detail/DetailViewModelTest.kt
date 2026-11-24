package com.fishkingsin.manhuagui.ui.detail

import com.fishkingsin.manhuagui.core.AppError
import com.fishkingsin.manhuagui.core.LibraryService
import com.fishkingsin.manhuagui.data.BookmarkRecord
import com.fishkingsin.manhuagui.data.DownloadedChapterRecord
import com.fishkingsin.manhuagui.data.InMemoryLocalStore
import com.fishkingsin.manhuagui.data.LocalStore
import com.fishkingsin.manhuagui.data.ReadingProgress
import com.fishkingsin.manhuagui.downloads.ArchiveExporter
import com.fishkingsin.manhuagui.downloads.ChapterDownloadRequest
import com.fishkingsin.manhuagui.downloads.ChapterDownloadStatus
import com.fishkingsin.manhuagui.downloads.OfflineDownloadManager
import com.fishkingsin.manhuagui.downloads.ServiceController
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
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
    @get:Rule
    val temporaryFolder = TemporaryFolder()

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

    @Test
    fun downloadChapterEnqueuesAndPublishesTheDownloadedRecordAndProgress() = runTest {
        val otherDownloadStarted = CompletableDeferred<Unit>()
        val finishOtherDownload = CompletableDeferred<Unit>()
        val library = FakeLibrary().apply {
            onGetComic = { comic(41, listOf(chapter(5, "Group", 2, 1.0))) }
            onDownloadChapter = { comicId, chapterId ->
                if (comicId == 42L) {
                    otherDownloadStarted.complete(Unit)
                    finishOtherDownload.await()
                }
                DownloadedChapter(
                    comicId = comicId,
                    comicTitle = "Comic $comicId",
                    chapterId = chapterId,
                    chapterTitle = "Chapter $chapterId",
                    groupName = "Group",
                    order = chapterId.toDouble(),
                    pageCount = 2,
                    chapterDir = "/offline/$comicId/$chapterId",
                )
            }
        }
        val viewModel = viewModel(
            41,
            library,
            this,
            initialRequests = listOf(ChapterDownloadRequest(42, 99)),
        )

        viewModel.load()
        runCurrent()
        assertTrue(otherDownloadStarted.isCompleted)
        assertTrue(viewModel.downloadProgress.value.isEmpty())

        viewModel.downloadChapter(5)
        runCurrent()
        assertEquals(ChapterDownloadStatus.QUEUED, viewModel.downloadProgress.value.getValue(5).status)

        finishOtherDownload.complete(Unit)
        runCurrent()

        assertEquals(setOf(5L, 99L), library.downloadedChapterIds.toSet())
        assertEquals(listOf(5L), viewModel.downloadedChapters.value.map { it.chapterId })
        assertEquals(ChapterDownloadStatus.COMPLETED, viewModel.downloadProgress.value.getValue(5).status)
    }

    @Test
    fun downloadUnreadChaptersOnlyQueuesChaptersBeforeSavedProgress() = runTest {
        val chapters = listOf(
            chapter(1, "Group", 4, 1.0),
            chapter(2, "Group", 4, 2.0),
            chapter(3, "Group", 4, 3.0),
            chapter(4, "Group", 4, 4.0),
        )
        val store = InMemoryLocalStore().apply {
            saveReadingProgress(
                ReadingProgress(
                    comicId = 41,
                    chapterId = 2,
                    chapterTitle = "Chapter 2",
                    pageIndex = 0,
                    updatedAtEpochMs = 10,
                ),
            )
        }
        val library = FakeLibrary().apply { onGetComic = { comic(41, chapters) } }
        val viewModel = viewModel(41, library, this, localStore = store)

        viewModel.load()
        runCurrent()
        viewModel.downloadUnreadChapters()
        runCurrent()

        assertEquals(listOf(4L, 3L), library.downloadedChapterIds)
    }

    @Test
    fun downloadUnreadChaptersQueuesEveryChapterWhenProgressIsAbsent() = runTest {
        val chapters = listOf(
            chapter(1, "Group", 4, 1.0),
            chapter(2, "Group", 4, 2.0),
            chapter(3, "Group", 4, 3.0),
            chapter(4, "Group", 4, 4.0),
        )
        val library = FakeLibrary().apply { onGetComic = { comic(41, chapters) } }
        val viewModel = viewModel(41, library, this)

        viewModel.load()
        runCurrent()
        viewModel.downloadUnreadChapters()
        runCurrent()

        assertEquals(listOf(4L, 3L, 2L, 1L), library.downloadedChapterIds)
    }

    @Test
    fun deleteAllDownloadsRemovesEveryLocalAndDiskRecordForTheComic() = runTest {
        val store = InMemoryLocalStore().apply {
            upsertDownloadedChapter(downloadedRecord(7))
            upsertDownloadedChapter(downloadedRecord(8))
        }
        val library = FakeLibrary()
        val viewModel = viewModel(41, library, this, localStore = store)

        viewModel.deleteAllDownloads()
        runCurrent()

        assertEquals(setOf(7L, 8L), library.deletedChapterIds)
        assertTrue(store.getDownloadedChapters(41).isEmpty())
    }

    @Test
    fun cancelAndDeleteChapterUpdateTheDownloadAndLocalRecords() = runTest {
        val store = InMemoryLocalStore().apply { upsertDownloadedChapter(downloadedRecord(7)) }
        val library = FakeLibrary()
        val viewModel = viewModel(41, library, this, localStore = store)

        viewModel.downloadChapter(8)
        viewModel.cancelDownload(8)
        runCurrent()
        viewModel.deleteDownloadedChapter(7)
        runCurrent()

        assertEquals(ChapterDownloadStatus.CANCELLED, viewModel.downloadProgress.value.getValue(8).status)
        assertTrue(store.getDownloadedChapters(41).isEmpty())
        assertTrue(viewModel.downloadedChapters.value.isEmpty())
        assertEquals(setOf(7L), library.deletedChapterIds)
    }

    @Test
    fun exportActionsWriteFilesAndEmitOneShotResults() = runTest {
        val store = InMemoryLocalStore().apply {
            upsertDownloadedChapter(downloadedRecord(7))
            upsertDownloadedChapter(downloadedRecord(8))
        }
        val library = FakeLibrary().apply {
            onExportChapters = { comicId, chapterIds, downloadRoot, exportRoot, format ->
                exportRequests += ExportRequest(comicId, chapterIds.toSet(), format)
                chapterIds.map { chapterId ->
                    val name = "chapter-$chapterId.${format.name.lowercase()}"
                    val file = File(exportRoot, name).apply {
                        writeBytes("$comicId:$chapterId:${format.name}".toByteArray())
                    }
                    ExportedChapterFile(chapterId, name, file.absolutePath)
                }
            }
        }
        val viewModel = viewModel(41, library, this, localStore = store)
        runCurrent()
        val chapterCbz = ByteArrayOutputStream()
        val chapterPdf = ByteArrayOutputStream()
        val cbzMessage = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            viewModel.exportResult.first()
        }
        viewModel.exportChapterCbz(7) { chapterCbz }
        runCurrent()
        assertEquals("Export completed.", cbzMessage.await())

        val pdfMessage = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            viewModel.exportResult.first()
        }
        viewModel.exportChapterPdf(7) { chapterPdf }
        runCurrent()
        assertEquals("Export completed.", pdfMessage.await())

        val comicCbz = mutableMapOf<String, ByteArrayOutputStream>()
        val comicCbzMessage = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            viewModel.exportResult.first()
        }
        viewModel.exportComicCbz { fileName -> comicCbz.getOrPut(fileName, ::ByteArrayOutputStream) }
        runCurrent()
        assertEquals("Export completed.", comicCbzMessage.await())

        val comicPdf = mutableMapOf<String, ByteArrayOutputStream>()
        val comicPdfMessage = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            viewModel.exportResult.first()
        }
        viewModel.exportComicPdf { fileName -> comicPdf.getOrPut(fileName, ::ByteArrayOutputStream) }
        runCurrent()
        assertEquals("Export completed.", comicPdfMessage.await())

        assertEquals("41:7:CBZ", chapterCbz.toString())
        assertEquals("41:7:PDF", chapterPdf.toString())
        assertEquals(setOf("chapter-7.cbz", "chapter-8.cbz"), comicCbz.keys)
        assertEquals("41:7:CBZ", comicCbz.getValue("chapter-7.cbz").toString())
        assertEquals("41:8:CBZ", comicCbz.getValue("chapter-8.cbz").toString())
        assertEquals(setOf("chapter-7.pdf", "chapter-8.pdf"), comicPdf.keys)
        assertEquals("41:7:PDF", comicPdf.getValue("chapter-7.pdf").toString())
        assertEquals("41:8:PDF", comicPdf.getValue("chapter-8.pdf").toString())
        assertEquals(
            listOf(
                ExportRequest(41, setOf(7), AndroidExportFormat.CBZ),
                ExportRequest(41, setOf(7), AndroidExportFormat.PDF),
                ExportRequest(41, setOf(7, 8), AndroidExportFormat.CBZ),
                ExportRequest(41, setOf(7, 8), AndroidExportFormat.PDF),
            ),
            library.exportRequests,
        )
    }

    private fun downloadedRecord(chapterId: Long) = DownloadedChapterRecord(
        comicId = 41,
        comicTitle = "Comic 41",
        chapterId = chapterId,
        chapterTitle = "Chapter $chapterId",
        groupName = "Group",
        order = chapterId.toDouble(),
        pageCount = 2,
        chapterDir = "/offline/41/$chapterId",
        downloadedAtEpochMs = 10,
    )

    private data class ExportRequest(
        val comicId: Long,
        val chapterIds: Set<Long>,
        val format: AndroidExportFormat,
    )

    private fun viewModel(
        nativeId: Long,
        library: FakeLibrary,
        scope: TestScope,
        localStore: LocalStore = InMemoryLocalStore(),
        clockMs: () -> Long = { 1_234L },
        initialRequests: List<ChapterDownloadRequest> = emptyList(),
    ): DetailViewModel {
        val io = StandardTestDispatcher(scope.testScheduler)
        val service = LibraryService(library, io)
        val downloadRoot = temporaryFolder.newFolder().absolutePath
        val manager = OfflineDownloadManager(
            service = service,
            localStore = localStore,
            downloadRoot = downloadRoot,
            io = io,
            serviceController = ServiceController { },
        )
        val exporter = ArchiveExporter(
            service = service,
            downloadRoot = downloadRoot,
            exportRoot = temporaryFolder.newFolder().absolutePath,
            io = io,
        )
        manager.enqueue(initialRequests)
        return DetailViewModel(
            nativeId = nativeId,
            service = service,
            localStore = localStore,
            offlineDownloadManager = manager,
            archiveExporter = exporter,
            clockMs = clockMs,
            scope = scope.backgroundScope,
        )
    }

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
        val downloadedChapterIds = mutableListOf<Long>()
        val deletedChapterIds = mutableSetOf<Long>()
        val exportRequests = mutableListOf<ExportRequest>()
        var onGetComic: suspend () -> ComicDetails = { error("Not used by these tests") }
        var onDownloadChapter: suspend (Long, Long) -> DownloadedChapter = { comicId, chapterId ->
            DownloadedChapter(
                comicId = comicId,
                comicTitle = "Comic $comicId",
                chapterId = chapterId,
                chapterTitle = "Chapter $chapterId",
                groupName = "Group",
                order = chapterId.toDouble(),
                pageCount = 2,
                chapterDir = "/offline/$comicId/$chapterId",
            )
        }
        var onExportChapters: suspend (
            Long,
            List<Long>,
            String,
            String,
            AndroidExportFormat,
        ) -> List<ExportedChapterFile> = { _, _, _, _, _ -> emptyList() }

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
        ) {
            deletedChapterIds += chapterId
        }

        override suspend fun downloadChapter(
            nativeId: Long,
            chapterId: Long,
            downloadRoot: String,
            observer: AndroidDownloadObserver,
        ): DownloadedChapter {
            downloadedChapterIds += chapterId
            return onDownloadChapter(nativeId, chapterId)
        }

        override suspend fun exportChapters(
            nativeId: Long,
            chapterIds: List<Long>,
            downloadRoot: String,
            exportRoot: String,
            format: AndroidExportFormat,
        ): List<ExportedChapterFile> = onExportChapters(nativeId, chapterIds, downloadRoot, exportRoot, format)

        override suspend fun readLocalChapterNavigation(
            nativeId: Long,
            chapterId: Long,
            downloadRoot: String,
        ): LocalChapterNavigation = error("Not used by these tests")

        override suspend fun scanDownloadedChapters(downloadRoot: String): List<DownloadedChapter> =
            error("Not used by these tests")
    }
}
