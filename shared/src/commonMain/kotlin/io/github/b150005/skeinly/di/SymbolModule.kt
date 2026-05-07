package io.github.b150005.skeinly.di

import org.koin.dsl.module

/**
 * Phase 41.2c emptied this module. The bundled
 * [io.github.b150005.skeinly.domain.symbol.catalog.DefaultSymbolCatalog] is now
 * consumed only as the `bundled` ctor parameter of
 * [io.github.b150005.skeinly.domain.symbol.CompositeSymbolCatalog], whose
 * Koin registration lives in [repositoryModule] alongside
 * [io.github.b150005.skeinly.domain.symbol.EntitlementResolver] and
 * [io.github.b150005.skeinly.data.local.LocalSymbolPackDataSource] — they all
 * compose through a single `single<SymbolCatalog>` site.
 *
 * The module is kept (rather than removed from [sharedModules]) so any future
 * symbol-catalog-only registrations have a natural home that does not pull in
 * the broader repository graph.
 */
val symbolModule = module { }
