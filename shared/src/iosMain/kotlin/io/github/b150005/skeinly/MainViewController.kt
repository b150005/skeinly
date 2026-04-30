package io.github.b150005.skeinly

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.ComposeUIViewController
import androidx.navigation.compose.rememberNavController
import io.github.b150005.skeinly.ui.navigation.SkeinlyNavHost

@Suppress("ktlint:standard:function-naming")
fun MainViewController() =
    ComposeUIViewController {
        SkeinlyApp()
    }

@Composable
private fun SkeinlyApp() {
    MaterialTheme {
        val navController = rememberNavController()
        SkeinlyNavHost(navController = navController)
    }
}
