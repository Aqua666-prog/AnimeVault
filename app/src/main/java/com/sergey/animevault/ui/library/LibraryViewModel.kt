package com.sergey.animevault.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sergey.animevault.data.model.LibraryTitleRow
import com.sergey.animevault.data.repository.LibraryRepository
import com.sergey.animevault.util.runCatchingCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class LibrarySort {
    Alphabetical,
    DateAdded,
    LastWatched,
}

enum class SmartCollection {
    All,
    InProgress,
    Unwatched,
    Completed,
    LinkedOnline,
}

internal fun applySmartCollection(
    titles: List<LibraryTitleRow>,
    collection: SmartCollection,
): List<LibraryTitleRow> = when (collection) {
    SmartCollection.All -> titles
    SmartCollection.InProgress -> titles.filter {
        it.episodeCount > 0L && it.completedCount < it.episodeCount && it.lastWatchedAt != null
    }
    SmartCollection.Unwatched -> titles.filter {
        it.completedCount == 0L && it.lastWatchedAt == null
    }
    SmartCollection.Completed -> titles.filter {
        it.episodeCount > 0L && it.completedCount >= it.episodeCount
    }
    SmartCollection.LinkedOnline -> titles.filter { it.onlineLinkCount > 0L }
}

sealed interface ScanUiState {
    data object Idle : ScanUiState
    data class Scanning(
        val folderName: String,
        val visitedDocuments: Int,
        val videosFound: Int,
    ) : ScanUiState
    data class Finished(
        val videosFound: Int,
        val titlesFound: Int,
        val warningCount: Int,
    ) : ScanUiState
    data class Error(val message: String) : ScanUiState
}

data class LibraryUiState(
    val titles: List<LibraryTitleRow> = emptyList(),
    val query: String = "",
    val sort: LibrarySort = LibrarySort.Alphabetical,
    val collection: SmartCollection = SmartCollection.All,
    val scan: ScanUiState = ScanUiState.Idle,
)

class LibraryViewModel(
    private val repository: LibraryRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val sort = MutableStateFlow(LibrarySort.Alphabetical)
    private val collection = MutableStateFlow(SmartCollection.All)
    private val scan = MutableStateFlow<ScanUiState>(ScanUiState.Idle)

    val uiState: StateFlow<LibraryUiState> = combine(
        repository.observeLibrary(),
        query,
        sort,
        collection,
        scan,
    ) { titles, currentQuery, currentSort, currentCollection, currentScan ->
        val collected = applySmartCollection(titles, currentCollection)
        val filtered = collected.filter { it.name.contains(currentQuery, ignoreCase = true) }
        val sorted = when (currentSort) {
            LibrarySort.Alphabetical -> filtered.sortedBy { it.name.lowercase() }
            LibrarySort.DateAdded -> filtered.sortedByDescending { it.dateAdded }
            LibrarySort.LastWatched -> filtered.sortedWith(
                compareByDescending<LibraryTitleRow> { it.lastWatchedAt != null }
                    .thenByDescending { it.lastWatchedAt ?: Long.MIN_VALUE }
                    .thenBy { it.name.lowercase() },
            )
        }
        LibraryUiState(
            titles = sorted,
            query = currentQuery,
            sort = currentSort,
            collection = currentCollection,
            scan = currentScan,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LibraryUiState(),
    )

    fun setQuery(value: String) {
        query.value = value
    }

    fun setSort(value: LibrarySort) {
        sort.value = value
    }

    fun setCollection(value: SmartCollection) {
        collection.value = value
    }

    fun addFolder(uri: Uri) {
        viewModelScope.launch {
            scan.value = ScanUiState.Scanning("Выбранная папка", 0, 0)
            runCatchingCancellable {
                repository.addFolderAndScan(uri) { progress ->
                    scan.value = ScanUiState.Scanning(
                        folderName = "Выбранная папка",
                        visitedDocuments = progress.visitedDocuments,
                        videosFound = progress.videosFound,
                    )
                }
            }.onSuccess { result ->
                scan.value = ScanUiState.Finished(
                    videosFound = result.videosFound,
                    titlesFound = result.titles.size,
                    warningCount = result.warnings.size,
                )
            }.onFailure { error ->
                scan.value = ScanUiState.Error(error.toUserMessage())
            }
        }
    }

    fun rescanAll() {
        viewModelScope.launch {
            runCatchingCancellable {
                repository.scanAllFolders(
                    onFolderStarted = { folder ->
                        scan.value = ScanUiState.Scanning(folder.displayName, 0, 0)
                    },
                    onProgress = { progress ->
                        val currentName = (scan.value as? ScanUiState.Scanning)?.folderName.orEmpty()
                        scan.value = ScanUiState.Scanning(
                            currentName,
                            progress.visitedDocuments,
                            progress.videosFound,
                        )
                    },
                )
            }.onSuccess { results ->
                scan.value = ScanUiState.Finished(
                    videosFound = results.sumOf { it.videosFound },
                    titlesFound = results.sumOf { it.titles.size },
                    warningCount = results.sumOf { it.warnings.size },
                )
            }.onFailure { error ->
                scan.value = ScanUiState.Error(error.toUserMessage())
            }
        }
    }

    fun dismissScanMessage() {
        if (scan.value !is ScanUiState.Scanning) scan.value = ScanUiState.Idle
    }

    private fun Throwable.toUserMessage(): String = when (this) {
        is SecurityException -> "Android не предоставил постоянный доступ к папке. Выберите её ещё раз."
        is ExceptionInInitializerError, is NoClassDefFoundError ->
            "Не удалось запустить анализатор имён файлов. Обновите AnimeVault и повторите сканирование."
        else -> message ?: "Не удалось просканировать папку"
    }

    class Factory(
        private val repository: LibraryRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LibraryViewModel(repository) as T
    }
}
