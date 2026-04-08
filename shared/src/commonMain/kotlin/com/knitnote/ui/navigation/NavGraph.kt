package com.knitnote.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.knitnote.ui.projectdetail.ProjectDetailScreen
import com.knitnote.ui.projectlist.ProjectListScreen

@Composable
fun KnitNoteNavHost(navController: NavHostController) {
    NavHost(navController = navController, startDestination = "projects") {
        composable("projects") {
            ProjectListScreen(
                onProjectClick = { projectId ->
                    navController.navigate("projects/$projectId")
                },
            )
        }
        composable(
            route = "projects/{projectId}",
            arguments = listOf(
                navArgument("projectId") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId") ?: return@composable
            ProjectDetailScreen(
                projectId = projectId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
