package com.fishkingsin.manhuagui.data

data class ReadingProgress(
    val sourceId: String = DEFAULT_SOURCE_ID,
    val comicId: Long,
    val chapterId: Long,
    val chapterTitle: String,
    val pageIndex: Int,
    val updatedAtEpochMs: Long,
)
