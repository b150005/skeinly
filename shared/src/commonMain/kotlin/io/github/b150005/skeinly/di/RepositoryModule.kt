package io.github.b150005.skeinly.di

import io.github.b150005.skeinly.data.local.LocalChartBranchDataSource
import io.github.b150005.skeinly.data.local.LocalChartRevisionDataSource
import io.github.b150005.skeinly.data.local.LocalPatternDataSource
import io.github.b150005.skeinly.data.local.LocalProgressDataSource
import io.github.b150005.skeinly.data.local.LocalProjectDataSource
import io.github.b150005.skeinly.data.local.LocalProjectSegmentDataSource
import io.github.b150005.skeinly.data.local.LocalPullRequestDataSource
import io.github.b150005.skeinly.data.local.LocalStructuredChartDataSource
import io.github.b150005.skeinly.data.local.LocalSubscriptionDataSource
import io.github.b150005.skeinly.data.local.LocalSymbolPackDataSource
import io.github.b150005.skeinly.data.realtime.RealtimeChannelProvider
import io.github.b150005.skeinly.data.realtime.SupabaseRealtimeChannelProvider
import io.github.b150005.skeinly.data.remote.ActivityDataSourceOperations
import io.github.b150005.skeinly.data.remote.CommentDataSourceOperations
import io.github.b150005.skeinly.data.remote.ConnectivityMonitor
import io.github.b150005.skeinly.data.remote.PublicPatternDataSource
import io.github.b150005.skeinly.data.remote.RemoteActivityDataSource
import io.github.b150005.skeinly.data.remote.RemoteChartBranchDataSource
import io.github.b150005.skeinly.data.remote.RemoteChartRevisionDataSource
import io.github.b150005.skeinly.data.remote.RemoteCommentDataSource
import io.github.b150005.skeinly.data.remote.RemotePatternDataSource
import io.github.b150005.skeinly.data.remote.RemoteProgressDataSource
import io.github.b150005.skeinly.data.remote.RemoteProjectDataSource
import io.github.b150005.skeinly.data.remote.RemoteProjectSegmentDataSource
import io.github.b150005.skeinly.data.remote.RemotePullRequestDataSource
import io.github.b150005.skeinly.data.remote.RemoteShareDataSource
import io.github.b150005.skeinly.data.remote.RemoteStorageDataSource
import io.github.b150005.skeinly.data.remote.RemoteStructuredChartDataSource
import io.github.b150005.skeinly.data.remote.RemoteSubscriptionDataSource
import io.github.b150005.skeinly.data.remote.RemoteSymbolPackDataSource
import io.github.b150005.skeinly.data.remote.RemoteUserDataSource
import io.github.b150005.skeinly.data.remote.ShareDataSourceOperations
import io.github.b150005.skeinly.data.remote.SubscriptionRemoteOperations
import io.github.b150005.skeinly.data.remote.SupabaseConfig
import io.github.b150005.skeinly.data.remote.SymbolPackRemoteOperations
import io.github.b150005.skeinly.data.remote.createSymbolPackHttpClient
import io.github.b150005.skeinly.data.remote.isConfigured
import io.github.b150005.skeinly.data.repository.ActivityRepositoryImpl
import io.github.b150005.skeinly.data.repository.AuthRepositoryImpl
import io.github.b150005.skeinly.data.repository.ChartBranchRepositoryImpl
import io.github.b150005.skeinly.data.repository.ChartRevisionRepositoryImpl
import io.github.b150005.skeinly.data.repository.CommentRepositoryImpl
import io.github.b150005.skeinly.data.repository.OfflineUserRepository
import io.github.b150005.skeinly.data.repository.PatternRepositoryImpl
import io.github.b150005.skeinly.data.repository.ProgressRepositoryImpl
import io.github.b150005.skeinly.data.repository.ProjectRepositoryImpl
import io.github.b150005.skeinly.data.repository.ProjectSegmentRepositoryImpl
import io.github.b150005.skeinly.data.repository.PullRequestRepositoryImpl
import io.github.b150005.skeinly.data.repository.ShareRepositoryImpl
import io.github.b150005.skeinly.data.repository.StructuredChartRepositoryImpl
import io.github.b150005.skeinly.data.repository.SubscriptionRepositoryImpl
import io.github.b150005.skeinly.data.repository.UserRepositoryImpl
import io.github.b150005.skeinly.domain.repository.ActivityRepository
import io.github.b150005.skeinly.domain.repository.AuthRepository
import io.github.b150005.skeinly.domain.repository.ChartBranchRepository
import io.github.b150005.skeinly.domain.repository.ChartRevisionRepository
import io.github.b150005.skeinly.domain.repository.CommentRepository
import io.github.b150005.skeinly.domain.repository.PatternRepository
import io.github.b150005.skeinly.domain.repository.ProgressRepository
import io.github.b150005.skeinly.domain.repository.ProjectRepository
import io.github.b150005.skeinly.domain.repository.ProjectSegmentRepository
import io.github.b150005.skeinly.domain.repository.PullRequestRepository
import io.github.b150005.skeinly.domain.repository.ShareRepository
import io.github.b150005.skeinly.domain.repository.StorageOperations
import io.github.b150005.skeinly.domain.repository.StructuredChartRepository
import io.github.b150005.skeinly.domain.repository.SubscriptionRepository
import io.github.b150005.skeinly.domain.repository.UserRepository
import io.github.b150005.skeinly.domain.symbol.CompositeSymbolCatalog
import io.github.b150005.skeinly.domain.symbol.DefaultSymbolPackCatalog
import io.github.b150005.skeinly.domain.symbol.EntitlementResolver
import io.github.b150005.skeinly.domain.symbol.SymbolCatalog
import io.github.b150005.skeinly.domain.symbol.SymbolPackCatalog
import io.github.b150005.skeinly.domain.symbol.catalog.DefaultSymbolCatalog
import io.github.jan.supabase.SupabaseClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import org.koin.dsl.module
import org.koin.dsl.onClose

