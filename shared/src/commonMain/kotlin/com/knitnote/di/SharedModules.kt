package com.knitnote.di

val sharedModules = listOf(
    applicationScopeModule,
    supabaseModule,
    databaseModule,
    syncModule,
    repositoryModule,
    useCaseModule,
    viewModelModule,
)
