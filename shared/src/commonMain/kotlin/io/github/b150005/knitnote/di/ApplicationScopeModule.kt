package io.github.b150005.knitnote.di

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose

val applicationScopeQualifier = named("applicationScope")
val ioDispatcherQualifier = named("ioDispatcher")
val chartImagesStorageQualifier = named("chartImagesStorage")
val progressPhotosStorageQualifier = named("progressPhotosStorage")
val avatarsStorageQualifier = named("avatarsStorage")

val applicationScopeModule =
    module {
        single(applicationScopeQualifier) {
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
        }.onClose { scope ->
            scope?.cancel()
        }

        single<CoroutineDispatcher>(ioDispatcherQualifier) { Dispatchers.IO }
    }
