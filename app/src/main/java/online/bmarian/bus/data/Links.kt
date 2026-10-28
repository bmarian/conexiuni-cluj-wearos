package online.bmarian.bus.data

import android.net.Uri
import online.bmarian.bus.BuildConfig
import androidx.core.net.toUri

object Links {
    const val SCHEME = "conexiunicluj"
    const val HOST = "bus.bmarian.online"
    const val FOLLOWING = "following"
    const val JOURNEY = "journey"
    const val SEARCH = "search"

    fun stop(stopId: Int) = "stop/$stopId"
    fun route(routeId: Int, direction: String) = "route/$routeId/$direction"
    fun departure(stopId: Int, routeId: Int, tripId: String) = "departure/$stopId/$routeId/$tripId"

    /** Opened here; without a city, the one on screen. */
    fun app(path: String): Uri = "$SCHEME://$HOST/$path".toUri()

    /** Opened on the phone, in the city on screen here. */
    fun phone(path: String): Uri = "$SCHEME://$HOST/${Cities.current.value}/$path".toUri()

    fun web(path: String): Uri = "${BuildConfig.SITE_ORIGIN}/${Cities.current.value}/$path".toUri()

    private val PAGES = setOf("stop", "route", "departure", SEARCH)

    class Destination(val city: String?, val route: String)

    fun destination(uri: Uri): Destination? {
        if (uri.scheme != SCHEME || uri.host != HOST) return null
        val city = uri.pathSegments.firstOrNull()?.takeIf { it !in PAGES && Cities.isSlug(it) }
        val parts = if (city != null) uri.pathSegments.drop(1) else uri.pathSegments
        val route = when {
            parts.size == 1 && parts[0] == SEARCH -> SEARCH
            parts.size == 2 && parts[0] == "stop" -> parts[1].toIntOrNull()?.let(::stop)
            parts.size == 4 && parts[0] == "departure" ->
                parts[1].toIntOrNull()?.let { stopId -> parts[2].toIntOrNull()?.let { departure(stopId, it, parts[3]) } }
            parts.size == 3 && parts[0] == "route" && (parts[2] == "0" || parts[2] == "1") ->
                parts[1].toIntOrNull()?.let { route(it, parts[2]) }
            else -> null
        } ?: return null
        return Destination(city, route)
    }
}
