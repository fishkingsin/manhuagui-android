package com.fishkingsin.manhuagui.ui.bookmarks

import com.fishkingsin.manhuagui.bookmarks.BookmarkUpdateChecker
import com.fishkingsin.manhuagui.data.BookmarkRecord
import com.fishkingsin.manhuagui.data.LocalStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BookmarksUiState(
    val items: List<BookmarkRecord> = emptyList(),
    val checking: Boolean = false,
    val lastUpdatedCount: Int? = null,
)

class BookmarksViewModel(
    private val localStore: LocalStore,
    private val checker: BookmarkUpdateChecker,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val mutableState = MutableStateFlow(BookmarksUiState())
    val state: StateFlow<BookmarksUiState> = combine(
        localStore.observeBookmarks(),
        mutableState,
    ) { items, transientState ->
        transientState.copy(items = items)
    }.stateIn(scope, SharingStarted.Eagerly, BookmarksUiState())

    fun checkUpdatesNow() {
        if (state.value.checking) return

        mutableState.update { it.copy(checking = true) }
        scope.launch {
            try {
                val updatedCount = checker.checkAll().size
                mutableState.update {
                    it.copy(checking = false, lastUpdatedCount = updatedCount)
                }
            } catch (cancellation: CancellationException) {
                mutableState.update { it.copy(checking = false) }
                throw cancellation
            } catch (_: Exception) {
                mutableState.update { it.copy(checking = false) }
            }
        }
    }

    fun removeBookmark(comicId: Long) {
        scope.launch { localStore.deleteBookmark(comicId) }
    }
}
