package com.fishkingsin.manhuagui

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.fishkingsin.manhuagui.bookmarks.BookmarkUpdateChecker
import com.fishkingsin.manhuagui.bookmarks.BookmarkUpdateNotification
import com.fishkingsin.manhuagui.bookmarks.BookmarkUpdateWorker
import com.fishkingsin.manhuagui.core.LibraryService
import com.fishkingsin.manhuagui.data.AppDatabase
import com.fishkingsin.manhuagui.data.LocalStore
import com.fishkingsin.manhuagui.data.RoomLocalStore
import com.fishkingsin.manhuagui.downloads.DownloadForegroundService
import com.fishkingsin.manhuagui.downloads.OfflineDownloadManager
import com.fishkingsin.manhuagui.downloads.ServiceController
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
        BookmarkUpdateNotification.ensureChannel(this)
        BookmarkUpdateWorker.schedulePeriodic(this)
    }
}

class AppContainer(
    context: Context,
    io: CoroutineDispatcher = Dispatchers.IO,
) {
    val library = AndroidLibrary()
    val libraryService = LibraryService(library, io)
    val localStore: LocalStore = RoomLocalStore(AppDatabase.build(context), io)
    val downloadManager = OfflineDownloadManager(
        service = libraryService,
        localStore = localStore,
        downloadRoot = context.filesDir.resolve("downloaded").absolutePath,
        io = io,
        serviceController = object : ServiceController {
            override fun onQueueStarted() = DownloadForegroundService.start(context)

            override fun onQueueDrained() = DownloadForegroundService.stop(context)
        },
    )
    val bookmarkUpdateChecker = BookmarkUpdateChecker(localStore, libraryService)
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
