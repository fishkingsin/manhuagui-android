package com.fishkingsin.manhuagui.ui.reader

import android.content.Context
import coil3.ImageLoader
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.size.Size
import com.fishkingsin.manhuagui.core.PageImages
import uniffi.manhuagui_ffi.ImagePage

const val DEFAULT_AHEAD_COUNT = 3

fun prefetchIndices(
    currentPage: Int,
    totalPages: Int,
    directionDelta: Int = 1,
    count: Int = DEFAULT_AHEAD_COUNT,
): List<Int> {
    if (totalPages <= 0 || count <= 0) return emptyList()

    val step = if (directionDelta < 0) -1 else 1
    return (1..count)
        .map { currentPage + it * step }
        .filter { it in 0 until totalPages }
}

fun evictIndices(currentPage: Int, totalPages: Int, keepRadius: Int = 2): List<Int> {
    if (totalPages <= 0) return emptyList()

    return (0 until totalPages).filter { kotlin.math.abs(it - currentPage) > keepRadius }
}

fun evictUrlFromMemoryCache(memoryCache: MemoryCache?, url: String) {
    if (memoryCache == null) return

    memoryCache.remove(MemoryCache.Key(url))
    memoryCache.keys
        .filter { it.key == url || it.key.startsWith("$url#retry=") }
        .forEach(memoryCache::remove)
}

class PagePrefetcher(
    private val enqueuePage: (ImagePage) -> Unit,
    private val evictUrl: (String) -> Unit,
) {
    fun onPageTurn(
        pages: List<ImagePage>,
        currentPage: Int,
        directionDelta: Int = 1,
        aheadCount: Int = DEFAULT_AHEAD_COUNT,
    ) {
        if (pages.isEmpty() || currentPage !in pages.indices) return

        for (index in prefetchIndices(currentPage, pages.size, directionDelta, aheadCount)) {
            val page = pages[index]
            if (!page.url.startsWith("file:")) enqueuePage(page)
        }
        for (index in evictIndices(currentPage, pages.size, 2)) {
            evictUrl(pages[index].url)
        }
    }

    companion object {
        fun create(context: Context, imageLoader: ImageLoader): PagePrefetcher = PagePrefetcher(
            enqueuePage = { page ->
                imageLoader.enqueue(
                    ImageRequest.Builder(context)
                        .data(page.url)
                        .size(Size.ORIGINAL)
                        .apply(PageImages.modelFor(page))
                        .build(),
                )
            },
            evictUrl = { url ->
                evictUrlFromMemoryCache(imageLoader.memoryCache, url)
            },
        )
    }
}
