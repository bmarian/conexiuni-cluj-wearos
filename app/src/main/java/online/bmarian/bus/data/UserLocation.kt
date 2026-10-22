package online.bmarian.bus.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class Fix(val lat: Double, val lon: Double, val accuracyMeters: Float, val bearing: Float? = null)

private const val UPDATE_MS = 10_000L
// A saved fix older than this is taken as unknown, like the phone's.
private const val RECENT_MS = 60 * 60_000L

object UserLocation {
    private val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun permitted(context: Context) = PERMISSIONS.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun prefs(context: Context) = context.getSharedPreferences("location", Context.MODE_PRIVATE)

    /** Null away from [city]; a null [city] takes anywhere. */
    fun saved(context: Context, city: String? = Cities.current.value): Fix? {
        val prefs = prefs(context)
        if (!prefs.contains("lat")) return null
        return Fix(
            Double.fromBits(prefs.getLong("lat", 0)),
            Double.fromBits(prefs.getLong("lon", 0)),
            prefs.getFloat("accuracy", 0f),
        ).takeIf { city == null || Cities.contains(it.lat, it.lon, city) }
    }

    fun save(context: Context, fix: Fix) {
        prefs(context).edit {
            putLong("lat", fix.lat.toRawBits())
            putLong("lon", fix.lon.toRawBits())
            putFloat("accuracy", fix.accuracyMeters)
            putLong("at", System.currentTimeMillis())
        }
    }

    /** The saved fix while it's fresh. */
    fun savedAt(context: Context) = prefs(context).getLong("at", 0)

    fun recent(context: Context, city: String? = Cities.current.value): Fix? =
        saved(context, city)?.takeIf { permitted(context) && System.currentTimeMillis() - prefs(context).getLong("at", 0) <= RECENT_MS }

    @SuppressLint("MissingPermission")
    /** Null away from [city]: nothing there is near the person. */
    fun updates(context: Context, intervalMs: Long = UPDATE_MS, city: String = Cities.current.value): Flow<Fix?> = callbackFlow {
        if (!permitted(context)) {
            send(null)
            awaitClose {}
            return@callbackFlow
        }
        val client = LocationServices.getFusedLocationProviderClient(context)
        fun offer(location: Location?) {
            trySend(
                location?.takeIf { Cities.contains(it.latitude, it.longitude, city) }
                    ?.let { Fix(it.latitude, it.longitude, it.accuracy, it.bearing.takeIf { _ -> it.hasBearing() }) },
            )
        }
        client.lastLocation.addOnSuccessListener { if (it != null) offer(it) }
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) = offer(result.lastLocation)
        }
        client.requestLocationUpdates(
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs).build(),
            callback,
            Looper.getMainLooper(),
        )
        awaitClose { client.removeLocationUpdates(callback) }
    }
}
