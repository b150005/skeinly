package com.knitnote

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.ComposeUIViewController
import androidx.navigation.compose.rememberNavController
import com.knitnote.ui.navigation.KnitNoteNavHost

fun MainViewController() = ComposeUIViewController {
    KnitNoteApp()
}

@Composable
private fun KnitNoteApp() {
    MaterialTheme {
        val navController = rememberNavController()
        KnitNoteNavHost(navController = navController)
    }
}
