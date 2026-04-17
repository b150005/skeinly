package io.github.b150005.knitnote.ui.symbol

import androidx.lifecycle.ViewModel
import io.github.b150005.knitnote.domain.symbol.SymbolCatalog
import io.github.b150005.knitnote.domain.symbol.SymbolCategory
import io.github.b150005.knitnote.domain.symbol.SymbolDefinition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SymbolGalleryState(
    val symbols: List<SymbolDefinition> = emptyList(),
    val totalCount: Int = 0,
    val activeCategoryFilter: SymbolCategory? = null,
)

sealed interface SymbolGalleryEvent {
    data class FilterByCategory(
        val category: SymbolCategory,
    ) : SymbolGalleryEvent

    data object ClearCategoryFilter : SymbolGalleryEvent
}

/**
 * Read-only browser over the bundled [SymbolCatalog]. Phase 30.1 uses this to expose
 * the 35 `jis.knit.*` glyphs for visual review; later phases can drive the chart
 * editor palette from the same state shape.
 */
class SymbolGalleryViewModel(
    private val catalog: SymbolCatalog,
) : ViewModel() {
    private val allSymbols: List<SymbolDefinition> = catalog.all()

    private val _state =
        MutableStateFlow(
            SymbolGalleryState(
                symbols = allSymbols,
                totalCount = allSymbols.size,
                activeCategoryFilter = null,
            ),
        )
    val state: StateFlow<SymbolGalleryState> = _state.asStateFlow()

    fun onEvent(event: SymbolGalleryEvent) {
        when (event) {
            is SymbolGalleryEvent.FilterByCategory -> applyFilter(event.category)
            SymbolGalleryEvent.ClearCategoryFilter -> clearFilter()
        }
    }

    private fun applyFilter(category: SymbolCategory) {
        _state.update { current ->
            current.copy(
                symbols = catalog.listByCategory(category),
                activeCategoryFilter = category,
            )
        }
    }

    private fun clearFilter() {
        _state.update { current ->
            current.copy(
                symbols = allSymbols,
                activeCategoryFilter = null,
            )
        }
    }
}
