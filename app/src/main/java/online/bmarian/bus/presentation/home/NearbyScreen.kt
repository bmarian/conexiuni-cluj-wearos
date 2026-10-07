package online.bmarian.bus.presentation.home

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import online.bmarian.bus.R
import online.bmarian.bus.Services
import online.bmarian.bus.logic.formatMeters
import online.bmarian.bus.logic.haversineMeters
import online.bmarian.bus.presentation.ListScreen
import online.bmarian.bus.presentation.Loading
import online.bmarian.bus.presentation.Message
import online.bmarian.bus.presentation.Title
import online.bmarian.bus.presentation.itemModifier
import online.bmarian.bus.presentation.surface
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.ceil

// Same reach and count as the website's nearby list.
private const val NEARBY_RADIUS_METERS = 700.0
private const val NEARBY_COUNT = 6
// The website's walking pace, for the minutes on foot.
private const val WALKING_METERS_PER_MINUTE = 80.0

private val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

private fun hasLocationPermission(context: Context) = LOCATION_PERMISSIONS.any {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

@SuppressLint("MissingPermission")
private suspend fun currentLocation(context: Context): Location? = runCatching {
    val client = LocationServices.getFusedLocationProviderClient(context)
    val tokens = CancellationTokenSource()
    try {
        withTimeoutOrNull(20_000) { client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, tokens.token).await() }
            ?: client.lastLocation.await()
    } finally {
        tokens.cancel()
    }
}.getOrNull()

private sealed interface Fix {
    data object Asking : Fix
    data object Denied : Fix
    data object Locating : Fix
    data object Unavailable : Fix
    class Found(val lat: Double, val lon: Double) : Fix
}

@Composable
fun NearbyScreen(onOpenStop: (Int) -> Unit) {
    val context = LocalContext.current
    val stops by Services.repository.stops.collectAsStateWithLifecycle()
    var fix by remember { mutableStateOf<Fix>(if (hasLocationPermission(context)) Fix.Locating else Fix.Asking) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        fix = if (result.values.any { it }) Fix.Locating else Fix.Denied
    }

    LaunchedEffect(Unit) {
        Services.repository.refreshStops()
        if (fix == Fix.Asking) launcher.launch(LOCATION_PERMISSIONS)
    }
    LaunchedEffect(fix) {
        if (fix != Fix.Locating) return@LaunchedEffect
        fix = currentLocation(context)?.let { Fix.Found(it.latitude, it.longitude) } ?: Fix.Unavailable
    }

    val found = fix as? Fix.Found
    val nearby = remember(stops, found) {
        if (found == null) emptyList()
        else stops.map { it to haversineMeters(found.lat, found.lon, it.lat, it.lon) }
            .filter { it.second <= NEARBY_RADIUS_METERS }
            .sortedBy { it.second }
            .take(NEARBY_COUNT)
    }

    ListScreen { spec ->
        item { Title(stringResource(R.string.nearby_stops), spec) }
        when (fix) {
            Fix.Asking, Fix.Locating -> item { Loading(spec) }
            Fix.Denied, Fix.Unavailable -> {
                item { Message(stringResource(R.string.nearby_unavailable), spec) }
                item {
                    FilledTonalButton(
                        onClick = { if (hasLocationPermission(context)) fix = Fix.Locating else launcher.launch(LOCATION_PERMISSIONS) },
                        modifier = itemModifier(spec),
                        transformation = surface(spec),
                        icon = { Icon(Icons.Rounded.MyLocation, contentDescription = null) },
                        label = { Text(stringResource(R.string.nearby_enable)) },
                    )
                }
            }
            is Fix.Found -> {
                if (nearby.isEmpty()) item { Message(stringResource(R.string.nearby_none), spec) }
                items(nearby.size, key = { nearby[it].first.stopId }) { i ->
                    val (stop, meters) = nearby[i]
                    FilledTonalButton(
                        onClick = { onOpenStop(stop.stopId) },
                        modifier = itemModifier(spec),
                        transformation = surface(spec),
                        icon = { Icon(Icons.Rounded.Place, contentDescription = null) },
                        label = { Text(stop.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        secondaryLabel = {
                            Text(stringResource(R.string.walk_distance, formatMeters(meters), ceil(meters / WALKING_METERS_PER_MINUTE).toInt().coerceAtLeast(1)))
                        },
                    )
                }
            }
        }
    }
}
