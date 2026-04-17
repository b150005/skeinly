package io.github.b150005.knitnote.di

import io.github.b150005.knitnote.domain.symbol.SymbolCatalog
import io.github.b150005.knitnote.domain.symbol.catalog.DefaultSymbolCatalog
import org.koin.dsl.module

/**
 * Exposes the bundled [SymbolCatalog]. A `single` keeps the catalog lazy —
 * construction validates every entry, which is cheap but non-zero, so we do it
 * once per process.
 */
val symbolModule =
    module {
        single<SymbolCatalog> { DefaultSymbolCatalog.INSTANCE }
    }
