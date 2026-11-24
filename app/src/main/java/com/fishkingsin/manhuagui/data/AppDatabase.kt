package com.fishkingsin.manhuagui.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "bookmarks", primaryKeys = ["sourceId", "comicId"])
data class BookmarkEntity(
    val sourceId: String,
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
    val sourceId: String,
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

@Dao
interface ReadingProgressDao {
    @Query("SELECT * FROM reading_progress WHERE comicId = :comicId AND sourceId = :sourceId LIMIT 1")
    suspend fun get(comicId: Long, sourceId: String): ReadingProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ReadingProgressEntity)
}

@Database(entities = [BookmarkEntity::class, ReadingProgressEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookmarkDao(): BookmarkDao

    abstract fun readingProgressDao(): ReadingProgressDao

    companion object {
        fun build(context: Context): AppDatabase = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "manhuagui.db",
        ).build()
    }
}
