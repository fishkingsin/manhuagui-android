package com.fishkingsin.manhuagui.downloads

import com.fishkingsin.manhuagui.core.LibraryService
import com.fishkingsin.manhuagui.data.DownloadedChapterRecord
import com.fishkingsin.manhuagui.data.InMemoryLocalStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineDownloadManagerTest {
    @Test
    fun reconcileOnStartupIndexesChaptersScannedFromDisk() = runTest {
        val store = InMemoryLocalStore()
        val library = DownloadsTestLibrary()
        val scanned = listOf(downloadedChapter(comicId = 12, chapterId = 34))
        var scannedRoot: String? = null
        library.onScan = { root ->
            scannedRoot = root
            scanned
        }
        val manager = manager(library, store)
        val startedAt = System.currentTimeMillis()

        manager.reconcileOnStartup()

        val indexed = store.getDownloadedChapters(comicId = 12).single()
        assertEquals("/offline", scannedRoot)
        assertEquals(12L, indexed.comicId)
        assertEquals(34L, indexed.chapterId)
        assertEquals("Comic 12", indexed.comicTitle)
        assertEquals("Chapter 34", indexed.chapterTitle)
        assertEquals("Group", indexed.groupName)
        assertEquals(34.0, indexed.order, 0.0)
        assertEquals(2, indexed.pageCount)
        assertEquals("/offline/12/34", indexed.chapterDir)
        assertTrue(indexed.downloadedAtEpochMs >= startedAt)
        manager.release()
    }

    @Test
    fun startupReconciliationDoesNotRaceWithDownloads() = runTest {
        val store = InMemoryLocalStore()
        val library = DownloadsTestLibrary()
        val scanStarted = CompletableDeferred<Unit>()
        val finishScan = CompletableDeferred<Unit>()
        val downloadStarted = CompletableDeferred<Unit>()
        library.onScan = {
            scanStarted.complete(Unit)
            finishScan.await()
            emptyList()
        }
        library.onDownload = { comicId, chapterId, _, _ ->
            downloadStarted.complete(Unit)
            downloadedChapter(comicId, chapterId)
        }
        val manager = manager(library, store)
        val reconciliation = backgroundScope.launch { manager.reconcileOnStartup() }
        runCurrent()
        assertTrue(scanStarted.isCompleted)

        manager.enqueue(listOf(ChapterDownloadRequest(comicId = 12, chapterId = 34)))
        runCurrent()

        assertFalse(downloadStarted.isCompleted)
        finishScan.complete(Unit)
        advanceUntilIdle()

        assertTrue(downloadStarted.isCompleted)
        assertEquals(ChapterDownloadStatus.COMPLETED, manager.progress.value.getValue(34).status)
        reconciliation.join()
        manager.release()
    }

    @Test
    fun enqueueDownloadsSequentiallyReportsPagesAndPersistsCompletedRecords() = runTest {
        val store = InMemoryLocalStore()
        val library = DownloadsTestLibrary()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val downloadOrder = mutableListOf<Long>()
        val events = mutableListOf<String>()
        library.onDownload = { comicId, chapterId, _, observer ->
            downloadOrder += chapterId
            if (chapterId == 21L) {
                observer.onProgress(comicId, chapterId, 1u, 3u)
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
            downloadedChapter(comicId, chapterId)
        }
        val manager = manager(library, store, events)

        manager.enqueue(
            listOf(
                ChapterDownloadRequest(comicId = 7, chapterId = 21),
                ChapterDownloadRequest(comicId = 7, chapterId = 22),
                ChapterDownloadRequest(comicId = 7, chapterId = 21),
            ),
        )

        assertEquals(ChapterDownloadStatus.QUEUED, manager.progress.value.getValue(21).status)
        assertEquals(ChapterDownloadStatus.QUEUED, manager.progress.value.getValue(22).status)
        runCurrent()
        assertTrue(firstStarted.isCompleted)
        assertEquals(listOf(21L), downloadOrder)
        assertEquals(ChapterDownloadStatus.DOWNLOADING, manager.progress.value.getValue(21).status)
        assertEquals(1, manager.progress.value.getValue(21).downloadedPages)
        assertEquals(3, manager.progress.value.getValue(21).totalPages)
        assertEquals(ChapterDownloadStatus.QUEUED, manager.progress.value.getValue(22).status)
        assertEquals(listOf("started"), events)

        releaseFirst.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(21L, 22L), downloadOrder)
        assertEquals(ChapterDownloadStatus.COMPLETED, manager.progress.value.getValue(21).status)
        assertEquals(ChapterDownloadStatus.COMPLETED, manager.progress.value.getValue(22).status)
        assertEquals("/offline/7/21", store.getDownloadedChapter(7, 21)?.chapterDir)
        assertEquals("/offline/7/22", store.getDownloadedChapter(7, 22)?.chapterDir)
        assertEquals(listOf("started", "drained"), events)
        manager.release()
    }

    @Test
    fun cancelRemovesQueuedChapterAndCancelsActiveChapterWithoutFailingItsStatus() = runTest {
        val store = InMemoryLocalStore()
        val library = DownloadsTestLibrary()
        val activeStarted = CompletableDeferred<Unit>()
        val finishCancelledDownload = CompletableDeferred<Unit>()
        val downloadOrder = mutableListOf<Long>()
        val cancelled = mutableListOf<Long>()
        library.onCancel = { chapterId ->
            cancelled += chapterId
            if (chapterId == 31L) finishCancelledDownload.complete(Unit)
        }
        library.onDownload = { comicId, chapterId, _, _ ->
            downloadOrder += chapterId
            if (chapterId == 31L) {
                activeStarted.complete(Unit)
                finishCancelledDownload.await()
                error("Native download cancelled")
            }
            downloadedChapter(comicId, chapterId)
        }
        val manager = manager(library, store)
        manager.enqueue(
            listOf(
                ChapterDownloadRequest(comicId = 8, chapterId = 31),
                ChapterDownloadRequest(comicId = 8, chapterId = 32),
            ),
        )
        runCurrent()
        assertTrue(activeStarted.isCompleted)

        manager.cancel(chapterId = 32)
        manager.cancel(chapterId = 31)
        runCurrent()
        advanceUntilIdle()

        assertEquals(listOf(31L), downloadOrder)
        assertEquals(listOf(32L, 31L), cancelled)
        assertEquals(ChapterDownloadStatus.CANCELLED, manager.progress.value.getValue(31).status)
        assertEquals(ChapterDownloadStatus.CANCELLED, manager.progress.value.getValue(32).status)
        assertTrue(store.getDownloadedChapters(comicId = 8).isEmpty())
        manager.release()
    }

    @Test
    fun cancellingActiveDownloadBeforeItReturnsCleansFilesWithoutPersisting() = runTest {
        val store = InMemoryLocalStore()
        val library = DownloadsTestLibrary()
        val downloadStarted = CompletableDeferred<Unit>()
        val finishDownload = CompletableDeferred<Unit>()
        val deleted = mutableListOf<Pair<Long, Long>>()
        library.onDownload = { comicId, chapterId, _, _ ->
            downloadStarted.complete(Unit)
            finishDownload.await()
            downloadedChapter(comicId, chapterId)
        }
        library.onDelete = { comicId, chapterId, root ->
            assertEquals("/offline", root)
            deleted += comicId to chapterId
        }
        val manager = manager(library, store)
        manager.enqueue(listOf(ChapterDownloadRequest(comicId = 8, chapterId = 35)))
        runCurrent()
        assertTrue(downloadStarted.isCompleted)

        manager.cancel(chapterId = 35)
        runCurrent()
        finishDownload.complete(Unit)
        advanceUntilIdle()

        assertEquals(ChapterDownloadStatus.CANCELLED, manager.progress.value.getValue(35).status)
        assertTrue(store.getDownloadedChapters(comicId = 8).isEmpty())
        assertEquals(listOf(8L to 35L), deleted)
        manager.release()
    }

    @Test
    fun cancellingCompletedChapterKeepsItsCompletedProgressAndRecord() = runTest {
        val store = InMemoryLocalStore()
        val library = DownloadsTestLibrary()
        val manager = manager(library, store)
        manager.enqueue(listOf(ChapterDownloadRequest(comicId = 8, chapterId = 33)))
        advanceUntilIdle()

        manager.cancel(chapterId = 33)
        runCurrent()

        assertEquals(ChapterDownloadStatus.COMPLETED, manager.progress.value.getValue(33).status)
        assertEquals(33L, store.getDownloadedChapter(8, 33)?.chapterId)
        manager.release()
    }

    @Test
    fun cancelAllMarksActiveAndQueuedChaptersCancelledAndDrainsQueueOnce() = runTest {
        val store = InMemoryLocalStore()
        val library = DownloadsTestLibrary()
        val activeStarted = CompletableDeferred<Unit>()
        val finishCancelledDownload = CompletableDeferred<Unit>()
        val cancelled = mutableListOf<Long>()
        val events = mutableListOf<String>()
        library.onCancel = { chapterId ->
            cancelled += chapterId
            if (chapterId == 41L) finishCancelledDownload.complete(Unit)
        }
        library.onDownload = { comicId, chapterId, _, _ ->
            if (chapterId == 41L) {
                activeStarted.complete(Unit)
                finishCancelledDownload.await()
                error("Native download cancelled")
            }
            downloadedChapter(comicId, chapterId)
        }
        val manager = manager(library, store, events)
        manager.enqueue(
            listOf(
                ChapterDownloadRequest(comicId = 9, chapterId = 41),
                ChapterDownloadRequest(comicId = 9, chapterId = 42),
            ),
        )
        runCurrent()
        assertTrue(activeStarted.isCompleted)

        manager.cancelAllActive()
        runCurrent()
        advanceUntilIdle()

        assertEquals(setOf(41L, 42L), cancelled.toSet())
        assertEquals(ChapterDownloadStatus.CANCELLED, manager.progress.value.getValue(41).status)
        assertEquals(ChapterDownloadStatus.CANCELLED, manager.progress.value.getValue(42).status)
        assertEquals(listOf("started", "drained"), events)
        manager.release()
    }

    @Test
    fun deleteChapterAndComicRemoveDiskFilesAndIndexedRecords() = runTest {
        val store = InMemoryLocalStore()
        listOf(
            record(comicId = 10, chapterId = 51),
            record(comicId = 10, chapterId = 52),
            record(comicId = 11, chapterId = 53),
        ).forEach { store.upsertDownloadedChapter(it) }
        val library = DownloadsTestLibrary()
        val deleted = mutableListOf<Pair<Long, Long>>()
        library.onDelete = { comicId, chapterId, root ->
            assertEquals("/offline", root)
            deleted += comicId to chapterId
        }
        val manager = manager(library, store)

        manager.deleteChapter(comicId = 10, chapterId = 51)
        manager.deleteComic(comicId = 10)

        assertEquals(setOf(10L to 51L, 10L to 52L), deleted.toSet())
        assertTrue(store.getDownloadedChapters(comicId = 10).isEmpty())
        assertEquals(listOf(53L), store.getDownloadedChapters(comicId = 11).map { it.chapterId })
        manager.release()
    }

    @Test
    fun deleteComicCancelsQueuedChapterBeforeItDownloads() = runTest {
        val store = InMemoryLocalStore()
        val library = DownloadsTestLibrary()
        val downloads = mutableListOf<Long>()
        library.onDownload = { comicId, chapterId, _, _ ->
            downloads += chapterId
            downloadedChapter(comicId, chapterId)
        }
        val manager = manager(library, store)
        manager.enqueue(listOf(ChapterDownloadRequest(comicId = 12, chapterId = 61)))

        manager.deleteComic(comicId = 12)
        advanceUntilIdle()

        assertEquals(ChapterDownloadStatus.CANCELLED, manager.progress.value.getValue(61).status)
        assertTrue(downloads.isEmpty())
        assertTrue(store.getDownloadedChapters(comicId = 12).isEmpty())
        manager.release()
    }

    @Test
    fun deleteComicDuringInFlightDownloadLeavesNoRecordAndCancelsIt() = runTest {
        val store = InMemoryLocalStore()
        val library = DownloadsTestLibrary()
        val downloadStarted = CompletableDeferred<Unit>()
        val finishDownload = CompletableDeferred<Unit>()
        val deleted = mutableListOf<Pair<Long, Long>>()
        library.onDownload = { comicId, chapterId, _, _ ->
            downloadStarted.complete(Unit)
            finishDownload.await()
            downloadedChapter(comicId, chapterId)
        }
        library.onDelete = { comicId, chapterId, _ ->
            deleted += comicId to chapterId
        }
        val manager = manager(library, store)
        manager.enqueue(listOf(ChapterDownloadRequest(comicId = 8, chapterId = 71)))
        runCurrent()
        assertTrue(downloadStarted.isCompleted)

        manager.deleteComic(comicId = 8)
        runCurrent()
        finishDownload.complete(Unit)
        advanceUntilIdle()

        assertEquals(ChapterDownloadStatus.CANCELLED, manager.progress.value.getValue(71).status)
        assertTrue(store.getDownloadedChapters(comicId = 8).isEmpty())
        assertTrue(deleted.contains(8L to 71L))
        manager.release()
    }

    private fun TestScope.manager(
        library: DownloadsTestLibrary,
        store: InMemoryLocalStore,
        events: MutableList<String> = mutableListOf(),
    ): OfflineDownloadManager {
        val io = StandardTestDispatcher(testScheduler)
        val controller = object : ServiceController {
            override fun onQueueStarted() {
                events += "started"
            }

            override fun onQueueDrained() {
                events += "drained"
            }
        }
        return OfflineDownloadManager(
            service = LibraryService(library, io),
            localStore = store,
            downloadRoot = "/offline",
            io = io,
            serviceController = controller,
        )
    }

    private fun record(comicId: Long, chapterId: Long) = DownloadedChapterRecord(
        comicId = comicId,
        comicTitle = "Comic $comicId",
        chapterId = chapterId,
        chapterTitle = "Chapter $chapterId",
        groupName = "Group",
        order = chapterId.toDouble(),
        pageCount = 2,
        chapterDir = "/offline/$comicId/$chapterId",
        downloadedAtEpochMs = 1,
    )
}
