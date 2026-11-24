package com.fishkingsin.manhuagui.bookmarks

import com.fishkingsin.manhuagui.core.LibraryService
import com.fishkingsin.manhuagui.data.BookmarkRecord
import com.fishkingsin.manhuagui.data.DEFAULT_SOURCE_ID
import com.fishkingsin.manhuagui.data.InMemoryLocalStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
class BookmarkUpdateCheckerTest {
    @Test
    fun checkAllWaitsBetweenComicRequestsAndClampsJitterToOneOrTwoSeconds() = runTest {
        val store = InMemoryLocalStore()
        listOf(1L, 2L, 3L, 4L).forEach { store.upsertBookmark(bookmark(it)) }
        val events = mutableListOf<String>()
        val jitterValues = listOf(999L, 1_500L, 2_501L).iterator()
        val library = FakeLibrary().apply {
            onGetComic = { comicId ->
                events += "comic:$comicId"
                comic(comicId, emptyList())
            }
        }
        val checker = checker(
            store = store,
            library = library,
            scope = this,
            delayMs = { events += "delay:$it" },
            jitterMs = { jitterValues.next() },
        )

        val updates = checker.checkAll()

        assertEquals(
            listOf(
                "comic:1",
                "delay:1000",
                "comic:2",
                "delay:1500",
                "comic:3",
                "delay:2000",
                "comic:4",
            ),
            events,
        )
        assertEquals(emptyList<UpdatedBookmark>(), updates)
    }

    @Test
    fun checkAllDetectsNewChapterIdsAndUpdatesBookmarkAndUnreadCount() = runTest {
        val store = InMemoryLocalStore()
        store.upsertBookmark(
            bookmark(
                comicId = 41,
                title = "Old title",
                lastKnownChapterIds = setOf(1L, 2L),
                newChapterCount = 4,
                hasUnreadUpdate = true,
            ),
        )
        val library = FakeLibrary().apply {
            onGetComic = { comicId ->
                comic(
                    comicId,
                    listOf(
                        chapter(1, "Z Group", 8, 5.0),
                        chapter(2, "A Group", 8, 1.0),
                        chapter(3, "A Group", 8, 3.0),
                        chapter(4, "A Group", 4, 10.0),
                    ),
                )
            }
        }
        val checker = checker(store, library, this, clockMs = { 123_456L })

        val updates = checker.checkAll()

        assertEquals(
            listOf(UpdatedBookmark(41, "Comic 41", "Chapter 3", 2)),
            updates,
        )
        assertEquals(
            bookmark(
                comicId = 41,
                title = "Comic 41",
                coverUrl = "https://example.test/41.jpg",
                lastKnownChapterIds = setOf(1L, 2L, 3L, 4L),
                latestChapterTitle = "Chapter 3",
                newChapterCount = 6,
                hasUnreadUpdate = true,
                lastCheckedEpochMs = 123_456L,
                updatedAtEpochMs = 123_456L,
            ),
            store.getBookmark(41),
        )
    }

    @Test
    fun checkAllContinuesAfterComicFetchFailure() = runTest {
        val store = InMemoryLocalStore()
        store.upsertBookmark(bookmark(comicId = 10))
        store.upsertBookmark(bookmark(comicId = 11))
        val library = FakeLibrary().apply {
            onGetComic = { comicId ->
                if (comicId == 10L) error("offline")
                comic(comicId, listOf(chapter(8, "Group", 2, 1.0)))
            }
        }
        val checker = checker(store, library, this)

        val updates = checker.checkAll()

        assertEquals(listOf(10L, 11L), library.comicIds)
        assertEquals(listOf(UpdatedBookmark(11, "Comic 11", "Chapter 8", 1)), updates)
        assertEquals(0L, store.getBookmark(10)?.lastCheckedEpochMs)
        assertEquals(setOf(8L), store.getBookmark(11)?.lastKnownChapterIds)
    }

