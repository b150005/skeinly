package io.github.b150005.knitnote.ui.symbol

import app.cash.turbine.test
import io.github.b150005.knitnote.domain.symbol.ParameterSlot
import io.github.b150005.knitnote.domain.symbol.SymbolCatalog
import io.github.b150005.knitnote.domain.symbol.SymbolCategory
import io.github.b150005.knitnote.domain.symbol.SymbolDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SymbolGalleryViewModelTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun def(
        id: String,
        category: SymbolCategory = SymbolCategory.KNIT,
        parameterSlots: List<ParameterSlot> = emptyList(),
    ): SymbolDefinition =
        SymbolDefinition(
            id = id,
            category = category,
            pathData = "M 0 0 L 1 1",
            jaLabel = "JA-$id",
            enLabel = "EN-$id",
            jisReference = "JIS L 0201-1995",
            cycName = "cyc-$id",
            parameterSlots = parameterSlots,
            jaDescription = "JA description for $id",
            enDescription = "EN description for $id",
        )

    private class FakeCatalog(
        private val items: List<SymbolDefinition>,
    ) : SymbolCatalog {
        override fun get(id: String) = items.firstOrNull { it.id == id }

        @Suppress("ktlint:standard:function-expression-body", "ktlint:standard:function-signature")
        override fun listByCategory(category: SymbolCategory): List<SymbolDefinition> {
            return items.filter { it.category == category }
        }

        override fun all() = items
    }

    @Test
    fun `initial state exposes catalog contents preserving order`() =
        runTest {
            val a = def("jis.knit.a")
            val b = def("jis.crochet.b", category = SymbolCategory.CROCHET)
            val catalog = FakeCatalog(listOf(a, b))

            val viewModel = SymbolGalleryViewModel(catalog)

            viewModel.state.test {
                val initial = awaitItem()
                assertEquals(listOf(a, b), initial.symbols)
                assertEquals(2, initial.totalCount)
                assertEquals(null, initial.activeCategoryFilter)
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `empty catalog produces empty state with zero total`() =
        runTest {
            val viewModel = SymbolGalleryViewModel(FakeCatalog(emptyList()))

            viewModel.state.test {
                val initial = awaitItem()
                assertTrue(initial.symbols.isEmpty())
                assertEquals(0, initial.totalCount)
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `filterByCategory narrows symbols to that category`() =
        runTest {
            val knit = def("jis.knit.a", category = SymbolCategory.KNIT)
            val purl = def("jis.knit.b", category = SymbolCategory.KNIT)
            val crochet = def("jis.crochet.d", category = SymbolCategory.CROCHET)
            val catalog = FakeCatalog(listOf(knit, purl, crochet))
            val viewModel = SymbolGalleryViewModel(catalog)

            viewModel.state.test {
                val initial = awaitItem()
                assertEquals(null, initial.activeCategoryFilter)
                assertEquals(3, initial.symbols.size)

                viewModel.onEvent(SymbolGalleryEvent.FilterByCategory(SymbolCategory.KNIT))

                val filtered = awaitItem()
                assertEquals(listOf(knit, purl), filtered.symbols)
                assertEquals(3, filtered.totalCount)
                assertEquals(2, filtered.symbols.size)
                assertEquals(SymbolCategory.KNIT, filtered.activeCategoryFilter)
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `clearCategoryFilter restores full catalog`() =
        runTest {
            val knit = def("jis.knit.a", category = SymbolCategory.KNIT)
            val crochet = def("jis.crochet.d", category = SymbolCategory.CROCHET)
            val catalog = FakeCatalog(listOf(knit, crochet))
            val viewModel = SymbolGalleryViewModel(catalog)

            viewModel.state.test {
                val initial = awaitItem()
                assertEquals(null, initial.activeCategoryFilter)

                viewModel.onEvent(SymbolGalleryEvent.FilterByCategory(SymbolCategory.KNIT))
                val filtered = awaitItem()
                assertEquals(SymbolCategory.KNIT, filtered.activeCategoryFilter)

                viewModel.onEvent(SymbolGalleryEvent.ClearCategoryFilter)
                val restored = awaitItem()
                assertEquals(listOf(knit, crochet), restored.symbols)
                assertEquals(null, restored.activeCategoryFilter)
                cancelAndConsumeRemainingEvents()
            }
        }
}
