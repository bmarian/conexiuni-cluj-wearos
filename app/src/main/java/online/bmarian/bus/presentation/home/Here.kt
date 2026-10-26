package online.bmarian.bus.presentation.home

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import online.bmarian.bus.data.Fix
import online.bmarian.bus.data.UserLocation
import online.bmarian.bus.logic.haversineMeters

internal const val MOVED_METERS = 50.0
private const val FIRST_FIX_TIMEOUT_MS = 20_000L

private val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

enum class HereStatus { Waiting, Found, Off, NotAllowed }

class Here(val fix: Fix?, val status: HereStatus, val ask: () -> Unit)

@Composable
fun rememberHere(): Here {
    val context = LocalContext.current
    var permitted by remember { mutableStateOf(UserLocation.permitted(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permitted = result.values.any { it }
    }
    // Setup can grant it while this screen is underneath.
    LifecycleResumeEffect(Unit) {
        permitted = UserLocation.permitted(context)
        onPauseOrDispose {}
    }
    var shown by remember { mutableStateOf(if (permitted) UserLocation.saved(context) else null) }
    var gaveUp by remember { mutableStateOf(false) }
    val updates = remember(permitted) { UserLocation.updates(context) }
    val latest by updates.collectAsStateWithLifecycle(null)

    LaunchedEffect(permitted) { if (permitted && shown == null) shown = UserLocation.saved(context) }
    LaunchedEffect(latest) {
        val fix = latest ?: return@LaunchedEffect
        val current = shown
        if (current == null || haversineMeters(current.lat, current.lon, fix.lat, fix.lon) > MOVED_METERS) {
            shown = fix
            UserLocation.save(context, fix)
        }
    }
    LaunchedEffect(permitted, shown, gaveUp) {
        if (permitted && shown == null && !gaveUp) {
            delay(FIRST_FIX_TIMEOUT_MS)
            if (shown == null) gaveUp = true
        }
    }

    val status = when {
        !permitted -> HereStatus.NotAllowed
        shown != null -> HereStatus.Found
        gaveUp -> HereStatus.Off
        else -> HereStatus.Waiting
    }
    return Here(shown.takeIf { permitted }, status) { if (permitted) gaveUp = false else launcher.launch(LOCATION_PERMISSIONS) }
}
