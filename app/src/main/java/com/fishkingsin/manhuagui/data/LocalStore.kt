package com.fishkingsin.manhuagui.data

import androidx.room.withTransaction
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

    suspend fun updateBookmarkIfPresent(
        comicId: Long,
        sourceId: String = DEFAULT_SOURCE_ID,
        transform: (BookmarkRecord) -> BookmarkRecord,
    ): BookmarkRecord?

    suspend fun upsertBookmark(record: BookmarkRecord)

    suspend fun deleteBookmark(comicId: Long, sourceId: String = DEFAULT_SOURCE_ID)

    suspend fun getReadingProgress(comicId: Long, sourceId: String = DEFAULT_SOURCE_ID): ReadingProgress?

    suspend fun saveReadingProgress(progress: ReadingProgress)

    fun observeDownloadedChapters(
        comicId: Long,
        sourceId: String = DEFAULT_SOURCE_ID,
    ): Flow<List<DownloadedChapterRecord>>

    suspend fun getDownloadedChapters(
        comicId: Long,
        sourceId: String = DEFAULT_SOURCE_ID,
    ): List<DownloadedChapterRecord>

    suspend fun getDownloadedChapter(
        comicId: Long,
        chapterId: Long,
        sourceId: String = DEFAULT_SOURCE_ID,
    ): DownloadedChapterRecord?

    suspend fun upsertDownloadedChapter(record: DownloadedChapterRecord)

    suspend fun deleteDownloadedChapter(
        comicId: Long,
        chapterId: Long,
        sourceId: String = DEFAULT_SOURCE_ID,
    )

    suspend fun reconcileDownloadedChapters(
        records: List<DownloadedChapterRecord>,
        sourceId: String = DEFAULT_SOURCE_ID,
    )
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

    override suspend fun updateBookmarkIfPresent(
        comicId: Long,
        sourceId: String,
        transform: (BookmarkRecord) -> BookmarkRecord,
    ): BookmarkRecord? = withContext(io) {
        database.withTransaction {
            val current = database.bookmarkDao().get(comicId, sourceId)?.toRecord()
                ?: return@withTransaction null
            val updated = transform(current)
            database.bookmarkDao().upsert(updated.toEntity())
            updated
        }
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

    override fun observeDownloadedChapters(
        comicId: Long,
        sourceId: String,
    ): Flow<List<DownloadedChapterRecord>> = database.downloadedChapterDao()
        .observeForComic(comicId, sourceId)
        .map { entities -> entities.map(DownloadedChapterEntity::toRecord) }
        .flowOn(io)

    override suspend fun getDownloadedChapters(
        comicId: Long,
        sourceId: String,
    ): List<DownloadedChapterRecord> = withContext(io) {
        database.downloadedChapterDao().getForComic(comicId, sourceId).map(DownloadedChapterEntity::toRecord)
    }

    override suspend fun getDownloadedChapter(
        comicId: Long,
        chapterId: Long,
        sourceId: String,
    ): DownloadedChapterRecord? = withContext(io) {
        database.downloadedChapterDao().get(comicId, chapterId, sourceId)?.toRecord()
    }

    override suspend fun upsertDownloadedChapter(record: DownloadedChapterRecord) = withContext(io) {
        database.downloadedChapterDao().upsert(record.toEntity())
    }

    override suspend fun deleteDownloadedChapter(comicId: Long, chapterId: Long, sourceId: String) = withContext(io) {
        database.downloadedChapterDao().delete(comicId, chapterId, sourceId)
    }

    override suspend fun reconcileDownloadedChapters(
        records: List<DownloadedChapterRecord>,
        sourceId: String,
    ) = withContext(io) {
        database.withTransaction {
            database.downloadedChapterDao().deleteAllForSource(sourceId)
            database.downloadedChapterDao().upsertAll(
                records.filter { it.sourceId == sourceId }.map(DownloadedChapterRecord::toEntity),
            )
        }
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

private fun DownloadedChapterEntity.toRecord() = DownloadedChapterRecord(
    sourceId = sourceId,
    comicId = comicId,
    comicTitle = comicTitle,
    chapterId = chapterId,
    chapterTitle = chapterTitle,
    groupName = groupName,
    order = order,
    pageCount = pageCount,
    chapterDir = chapterDir,
    downloadedAtEpochMs = downloadedAtEpochMs,
)

private fun DownloadedChapterRecord.toEntity() = DownloadedChapterEntity(
    sourceId = sourceId,
    comicId = comicId,
    comicTitle = comicTitle,
    chapterId = chapterId,
    chapterTitle = chapterTitle,
    groupName = groupName,
    order = order,
    pageCount = pageCount,
    chapterDir = chapterDir,
    downloadedAtEpochMs = downloadedAtEpochMs,
)