    @Test
    fun checkAllPreservesUnreadBadgeWhenNoAdditionalChaptersArrive() = runTest {
        val store = InMemoryLocalStore()
        store.upsertBookmark(
            bookmark(
                comicId = 21,
                title = "Old title",
                lastKnownChapterIds = setOf(7L),
                latestChapterTitle = "Saved chapter",
                newChapterCount = 3,
                hasUnreadUpdate = true,
                lastCheckedEpochMs = 10L,
                updatedAtEpochMs = 20L,
            ),
        )
        val library = FakeLibrary().apply {
            onGetComic = { comicId -> comic(comicId, listOf(chapter(7, "Group", 2, 1.0))) }
        }
        val checker = checker(store, library, this, clockMs = { 30L })

        val updates = checker.checkAll()

        assertEquals(emptyList<UpdatedBookmark>(), updates)
        assertEquals(
            bookmark(
                comicId = 21,
                title = "Comic 21",
                lastKnownChapterIds = setOf(7L),
                latestChapterTitle = "Chapter 7",
                newChapterCount = 3,
                hasUnreadUpdate = true,
                lastCheckedEpochMs = 30L,
                updatedAtEpochMs = 20L,
            ),
            store.getBookmark(21),
        )
    }

    @Test
    fun formatUpdateNotificationHandlesEmptySingleAndMultipleUpdates() {
        val one = UpdatedBookmark(1, "Manga A", "Chapter 2", 2)
        val two = UpdatedBookmark(2, "Manga B", "Chapter 8", 1)

        assertNull(formatUpdateNotification(emptyList()))
        assertEquals(
            NotificationSummary("1 manga updated", "Manga A: Chapter 2 (+2)"),
            formatUpdateNotification(listOf(one)),
        )
        assertEquals(
            NotificationSummary(
                "2 manga updated",
                "Manga A (Chapter 2), Manga B (Chapter 8)",
            ),
            formatUpdateNotification(listOf(one, two)),
        )
    }

    private fun checker(
        store: InMemoryLocalStore,
        library: FakeLibrary,
        scope: TestScope,
        delayMs: suspend (Long) -> Unit = {},
        jitterMs: () -> Long = { 1_000L },
        clockMs: () -> Long = { 123L },
    ) = BookmarkUpdateChecker(
        localStore = store,
        service = LibraryService(library, StandardTestDispatcher(scope.testScheduler)),
        delayMs = delayMs,
        jitterMs = jitterMs,
        clockMs = clockMs,
    )

    private fun bookmark(
        comicId: Long,
        title: String = "Comic $comicId",
        coverUrl: String = "https://example.test/$comicId.jpg",
        lastKnownChapterIds: Set<Long> = emptySet(),
        latestChapterTitle: String = "Chapter",
        newChapterCount: Int = 0,
        hasUnreadUpdate: Boolean = false,
        lastCheckedEpochMs: Long = 0L,
        updatedAtEpochMs: Long = 0L,
    ) = BookmarkRecord(
        sourceId = DEFAULT_SOURCE_ID,
        comicId = comicId,
        title = title,
        coverUrl = coverUrl,
        lastKnownChapterIds = lastKnownChapterIds,
        latestChapterTitle = latestChapterTitle,
        newChapterCount = newChapterCount,
        hasUnreadUpdate = hasUnreadUpdate,
        lastCheckedEpochMs = lastCheckedEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
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
        var onGetComic: suspend (Long) -> ComicDetails = { error("Not configured") }

        override suspend fun browseWithin(
            window: AndroidUpdateWindow,
            startPage: Int,
            maxPages: Int,
        ): List<ComicListItem> = error("Not used by these tests")

        override suspend fun browseLatestPage(page: Int): BrowsePage = error("Not used by these tests")

        override suspend fun search(keyword: String, page: Int): SearchPage = error("Not used by these tests")

        override suspend fun getComic(nativeId: Long): ComicDetails {
            comicIds += nativeId
            return onGetComic(nativeId)
        }

        override suspend fun getChapterPages(nativeId: Long, chapterId: Long): ChapterPagesResult =
            error("Not used by these tests")
    }
}
