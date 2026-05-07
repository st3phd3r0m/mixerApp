package com.example.mixerapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.mixerapp.ui.screens.MixerScreen
import com.example.mixerapp.ui.screens.SessionsScreen
import com.example.mixerapp.ui.theme.MixerAppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MixerAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MixerNavGraph()
                }
            }
        }
    }
}

@Composable
fun MixerNavGraph() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "sessions") {

        composable("sessions") {
            SessionsScreen(
                onSessionClick = { session ->
                    navController.navigate("mixer/${session.id}/${session.name.encode()}")
                }
            )
        }

        composable(
            route = "mixer/{sessionId}/{sessionName}",
            arguments = listOf(
                navArgument("sessionId") { type = NavType.IntType },
                navArgument("sessionName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getInt("sessionId") ?: 0
            val sessionName = backStackEntry.arguments?.getString("sessionName")?.decode() ?: "Session"
            MixerScreen(
                sessionId = sessionId,
                sessionName = sessionName,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

// ── Helpers pour encoder les noms dans les routes ────────────────────────────

private fun String.encode(): String =
    java.net.URLEncoder.encode(this, "UTF-8")

private fun String.decode(): String =
    java.net.URLDecoder.decode(this, "UTF-8")
