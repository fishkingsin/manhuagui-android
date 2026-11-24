package com.fishkingsin.manhuagui.bookmarks

import com.fishkingsin.manhuagui.core.LibraryService
import com.fishkingsin.manhuagui.data.DEFAULT_SOURCE_ID
import com.fishkingsin.manhuagui.data.LocalStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uniffi.manhuagui_ffi.ChapterItem

data class UpdatedBookmark(
    val comicId: Long,
    val title: String,
    val latestChapterTitle: String,
    val newChapterCount: Int,
)

data class NotificationSummary(val title: String, val body: String)

fun formatUpdateNotification(updates: List<UpdatedBookmark>): NotificationSummary? = when (updates.size) {
    0 -> null
    1 -> {
        val update = updates.first()
        NotificationSummary(
            title = "1 manga updated",
            body = "${update.title}: ${update.latestChapterTitle} (+${update.newChapterCount})",
        )
    }
    else -> NotificationSummary(
        title = "${updates.size} manga updated",
        body = updates.joinToString(", ") { "${it.title} (${it.latestChapterTitle})" },
    )
}

class BookmarkUpdateChecker(
    private val localStore: LocalStore,
    private val service: LibraryService,
    private val delayMs: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val jitterMs: () -> Long = { kotlin.random.Random.nextLong(1000L, 2001L) },
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val checkMutex = Mutex()

    suspend fun checkAll(sourceId: String = DEFAULT_SOURCE_ID): List<UpdatedBookmark> = checkMutex.withLock {
        val bookmarks = localStore.getBookmarks(sourceId)
        val updated = mutableListOf<UpdatedBookmark>()
        bookmarks.forEachIndexed { index, bookmark ->
            if (index > 0) delayMs(jitterMs().coerceIn(1000L, 2000L))
            val comic = service.getComic(bookmark.comicId).getOrNull() ?: return@forEachIndexed
            val current = localStore.getBookmark(bookmark.comicId, sourceId) ?: return@forEachIndexed
            val now = clockMs()
            val currentIds = comic.chapters.map { it.chapterId }.toSet()
            val newIds = currentIds - current.lastKnownChapterIds
            val newestTitle = comic.chapters.sortedWith(
                compareByDescending<ChapterItem> { it.groupSize }
                    .thenBy { it.groupName }
                    .thenByDescending { it.order },
            ).firstOrNull()?.chapterTitle ?: current.latestChapterTitle

            if (newIds.isNotEmpty()) {
                val totalNewCount = current.newChapterCount + newIds.size
                localStore.upsertBookmark(
                    current.copy(
                        title = comic.title,
                        coverUrl = comic.coverUrl,
                        lastKnownChapterIds = currentIds,
                        latestChapterTitle = newestTitle,
                        newChapterCount = totalNewCount,
                        hasUnreadUpdate = true,
                        lastCheckedEpochMs = now,
                        updatedAtEpochMs = now,
                    ),
                )
                updated += UpdatedBookmark(
                    comicId = current.comicId,
                    title = comic.title,
                    latestChapterTitle = newestTitle,
                    newChapterCount = newIds.size,
                )
            } else {
                localStore.upsertBookmark(
                    current.copy(
                        title = comic.title,
                        coverUrl = comic.coverUrl,
                        lastKnownChapterIds = currentIds,
                        latestChapterTitle = newestTitle,
                        lastCheckedEpochMs = now,
                    ),
                )
            }
        }
        updated
    }
}
