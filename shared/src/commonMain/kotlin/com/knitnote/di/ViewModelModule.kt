package com.knitnote.di

import com.knitnote.ui.projectdetail.ProjectDetailViewModel
import com.knitnote.ui.projectlist.ProjectListViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val viewModelModule = module {
    viewModelOf(::ProjectListViewModel)
    viewModel { params ->
        ProjectDetailViewModel(
            projectId = params.get(),
            projectRepository = get(),
            incrementRow = get(),
            decrementRow = get(),
            addProgressNote = get(),
            getProgressNotes = get(),
            deleteProgressNote = get(),
            updateProject = get(),
            completeProject = get(),
            reopenProject = get(),
        )
    }
}
