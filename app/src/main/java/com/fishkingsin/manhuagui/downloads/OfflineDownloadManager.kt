package com.fishkingsin.manhuagui.downloads

import com.fishkingsin.manhuagui.core.LibraryService
import com.fishkingsin.manhuagui.data.DownloadedChapterRecord
import com.fishkingsin.manhuagui.data.LocalStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uniffi.manhuagui_ffi.AndroidDownloadObserver
import uniffi.manhuagui_ffi.DownloadedChapter

data class ChapterDownloadRequest(
    val comicId: Long,
    val chapterId: Long,
)

enum class ChapterDownloadStatus {
    QUEUED,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class ChapterDownloadProgress(
    val comicId: Long,
    val chapterId: Long,
    val status: ChapterDownloadStatus,
    val downloadedPages: Int = 0,
    val totalPages: Int = 0,
)

fun interface ServiceController {
    fun onQueueStarted()

    fun onQueueDrained() = Unit
}

class OfflineDownloadManager(
    private val service: LibraryService,
    private val localStore: LocalStore,
    private val downloadRoot: String,
    io: CoroutineDispatcher = Dispatchers.IO,
    val serviceController: ServiceController,
) {
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + io)
    private val lock = Any()
    private val queue = MutableStateFlow<List<ChapterDownloadRequest>>(emptyList())
    private val active = MutableStateFlow<Set<Long>>(emptySet())
    private val mutableProgress = MutableStateFlow<Map<Long, ChapterDownloadProgress>>(emptyMap())
    private var consumer: Job? = null
    private var queueStarted = false

    val progress: StateFlow<Map<Long, ChapterDownloadProgress>> = mutableProgress.asStateFlow()

    suspend fun reconcileOnStartup() {
        val records = service.scanDownloadedChapters(downloadRoot).getOrThrow().map { it.toRecord() }
        localStore.reconcileDownloadedChapters(records)
    }

    fun enqueue(requests: List<ChapterDownloadRequest>) {
        synchronized(lock) {
            val pending = queue.value.mapTo(mutableSetOf()) { it.chapterId } + active.value
            val additions = requests.distinctBy { it.chapterId }.filterNot { it.chapterId in pending }
            if (additions.isEmpty()) return

            queue.value = queue.value + additions
            mutableProgress.update { current ->
                current + additions.associate { request ->
                    request.chapterId to ChapterDownloadProgress(
                        comicId = request.comicId,
                        chapterId = request.chapterId,
                        status = ChapterDownloadStatus.QUEUED,
                    )
                }
            }
            if (!queueStarted) {
                queueStarted = true
                serviceController.onQueueStarted()
            }
            if (consumer?.isActive != true) {
                consumer = scope.launch { consumeQueue() }
            }
        }
    }

    fun cancel(chapterId: Long) {
        synchronized(lock) {
            val pending = chapterId in active.value || queue.value.any { it.chapterId == chapterId }
            queue.value = queue.value.filterNot { it.chapterId == chapterId }
            if (pending) {
                mutableProgress.update { current ->
                    current[chapterId]?.let { current + (chapterId to it.copy(status = ChapterDownloadStatus.CANCELLED)) }
                        ?: current
                }
            }
        }
        cancelInService(chapterId)
    }

    fun cancelAllActive() {
        val chapterIds = synchronized(lock) {
            val ids = queue.value.mapTo(mutableSetOf()) { it.chapterId } + active.value
            queue.value = emptyList()
            active.value = emptySet()
            mutableProgress.update { current ->
                ids.fold(current) { updated, chapterId ->
                    updated[chapterId]?.let {
                        updated + (chapterId to it.copy(status = ChapterDownloadStatus.CANCELLED))
                    } ?: updated
                }
            }
            queueStarted = false
            serviceController.onQueueDrained()
            ids
        }
        chapterIds.forEach(::cancelInService)
    }

