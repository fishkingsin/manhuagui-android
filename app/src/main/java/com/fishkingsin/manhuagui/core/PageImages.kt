package com.fishkingsin.manhuagui.core

import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import uniffi.manhuagui_ffi.ImagePage

object PageImages {
    fun headersFor(page: ImagePage): Map<String, String> = page.headers

    fun modelFor(page: ImagePage): ImageRequest.Builder.() -> Unit = {
        httpHeaders(
            NetworkHeaders.Builder().apply {
                headersFor(page).forEach { (name, value) -> set(name, value) }
            }.build(),
        )
        memoryCacheKey(page.url)
    }
}
