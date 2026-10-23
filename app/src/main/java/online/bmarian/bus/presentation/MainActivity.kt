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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import online.bmarian.bus.Links
import online.bmarian.bus.Services
import online.bmarian.bus.data.Api
import online.bmarian.bus.data.Cities
import online.bmarian.bus.data.directionFromTripId
import online.bmarian.bus.follow.FollowManager
import online.bmarian.bus.follow.Followed
import online.bmarian.bus.follow.soonest
import online.bmarian.bus.follow.JourneyMirror
import online.bmarian.bus.presentation.home.HomeScreen
import online.bmarian.bus.presentation.home.NearbyMapScreen
import online.bmarian.bus.presentation.home.NearbyScreen
import online.bmarian.bus.presentation.home.RoutesScreen
import online.bmarian.bus.presentation.home.SearchScreen
import online.bmarian.bus.presentation.home.StopsScreen
import online.bmarian.bus.presentation.journey.JourneyMapScreen
import online.bmarian.bus.presentation.journey.JourneyScreen
import online.bmarian.bus.presentation.pair.PairScreen
import online.bmarian.bus.presentation.route.RouteScreen
import online.bmarian.bus.presentation.setup.Setup
import online.bmarian.bus.presentation.setup.SetupScreen
import online.bmarian.bus.presentation.setup.SetupStep
import online.bmarian.bus.presentation.route.routeViewModel
import online.bmarian.bus.presentation.stop.DepartureScreen
import online.bmarian.bus.presentation.stop.StopMapScreen
import online.bmarian.bus.presentation.stop.StopScreen
import online.bmarian.bus.presentation.stop.stopViewModel
import online.bmarian.bus.presentation.theme.ConexiuniClujWearOSTheme

const val EXTRA_OPEN_FOLLOWED = "openFollowed"
// Which follow to open; without it, the latest.
const val EXTRA_FOLLOW_ID = "followId"
// Tiles can't set intent data.
const val EXTRA_LINK = "link"
// A tapped reminder: its bus is followed.
const val EXTRA_FOLLOW = "follow"

