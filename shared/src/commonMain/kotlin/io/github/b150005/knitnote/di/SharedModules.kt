package io.github.b150005.knitnote.di

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
