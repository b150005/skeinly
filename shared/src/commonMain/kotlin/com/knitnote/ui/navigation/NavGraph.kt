package com.knitnote.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.knitnote.ui.projectdetail.ProjectDetailScreen
import com.knitnote.ui.projectlist.ProjectListScreen
import kotlinx.serialization.Serializable

@Serializable
data object ProjectList

@Serializable
data class ProjectDetail(val projectId: String)

@Composable
fun KnitNoteNavHost(navController: NavHostController) {
    NavHost(navController = navController, startDestination = ProjectList) {
        composable<ProjectList> {
            ProjectListScreen(
                onProjectClick = { projectId ->
                    navController.navigate(ProjectDetail(projectId = projectId))
                },
            )
        }
        composable<ProjectDetail> { backStackEntry ->
            val route = backStackEntry.toRoute<ProjectDetail>()
            ProjectDetailScreen(
                projectId = route.projectId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
