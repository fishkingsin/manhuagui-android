package com.fishkingsin.manhuagui.core

import android.content.ContextWrapper
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.manhuagui_ffi.ImagePage

class PageImagesTest {
    @Test
    fun headersForPreservesThePageReferer() {
        val page = ImagePage(
            url = "https://img.example/page.jpg",
            headers = mapOf("Referer" to "https://www.example/reader/"),
        )

        assertEquals(mapOf("Referer" to "https://www.example/reader/"), PageImages.headersFor(page))
    }

    @Test
    fun modelForUsesThePageUrlAsMemoryCacheKey() {
        val page = ImagePage(
            url = "https://img.example/page.jpg",
            headers = mapOf(
                "Referer" to "https://www.example/reader/",
                "X-Image-Token" to "chapter-token",
            ),
        )
        val request = ImageRequest.Builder(ContextWrapper(null))
            .data(page.url)
            .apply(PageImages.modelFor(page))
            .build()

        assertEquals("https://img.example/page.jpg", request.memoryCacheKey)
        assertEquals("https://www.example/reader/", request.httpHeaders["Referer"])
        assertEquals("chapter-token", request.httpHeaders["X-Image-Token"])
    }
}
