package com.michele.teslawatch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.michele.teslawatch.ui.ChargingScreen
import com.michele.teslawatch.ui.ClimateScreen
import com.michele.teslawatch.ui.HomeScreen
import com.michele.teslawatch.ui.MainScreen
import com.michele.teslawatch.ui.SettingsScreen
import com.michele.teslawatch.ui.TrunkScreen
import com.michele.teslawatch.ui.theme.TeslaWatchTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object Routes {
    const val MAIN = "main"
    const val CLIMATE = "climate"
    const val TRUNK = "trunk"
    const val CHARGING = "charging"
    const val HOME = "home"
    const val SETTINGS = "settings"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        val repo = TeslaWatchApp.instance.repo

        // Refresh every 15 s only while the app is visible: stops by itself when you leave
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    repo.refresh(maxAgeMs = 5_000)
                    delay(15_000)
                }
            }
        }

        setContent {
            TeslaWatchTheme {
                AppNavigation()
            }
        }
    }
}

@Composable
private fun AppNavigation() {
    val nav = rememberSwipeDismissableNavController()
    val state by TeslaWatchApp.instance.repo.state.collectAsState()

    SwipeDismissableNavHost(
        navController = nav,
        startDestination = Routes.MAIN
    ) {
        composable(Routes.MAIN) {
            MainScreen(
                state = state,
                onClimate = { nav.navigate(Routes.CLIMATE) },
                onTrunk = { nav.navigate(Routes.TRUNK) },
                onCharging = { nav.navigate(Routes.CHARGING) },
                onHome = { nav.navigate(Routes.HOME) },
                onSettings = { nav.navigate(Routes.SETTINGS) }
            )
        }
        composable(Routes.CLIMATE) { ClimateScreen(state = state) }
        composable(Routes.TRUNK) { TrunkScreen(state = state) }
        composable(Routes.CHARGING) { ChargingScreen(state = state) }
        composable(Routes.HOME) { HomeScreen(state = state) }
        composable(Routes.SETTINGS) { SettingsScreen() }
    }
}
