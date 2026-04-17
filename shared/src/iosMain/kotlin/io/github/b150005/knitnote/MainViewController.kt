package io.github.b150005.knitnote

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.ComposeUIViewController
import androidx.navigation.compose.rememberNavController
import io.github.b150005.knitnote.ui.navigation.KnitNoteNavHost

@Suppress("ktlint:standard:function-naming")
fun MainViewController() =
    ComposeUIViewController {
        KnitNoteApp()
    }

@Composable
private fun KnitNoteApp() {
    MaterialTheme {
        val navController = rememberNavController()
        KnitNoteNavHost(navController = navController)
    }
}
