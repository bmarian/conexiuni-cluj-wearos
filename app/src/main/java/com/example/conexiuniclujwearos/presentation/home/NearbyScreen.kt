package com.example.conexiuniclujwearos.presentation.home

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.conexiuniclujwearos.R
import com.example.conexiuniclujwearos.Services
import com.example.conexiuniclujwearos.logic.formatMeters
import com.example.conexiuniclujwearos.logic.haversineMeters
import com.example.conexiuniclujwearos.presentation.ActionRow
import com.example.conexiuniclujwearos.presentation.Heading
import com.example.conexiuniclujwearos.presentation.Icons
import com.example.conexiuniclujwearos.presentation.ListRow
import com.example.conexiuniclujwearos.presentation.ListScreen
import com.example.conexiuniclujwearos.presentation.Note
import com.example.conexiuniclujwearos.presentation.StopIcon
import com.example.conexiuniclujwearos.presentation.transformed
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

// Same reach and count as the website's nearby list.
private const val NEARBY_RADIUS_METERS = 700.0
private const val NEARBY_COUNT = 6

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
        item { Heading(stringResource(R.string.nearby_stops), transformed(spec)) }
        when (fix) {
            Fix.Asking, Fix.Locating -> item { Note(stringResource(R.string.nearby_locating), transformed(spec)) }
            Fix.Denied, Fix.Unavailable -> {
                item { Note(stringResource(R.string.nearby_unavailable), transformed(spec)) }
                item {
                    ActionRow(
                        stringResource(R.string.nearby_enable),
                        onClick = {
                            if (hasLocationPermission(context)) fix = Fix.Locating else launcher.launch(LOCATION_PERMISSIONS)
                        },
                        modifier = transformed(spec),
                        icon = Icons.Location,
                    )
                }
            }
            is Fix.Found -> {
                if (nearby.isEmpty()) item { Note(stringResource(R.string.nearby_none), transformed(spec)) }
                items(nearby.size, key = { nearby[it].first.stopId }) { i ->
                    val (stop, meters) = nearby[i]
                    ListRow(
                        title = stop.name,
                        sub = formatMeters(meters),
                        onClick = { onOpenStop(stop.stopId) },
                        modifier = transformed(spec),
                        leading = { StopIcon() },
                    )
                }
            }
        }
    }
}
