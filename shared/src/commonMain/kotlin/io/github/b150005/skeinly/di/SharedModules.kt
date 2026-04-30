package io.github.b150005.skeinly.di

val sharedModules =
    listOf(
        applicationScopeModule,
        preferencesModule,
        supabaseModule,
        databaseModule,
        syncModule,
        symbolModule,
        repositoryModule,
        useCaseModule,
        viewModelModule,
    )
