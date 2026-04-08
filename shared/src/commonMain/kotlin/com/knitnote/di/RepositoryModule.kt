package com.knitnote.di

import com.knitnote.data.local.LocalProgressDataSource
import com.knitnote.data.local.LocalProjectDataSource
import com.knitnote.data.remote.ConnectivityMonitor
import com.knitnote.data.remote.RemoteProgressDataSource
import com.knitnote.data.remote.RemoteProjectDataSource
import com.knitnote.data.repository.AuthRepositoryImpl
import com.knitnote.data.repository.ProjectRepositoryImpl
import com.knitnote.data.repository.ProgressRepositoryImpl
import com.knitnote.domain.repository.AuthRepository
import com.knitnote.domain.repository.ProjectRepository
import com.knitnote.domain.repository.ProgressRepository
import io.github.jan.supabase.SupabaseClient
import org.koin.dsl.module

val repositoryModule = module {
    // Auth
    single<AuthRepository> { AuthRepositoryImpl(getOrNull<SupabaseClient>()) }

    // Local data sources
    single { LocalProjectDataSource(get()) }
    single { LocalProgressDataSource(get()) }

    // Remote data sources (nullable — only created when Supabase is configured)
    single<RemoteProjectDataSource?> {
        getOrNull<SupabaseClient>()?.let { RemoteProjectDataSource(it) }
    }
    single<RemoteProgressDataSource?> {
        getOrNull<SupabaseClient>()?.let { RemoteProgressDataSource(it) }
    }

    // Coordinator repositories
    single<ProjectRepository> {
        ProjectRepositoryImpl(
            local = get(),
            remote = getOrNull(),
            isOnline = get<ConnectivityMonitor>().isOnline,
        )
    }
    single<ProgressRepository> {
        ProgressRepositoryImpl(
            local = get(),
            remote = getOrNull(),
            isOnline = get<ConnectivityMonitor>().isOnline,
        )
    }
}
