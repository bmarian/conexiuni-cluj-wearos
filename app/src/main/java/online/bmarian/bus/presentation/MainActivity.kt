package online.bmarian.bus.presentation

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.ambient.AmbientLifecycleObserver
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import online.bmarian.bus.data.directionFromTripId
import online.bmarian.bus.follow.FollowManager
import online.bmarian.bus.presentation.home.HomeScreen
import online.bmarian.bus.presentation.home.NearbyScreen
import online.bmarian.bus.presentation.home.RoutesScreen
import online.bmarian.bus.presentation.home.SearchScreen
import online.bmarian.bus.presentation.home.StopsScreen
import online.bmarian.bus.presentation.pair.PairScreen
import online.bmarian.bus.presentation.route.RouteScreen
import online.bmarian.bus.presentation.route.routeViewModel
import online.bmarian.bus.presentation.stop.DepartureScreen
import online.bmarian.bus.presentation.stop.StopMapScreen
import online.bmarian.bus.presentation.stop.StopScreen
import online.bmarian.bus.presentation.stop.stopViewModel
import online.bmarian.bus.presentation.theme.ConexiuniClujWearOSTheme

const val EXTRA_OPEN_FOLLOWED = "openFollowed"


class MainActivity : ComponentActivity() {
    // Set when the followed bus's notification or watch-face icon opened the app.
    private val openFollowed = MutableStateFlow(false)
    private val ambient = MutableStateFlow(false)

    private val ambientObserver = AmbientLifecycleObserver(
        this,
        object : AmbientLifecycleObserver.AmbientLifecycleCallback {
            override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                ambient.value = true
            }

            override fun onExitAmbient() {
                ambient.value = false
            }

            override fun onUpdateAmbient() {
                AmbientTicks.ticks.tryEmit(Unit)
            }
        },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(ambientObserver)
        if (savedInstanceState == null) take(intent)
        setContent {
            val isAmbient by ambient.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalAmbient provides isAmbient) { WearApp(openFollowed) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    private fun take(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_FOLLOWED, false) == true) openFollowed.value = true
    }
}

@Composable
private fun WearApp(openFollowed: MutableStateFlow<Boolean>) {
    val navController = rememberSwipeDismissableNavController()
    val homeState = rememberTransformingLazyColumnState()
    val scope = rememberCoroutineScope()
    val toOpen by openFollowed.collectAsStateWithLifecycle()
    val following by FollowManager.state.collectAsStateWithLifecycle()

    fun openFollowed() {
        val followed = FollowManager.state.value?.followed ?: return
        navController.navigate("departure/${followed.stopId}/${followed.routeId}/${followed.tripId}")
    }

    LaunchedEffect(toOpen) {
        if (!toOpen) return@LaunchedEffect
        openFollowed.value = false
        navController.popBackStack("home", inclusive = false)
        openFollowed()
    }

    ConexiuniClujWearOSTheme {
        AppScaffold {
            Box(Modifier.fillMaxSize()) {
                SwipeDismissableNavHost(navController = navController, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            state = homeState,
                            onOpenStop = { navController.navigate("stop/$it") },
                            onOpenRoute = { id, direction -> navController.navigate("route/$id/$direction") },
                            onOpenFollowed = ::openFollowed,
                            onSearch = { navController.navigate("search/${Uri.encode(it)}") },
                            onNearby = { navController.navigate("nearby") },
                            onBrowseRoutes = { navController.navigate("routes") },
                            onBrowseStops = { navController.navigate("stops") },
                            onCopyFavorites = { navController.navigate("pair") },
                        )
                    }
                    composable("search/{query}") { entry ->
                        SearchScreen(
                            query = entry.arguments?.getString("query").orEmpty(),
                            onSearch = { navController.navigate("search/${Uri.encode(it)}") { popUpTo("home") } },
                            onOpenRoute = { navController.navigate("route/$it/0") },
                            onOpenStop = { navController.navigate("stop/$it") },
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
                            onOpenDeparture = { navController.navigate("departure/$stopId/${it.routeId}/${it.tripId}") },
                            onOpenMap = { navController.navigate("stopMap/$stopId") },
                        )
                    }
                    composable(
                        "departure/{stopId}/{routeId}/{tripId}",
                        arguments = listOf(
                            navArgument("stopId") { type = NavType.IntType },
                            navArgument("routeId") { type = NavType.IntType },
                            navArgument("tripId") { type = NavType.StringType },
                        ),
                    ) { entry ->
                        val args = entry.arguments
                        val stopId = args?.getInt("stopId") ?: 0
                        val routeId = args?.getInt("routeId") ?: 0
                        val tripId = args?.getString("tripId").orEmpty()
                        DepartureScreen(
                            vm = stopViewModel(stopId),
                            routeId = routeId,
                            tripId = tripId,
                            onOpenRoute = { navController.navigate("route/$routeId/${directionFromTripId(tripId)}?from=$stopId") },
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
                // Wrist down while following: only the countdown, like Maps shows the next turn.
                val follow = following
                if (LocalAmbient.current && follow != null) AmbientFollow(follow)
            }
        }
    }
}