val repositoryModule =
    module {
        // Auth
        single<AuthRepository> { AuthRepositoryImpl(getOrNull<SupabaseClient>()) }

        // Local data sources
        single { LocalProjectDataSource(get(), get(ioDispatcherQualifier)) }
        single { LocalProgressDataSource(get(), get(ioDispatcherQualifier)) }
        single { LocalPatternDataSource(get(), get(ioDispatcherQualifier)) }
        single { LocalStructuredChartDataSource(get(), get(ioDispatcherQualifier), get()) }
        single { LocalProjectSegmentDataSource(get(), get(ioDispatcherQualifier)) }
        single { LocalChartRevisionDataSource(get(), get(ioDispatcherQualifier), get()) }
        single { LocalChartBranchDataSource(get(), get(ioDispatcherQualifier)) }
        single { LocalPullRequestDataSource(get(), get(ioDispatcherQualifier)) }
        single { LocalSubscriptionDataSource(get(), get(ioDispatcherQualifier)) }
        single { LocalSymbolPackDataSource(get(), get(ioDispatcherQualifier)) }

        // Remote data sources & repositories — only registered when Supabase is configured.
        // Consumers use getOrNull() to handle their absence in local-only mode.
        if (SupabaseConfig.isConfigured) {
            single { RemoteProjectDataSource(get<SupabaseClient>()) }
            single { RemoteProgressDataSource(get<SupabaseClient>()) }
            single { RemotePatternDataSource(get<SupabaseClient>()) }
            single<PublicPatternDataSource> { get<RemotePatternDataSource>() }
            single { RemoteStructuredChartDataSource(get<SupabaseClient>()) }
            single { RemoteProjectSegmentDataSource(get<SupabaseClient>()) }
            single { RemoteChartRevisionDataSource(get<SupabaseClient>()) }
            single { RemoteChartBranchDataSource(get<SupabaseClient>()) }
            single { RemotePullRequestDataSource(get<SupabaseClient>()) }
            single { RemoteSubscriptionDataSource(get<SupabaseClient>()) }
            // Interface alias lets SubscriptionRepositoryImpl be tested
            // with an in-memory fake without standing up Supabase. Same
            // shape as PullRequestMergeOperations above.
            single<SubscriptionRemoteOperations> { get<RemoteSubscriptionDataSource>() }
            // Phase 41.2b (ADR-016 §3.3, §4.3): symbol pack catalog +
            // Edge Function download mediation. The injected HttpClient is
            // a separate Ktor instance (NOT the supabase-kt internal one
            // which is `@SupabaseInternal`) used for the absolute signed
            // Storage URL fetch. Constructed via expect/actual factory so
            // the platform engine (Android / Darwin) is picked correctly
            // on Kotlin/Native — `HttpClient { }` no-arg form does NOT
            // discover engines on Native. Named qualifier prevents
            // ambiguity with any future bare `HttpClient` consumer.
            single<HttpClient>(symbolPackHttpClientQualifier) {
                createSymbolPackHttpClient()
            }.onClose { it?.close() }
            single {
                RemoteSymbolPackDataSource(
                    supabaseClient = get<SupabaseClient>(),
                    httpClient = get<HttpClient>(symbolPackHttpClientQualifier),
                    json = get(),
                )
            }
            single<SymbolPackRemoteOperations> { get<RemoteSymbolPackDataSource>() }
            // Phase 38.4: Expose the merge RPC port as a domain-layer
            // interface so MergePullRequestUseCase doesn't take a hard
            // dependency on the Supabase-typed data source.
            single<io.github.b150005.skeinly.domain.repository.PullRequestMergeOperations> {
                get<RemotePullRequestDataSource>()
            }
            single<ShareDataSourceOperations> { RemoteShareDataSource(get<SupabaseClient>()) }
            single { RemoteUserDataSource(get<SupabaseClient>()) }
            single<CommentDataSourceOperations> { RemoteCommentDataSource(get<SupabaseClient>()) }
            single<ActivityDataSourceOperations> { RemoteActivityDataSource(get<SupabaseClient>()) }
            single<StorageOperations>(chartImagesStorageQualifier) {
                RemoteStorageDataSource(get<SupabaseClient>(), "chart-images")
            }
            single<StorageOperations>(progressPhotosStorageQualifier) {
                RemoteStorageDataSource(get<SupabaseClient>(), "progress-photos")
            }
            single<StorageOperations>(avatarsStorageQualifier) {
                RemoteStorageDataSource(get<SupabaseClient>(), "avatars")
            }
            single<RealtimeChannelProvider> { SupabaseRealtimeChannelProvider(get<SupabaseClient>()) }

            // Comment — remote-only with Realtime
            single<CommentRepository> {
                CommentRepositoryImpl(
                    remote = get<CommentDataSourceOperations>(),
                    channelProvider = get<RealtimeChannelProvider>(),
                    scope = get<CoroutineScope>(applicationScopeQualifier),
                )
            }
            // Activity — remote-only with Realtime
            single<ActivityRepository> {
                ActivityRepositoryImpl(
                    remote = get<ActivityDataSourceOperations>(),
                    channelProvider = get<RealtimeChannelProvider>(),
                    scope = get<CoroutineScope>(applicationScopeQualifier),
                )
            }
            // Share — remote-only
            single<ShareRepository> {
                ShareRepositoryImpl(
                    remote = get<ShareDataSourceOperations>(),
                    channelProvider = get<RealtimeChannelProvider>(),
                    scope = get<CoroutineScope>(applicationScopeQualifier),
                )
            }
        }

        // Coordinator repositories (local-first + optional remote sync)
        single<ProjectRepository> {
            ProjectRepositoryImpl(
                local = get(),
                remote = getOrNull(),
                isOnline = get<ConnectivityMonitor>().isOnline,
                syncManager = get(),
                json = get(),
            )
        }
        single<ProgressRepository> {
            ProgressRepositoryImpl(
                local = get(),
                remote = getOrNull(),
                isOnline = get<ConnectivityMonitor>().isOnline,
                syncManager = get(),
                json = get(),
            )
        }
        single<PatternRepository> {
            PatternRepositoryImpl(
                local = get(),
                remote = getOrNull(),
                isOnline = get<ConnectivityMonitor>().isOnline,
                syncManager = get(),
                json = get(),
            )
        }
        single<StructuredChartRepository> {
            StructuredChartRepositoryImpl(
                local = get(),
                remote = getOrNull(),
                isOnline = get<ConnectivityMonitor>().isOnline,
                syncManager = get(),
                json = get(),
                chartRevisionRepository = get(),
                localChartBranch = get(),
                chartBranchRepository = get(),
            )
        }
        single<ChartRevisionRepository> {
            ChartRevisionRepositoryImpl(
                local = get(),
                remote = getOrNull(),
                isOnline = get<ConnectivityMonitor>().isOnline,
                syncManager = get(),
                json = get(),
            )
        }
        single<ChartBranchRepository> {
            ChartBranchRepositoryImpl(
                local = get(),
                syncManager = get(),
                json = get(),
            )
        }
        single<PullRequestRepository> {
            PullRequestRepositoryImpl(
                local = get(),
                remote = getOrNull(),
                isOnline = get<ConnectivityMonitor>().isOnline,
                syncManager = get(),
                json = get(),
                // Phase 38.3 (ADR-014 §6 §7): per-PR comments Realtime channel
                // is optional; null in local-only mode (no Supabase) is the
                // "channel is a no-op" branch handled inside subscribeToCommentsChannel.
                channelProvider = getOrNull<RealtimeChannelProvider>(),
                scope = getOrNull<CoroutineScope>(applicationScopeQualifier),
            )
        }
        single<ProjectSegmentRepository> {
            ProjectSegmentRepositoryImpl(
                local = get(),
                syncManager = get(),
                json = get(),
            )
        }
        // User profiles — remote with offline fallback
        single<UserRepository> {
            getOrNull<RemoteUserDataSource>()?.let { UserRepositoryImpl(it) }
                ?: OfflineUserRepository()
        }
        // Phase 41.2a (ADR-016 §4.2) — read-only subscription mirror feeding
        // EntitlementResolver. Remote nullable for local-only mode.
        single<SubscriptionRepository> {
            SubscriptionRepositoryImpl(
                local = get(),
                remote = getOrNull<SubscriptionRemoteOperations>(),
                isOnline = get<ConnectivityMonitor>().isOnline,
            )
        }
        single { EntitlementResolver(subscriptionRepository = get(), authRepository = get()) }
        // Phase 41.2c (ADR-016 §4.1) — composite catalog overlays downloaded
        // packs on top of the bundled compile-time catalog, gated by
        // EntitlementResolver for Pro-tier entries. The constructor schedules
        // an async warm-up on the application scope so the first chart
        // editor open after process boot sees the downloaded packs as soon
        // as the local mirror has them.
        single<SymbolCatalog> {
            CompositeSymbolCatalog(
                bundled = DefaultSymbolCatalog.INSTANCE,
                localSymbolPackDataSource = get(),
                entitlementResolver = get(),
                json = get(),
                applicationScope = get<CoroutineScope>(applicationScopeQualifier),
            )
        }
        // Phase 41.5.1 (ADR-016 §41.5.3 + §41.5.6) — pack-management
        // catalog. Sibling to [SymbolCatalog]; owns the Pro entitlement
        // gate so [PackManagementViewModel] stays Pro-policy-agnostic
        // per §41.5.1.
        single<SymbolPackCatalog> {
            DefaultSymbolPackCatalog(
                localSymbolPackDataSource = get(),
                entitlementResolver = get(),
            )
        }
        // Phase 41.3 (ADR-016 §6 §41.3) — RevenueCat IAP service. Production
        // wiring uses the real `purchases-kmp-core` SDK via `Purchases.sharedInstance`.
        // Tests inject a fake via the `RevenueCatService` interface. Local-dev
        // builds without the SDK key (REVENUECAT_API_KEY empty) still get the
        // singleton wired — but every method surfaces a "subscriptions
        // unavailable" error gracefully because RevenueCatBootstrap.configure
        // short-circuits on no key.
        single<io.github.b150005.skeinly.domain.subscription.RevenueCatService> {
            io.github.b150005.skeinly.data.subscription
                .RevenueCatServiceImpl()
        }
    }
