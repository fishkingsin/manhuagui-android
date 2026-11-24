package com.fishkingsin.manhuagui.data

data class DownloadedChapterRecord(
    val sourceId: String = DEFAULT_SOURCE_ID,
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
