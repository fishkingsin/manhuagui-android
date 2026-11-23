package com.fishkingsin.manhuagui

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.fishkingsin.manhuagui.core.LibraryService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okio.Path.Companion.toOkioPath
import uniffi.manhuagui_ffi.AndroidLibrary

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(applicationContext)
    }
}

class AppContainer(
    context: Context,
    io: CoroutineDispatcher = Dispatchers.IO,
) {
    val library = AndroidLibrary()
    val libraryService = LibraryService(library, io)
    val imageLoader = ImageLoader.Builder(context)
        .components {
            add(OkHttpNetworkFetcherFactory())
        }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("image_cache").toOkioPath())
                .maxSizeBytes(512L * 1024 * 1024)
                .build()
        }
        .memoryCache {
            MemoryCache.Builder()
                .maxSizePercent(context, 0.25)
                .build()
        }
        .crossfade(false)
        .build()
}
