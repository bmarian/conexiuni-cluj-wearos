package com.example.conexiuniclujwearos.presentation

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.example.conexiuniclujwearos.presentation.home.HomeScreen
import com.example.conexiuniclujwearos.presentation.home.NearbyScreen
import com.example.conexiuniclujwearos.presentation.home.RoutesScreen
import com.example.conexiuniclujwearos.presentation.home.StopsScreen
import com.example.conexiuniclujwearos.presentation.pair.PairScreen
import com.example.conexiuniclujwearos.presentation.route.RouteScreen
import com.example.conexiuniclujwearos.presentation.route.routeViewModel
import com.example.conexiuniclujwearos.presentation.stop.StopMapScreen
import com.example.conexiuniclujwearos.presentation.stop.StopScreen
import com.example.conexiuniclujwearos.presentation.stop.stopViewModel
import com.example.conexiuniclujwearos.presentation.theme.ConexiuniClujWearOSTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

const val EXTRA_STOP_ID = "stopId"

class MainActivity : ComponentActivity() {
    // A stop to open, from the tile or the complication.
    private val pendingStop = MutableStateFlow<Int?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) takeStop(intent)
        setContent { WearApp(pendingStop) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeStop(intent)
    }

    private fun takeStop(intent: Intent?) {
        val stopId = intent?.getIntExtra(EXTRA_STOP_ID, -1) ?: -1
        if (stopId >= 0) pendingStop.value = stopId
    }
}

@Composable
fun WearApp(pendingStop: MutableStateFlow<Int?>) {
    val navController = rememberSwipeDismissableNavController()
    val homeState = rememberTransformingLazyColumnState()
    val scope = rememberCoroutineScope()
    val stopToOpen by pendingStop.collectAsStateWithLifecycle()

    LaunchedEffect(stopToOpen) {
        val stopId = stopToOpen ?: return@LaunchedEffect
        pendingStop.value = null
        navController.popBackStack("home", inclusive = false)
        navController.navigate("stop/$stopId")
    }

    ConexiuniClujWearOSTheme {
        AppScaffold {
            SwipeDismissableNavHost(navController = navController, startDestination = "home") {
                composable("home") {
                    HomeScreen(
                        state = homeState,
                        onOpenStop = { navController.navigate("stop/$it") },
                        onOpenRoute = { id, direction -> navController.navigate("route/$id/$direction") },
                        onNearby = { navController.navigate("nearby") },
                        onBrowseRoutes = { navController.navigate("routes") },
                        onBrowseStops = { navController.navigate("stops") },
                        onCopyFavorites = { navController.navigate("pair") },
                    )
                }
                composable("nearby") {
                    NearbyScreen(onOpenStop = { navController.navigate("stop/$it") })
                }
                composable("routes") {
                    RoutesScreen(onOpenRoute = { navController.navigate("route/$it/0") })
                }
                composable(
                    "stops?letter={letter}&prefix={prefix}",
                    arguments = listOf(
                        navArgument("letter") { type = NavType.StringType; nullable = true },
                        navArgument("prefix") { type = NavType.StringType; nullable = true },
                    ),
                ) { entry ->
                    StopsScreen(
                        letter = entry.arguments?.getString("letter"),
                        prefix = entry.arguments?.getString("prefix"),
                        onBrowse = { letter, prefix ->
                            navController.navigate("stops?letter=$letter" + (prefix?.let { "&prefix=$it" } ?: ""))
                        },
                        onOpenStop = { navController.navigate("stop/$it") },
                    )
                }
                composable("stop/{stopId}", arguments = listOf(navArgument("stopId") { type = NavType.IntType })) { entry ->
                    val stopId = entry.arguments?.getInt("stopId") ?: 0
                    StopScreen(
                        vm = stopViewModel(stopId),
                        onOpenRoute = { routeId, direction -> navController.navigate("route/$routeId/$direction?from=$stopId") },
                        onOpenMap = { navController.navigate("stopMap/$stopId") },
                    )
                }
                composable("stopMap/{stopId}", arguments = listOf(navArgument("stopId") { type = NavType.IntType })) { entry ->
                    StopMapScreen(stopViewModel(entry.arguments?.getInt("stopId") ?: 0))
                }
                composable(
                    "route/{routeId}/{direction}?from={from}",
                    arguments = listOf(
                        navArgument("routeId") { type = NavType.IntType },
                        navArgument("direction") { type = NavType.StringType },
                        navArgument("from") { type = NavType.IntType; defaultValue = -1 },
                    ),
                ) { entry ->
                    val args = entry.arguments
                    val from = args?.getInt("from") ?: -1
                    RouteScreen(
                        routeViewModel(
                            routeId = args?.getInt("routeId") ?: 0,
                            direction = args?.getString("direction") ?: "0",
                            fromStopId = from.takeIf { it >= 0 },
                        ),
                    )
                }
                composable("pair") {
                    PairScreen(onDone = {
                        // Back on home at the top, where the copied favorites are.
                        scope.launch { homeState.scrollToItem(0) }
                        navController.popBackStack("home", inclusive = false)
                    })
                }
            }
        }
    }
}