class MainActivity : ComponentActivity() {
    // A follow's id, or "" for the latest.
    private val openFollowed = MutableStateFlow<String?>(null)
    private val openLink = MutableStateFlow<Links.Destination?>(null)
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
            CompositionLocalProvider(LocalAmbient provides isAmbient) { WearApp(openFollowed, openLink) }
        }
    }

    override fun onStart() {
        super.onStart()
        Services.scope.launch { Services.sync.syncNow() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    private fun take(intent: Intent?) {
        // Not again when reopened from recents.
        if (intent != null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) {
            intent.getStringExtra(EXTRA_FOLLOW)
                ?.let { runCatching { Api.json.decodeFromString<Followed>(it) }.getOrNull() }
                ?.let { followed ->
                    FollowManager.start(this, followed)
                    openFollowed.value = ""
                }
        }
        if (intent?.getBooleanExtra(EXTRA_OPEN_FOLLOWED, false) == true) openFollowed.value = intent.getStringExtra(EXTRA_FOLLOW_ID).orEmpty()
        val link = intent?.data ?: intent?.getStringExtra(EXTRA_LINK)?.toUri()
        when (link?.takeIf { it.scheme == Links.SCHEME }?.pathSegments?.firstOrNull()) {
            Links.FOLLOWING -> openFollowed.value = ""
            Links.JOURNEY -> openLink.value = Links.Destination(null, "journey")
            else -> link?.let(Links::destination)?.let { openLink.value = it }
        }
    }
}

private const val DEPARTURE_ROUTE = "departure/{stopId}/{routeId}/{tripId}"

@Composable
private fun WearApp(openFollowed: MutableStateFlow<String?>, openLink: MutableStateFlow<Links.Destination?>) {
    val navController = rememberSwipeDismissableNavController()
    val homeState = rememberTransformingLazyColumnState()
    val scope = rememberCoroutineScope()
    val toOpen by openFollowed.collectAsStateWithLifecycle()
    val linked by openLink.collectAsStateWithLifecycle()
    val follows by FollowManager.follows.collectAsStateWithLifecycle()
    val journey by JourneyMirror.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var setup by remember { mutableStateOf(Setup.firstRun(context)) }

    // The screens on the stack belong to this city.
    val city by Cities.current.collectAsStateWithLifecycle()
    var stackCity by rememberSaveable { mutableStateOf(city) }
    LaunchedEffect(city) {
        if (city == stackCity) return@LaunchedEffect
        navController.popBackStack("home", inclusive = false)
        stackCity = city
    }
    // Opens once the stack has moved to [target]'s city.
    suspend fun inCity(target: String?) {
        if (target == null || !Cities.known(target)) return
        Services.showCity(target)
        snapshotFlow { stackCity }.first { it == target }
    }
    fun askForNotifications() {
        if (setup.isEmpty() && !Setup.notificationsAllowed(context)) setup = listOf(SetupStep.Notifications)
    }

    fun openBus(followed: Followed) {
        scope.launch {
            inCity(followed.city)
            navController.navigate("departure/${followed.stopId}/${followed.routeId}/${followed.tripId}")
        }
    }

    LaunchedEffect(toOpen) {
        val wanted = toOpen ?: return@LaunchedEffect
        openFollowed.value = null
        // The link can arrive before the follow state.
        val follow = withTimeoutOrNull(10_000) {
            FollowManager.follows.map { list -> if (wanted.isEmpty()) list.lastOrNull() else list.firstOrNull { it.id == wanted } }.filterNotNull().first()
        } ?: return@LaunchedEffect
        inCity(follow.followed.city)
        val here = navController.currentBackStackEntry
        askForNotifications()
        if (here?.destination?.route == DEPARTURE_ROUTE && here.arguments?.getString("tripId") == follow.followed.tripId) return@LaunchedEffect
        navController.popBackStack("home", inclusive = false)
        openBus(follow.followed)
    }

    LaunchedEffect(linked) {
        val link = linked ?: return@LaunchedEffect
        openLink.value = null
        inCity(link.city)
        val route = link.route
        val here = navController.currentBackStackEntry?.destination?.route
        if (route == "journey") askForNotifications()
        if (route == "journey" && (here == "journey" || here == "journeyMap")) return@LaunchedEffect
        navController.popBackStack("home", inclusive = false)
        if (route != "home") navController.navigate(route)
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
                            onOpenBus = ::openBus,
                            onSearch = { navController.navigate("search/${Uri.encode(it)}") },
                            onNearby = { navController.navigate("nearby") },
                            onBrowseRoutes = { navController.navigate("routes") },
                            onBrowseStops = { navController.navigate("stops") },
                            onCopyFavorites = { navController.navigate("pair") },
                            onOpenJourney = { navController.navigate("journey") },
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
                        NearbyScreen(
                            onOpenStop = { navController.navigate("stop/$it") },
                            onOpenMap = { navController.navigate("nearbyMap") },
                        )
                    }
                    composable("nearbyMap") {
                        NearbyMapScreen(onOpenStop = { navController.navigate("stop/$it") })
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
                        DEPARTURE_ROUTE,
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
                    composable("journey") {
                        JourneyScreen(
                            onEnded = { navController.popBackStack("home", inclusive = false) },
                            onMap = { navController.navigate("journeyMap") },
                        )
                    }
                    composable("journeyMap") {
                        JourneyMapScreen(onEnded = { navController.popBackStack("home", inclusive = false) })
                    }
                    composable("pair") {
                        PairScreen(onDone = {
                            scope.launch { homeState.scrollToItem(0) }
                            navController.popBackStack("home", inclusive = false)
                        })
                    }
                }
                if (setup.isNotEmpty()) {
                    SetupScreen(setup) {
                        Setup.finish(context)
                        setup = emptyList()
                    }
                }
                val follow = follows.soonest()
                val trip = journey
                if (LocalAmbient.current && follow != null) AmbientFollow(follow)
                else if (LocalAmbient.current && trip != null) AmbientJourney(trip)
            }
        }
    }
}
