package com.knitnote.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose

val applicationScopeQualifier = named("applicationScope")

val applicationScopeModule = module {
    single(applicationScopeQualifier) {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }.onClose { scope ->
        scope?.cancel()
    }
}
