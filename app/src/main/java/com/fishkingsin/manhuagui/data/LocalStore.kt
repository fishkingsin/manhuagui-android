package com.fishkingsin.manhuagui.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

interface LocalStore {
    fun observeBookmarks(sourceId: String = DEFAULT_SOURCE_ID): Flow<List<BookmarkRecord>>

    suspend fun getBookmarks(sourceId: String = DEFAULT_SOURCE_ID): List<BookmarkRecord>

    suspend fun getBookmark(comicId: Long, sourceId: String = DEFAULT_SOURCE_ID): BookmarkRecord?

    suspend fun upsertBookmark(record: BookmarkRecord)

    suspend fun deleteBookmark(comicId: Long, sourceId: String = DEFAULT_SOURCE_ID)

    suspend fun getReadingProgress(comicId: Long, sourceId: String = DEFAULT_SOURCE_ID): ReadingProgress?

    suspend fun saveReadingProgress(progress: ReadingProgress)
}

class RoomLocalStore(
    private val database: AppDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : LocalStore {
    override fun observeBookmarks(sourceId: String): Flow<List<BookmarkRecord>> =
        database.bookmarkDao().observeAll(sourceId)
            .map { entities -> entities.map(BookmarkEntity::toRecord) }
            .flowOn(io)

    override suspend fun getBookmarks(sourceId: String): List<BookmarkRecord> = withContext(io) {
        database.bookmarkDao().getAll(sourceId).map(BookmarkEntity::toRecord)
    }

    override suspend fun getBookmark(comicId: Long, sourceId: String): BookmarkRecord? = withContext(io) {
        database.bookmarkDao().get(comicId, sourceId)?.toRecord()
    }

    override suspend fun upsertBookmark(record: BookmarkRecord) = withContext(io) {
        database.bookmarkDao().upsert(record.toEntity())
    }

    override suspend fun deleteBookmark(comicId: Long, sourceId: String) = withContext(io) {
        database.bookmarkDao().delete(comicId, sourceId)
    }

    override suspend fun getReadingProgress(comicId: Long, sourceId: String): ReadingProgress? = withContext(io) {
        database.readingProgressDao().get(comicId, sourceId)?.toRecord()
    }

    override suspend fun saveReadingProgress(progress: ReadingProgress) = withContext(io) {
        database.readingProgressDao().upsert(progress.toEntity())
    }
}

private fun BookmarkEntity.toRecord() = BookmarkRecord(
    sourceId = sourceId,
    comicId = comicId,
    title = title,
    coverUrl = coverUrl,
    lastKnownChapterIds = ChapterIdsCodec.decode(lastKnownChapterIds),
    latestChapterTitle = latestChapterTitle,
    newChapterCount = newChapterCount,
    hasUnreadUpdate = hasUnreadUpdate,
    lastCheckedEpochMs = lastCheckedEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
)

private fun BookmarkRecord.toEntity() = BookmarkEntity(
    sourceId = sourceId,
    comicId = comicId,
    title = title,
    coverUrl = coverUrl,
    lastKnownChapterIds = ChapterIdsCodec.encode(lastKnownChapterIds),
    latestChapterTitle = latestChapterTitle,
    newChapterCount = newChapterCount,
    hasUnreadUpdate = hasUnreadUpdate,
    lastCheckedEpochMs = lastCheckedEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
)

private fun ReadingProgressEntity.toRecord() = ReadingProgress(
    sourceId = sourceId,
    comicId = comicId,
    chapterId = chapterId,
    chapterTitle = chapterTitle,
    pageIndex = pageIndex,
    updatedAtEpochMs = updatedAtEpochMs,
)

private fun ReadingProgress.toEntity() = ReadingProgressEntity(
    sourceId = sourceId,
    comicId = comicId,
    chapterId = chapterId,
    chapterTitle = chapterTitle,
    pageIndex = pageIndex,
    updatedAtEpochMs = updatedAtEpochMs,
)
