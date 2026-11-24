package com.fishkingsin.manhuagui.ui.detail

import com.fishkingsin.manhuagui.downloads.ChapterDownloadProgress
import com.fishkingsin.manhuagui.downloads.ChapterDownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterDownloadPresentationTest {
    @Test
    fun completedProgressWithoutAStoredChapterOffersDownload() {
        val presentation = chapterDownloadPresentation(
            isDownloaded = false,
            progress = ChapterDownloadProgress(41, 7, ChapterDownloadStatus.COMPLETED),
        )

        assertEquals(
            ChapterDownloadPresentation("Not downloaded", ChapterDownloadAction.DOWNLOAD, false),
            presentation,
        )
    }

    @Test
    fun aStoredChapterOffersDeleteAndExport() {
        val presentation = chapterDownloadPresentation(
            isDownloaded = true,
            progress = ChapterDownloadProgress(41, 7, ChapterDownloadStatus.CANCELLED),
        )

        assertEquals(
            ChapterDownloadPresentation("Downloaded", ChapterDownloadAction.DELETE, true),
            presentation,
        )
    }

    @Test
    fun queuedAndActiveChaptersOfferCancel() {
        val queued = chapterDownloadPresentation(
            isDownloaded = false,
            progress = ChapterDownloadProgress(41, 7, ChapterDownloadStatus.QUEUED),
        )
        val downloading = chapterDownloadPresentation(
            isDownloaded = false,
            progress = ChapterDownloadProgress(41, 7, ChapterDownloadStatus.DOWNLOADING),
        )

        assertEquals(ChapterDownloadPresentation("Queued", ChapterDownloadAction.CANCEL, false), queued)
        assertEquals(ChapterDownloadPresentation("Downloading", ChapterDownloadAction.CANCEL, false), downloading)
    }
}
