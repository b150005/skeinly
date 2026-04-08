package com.knitnote.di

import com.knitnote.data.repository.ProjectRepositoryImpl
import com.knitnote.data.repository.ProgressRepositoryImpl
import com.knitnote.domain.repository.ProjectRepository
import com.knitnote.domain.repository.ProgressRepository
import org.koin.dsl.module

val repositoryModule = module {
    single<ProjectRepository> { ProjectRepositoryImpl(get()) }
    single<ProgressRepository> { ProgressRepositoryImpl(get()) }
}
