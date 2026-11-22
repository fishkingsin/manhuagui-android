package com.fishkingsin.manhuagui.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.manhuagui_ffi.AndroidLibrary
import uniffi.manhuagui_ffi.AndroidUpdateWindow
import uniffi.manhuagui_ffi.BrowsePage
import uniffi.manhuagui_ffi.ChapterPagesResult
import uniffi.manhuagui_ffi.ComicDetails
import uniffi.manhuagui_ffi.ComicListItem
import uniffi.manhuagui_ffi.SearchPage

class LibraryService(
    private val library: AndroidLibrary,
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

    private suspend fun <T> callFfi(call: suspend () -> T): Result<T> = try {
        Result.success(withContext(io) { call() })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
}
