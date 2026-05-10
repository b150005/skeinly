package io.github.b150005.skeinly.di

import io.github.b150005.skeinly.data.local.LocalChartVersionDataSource
import io.github.b150005.skeinly.data.local.LocalPatternDataSource
import io.github.b150005.skeinly.data.local.LocalPendingSyncDataSource
import io.github.b150005.skeinly.data.local.LocalProgressDataSource
import io.github.b150005.skeinly.data.local.LocalProjectDataSource
import io.github.b150005.skeinly.data.local.LocalProjectSegmentDataSource
import io.github.b150005.skeinly.data.local.LocalSuggestionDataSource
import io.github.b150005.skeinly.data.local.LocalSymbolPackDataSource
import io.github.b150005.skeinly.data.realtime.RealtimeChannelProvider
import io.github.b150005.skeinly.data.remote.ConnectivityMonitor
import io.github.b150005.skeinly.data.remote.RemoteChartDataSource
import io.github.b150005.skeinly.data.remote.RemoteChartVariationDataSource
import io.github.b150005.skeinly.data.remote.RemoteChartVersionDataSource
import io.github.b150005.skeinly.data.remote.RemotePatternDataSource
import io.github.b150005.skeinly.data.remote.RemoteProgressDataSource
import io.github.b150005.skeinly.data.remote.RemoteProjectDataSource
import io.github.b150005.skeinly.data.remote.RemoteProjectSegmentDataSource
import io.github.b150005.skeinly.data.remote.RemoteSuggestionDataSource
import io.github.b150005.skeinly.data.remote.SupabaseConfig
import io.github.b150005.skeinly.data.remote.SymbolPackRemoteOperations
import io.github.b150005.skeinly.data.remote.isConfigured
import io.github.b150005.skeinly.data.sync.PendingSyncDataSource
import io.github.b150005.skeinly.data.sync.RealtimeSyncManager
import io.github.b150005.skeinly.data.sync.RemoteChartSyncOperations
import io.github.b150005.skeinly.data.sync.RemoteChartVariationSyncOperations
import io.github.b150005.skeinly.data.sync.RemoteChartVersionSyncOperations
import io.github.b150005.skeinly.data.sync.RemotePatternSyncOperations
import io.github.b150005.skeinly.data.sync.RemoteProgressSyncOperations
import io.github.b150005.skeinly.data.sync.RemoteProjectSegmentSyncOperations
import io.github.b150005.skeinly.data.sync.RemoteProjectSyncOperations
import io.github.b150005.skeinly.data.sync.RemoteSuggestionCommentSyncOperations
import io.github.b150005.skeinly.data.sync.RemoteSuggestionSyncOperations
import io.github.b150005.skeinly.data.sync.SymbolPackSyncManager
import io.github.b150005.skeinly.data.sync.SyncExecutor
import io.github.b150005.skeinly.data.sync.SyncManager
import io.github.b150005.skeinly.data.sync.SyncManagerOperations
import io.github.b150005.skeinly.domain.repository.AuthRepository
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
            single<RemoteChartSyncOperations> { get<RemoteChartDataSource>() }
            single<RemoteProjectSegmentSyncOperations> { get<RemoteProjectSegmentDataSource>() }
            single<RemoteChartVersionSyncOperations> { get<RemoteChartVersionDataSource>() }
            single<RemoteChartVariationSyncOperations> { get<RemoteChartVariationDataSource>() }
            single<RemoteSuggestionSyncOperations> { get<RemoteSuggestionDataSource>() }
            single<RemoteSuggestionCommentSyncOperations> { get<RemoteSuggestionDataSource>() }
        }

        single {
            SyncExecutor(
                remoteProject = getOrNull(),
                remoteProgress = getOrNull(),
                remotePattern = getOrNull(),
                remoteChart = getOrNull(),
                json = get(),
                remoteProjectSegment = getOrNull(),
                remoteChartVersion = getOrNull(),
                remoteChartVariation = getOrNull(),
                remoteSuggestion = getOrNull(),
                remoteSuggestionComment = getOrNull(),
            )
        }

        single<SyncManagerOperations> {
            SyncManager(
                pendingSyncDataSource = get(),
                syncExecutor = get(),
                isOnline = get<ConnectivityMonitor>().isOnline,
                scope = get<CoroutineScope>(applicationScopeQualifier),
            ).also { it.start() }
        }

        // Phase 41.2b (ADR-016 §4.3): Symbol pack sync orchestrator.
        // Always registered (even in local-only mode) — the impl gracefully
        // returns SyncCycleResult.Skipped when remote is null. Phase 41.3
        // wires the foreground-hook + post-purchase callers; for now sync
        // can be invoked manually from a debug entry point or test.
        single {
            SymbolPackSyncManager(
                remote = getOrNull<SymbolPackRemoteOperations>(),
                local = get<LocalSymbolPackDataSource>(),
                json = get(),
            )
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
                    localProjectSegment = get<LocalProjectSegmentDataSource>(),
                    authRepository = get<AuthRepository>(),
                    scope = get<CoroutineScope>(applicationScopeQualifier),
                    isOnline = get<ConnectivityMonitor>().isOnline,
                    localChartVersion = get<LocalChartVersionDataSource>(),
                    localSuggestion = get<LocalSuggestionDataSource>(),
                ).also { it.start() }
            }
        }
    }
