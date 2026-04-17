package io.github.b150005.knitnote.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.navigation.compose.rememberNavController
import io.github.b150005.knitnote.ui.navigation.KnitNoteNavHost

class MainActivity : ComponentActivity() {
    private var deepLinkToken by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        deepLinkToken = extractShareToken(intent)
        setContent {
            KnitNoteTheme {
                val navController = rememberNavController()
                Box(
                    Modifier
                        .fillMaxSize()
                        .semantics { testTagsAsResourceId = true },
                ) {
                    KnitNoteNavHost(
                        navController = navController,
                        deepLinkToken = deepLinkToken,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkToken = extractShareToken(intent)
    }

    private fun extractShareToken(intent: Intent?): String? {
        val data = intent?.data ?: return null
        if (data.scheme != "knitnote" || data.host != "share") return null
        val segment = data.lastPathSegment ?: return null

        // Validate token format: UUID v4 (tokens are generated via Uuid.random().toString())
        val uuidPattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        return if (uuidPattern.matches(segment)) segment else null
    }
}
