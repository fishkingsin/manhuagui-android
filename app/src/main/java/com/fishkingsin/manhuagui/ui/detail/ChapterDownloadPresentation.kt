package com.fishkingsin.manhuagui.ui.detail

import com.fishkingsin.manhuagui.downloads.ChapterDownloadProgress
import com.fishkingsin.manhuagui.downloads.ChapterDownloadStatus

enum class ChapterDownloadAction(val label: String) {
    DOWNLOAD("Download"),
    CANCEL("Cancel"),
    DELETE("Delete"),
}

data class ChapterDownloadPresentation(
    val statusLabel: String,
    val action: ChapterDownloadAction,
    val canExport: Boolean,
)

fun chapterDownloadPresentation(
    isDownloaded: Boolean,
    progress: ChapterDownloadProgress?,
): ChapterDownloadPresentation = when {
    isDownloaded -> ChapterDownloadPresentation("Downloaded", ChapterDownloadAction.DELETE, true)
    progress?.status == ChapterDownloadStatus.QUEUED ->
        ChapterDownloadPresentation("Queued", ChapterDownloadAction.CANCEL, false)
    progress?.status == ChapterDownloadStatus.DOWNLOADING ->
        ChapterDownloadPresentation("Downloading", ChapterDownloadAction.CANCEL, false)
    progress?.status == ChapterDownloadStatus.FAILED ->
        ChapterDownloadPresentation("Failed", ChapterDownloadAction.DOWNLOAD, false)
    else -> ChapterDownloadPresentation("Not downloaded", ChapterDownloadAction.DOWNLOAD, false)
}
