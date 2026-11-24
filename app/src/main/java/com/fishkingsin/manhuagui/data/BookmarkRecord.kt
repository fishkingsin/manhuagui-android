package com.fishkingsin.manhuagui.data

const val DEFAULT_SOURCE_ID = "manhuagui"

data class BookmarkRecord(
    val sourceId: String = DEFAULT_SOURCE_ID,
    val comicId: Long,
    val title: String,
    val coverUrl: String,
    val lastKnownChapterIds: Set<Long>,
    val latestChapterTitle: String,
    val newChapterCount: Int = 0,
    val hasUnreadUpdate: Boolean = false,
    val lastCheckedEpochMs: Long = 0L,
    val updatedAtEpochMs: Long = 0L,
)

object ChapterIdsCodec {
    fun encode(ids: Set<Long>): String = ids.sorted().joinToString(",")

    fun decode(raw: String): Set<Long> = raw
        .split(',')
        .mapNotNull { it.trim().toLongOrNull() }
        .toSet()
}
