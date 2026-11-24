package com.fishkingsin.manhuagui.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "bookmarks", primaryKeys = ["sourceId", "comicId"])
data class BookmarkEntity(
    @ColumnInfo(defaultValue = DEFAULT_SOURCE_ID) val sourceId: String = DEFAULT_SOURCE_ID,
    val comicId: Long,
    val title: String,
    val coverUrl: String,
    val lastKnownChapterIds: String,
    val latestChapterTitle: String,
    val newChapterCount: Int,
    val hasUnreadUpdate: Boolean,
    val lastCheckedEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "reading_progress", primaryKeys = ["sourceId", "comicId"])
data class ReadingProgressEntity(
    @ColumnInfo(defaultValue = DEFAULT_SOURCE_ID) val sourceId: String = DEFAULT_SOURCE_ID,
    val comicId: Long,
    val chapterId: Long,
    val chapterTitle: String,
    val pageIndex: Int,
    val updatedAtEpochMs: Long,
)

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE sourceId = :sourceId ORDER BY hasUnreadUpdate DESC, updatedAtEpochMs DESC, comicId ASC")
    fun observeAll(sourceId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE sourceId = :sourceId ORDER BY hasUnreadUpdate DESC, updatedAtEpochMs DESC, comicId ASC")
    suspend fun getAll(sourceId: String): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks WHERE comicId = :comicId AND sourceId = :sourceId LIMIT 1")
    suspend fun get(comicId: Long, sourceId: String): BookmarkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE comicId = :comicId AND sourceId = :sourceId")
    suspend fun delete(comicId: Long, sourceId: String)
}

@Entity(tableName = "downloaded_chapters", primaryKeys = ["sourceId", "comicId", "chapterId"])
data class DownloadedChapterEntity(
    @ColumnInfo(defaultValue = DEFAULT_SOURCE_ID) val sourceId: String = DEFAULT_SOURCE_ID,
    val comicId: Long,
    val comicTitle: String,
    val chapterId: Long,
    val chapterTitle: String,
    val groupName: String,
    val order: Double,
    val pageCount: Int,
    val chapterDir: String,
    val downloadedAtEpochMs: Long,
)

@Dao
interface ReadingProgressDao {
    @Query("SELECT * FROM reading_progress WHERE comicId = :comicId AND sourceId = :sourceId LIMIT 1")
    suspend fun get(comicId: Long, sourceId: String): ReadingProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ReadingProgressEntity)
}

@Dao
interface DownloadedChapterDao {
    @Query("SELECT * FROM downloaded_chapters WHERE comicId = :comicId AND sourceId = :sourceId ORDER BY groupName ASC, `order` DESC, chapterId ASC")
    fun observeForComic(comicId: Long, sourceId: String): Flow<List<DownloadedChapterEntity>>

    @Query("SELECT * FROM downloaded_chapters WHERE comicId = :comicId AND sourceId = :sourceId ORDER BY groupName ASC, `order` DESC, chapterId ASC")
    suspend fun getForComic(comicId: Long, sourceId: String): List<DownloadedChapterEntity>

    @Query("SELECT * FROM downloaded_chapters WHERE comicId = :comicId AND chapterId = :chapterId AND sourceId = :sourceId LIMIT 1")
    suspend fun get(comicId: Long, chapterId: Long, sourceId: String): DownloadedChapterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadedChapterEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<DownloadedChapterEntity>)

    @Query("DELETE FROM downloaded_chapters WHERE comicId = :comicId AND chapterId = :chapterId AND sourceId = :sourceId")
    suspend fun delete(comicId: Long, chapterId: Long, sourceId: String)

    @Query("DELETE FROM downloaded_chapters WHERE sourceId = :sourceId")
    suspend fun deleteAllForSource(sourceId: String)
}

@Database(
    entities = [BookmarkEntity::class, ReadingProgressEntity::class, DownloadedChapterEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookmarkDao(): BookmarkDao

    abstract fun readingProgressDao(): ReadingProgressDao

    abstract fun downloadedChapterDao(): DownloadedChapterDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `downloaded_chapters` (`sourceId` TEXT NOT NULL DEFAULT 'manhuagui', `comicId` INTEGER NOT NULL, `comicTitle` TEXT NOT NULL, `chapterId` INTEGER NOT NULL, `chapterTitle` TEXT NOT NULL, `groupName` TEXT NOT NULL, `order` REAL NOT NULL, `pageCount` INTEGER NOT NULL, `chapterDir` TEXT NOT NULL, `downloadedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`sourceId`, `comicId`, `chapterId`))",
                )
            }
        }

        fun build(context: Context): AppDatabase = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "manhuagui.db",
        ).addMigrations(MIGRATION_1_2).build()
    }
}
