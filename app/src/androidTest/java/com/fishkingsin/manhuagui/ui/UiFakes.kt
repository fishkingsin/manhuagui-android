package com.fishkingsin.manhuagui.ui

import android.content.Context
import coil3.ImageLoader
import com.fishkingsin.manhuagui.core.LibraryService
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
import uniffi.manhuagui_ffi.ImagePage
import uniffi.manhuagui_ffi.LocalChapterNavigation
import uniffi.manhuagui_ffi.SearchPage

class FakeLibrary : AndroidLibraryInterface {
    var onBrowseWithin: suspend (AndroidUpdateWindow, Int, Int) -> List<ComicListItem> = { _, _, _ ->
        error("browseWithin not stubbed")
    }

    var onBrowseLatestPage: suspend (Int) -> BrowsePage = { error("browseLatestPage not stubbed") }

    var onSearch: suspend (String, Int) -> SearchPage = { _, _ -> error("search not stubbed") }

    var onGetComic: suspend (Long) -> ComicDetails = { error("getComic not stubbed") }

    var onGetChapterPages: suspend (Long, Long) -> ChapterPagesResult = { _, _ ->
        error("getChapterPages not stubbed")
    }

    override suspend fun browseWithin(
        window: AndroidUpdateWindow,
        startPage: Int,
        maxPages: Int,
    ): List<ComicListItem> = onBrowseWithin(window, startPage, maxPages)

    override suspend fun browseLatestPage(page: Int): BrowsePage = onBrowseLatestPage(page)

    override suspend fun search(keyword: String, page: Int): SearchPage = onSearch(keyword, page)

    override suspend fun getComic(nativeId: Long): ComicDetails = onGetComic(nativeId)

    override suspend fun getChapterPages(nativeId: Long, chapterId: Long): ChapterPagesResult =
        onGetChapterPages(nativeId, chapterId)

    override fun cancelDownload(chapterId: Long) = Unit

    override suspend fun downloadChapter(
        nativeId: Long,
        chapterId: Long,
        downloadRoot: String,
        observer: AndroidDownloadObserver,
    ): DownloadedChapter = error("downloadChapter not stubbed")

    override suspend fun deleteDownloadedChapter(
        nativeId: Long,
        chapterId: Long,
        downloadRoot: String,
    ) = Unit

    override suspend fun scanDownloadedChapters(downloadRoot: String): List<DownloadedChapter> =
        error("scanDownloadedChapters not stubbed")

    override suspend fun readLocalChapterNavigation(
        nativeId: Long,
        chapterId: Long,
        downloadRoot: String,
    ): LocalChapterNavigation = error("readLocalChapterNavigation not stubbed")

    override suspend fun exportChapters(
        nativeId: Long,
        chapterIds: List<Long>,
        downloadRoot: String,
        exportRoot: String,
        format: AndroidExportFormat,
    ): List<ExportedChapterFile> = error("exportChapters not stubbed")

    fun service(): LibraryService = LibraryService(this)
}

fun testImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context).build()

fun comicListItem(
    id: Long,
    title: String,
    status: String = "连载中",
    updateText: String? = "更新于 2026-01-01",
): ComicListItem = ComicListItem(
    comicId = id,
    title = title,
    coverUrl = "https://example.invalid/$id.jpg",
    statusText = status,
    updateText = updateText,
    rating = 4.5f,
)

fun chapterItem(
    id: Long,
    title: String,
    groupName: String = "单话",
): uniffi.manhuagui_ffi.ChapterItem = uniffi.manhuagui_ffi.ChapterItem(
    chapterId = id,
    chapterTitle = title,
    chapterSize = 20,
    groupName = groupName,
    groupSize = 3,
    order = id.toDouble(),
)

fun comicDetails(
    id: Long,
    title: String,
    chapters: List<uniffi.manhuagui_ffi.ChapterItem>,
): ComicDetails = ComicDetails(
    comicId = id,
    title = title,
    subtitle = null,
    coverUrl = "https://example.invalid/$id.jpg",
    statusText = "连载中",
    updateText = "更新于 2026-01-01",
    year = 2026,
    region = "日本",
    genres = listOf("冒险"),
    authors = listOf("作者"),
    aliases = emptyList(),
    intro = "简介",
    chapters = chapters,
)
