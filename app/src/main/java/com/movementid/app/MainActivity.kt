package com.movementid.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.movementid.app.backup.BackupWorker
import com.movementid.app.ui.screens.DetailScreen
import com.movementid.app.ui.screens.HomeScreen
import com.movementid.app.ui.screens.ScanScreen
import com.movementid.app.ui.screens.SettingsScreen
import com.movementid.app.ui.screens.SplashScreen
import com.movementid.app.ui.theme.MovementIdTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate(). Swaps the black system splash for the app theme,
        // so the only splash the user actually perceives is the Compose one.
        installSplashScreen()
        // Android 15+ forces edge-to-edge for apps targeting API 35 or higher. Opting in
        // explicitly gives consistent system-bar styling on older versions too; the Scaffold on
        // each screen already pads content clear of the bars.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Images shared from Gallery, Google Photos or anywhere else.
        handleShareIntent(intent)

        // Re-assert the daily backup job; it's a no-op when backup is switched off.
        BackupWorker.schedule(this)

        setContent {
            MovementIdTheme {
                MovementIdApp(viewModel)
            }
        }
    }

    /**
     * The activity is singleTask, so a share that arrives while the app is already open comes
     * here rather than through onCreate.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.type?.startsWith("image/") != true) return

        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))

            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    .orEmpty()

            else -> emptyList()
        }

        viewModel.receiveSharedImages(uris)

        // Consume it, so a configuration change doesn't re-import the same images.
        intent.action = null
        intent.removeExtra(Intent.EXTRA_STREAM)
    }
}

/**
 * Home is the start destination: the app opens on the saved collection, and scanning is an
 * action taken from there rather than the thing the app throws you into.
 */
@Composable
fun MovementIdApp(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val sharedReady by viewModel.sharedImagesReady.collectAsState()

    // A shared image means the user already chose their subject — go straight to Identify.
    // Skipped while the splash is still up: it finishes by routing to Scan itself, otherwise
    // its own navigation would land on top of this one on a cold start.
    LaunchedEffect(sharedReady) {
        val onSplash = navController.currentBackStackEntry?.destination?.route == "splash"
        if (sharedReady && !onSplash) {
            navController.navigate("scan") { launchSingleTop = true }
            viewModel.onSharedImagesHandled()
        }
    }

    NavHost(navController = navController, startDestination = "splash") {

        composable("splash") {
            SplashScreen(
                onFinished = {
                    // Home is always the base of the stack, so Back from a shared-image scan
                    // lands on the collection rather than closing the app.
                    navController.navigate("home") {
                        // Drop the splash so Back from Home exits rather than replaying it.
                        popUpTo("splash") { inclusive = true }
                    }
                    if (sharedReady) {
                        navController.navigate("scan") { launchSingleTop = true }
                        viewModel.onSharedImagesHandled()
                    }
                }
            )
        }

        composable("home") {
            HomeScreen(
                viewModel = viewModel,
                onEntryClick = { id -> navController.navigate("detail/$id") },
                onScanClick = { navController.navigate("scan") },
                onSettingsClick = { navController.navigate("settings") }
            )
        }

        composable("scan") {
            ScanScreen(
                viewModel = viewModel,
                onSaved = { id ->
                    // Replace the scan screen so Back from details returns Home, not the camera.
                    navController.navigate("detail/$id") {
                        popUpTo("home")
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = "detail/{entryId}",
            arguments = listOf(navArgument("entryId") { type = NavType.LongType })
        ) { backStackEntry ->
            DetailScreen(
                viewModel = viewModel,
                entryId = backStackEntry.arguments?.getLong("entryId") ?: 0L,
                onBack = { navController.popBackStack() }
            )
        }

        composable("settings") {
            SettingsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
    }
}
