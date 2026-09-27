package dev.zapstore.app.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.zapstore.app.catalog.AvailableUpdate
import dev.zapstore.app.catalog.CatalogSync
import dev.zapstore.app.catalog.SyncStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class UpdatesUiState(
    val status: SyncStatus = SyncStatus(),
    val updates: List<AvailableUpdate> = emptyList(),
    val loaded: Boolean = false,
)

class UpdatesViewModel(private val catalogSync: CatalogSync) : ViewModel() {
    val uiState: StateFlow<UpdatesUiState> = combine(catalogSync.status, catalogSync.updates) { status, updates ->
        UpdatesUiState(status, updates, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UpdatesUiState())

    fun sync() {
        viewModelScope.launch { catalogSync.sync() }
    }

    companion object {
        fun factory(catalogSync: CatalogSync): ViewModelProvider.Factory = viewModelFactory {
            initializer { UpdatesViewModel(catalogSync) }
        }
    }
}
