package com.fishkingsin.manhuagui.ui.reader

import coil3.memory.MemoryCache
import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.manhuagui_ffi.ImagePage

class PagePrefetcherTest {
    @Test
    fun prefetchesPagesAheadInEitherDirection() {
        assertEquals(listOf(5, 6, 7), prefetchIndices(currentPage = 4, totalPages = 10))
        assertEquals(listOf(3, 2, 1), prefetchIndices(currentPage = 4, totalPages = 10, directionDelta = -1))
    }

    @Test
    fun prefetchWindowStopsAtChapterBoundaries() {
        assertEquals(listOf(1, 2), prefetchIndices(currentPage = 0, totalPages = 3))
        assertEquals(emptyList<Int>(), prefetchIndices(currentPage = 0, totalPages = 3, directionDelta = -1))
        assertEquals(emptyList<Int>(), prefetchIndices(currentPage = 4, totalPages = 5))
        assertEquals(listOf(3, 2, 1), prefetchIndices(currentPage = 4, totalPages = 5, directionDelta = -1))
        assertEquals(emptyList<Int>(), prefetchIndices(currentPage = 0, totalPages = 3, count = 0))
        assertEquals(emptyList<Int>(), prefetchIndices(currentPage = 0, totalPages = 0))
    }

    @Test
    fun evictsPagesOutsideTheKeepRadius() {
        assertEquals(listOf(0, 1, 7, 8, 9), evictIndices(currentPage = 4, totalPages = 10))
        assertEquals(listOf(3, 4), evictIndices(currentPage = 0, totalPages = 5))
        assertEquals(emptyList<Int>(), evictIndices(currentPage = 0, totalPages = 0))
    }

    @Test
    fun pageTurnEnqueuesAheadAndEvictsPagesOutsideTheRadius() {
        val pages = (0..7).map(::imagePage)
        val enqueued = mutableListOf<ImagePage>()
        val evicted = mutableListOf<String>()
        val prefetcher = PagePrefetcher({ enqueued.add(it) }, { evicted.add(it) })

        prefetcher.onPageTurn(pages, currentPage = 4, directionDelta = -1)

        assertEquals(listOf(pages[3], pages[2], pages[1]), enqueued)
        assertEquals(listOf(pages[0].url, pages[1].url, pages[7].url), evicted)
    }

    @Test
    fun evictsCanonicalAndRetryKeysButKeepsOtherPageKeys() {
        val url = "https://example.test/1.jpg"
        val otherUrl = "https://example.test/2.jpg"
        val memoryCache = FakeMemoryCache(
            setOf(
                MemoryCache.Key(url),
                MemoryCache.Key("$url#retry=1"),
                MemoryCache.Key("$url#retry=2"),
                MemoryCache.Key(otherUrl),
            ),
        )

        evictUrlFromMemoryCache(memoryCache, url)

        assertEquals(setOf(MemoryCache.Key(otherUrl)), memoryCache.keys)
    }

    @Test
    fun invalidCurrentPageDoesNotEnqueueOrEvict() {
        val enqueued = mutableListOf<ImagePage>()
        val evicted = mutableListOf<String>()
        val prefetcher = PagePrefetcher({ enqueued.add(it) }, { evicted.add(it) })

        prefetcher.onPageTurn(listOf(imagePage(0)), currentPage = 1)
        prefetcher.onPageTurn(emptyList(), currentPage = 0)

        assertEquals(emptyList<ImagePage>(), enqueued)
        assertEquals(emptyList<String>(), evicted)
    }

    private fun imagePage(index: Int) = ImagePage(
        url = "https://example.test/$index.jpg",
        headers = mapOf("Referer" to "https://example.test/"),
    )

    @OptIn(coil3.annotation.ExperimentalCoilApi::class)
    private class FakeMemoryCache(keys: Set<MemoryCache.Key>) : MemoryCache {
        private val cachedKeys = keys.toMutableSet()

        override val size: Long = 0
        override var maxSize: Long = 0
        override val initialMaxSize: Long = 0
        override val keys: Set<MemoryCache.Key>
            get() = cachedKeys.toSet()

        override fun get(key: MemoryCache.Key): MemoryCache.Value? = null

        override fun set(key: MemoryCache.Key, value: MemoryCache.Value) {
            cachedKeys.add(key)
        }

        override fun remove(key: MemoryCache.Key): Boolean = cachedKeys.remove(key)

        override fun trimToSize(size: Long) = Unit

        override fun clear() {
            cachedKeys.clear()
        }
    }
}
