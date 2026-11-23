package com.fishkingsin.manhuagui.ui.search

import com.fishkingsin.manhuagui.core.AppError
import com.fishkingsin.manhuagui.core.LibraryService
import com.fishkingsin.manhuagui.core.toAppError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uniffi.manhuagui_ffi.SearchPage

data class SearchUiState(
    val query: String = "",
    val results: SearchPage? = null,
    val loading: Boolean = false,
    val error: AppError? = null,
)

class SearchViewModel(
    private val service: LibraryService,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val mutableState = MutableStateFlow(SearchUiState())
    val state = mutableState.asStateFlow()

    fun setQuery(query: String) {
        mutableState.value = mutableState.value.copy(query = query)
    }

    fun submit() {
        val current = mutableState.value
        if (current.query.isBlank() || current.loading) return

        mutableState.value = current.copy(loading = true, error = null, results = null)
        scope.launch {
            service.search(current.query.trim(), 1).fold(
                onSuccess = { page ->
                    mutableState.value = mutableState.value.copy(loading = false, results = page)
                },
                onFailure = { throwable ->
                    mutableState.value = mutableState.value.copy(
                        loading = false,
                        results = null,
                        error = throwable.toAppError(),
                    )
                },
            )
        }
    }
}
