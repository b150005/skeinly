package com.knitnote.di

import com.knitnote.data.local.LocalPendingSyncDataSource
import com.knitnote.data.remote.ConnectivityMonitor
import com.knitnote.data.remote.RemoteProgressDataSource
import com.knitnote.data.remote.RemoteProjectDataSource
import com.knitnote.data.sync.PendingSyncDataSource
import com.knitnote.data.sync.RemoteProgressSyncOperations
import com.knitnote.data.sync.RemoteProjectSyncOperations
import com.knitnote.data.sync.SyncExecutor
import com.knitnote.data.sync.SyncManager
import com.knitnote.data.sync.SyncManagerOperations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import org.koin.dsl.module

val syncModule = module {
    single { Json { ignoreUnknownKeys = true } }

    single<PendingSyncDataSource> { LocalPendingSyncDataSource(get()) }

    // Sync interfaces backed by the remote data sources
    single<RemoteProjectSyncOperations?> { getOrNull<RemoteProjectDataSource>() }
    single<RemoteProgressSyncOperations?> { getOrNull<RemoteProgressDataSource>() }

    single { SyncExecutor(getOrNull(), getOrNull(), get()) }

    single<SyncManagerOperations> {
        SyncManager(
            pendingSyncDataSource = get(),
            syncExecutor = get(),
            isOnline = get<ConnectivityMonitor>().isOnline,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ).also { it.start() }
    }
}
