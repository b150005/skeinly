package com.knitnote.di

val sharedModules =
    listOf(
        applicationScopeModule,
        preferencesModule,
        supabaseModule,
        databaseModule,
        syncModule,
        repositoryModule,
        useCaseModule,
        viewModelModule,
    )
