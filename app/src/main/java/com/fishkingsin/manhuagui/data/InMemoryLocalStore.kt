package com.fishkingsin.manhuagui.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class InMemoryLocalStore : LocalStore {
    private val bookmarks = MutableStateFlow<Map<StoreKey, BookmarkRecord>>(emptyMap())
    private val readingProgress = MutableStateFlow<Map<StoreKey, ReadingProgress>>(emptyMap())

    override fun observeBookmarks(sourceId: String): Flow<List<BookmarkRecord>> = bookmarks
        .map { records -> records.values.filter { it.sourceId == sourceId }.sortedWith(bookmarkOrder) }
        .distinctUntilChanged()

    override suspend fun getBookmarks(sourceId: String): List<BookmarkRecord> = bookmarks.value.values
        .filter { it.sourceId == sourceId }
        .sortedWith(bookmarkOrder)

    override suspend fun getBookmark(comicId: Long, sourceId: String): BookmarkRecord? =
        bookmarks.value[StoreKey(sourceId, comicId)]

    override suspend fun upsertBookmark(record: BookmarkRecord) {
        bookmarks.update { it + (StoreKey(record.sourceId, record.comicId) to record) }
    }

    override suspend fun deleteBookmark(comicId: Long, sourceId: String) {
        bookmarks.update { it - StoreKey(sourceId, comicId) }
    }

    override suspend fun getReadingProgress(comicId: Long, sourceId: String): ReadingProgress? =
        readingProgress.value[StoreKey(sourceId, comicId)]

    override suspend fun saveReadingProgress(progress: ReadingProgress) {
        readingProgress.update { it + (StoreKey(progress.sourceId, progress.comicId) to progress) }
    }

    private data class StoreKey(val sourceId: String, val comicId: Long)

    private companion object {
        val bookmarkOrder = compareByDescending<BookmarkRecord> { it.hasUnreadUpdate }
            .thenByDescending { it.updatedAtEpochMs }
            .thenBy { it.comicId }
    }
}
