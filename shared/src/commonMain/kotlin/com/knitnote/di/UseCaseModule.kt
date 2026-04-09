package com.knitnote.di

import com.knitnote.domain.usecase.AddProgressNoteUseCase
import com.knitnote.domain.usecase.CompleteProjectUseCase
import com.knitnote.domain.usecase.CreateActivityUseCase
import com.knitnote.domain.usecase.CreateCommentUseCase
import com.knitnote.domain.usecase.CreateProjectUseCase
import com.knitnote.domain.usecase.DeleteCommentUseCase
import com.knitnote.domain.usecase.DecrementRowUseCase
import com.knitnote.domain.usecase.DeleteProgressNoteUseCase
import com.knitnote.domain.usecase.DeleteProjectUseCase
import com.knitnote.domain.usecase.ForkSharedPatternUseCase
import com.knitnote.domain.usecase.GetActivitiesUseCase
import com.knitnote.domain.usecase.GetCommentsUseCase
import com.knitnote.domain.usecase.GetProgressNotesUseCase
import com.knitnote.domain.usecase.GetProjectByIdUseCase
import com.knitnote.domain.usecase.GetProjectsUseCase
import com.knitnote.domain.usecase.GetReceivedSharesUseCase
import com.knitnote.domain.usecase.IncrementRowUseCase
import com.knitnote.domain.usecase.ObserveAuthStateUseCase
import com.knitnote.domain.usecase.ReopenProjectUseCase
import com.knitnote.domain.usecase.ResolveShareTokenUseCase
import com.knitnote.domain.usecase.ShareProjectUseCase
import com.knitnote.domain.usecase.SignInUseCase
import com.knitnote.domain.usecase.SignOutUseCase
import com.knitnote.domain.usecase.SignUpUseCase
import com.knitnote.domain.usecase.UpdateProjectUseCase
import com.knitnote.domain.usecase.UpdateShareStatusUseCase
import org.koin.dsl.module

val useCaseModule = module {
    factory { ObserveAuthStateUseCase(get()) }
    factory { SignInUseCase(get()) }
    factory { SignUpUseCase(get()) }
    factory { SignOutUseCase(get(), getOrNull(), getOrNull(), getOrNull()) }
    factory { GetProjectsUseCase(get(), get()) }
    factory { CreateProjectUseCase(get(), get(), getOrNull()) }
    factory { IncrementRowUseCase(get()) }
    factory { DecrementRowUseCase(get()) }
    factory { GetProjectByIdUseCase(get()) }
    factory { DeleteProjectUseCase(get()) }
    factory { AddProgressNoteUseCase(get()) }
    factory { GetProgressNotesUseCase(get()) }
    factory { DeleteProgressNoteUseCase(get()) }
    factory { UpdateProjectUseCase(get()) }
    factory { CompleteProjectUseCase(get(), getOrNull()) }
    factory { ReopenProjectUseCase(get()) }

    // Activity use cases (ActivityRepository is nullable — only available with Supabase)
    factory { CreateActivityUseCase(getOrNull()) }
    factory { GetActivitiesUseCase(getOrNull()) }

    // Comment use cases (CommentRepository is nullable — only available with Supabase)
    factory { GetCommentsUseCase(getOrNull()) }
    factory { CreateCommentUseCase(getOrNull(), get(), getOrNull()) }
    factory { DeleteCommentUseCase(getOrNull(), get()) }

    // Share use cases (ShareRepository is nullable — only available with Supabase)
    factory { ShareProjectUseCase(get(), get(), getOrNull(), get(), getOrNull()) }
    factory { ResolveShareTokenUseCase(getOrNull(), get(), get()) }
    factory { GetReceivedSharesUseCase(getOrNull(), get()) }
    factory { ForkSharedPatternUseCase(getOrNull(), get(), get(), get(), getOrNull()) }
    factory { UpdateShareStatusUseCase(getOrNull(), get()) }
}
