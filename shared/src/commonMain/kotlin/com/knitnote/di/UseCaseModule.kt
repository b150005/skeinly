package com.knitnote.di

import com.knitnote.domain.usecase.AddProgressNoteUseCase
import com.knitnote.domain.usecase.CreateProjectUseCase
import com.knitnote.domain.usecase.DecrementRowUseCase
import com.knitnote.domain.usecase.DeleteProgressNoteUseCase
import com.knitnote.domain.usecase.DeleteProjectUseCase
import com.knitnote.domain.usecase.GetProgressNotesUseCase
import com.knitnote.domain.usecase.GetProjectByIdUseCase
import com.knitnote.domain.usecase.GetProjectsUseCase
import com.knitnote.domain.usecase.IncrementRowUseCase
import org.koin.dsl.module

val useCaseModule = module {
    factory { GetProjectsUseCase(get()) }
    factory { CreateProjectUseCase(get()) }
    factory { IncrementRowUseCase(get()) }
    factory { DecrementRowUseCase(get()) }
    factory { GetProjectByIdUseCase(get()) }
    factory { DeleteProjectUseCase(get()) }
    factory { AddProgressNoteUseCase(get()) }
    factory { GetProgressNotesUseCase(get()) }
    factory { DeleteProgressNoteUseCase(get()) }
}
