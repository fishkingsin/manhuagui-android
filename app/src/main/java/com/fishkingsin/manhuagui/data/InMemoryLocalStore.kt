package com.fishkingsin.manhuagui.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class InMemoryLocalStore : LocalStore {
    private val bookmarks = MutableStateFlow<Map<StoreKey, BookmarkRecord>>(emptyMap())
    private val readingProgress = MutableStateFlow<Map<StoreKey, ReadingProgress>>(emptyMap())
    private val downloadedChapters = MutableStateFlow<Map<ChapterKey, DownloadedChapterRecord>>(emptyMap())

    override fun observeBookmarks(sourceId: String): Flow<List<BookmarkRecord>> = bookmarks
        .map { records -> records.values.filter { it.sourceId == sourceId }.sortedWith(bookmarkOrder) }
        .distinctUntilChanged()

    override suspend fun getBookmarks(sourceId: String): List<BookmarkRecord> = bookmarks.value.values
        .filter { it.sourceId == sourceId }
        .sortedWith(bookmarkOrder)

    override suspend fun getBookmark(comicId: Long, sourceId: String): BookmarkRecord? =
        bookmarks.value[StoreKey(sourceId, comicId)]

    override suspend fun updateBookmarkIfPresent(
        comicId: Long,
        sourceId: String,
        transform: (BookmarkRecord) -> BookmarkRecord,
    ): BookmarkRecord? {
        val key = StoreKey(sourceId, comicId)
        var updated: BookmarkRecord? = null
        bookmarks.update { currentMap ->
            val current = currentMap[key]
            if (current == null) {
                updated = null
                currentMap
            } else {
                val record = transform(current)
                updated = record
                currentMap + (key to record)
            }
        }
        return updated
    }

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

    override fun observeDownloadedChapters(
        comicId: Long,
        sourceId: String,
    ): Flow<List<DownloadedChapterRecord>> = downloadedChapters
        .map { records ->
            records.values
                .filter { it.comicId == comicId && it.sourceId == sourceId }
                .sortedWith(downloadedChapterOrder)
        }
        .distinctUntilChanged()

    override suspend fun getDownloadedChapters(
        comicId: Long,
        sourceId: String,
    ): List<DownloadedChapterRecord> = downloadedChapters.value.values
        .filter { it.comicId == comicId && it.sourceId == sourceId }
        .sortedWith(downloadedChapterOrder)

    override suspend fun getDownloadedChapter(
        comicId: Long,
        chapterId: Long,
        sourceId: String,
    ): DownloadedChapterRecord? = downloadedChapters.value[ChapterKey(sourceId, comicId, chapterId)]

    override suspend fun upsertDownloadedChapter(record: DownloadedChapterRecord) {
        downloadedChapters.update {
            it + (ChapterKey(record.sourceId, record.comicId, record.chapterId) to record)
        }
    }

    override suspend fun deleteDownloadedChapter(comicId: Long, chapterId: Long, sourceId: String) {
        downloadedChapters.update { it - ChapterKey(sourceId, comicId, chapterId) }
    }

    override suspend fun reconcileDownloadedChapters(
        records: List<DownloadedChapterRecord>,
        sourceId: String,
    ) {
        downloadedChapters.update { current ->
            current.filterKeys { it.sourceId != sourceId } + records
                .filter { it.sourceId == sourceId }
                .associateBy { ChapterKey(it.sourceId, it.comicId, it.chapterId) }
        }
    }

    private data class StoreKey(val sourceId: String, val comicId: Long)

    private data class ChapterKey(val sourceId: String, val comicId: Long, val chapterId: Long)

    private companion object {
        val bookmarkOrder = compareByDescending<BookmarkRecord> { it.hasUnreadUpdate }
            .thenByDescending { it.updatedAtEpochMs }
            .thenBy { it.comicId }
        val downloadedChapterOrder = compareBy<DownloadedChapterRecord> { it.groupName }
            .thenByDescending { it.order }
            .thenBy { it.chapterId }
    }
}
