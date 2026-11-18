package com.euromillones.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.euromillones.app.data.Draw
import com.euromillones.app.data.DrawRepository
import com.euromillones.app.data.Gordo
import com.euromillones.app.data.NavidadRepository
import com.euromillones.app.data.SearchQuery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val draws: List<Draw> = emptyList(),
    val loading: Boolean = false,
    val progress: String? = null,
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = DrawRepository(app)
    private val navRepo = NavidadRepository(app)

    // Gordos de la Lotería de Navidad
    private val _gordos = MutableStateFlow<List<Gordo>>(emptyList())
    val gordos: StateFlow<List<Gordo>> = _gordos.asStateFlow()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    // El estado de la búsqueda vive aquí para no perderse al cambiar de pestaña
    private val _query = MutableStateFlow(SearchQuery())
    val query: StateFlow<SearchQuery> = _query.asStateFlow()

    init {
        viewModelScope.launch {
            val cached = repo.loadCache()
            _state.update { it.copy(draws = cached) }
            _gordos.value = navRepo.load()
            refresh()
        }
    }

    fun refresh() {
        if (_state.value.loading) return
        viewModelScope.launch {
            runCatching { navRepo.refresh() }.onSuccess { _gordos.value = it }
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val all = repo.refresh { p ->
                    _state.update { s -> s.copy(progress = p) }
                }
                _state.update { it.copy(draws = all, loading = false, progress = null) }
            } catch (e: Exception) {
                // Si falla, recargamos lo que se haya guardado hasta ese momento
                val cached = repo.loadCache()
                _state.update {
                    it.copy(
                        draws = cached.ifEmpty { it.draws },
                        loading = false,
                        progress = null,
                        error = "No se pudo actualizar (${e.message ?: "sin conexión"})",
                    )
                }
            }
        }
    }

    fun updateQuery(transform: (SearchQuery) -> SearchQuery) = _query.update(transform)

    fun clearQuery() {
        _query.value = SearchQuery()
    }
}
