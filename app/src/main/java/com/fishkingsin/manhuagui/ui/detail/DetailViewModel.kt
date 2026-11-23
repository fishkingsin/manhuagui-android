package com.fishkingsin.manhuagui.ui.detail

import com.fishkingsin.manhuagui.core.AppError
import com.fishkingsin.manhuagui.core.LibraryService
import com.fishkingsin.manhuagui.core.toAppError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uniffi.manhuagui_ffi.ChapterItem
import uniffi.manhuagui_ffi.ComicDetails

data class DetailUiState(
    val comic: ComicDetails? = null,
    val chapters: List<ChapterItem> = emptyList(),
    val loading: Boolean = false,
    val error: AppError? = null,
)

class DetailViewModel(
    private val nativeId: Long,
    private val service: LibraryService,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val mutableState = MutableStateFlow(DetailUiState())
    val state = mutableState.asStateFlow()

    fun load() {
        if (mutableState.value.loading) return

        mutableState.value = mutableState.value.copy(loading = true, error = null)
        scope.launch {
            service.getComic(nativeId).fold(
                onSuccess = { comic ->
                    mutableState.value = mutableState.value.copy(
                        loading = false,
                        comic = comic,
                        chapters = comic.chapters.sortedWith(
                            compareByDescending<ChapterItem> { it.groupSize }
                                .thenBy { it.groupName }
                                .thenByDescending { it.order },
                        ),
                        error = null,
                    )
                },
                onFailure = { throwable ->
                    mutableState.value = mutableState.value.copy(
                        loading = false,
                        comic = null,
                        chapters = emptyList(),
                        error = throwable.toAppError(),
                    )
                },
            )
        }
    }
}
