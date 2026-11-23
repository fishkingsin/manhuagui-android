package com.fishkingsin.manhuagui.ui.latest

import com.fishkingsin.manhuagui.core.AppError
import com.fishkingsin.manhuagui.core.LibraryService
import com.fishkingsin.manhuagui.core.toAppError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uniffi.manhuagui_ffi.AndroidUpdateWindow
import uniffi.manhuagui_ffi.ComicListItem

data class LatestUiState(
    val window: AndroidUpdateWindow = AndroidUpdateWindow.DAYS7,
    val loading: Boolean = false,
    val items: List<ComicListItem> = emptyList(),
    val error: AppError? = null,
    val nextPage: Int = 1,
    val totalPages: Int = 0,
    val endReached: Boolean = false,
)

class LatestViewModel(
    private val service: LibraryService,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val mutableState = MutableStateFlow(LatestUiState())
    val state = mutableState.asStateFlow()

    private var requestJob: Job? = null

    fun refresh() {
        if (mutableState.value.loading) return

        val window = mutableState.value.window
        mutableState.value = LatestUiState(window = window, loading = true)
        requestJob = scope.launch {
            when (window) {
                AndroidUpdateWindow.ALL -> service.browseLatestPage(1).fold(
                    onSuccess = { page ->
                        mutableState.value = mutableState.value.copy(
                            loading = false,
                            items = page.items,
                            nextPage = (page.page + 1).toInt(),
                            totalPages = page.totalPages.toInt(),
                            endReached = page.page >= page.totalPages,
                        )
                    },
                    onFailure = ::showRefreshError,
                )

                else -> service.browseWithin(window, 1, 10).fold(
                    onSuccess = { items ->
                        mutableState.value = mutableState.value.copy(
                            loading = false,
                            items = items,
                            endReached = true,
                        )
                    },
                    onFailure = ::showRefreshError,
                )
            }
        }
    }

    fun setWindow(window: AndroidUpdateWindow) {
        requestJob?.cancel()
        mutableState.value = LatestUiState(window = window)
        refresh()
    }

    fun loadMore() {
        val current = mutableState.value
        if (current.window != AndroidUpdateWindow.ALL || current.loading || current.endReached) return

        mutableState.value = current.copy(loading = true, error = null)
        requestJob = scope.launch {
            service.browseLatestPage(current.nextPage).fold(
                onSuccess = { page ->
                    mutableState.value = mutableState.value.copy(
                        loading = false,
                        items = mutableState.value.items + page.items,
                        nextPage = (page.page + 1).toInt(),
                        totalPages = page.totalPages.toInt(),
                        endReached = page.page >= page.totalPages,
                    )
                },
                onFailure = { throwable ->
                    mutableState.value = mutableState.value.copy(
                        loading = false,
                        error = throwable.toAppError(),
                    )
                },
            )
        }
    }

    private fun showRefreshError(throwable: Throwable) {
        mutableState.value = mutableState.value.copy(
            loading = false,
            items = emptyList(),
            error = throwable.toAppError(),
        )
    }
}
