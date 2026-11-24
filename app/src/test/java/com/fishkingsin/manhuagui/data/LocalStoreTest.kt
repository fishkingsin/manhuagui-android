package com.fishkingsin.manhuagui.data

import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalStoreTest {
    @Test
    fun roomEntitiesDefaultToTheManhuaguiSource() {
        val bookmark = BookmarkEntity(
            comicId = 1,
            title = "Comic",
            coverUrl = "https://example.test/1.jpg",
            lastKnownChapterIds = "",
            latestChapterTitle = "Chapter",
            newChapterCount = 0,
            hasUnreadUpdate = false,
            lastCheckedEpochMs = 0,
            updatedAtEpochMs = 0,
        )
        val progress = ReadingProgressEntity(
            comicId = 1,
            chapterId = 10,
            chapterTitle = "Chapter 10",
            pageIndex = 0,
            updatedAtEpochMs = 0,
        )

        assertEquals(DEFAULT_SOURCE_ID, bookmark.sourceId)
        assertEquals(DEFAULT_SOURCE_ID, progress.sourceId)
    }

    @Test
    fun chapterIdsCodecRoundTripsEmptyAndUnsortedSets() {
        assertEquals("", ChapterIdsCodec.encode(emptySet()))
        assertEquals(emptySet<Long>(), ChapterIdsCodec.decode(""))
        assertEquals("2,7,9", ChapterIdsCodec.encode(setOf(9L, 2L, 7L)))
        assertEquals(setOf(2L, 7L, 9L), ChapterIdsCodec.decode("9,2,7"))
    }

    @Test
    fun chapterIdsCodecIgnoresWhitespaceAndMalformedTokens() {
        assertEquals(
            setOf(8L, -2L, 1L),
            ChapterIdsCodec.decode(" 8, bad, , -2, 8, 9223372036854775808, 1 "),
        )
    }

    @Test
    fun bookmarksOrderUnreadThenUpdatedThenComicIdInGetAndObserve() = runTest {
        val store = InMemoryLocalStore()
        listOf(
            bookmark(comicId = 1, hasUnreadUpdate = false, updatedAtEpochMs = 900),
            bookmark(comicId = 20, hasUnreadUpdate = true, updatedAtEpochMs = 200),
            bookmark(comicId = 3, hasUnreadUpdate = true, updatedAtEpochMs = 200),
            bookmark(comicId = 4, hasUnreadUpdate = true, updatedAtEpochMs = 500),
            bookmark(comicId = 5, hasUnreadUpdate = false, updatedAtEpochMs = 1_000),
            bookmark(comicId = 1, sourceId = "other", hasUnreadUpdate = true, updatedAtEpochMs = 2_000),
        ).forEach { store.upsertBookmark(it) }

        val expectedComicIds = listOf(4L, 3L, 20L, 5L, 1L)

        assertEquals(expectedComicIds, store.getBookmarks().map(BookmarkRecord::comicId))
        assertEquals(expectedComicIds, store.observeBookmarks().first().map(BookmarkRecord::comicId))
    }

    @Test
    fun bookmarkUpsertReplacesSameSourceAndDeleteKeepsOtherSources() = runTest {
        val store = InMemoryLocalStore()
        store.upsertBookmark(bookmark(comicId = 42, title = "Old title"))
        store.upsertBookmark(bookmark(comicId = 42, title = "New title"))
        store.upsertBookmark(bookmark(comicId = 42, sourceId = "other", title = "Other source"))

        assertEquals("New title", store.getBookmark(42)?.title)

        store.deleteBookmark(42)

        assertNull(store.getBookmark(42))
        assertEquals("Other source", store.getBookmark(42, "other")?.title)
    }

    @Test
    fun updateBookmarkIfPresentTransformsAndReturnsAnExistingRecord() = runTest {
        val store = InMemoryLocalStore()
        val initial = bookmark(comicId = 11)
        store.upsertBookmark(initial)

        val updated = updateBookmarkIfPresent(store, 11) {
            it.copy(title = "Updated title", newChapterCount = 3)
        }

        assertEquals(initial.copy(title = "Updated title", newChapterCount = 3), updated)
        assertEquals(updated, store.getBookmark(11))
    }

    @Test
    fun updateBookmarkIfPresentReturnsNullWithoutInsertingAnAbsentRecord() = runTest {
        val store = InMemoryLocalStore()

        val updated = updateBookmarkIfPresent(store, 12) { it.copy(title = "Unexpected") }

        assertNull(updated)
        assertNull(store.getBookmark(12))
    }

    @Test
    fun readingProgressOverwritesBySourceAndComic() = runTest {
        val store = InMemoryLocalStore()
        store.saveReadingProgress(progress(comicId = 7, chapterId = 10, pageIndex = 3))
        store.saveReadingProgress(progress(comicId = 7, chapterId = 11, pageIndex = 4))
        store.saveReadingProgress(progress(comicId = 7, sourceId = "other", chapterId = 12))
        store.saveReadingProgress(progress(comicId = 8, chapterId = 13))

        assertEquals(progress(comicId = 7, chapterId = 11, pageIndex = 4), store.getReadingProgress(7))
        assertEquals(progress(comicId = 7, sourceId = "other", chapterId = 12), store.getReadingProgress(7, "other"))
        assertEquals(progress(comicId = 8, chapterId = 13), store.getReadingProgress(8))
    }

    private suspend fun updateBookmarkIfPresent(
        store: LocalStore,
        comicId: Long,
        transform: (BookmarkRecord) -> BookmarkRecord,
    ): BookmarkRecord? = suspendCoroutine { continuation ->
        val method = store.javaClass.methods.firstOrNull { it.name == "updateBookmarkIfPresent" }
            ?: throw AssertionError("LocalStore does not support atomic bookmark updates")
        val result = method.invoke(store, comicId, DEFAULT_SOURCE_ID, transform, continuation)
        if (result !== COROUTINE_SUSPENDED) continuation.resume(result as BookmarkRecord?)
    }

    private fun bookmark(
        comicId: Long,
        sourceId: String = DEFAULT_SOURCE_ID,
        title: String = "Comic $comicId",
        hasUnreadUpdate: Boolean = false,
        updatedAtEpochMs: Long = 0L,
    ) = BookmarkRecord(
        sourceId = sourceId,
        comicId = comicId,
        title = title,
        coverUrl = "https://example.test/$comicId.jpg",
        lastKnownChapterIds = emptySet(),
        latestChapterTitle = "Chapter",
        hasUnreadUpdate = hasUnreadUpdate,
        updatedAtEpochMs = updatedAtEpochMs,
    )

    private fun progress(
        comicId: Long,
        sourceId: String = DEFAULT_SOURCE_ID,
        chapterId: Long,
        pageIndex: Int = 0,
    ) = ReadingProgress(
        sourceId = sourceId,
        comicId = comicId,
        chapterId = chapterId,
        chapterTitle = "Chapter $chapterId",
        pageIndex = pageIndex,
        updatedAtEpochMs = chapterId,
    )
}
