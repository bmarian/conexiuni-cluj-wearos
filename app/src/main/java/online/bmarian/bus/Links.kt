package online.bmarian.bus

import android.net.Uri
import androidx.core.net.toUri
import online.bmarian.bus.data.Cities

object Links {
    const val SCHEME = "conexiunicluj"
    const val HOST = "bus.bmarian.online"
    const val FOLLOWING = "following"
    const val JOURNEY = "journey"

    fun stop(stopId: Int) = "stop/$stopId"
    fun route(routeId: Int, direction: String) = "route/$routeId/$direction"

    /** Opened here; without a city, the one on screen. */
    fun app(path: String): Uri = "$SCHEME://$HOST/$path".toUri()

    /** Opened on the phone, in the city on screen here. */
    fun phone(path: String): Uri = "$SCHEME://$HOST/${Cities.current.value}/$path".toUri()

    fun web(path: String): Uri = "${BuildConfig.SITE_ORIGIN}/${Cities.current.value}/$path".toUri()

    class Destination(val city: String?, val route: String)

    fun destination(uri: Uri): Destination? {
        if (uri.scheme != SCHEME || uri.host != HOST) return null
        val city = uri.pathSegments.firstOrNull()?.takeIf { it != "stop" && it != "route" && Cities.isSlug(it) }
        val parts = if (city != null) uri.pathSegments.drop(1) else uri.pathSegments
        val route = when {
            parts.size == 2 && parts[0] == "stop" -> parts[1].toIntOrNull()?.let(::stop)
            parts.size == 3 && parts[0] == "route" && (parts[2] == "0" || parts[2] == "1") ->
                parts[1].toIntOrNull()?.let { route(it, parts[2]) }
            else -> null
        } ?: return null
        return Destination(city, route)
    }
}
