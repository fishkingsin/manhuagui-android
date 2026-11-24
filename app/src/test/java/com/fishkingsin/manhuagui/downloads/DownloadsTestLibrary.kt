package com.fishkingsin.manhuagui.downloads

import uniffi.manhuagui_ffi.AndroidDownloadObserver
import uniffi.manhuagui_ffi.AndroidExportFormat
import uniffi.manhuagui_ffi.AndroidLibraryInterface
import uniffi.manhuagui_ffi.AndroidUpdateWindow
import uniffi.manhuagui_ffi.BrowsePage
import uniffi.manhuagui_ffi.ChapterPagesResult
import uniffi.manhuagui_ffi.ComicDetails
import uniffi.manhuagui_ffi.ComicListItem
import uniffi.manhuagui_ffi.DownloadedChapter
import uniffi.manhuagui_ffi.ExportedChapterFile
import uniffi.manhuagui_ffi.LocalChapterNavigation
import uniffi.manhuagui_ffi.SearchPage

internal class DownloadsTestLibrary : AndroidLibraryInterface {
    var onDownload: suspend (Long, Long, String, AndroidDownloadObserver) -> DownloadedChapter =
        { comicId, chapterId, _, _ -> downloadedChapter(comicId, chapterId) }
    var onDelete: suspend (Long, Long, String) -> Unit = { _, _, _ -> }
    var onScan: suspend (String) -> List<DownloadedChapter> = { emptyList() }
    var onExport: suspend (Long, List<Long>, String, String, AndroidExportFormat) -> List<ExportedChapterFile> =
        { _, _, _, _, _ -> emptyList() }
    var onCancel: (Long) -> Unit = {}

    override suspend fun browseWithin(
        window: AndroidUpdateWindow,
        startPage: Int,
        maxPages: Int,
    ): List<ComicListItem> = error("Not used by these tests")

    override suspend fun browseLatestPage(page: Int): BrowsePage = error("Not used by these tests")

    override suspend fun search(keyword: String, page: Int): SearchPage = error("Not used by these tests")

    override suspend fun getComic(nativeId: Long): ComicDetails = error("Not used by these tests")

    override suspend fun getChapterPages(nativeId: Long, chapterId: Long): ChapterPagesResult =
        error("Not used by these tests")

    override fun cancelDownload(chapterId: Long) = onCancel(chapterId)

    override suspend fun deleteDownloadedChapter(
        nativeId: Long,
        chapterId: Long,
        downloadRoot: String,
    ) = onDelete(nativeId, chapterId, downloadRoot)

    override suspend fun downloadChapter(
        nativeId: Long,
        chapterId: Long,
        downloadRoot: String,
        observer: AndroidDownloadObserver,
    ): DownloadedChapter = onDownload(nativeId, chapterId, downloadRoot, observer)

    override suspend fun exportChapters(
        nativeId: Long,
        chapterIds: List<Long>,
        downloadRoot: String,
        exportRoot: String,
        format: AndroidExportFormat,
    ): List<ExportedChapterFile> = onExport(nativeId, chapterIds, downloadRoot, exportRoot, format)

    override suspend fun readLocalChapterNavigation(
        nativeId: Long,
        chapterId: Long,
        downloadRoot: String,
    ): LocalChapterNavigation = error("Not used by these tests")

    override suspend fun scanDownloadedChapters(downloadRoot: String): List<DownloadedChapter> =
        onScan(downloadRoot)
}

internal fun downloadedChapter(comicId: Long, chapterId: Long) = DownloadedChapter(
    comicId = comicId,
    comicTitle = "Comic $comicId",
    chapterId = chapterId,
    chapterTitle = "Chapter $chapterId",
    groupName = "Group",
    order = chapterId.toDouble(),
    pageCount = 2,
    chapterDir = "/offline/$comicId/$chapterId",
)