    suspend fun deleteChapter(comicId: Long, chapterId: Long) {
        service.deleteDownloadedChapter(comicId, chapterId, downloadRoot).getOrThrow()
        localStore.deleteDownloadedChapter(comicId, chapterId)
    }

    suspend fun deleteComic(comicId: Long) {
        localStore.getDownloadedChapters(comicId).forEach { record ->
            service.deleteDownloadedChapter(comicId, record.chapterId, downloadRoot).getOrThrow()
            localStore.deleteDownloadedChapter(comicId, record.chapterId)
        }
    }

    fun release() {
        scope.cancel()
    }

    private suspend fun consumeQueue() {
        val currentJob = currentCoroutineContext()[Job] ?: return
        try {
            while (true) {
                val request = synchronized(lock) {
                    queue.value.firstOrNull()?.also { next ->
                        queue.value = queue.value.drop(1)
                        active.update { it + next.chapterId }
                    }
                } ?: break
                process(request)
            }
        } finally {
            synchronized(lock) {
                if (consumer === currentJob) {
                    consumer = null
                    if (queue.value.isNotEmpty() && scope.isActive) {
                        consumer = scope.launch { consumeQueue() }
                    } else if (queue.value.isEmpty() && active.value.isEmpty() && queueStarted) {
                        queueStarted = false
                        serviceController.onQueueDrained()
                    }
                }
            }
        }
    }

    private suspend fun process(request: ChapterDownloadRequest) {
        updateProgress(request) { it.copy(status = ChapterDownloadStatus.DOWNLOADING) }
        try {
            val observer = object : AndroidDownloadObserver {
                override fun onProgress(
                    comicId: Long,
                    chapterId: Long,
                    downloadedPages: UInt,
                    totalPages: UInt,
                ) {
                    mutableProgress.update { current ->
                        val existing = current[request.chapterId] ?: return@update current
                        if (existing.status == ChapterDownloadStatus.CANCELLED) {
                            current
                        } else {
                            current + (request.chapterId to existing.copy(
                                status = ChapterDownloadStatus.DOWNLOADING,
                                downloadedPages = downloadedPages.toInt(),
                                totalPages = totalPages.toInt(),
                            ))
                        }
                    }
                }
            }
            val downloaded = service.downloadChapter(
                request.comicId,
                request.chapterId,
                downloadRoot,
                observer,
            ).getOrThrow()
            localStore.upsertDownloadedChapter(downloaded.toRecord())
            updateProgress(request) { it.copy(status = ChapterDownloadStatus.COMPLETED) }
        } catch (cancelled: CancellationException) {
            updateProgress(request) { it.copy(status = ChapterDownloadStatus.CANCELLED) }
            throw cancelled
        } catch (failure: Exception) {
            mutableProgress.update { current ->
                val existing = current[request.chapterId] ?: return@update current
                if (existing.status == ChapterDownloadStatus.CANCELLED) {
                    current
                } else {
                    current + (request.chapterId to existing.copy(status = ChapterDownloadStatus.FAILED))
                }
            }
        } finally {
            active.update { it - request.chapterId }
        }
    }

    private fun updateProgress(
        request: ChapterDownloadRequest,
        transform: (ChapterDownloadProgress) -> ChapterDownloadProgress,
    ) {
        mutableProgress.update { current ->
            val existing = current[request.chapterId] ?: ChapterDownloadProgress(
                comicId = request.comicId,
                chapterId = request.chapterId,
                status = ChapterDownloadStatus.QUEUED,
            )
            current + (request.chapterId to transform(existing))
        }
    }

    private fun cancelInService(chapterId: Long) {
        scope.launch { service.cancelDownload(chapterId) }
    }

    private fun DownloadedChapter.toRecord() = DownloadedChapterRecord(
        comicId = comicId,
        comicTitle = comicTitle,
        chapterId = chapterId,
        chapterTitle = chapterTitle,
        groupName = groupName,
        order = order,
        pageCount = pageCount,
        chapterDir = chapterDir,
        downloadedAtEpochMs = System.currentTimeMillis(),
    )
}
