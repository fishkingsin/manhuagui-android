package com.fishkingsin.manhuagui.ui.reader

import android.content.Context
import coil3.ImageLoader
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.size.Size
import com.fishkingsin.manhuagui.core.PageImages
import uniffi.manhuagui_ffi.ImagePage

fun prefetchIndices(
    currentPage: Int,
    totalPages: Int,
    directionDelta: Int = 1,
    count: Int = 3,
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

class PagePrefetcher(
    private val enqueuePage: (ImagePage) -> Unit,
    private val evictUrl: (String) -> Unit,
) {
    fun onPageTurn(pages: List<ImagePage>, currentPage: Int, directionDelta: Int = 1) {
        if (pages.isEmpty() || currentPage !in pages.indices) return

        for (index in prefetchIndices(currentPage, pages.size, directionDelta, 3)) {
            enqueuePage(pages[index])
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
                imageLoader.memoryCache?.remove(MemoryCache.Key(url))
            },
        )
    }
}
