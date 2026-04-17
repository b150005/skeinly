package io.github.b150005.knitnote.di

import io.github.b150005.knitnote.data.local.LocalPatternDataSource
import io.github.b150005.knitnote.data.local.LocalPendingSyncDataSource
import io.github.b150005.knitnote.data.local.LocalProgressDataSource
import io.github.b150005.knitnote.data.local.LocalProjectDataSource
import io.github.b150005.knitnote.data.realtime.RealtimeChannelProvider
import io.github.b150005.knitnote.data.remote.ConnectivityMonitor
import io.github.b150005.knitnote.data.remote.RemotePatternDataSource
import io.github.b150005.knitnote.data.remote.RemoteProgressDataSource
import io.github.b150005.knitnote.data.remote.RemoteProjectDataSource
import io.github.b150005.knitnote.data.remote.SupabaseConfig
import io.github.b150005.knitnote.data.remote.isConfigured
import io.github.b150005.knitnote.data.sync.PendingSyncDataSource
import io.github.b150005.knitnote.data.sync.RealtimeSyncManager
import io.github.b150005.knitnote.data.sync.RemotePatternSyncOperations
import io.github.b150005.knitnote.data.sync.RemoteProgressSyncOperations
import io.github.b150005.knitnote.data.sync.RemoteProjectSyncOperations
import io.github.b150005.knitnote.data.sync.SyncExecutor
import io.github.b150005.knitnote.data.sync.SyncManager
import io.github.b150005.knitnote.data.sync.SyncManagerOperations
import io.github.b150005.knitnote.domain.repository.AuthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import org.koin.dsl.module

val syncModule =
    module {
        single { Json { ignoreUnknownKeys = true } }

        single<PendingSyncDataSource> { LocalPendingSyncDataSource(get(), get(ioDispatcherQualifier)) }

        // Sync interfaces backed by the remote data sources.
        // Only registered when Supabase is configured — consumers use getOrNull().
        if (SupabaseConfig.isConfigured) {
            single<RemoteProjectSyncOperations> { get<RemoteProjectDataSource>() }
            single<RemoteProgressSyncOperations> { get<RemoteProgressDataSource>() }
            single<RemotePatternSyncOperations> { get<RemotePatternDataSource>() }
        }

        single { SyncExecutor(getOrNull(), getOrNull(), getOrNull(), get()) }

        single<SyncManagerOperations> {
            SyncManager(
                pendingSyncDataSource = get(),
                syncExecutor = get(),
                isOnline = get<ConnectivityMonitor>().isOnline,
                scope = get<CoroutineScope>(applicationScopeQualifier),
            ).also { it.start() }
        }

        // RealtimeSyncManager — only registered when Supabase is configured.
        // Consumers use getOrNull<RealtimeSyncManager>().
        if (SupabaseConfig.isConfigured) {
            single<RealtimeSyncManager> {
                RealtimeSyncManager(
                    channelProvider = get<RealtimeChannelProvider>(),
                    localProject = get<LocalProjectDataSource>(),
                    localProgress = get<LocalProgressDataSource>(),
                    localPattern = get<LocalPatternDataSource>(),
                    authRepository = get<AuthRepository>(),
                    scope = get<CoroutineScope>(applicationScopeQualifier),
                    isOnline = get<ConnectivityMonitor>().isOnline,
                ).also { it.start() }
            }
        }
    }
