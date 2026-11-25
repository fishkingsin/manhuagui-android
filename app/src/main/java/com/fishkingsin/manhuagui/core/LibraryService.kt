package com.fishkingsin.manhuagui.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

class LibraryService(
    private val library: AndroidLibraryInterface,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun browseWithin(
        window: AndroidUpdateWindow,
        startPage: Int,
        maxPages: Int,
    ): Result<List<ComicListItem>> = callFfi { library.browseWithin(window, startPage, maxPages) }

    suspend fun browseLatestPage(page: Int): Result<BrowsePage> =
        callFfi { library.browseLatestPage(page) }

    suspend fun search(keyword: String, page: Int): Result<SearchPage> =
        callFfi { library.search(keyword, page) }

    suspend fun getComic(nativeId: Long): Result<ComicDetails> =
        callFfi { library.getComic(nativeId) }

    suspend fun getChapterPages(nativeId: Long, chapterId: Long): Result<ChapterPagesResult> =
        callFfi { library.getChapterPages(nativeId, chapterId) }

    suspend fun downloadChapter(
        nativeId: Long,
        chapterId: Long,
        downloadRoot: String,
        observer: AndroidDownloadObserver,
    ): Result<DownloadedChapter> =
        callFfi { library.downloadChapter(nativeId, chapterId, downloadRoot, observer) }

    suspend fun cancelDownload(chapterId: Long): Result<Unit> =
        callFfi { library.cancelDownload(chapterId) }

    suspend fun deleteDownloadedChapter(
        nativeId: Long,
        chapterId: Long,
        downloadRoot: String,
    ): Result<Unit> = callFfi { library.deleteDownloadedChapter(nativeId, chapterId, downloadRoot) }

    suspend fun scanDownloadedChapters(downloadRoot: String): Result<List<DownloadedChapter>> =
        callFfi { library.scanDownloadedChapters(downloadRoot) }

    suspend fun readLocalChapterNavigation(
        nativeId: Long,
        chapterId: Long,
        downloadRoot: String,
    ): Result<LocalChapterNavigation> =
        callFfi { library.readLocalChapterNavigation(nativeId, chapterId, downloadRoot) }

    suspend fun exportChapters(
        nativeId: Long,
        chapterIds: List<Long>,
        downloadRoot: String,
        exportRoot: String,
        format: AndroidExportFormat,
    ): Result<List<ExportedChapterFile>> =
        callFfi { library.exportChapters(nativeId, chapterIds, downloadRoot, exportRoot, format) }

    private suspend fun <T> callFfi(call: suspend () -> T): Result<T> = try {
        Result.success(withContext(io) { call() })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
}
