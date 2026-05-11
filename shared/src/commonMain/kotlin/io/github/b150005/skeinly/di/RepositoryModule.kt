package io.github.b150005.skeinly.di

import io.github.b150005.skeinly.data.local.LocalChartDataSource
import io.github.b150005.skeinly.data.local.LocalChartVariationDataSource
import io.github.b150005.skeinly.data.local.LocalChartVersionDataSource
import io.github.b150005.skeinly.data.local.LocalPatternDataSource
import io.github.b150005.skeinly.data.local.LocalProgressDataSource
import io.github.b150005.skeinly.data.local.LocalProjectDataSource
import io.github.b150005.skeinly.data.local.LocalProjectSegmentDataSource
import io.github.b150005.skeinly.data.local.LocalSubscriptionDataSource
import io.github.b150005.skeinly.data.local.LocalSuggestionDataSource
import io.github.b150005.skeinly.data.local.LocalSymbolPackDataSource
import io.github.b150005.skeinly.data.realtime.RealtimeChannelProvider
import io.github.b150005.skeinly.data.realtime.SupabaseRealtimeChannelProvider
import io.github.b150005.skeinly.data.remote.ActivityDataSourceOperations
import io.github.b150005.skeinly.data.remote.CommentDataSourceOperations
import io.github.b150005.skeinly.data.remote.ConnectivityMonitor
import io.github.b150005.skeinly.data.remote.DeviceTokenRemoteOperations
import io.github.b150005.skeinly.data.remote.PublicPatternDataSource
import io.github.b150005.skeinly.data.remote.RemoteActivityDataSource
import io.github.b150005.skeinly.data.remote.RemoteAppConfigDataSource
import io.github.b150005.skeinly.data.remote.RemoteChartDataSource
import io.github.b150005.skeinly.data.remote.RemoteChartVariationDataSource
import io.github.b150005.skeinly.data.remote.RemoteChartVersionDataSource
import io.github.b150005.skeinly.data.remote.RemoteCommentDataSource
import io.github.b150005.skeinly.data.remote.RemoteDeviceTokenDataSource
import io.github.b150005.skeinly.data.remote.RemotePatternDataSource
import io.github.b150005.skeinly.data.remote.RemoteProgressDataSource
import io.github.b150005.skeinly.data.remote.RemoteProjectDataSource
import io.github.b150005.skeinly.data.remote.RemoteProjectSegmentDataSource
import io.github.b150005.skeinly.data.remote.RemoteShareDataSource
import io.github.b150005.skeinly.data.remote.RemoteStorageDataSource
import io.github.b150005.skeinly.data.remote.RemoteSubscriptionDataSource
import io.github.b150005.skeinly.data.remote.RemoteSuggestionDataSource
import io.github.b150005.skeinly.data.remote.RemoteSymbolPackDataSource
import io.github.b150005.skeinly.data.remote.RemoteUserDataSource
import io.github.b150005.skeinly.data.remote.ShareDataSourceOperations
import io.github.b150005.skeinly.data.remote.SubscriptionRemoteOperations
import io.github.b150005.skeinly.data.remote.SupabaseConfig
import io.github.b150005.skeinly.data.remote.SymbolPackRemoteOperations
import io.github.b150005.skeinly.data.remote.createSymbolPackHttpClient
import io.github.b150005.skeinly.data.remote.isConfigured
import io.github.b150005.skeinly.data.repository.ActivityRepositoryImpl
import io.github.b150005.skeinly.data.repository.AppConfigRepositoryImpl
import io.github.b150005.skeinly.data.repository.AuthRepositoryImpl
import io.github.b150005.skeinly.data.repository.ChartRepositoryImpl
import io.github.b150005.skeinly.data.repository.ChartVariationRepositoryImpl
import io.github.b150005.skeinly.data.repository.ChartVersionRepositoryImpl
import io.github.b150005.skeinly.data.repository.CommentRepositoryImpl
import io.github.b150005.skeinly.data.repository.DeviceTokenRepositoryImpl
import io.github.b150005.skeinly.data.repository.OfflineUserRepository
import io.github.b150005.skeinly.data.repository.PatternRepositoryImpl
import io.github.b150005.skeinly.data.repository.ProgressRepositoryImpl
import io.github.b150005.skeinly.data.repository.ProjectRepositoryImpl
import io.github.b150005.skeinly.data.repository.ProjectSegmentRepositoryImpl
import io.github.b150005.skeinly.data.repository.ShareRepositoryImpl
import io.github.b150005.skeinly.data.repository.SubscriptionRepositoryImpl
import io.github.b150005.skeinly.data.repository.SuggestionRepositoryImpl
import io.github.b150005.skeinly.data.repository.UserRepositoryImpl
import io.github.b150005.skeinly.domain.repository.ActivityRepository
import io.github.b150005.skeinly.domain.repository.AppConfigRepository
import io.github.b150005.skeinly.domain.repository.AuthRepository
import io.github.b150005.skeinly.domain.repository.ChartRepository
import io.github.b150005.skeinly.domain.repository.ChartVariationRepository
import io.github.b150005.skeinly.domain.repository.ChartVersionRepository
import io.github.b150005.skeinly.domain.repository.CommentRepository
import io.github.b150005.skeinly.domain.repository.DeviceTokenRepository
import io.github.b150005.skeinly.domain.repository.PatternRepository
import io.github.b150005.skeinly.domain.repository.ProgressRepository
import io.github.b150005.skeinly.domain.repository.ProjectRepository
import io.github.b150005.skeinly.domain.repository.ProjectSegmentRepository
import io.github.b150005.skeinly.domain.repository.ShareRepository
import io.github.b150005.skeinly.domain.repository.StorageOperations
import io.github.b150005.skeinly.domain.repository.SubscriptionRepository
import io.github.b150005.skeinly.domain.repository.SuggestionRepository
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

        // Phase 24.2e (ADR-017 §3.5) — push-token upsert. Registered
        // unconditionally (not gated on SupabaseConfig.isConfigured)
        // because the impl handles `remote = null` internally via the
        // RequiresConnectivity short-circuit. Wiring it here keeps
        // `PushTokenRegistrar` actuals' Koin `get<DeviceTokenRepository>()`
        // resolution simple — there is always a binding.
        single<DeviceTokenRepository> {
            DeviceTokenRepositoryImpl(
                remote = getOrNull<DeviceTokenRemoteOperations>(),
                authRepository = get(),
            )
        }

        // Phase 39 (W4 / 2026-05-11) — force-update gate. Registered
        // unconditionally so [ForceUpdateGate]'s `koinInject<AppConfigRepository>()`
        // always resolves. Local-only mode (Supabase not configured)
        // passes a null `remote`; the gate fails-open per the
        // offline-first contract.
        single<AppConfigRepository> {
            AppConfigRepositoryImpl(
                remote = getOrNull<RemoteAppConfigDataSource>(),
                cache = get(),
            )
        }

        // Local data sources
        single { LocalProjectDataSource(get(), get(ioDispatcherQualifier)) }
        single { LocalProgressDataSource(get(), get(ioDispatcherQualifier)) }
        single { LocalPatternDataSource(get(), get(ioDispatcherQualifier)) }
        single { LocalChartDataSource(get(), get(ioDispatcherQualifier), get()) }
        single { LocalProjectSegmentDataSource(get(), get(ioDispatcherQualifier)) }
        single { LocalChartVersionDataSource(get(), get(ioDispatcherQualifier), get()) }
        single { LocalChartVariationDataSource(get(), get(ioDispatcherQualifier)) }
        single { LocalSuggestionDataSource(get(), get(ioDispatcherQualifier)) }
        single { LocalSubscriptionDataSource(get(), get(ioDispatcherQualifier)) }
        single { LocalSymbolPackDataSource(get(), get(ioDispatcherQualifier)) }

        // Remote data sources & repositories — only registered when Supabase is configured.
        // Consumers use getOrNull() to handle their absence in local-only mode.
        if (SupabaseConfig.isConfigured) {
            single { RemoteProjectDataSource(get<SupabaseClient>()) }
            single { RemoteProgressDataSource(get<SupabaseClient>()) }
            single { RemotePatternDataSource(get<SupabaseClient>()) }
            single<PublicPatternDataSource> { get<RemotePatternDataSource>() }
            single { RemoteChartDataSource(get<SupabaseClient>()) }
            single { RemoteProjectSegmentDataSource(get<SupabaseClient>()) }
            single { RemoteChartVersionDataSource(get<SupabaseClient>()) }
            single { RemoteChartVariationDataSource(get<SupabaseClient>()) }
            single { RemoteSuggestionDataSource(get<SupabaseClient>()) }
            // Phase 39 (W4 / 2026-05-11) — force-update remote source.
            // Only registered when Supabase is configured; the repository
            // below uses getOrNull() so local-only mode degrades to
            // never-refresh + fail-open per the offline-first contract.
            single { RemoteAppConfigDataSource(get<SupabaseClient>()) }
            single { RemoteSubscriptionDataSource(get<SupabaseClient>()) }
            // Phase 24.2e (ADR-017 §3.5) — device_tokens upsert
            // adapter. Interface alias lets DeviceTokenRepositoryImpl
            // be tested with an in-memory fake without standing up
            // Supabase. Same shape as SubscriptionRemoteOperations
            // above.
            single { RemoteDeviceTokenDataSource(get<SupabaseClient>()) }
            single<DeviceTokenRemoteOperations> { get<RemoteDeviceTokenDataSource>() }
            // Interface alias lets SubscriptionRepositoryImpl be tested
            // with an in-memory fake without standing up Supabase. Same
            // shape as SuggestionMergeOperations above.
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
            // Phase 39 W5b (ADR-020) — bug-report proxy client. POSTs
            // to `${supabaseConfig.url}/functions/v1/submit-bug-report`
            // which authenticates as the "Skeinly Feedback"
            // GitHub App and creates an Issue on b150005/skeinly.
            // Replaces Phase 39.5's `BugSubmissionLauncher`
            // expect/actual (deleted in the same commit). The injected
            // [HttpClient] is the existing `symbolPackHttpClient` —
            // both use a plain Ktor instance with no auth plugins and
            // identical default timeouts, so the qualifier reuse
            // avoids growing the DI surface by a redundant alias.
            // [SupabaseConfig] (the existing expect/actual) supplies
            // both the function URL host and the publishable key used
            // in the `Authorization: Bearer` header.
            single {
                io.github.b150005.skeinly.data.bug.BugReportProxyClient(
                    httpClient = get<HttpClient>(symbolPackHttpClientQualifier),
                    supabaseUrl = io.github.b150005.skeinly.data.remote.SupabaseConfig.url,
                    supabasePublishableKey = io.github.b150005.skeinly.data.remote.SupabaseConfig.publishableKey,
                    json = get(),
                )
            }
            // Phase 38.4: Expose the merge RPC port as a domain-layer
            // interface so ApplySuggestionUseCase doesn't take a hard
            // dependency on the Supabase-typed data source.
            single<io.github.b150005.skeinly.domain.repository.SuggestionMergeOperations> {
                get<RemoteSuggestionDataSource>()
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
        single<ChartRepository> {
            ChartRepositoryImpl(
                local = get(),
                remote = getOrNull(),
                isOnline = get<ConnectivityMonitor>().isOnline,
                syncManager = get(),
                json = get(),
                chartVersionRepository = get(),
                localChartVariation = get(),
                chartVariationRepository = get(),
            )
        }
        single<ChartVersionRepository> {
            ChartVersionRepositoryImpl(
                local = get(),
                remote = getOrNull(),
                isOnline = get<ConnectivityMonitor>().isOnline,
                syncManager = get(),
                json = get(),
            )
        }
        single<ChartVariationRepository> {
            ChartVariationRepositoryImpl(
                local = get(),
                syncManager = get(),
                json = get(),
            )
        }
        single<SuggestionRepository> {
            SuggestionRepositoryImpl(
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
