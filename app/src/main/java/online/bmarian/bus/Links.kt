package online.bmarian.bus

import android.net.Uri
import androidx.core.net.toUri

/**
 * Links to a stop or a line that both apps open: conexiunicluj:// in the app on the phone or the
 * watch, https on the website. The paths are the same as this app's navigation routes.
 */
object Links {
    const val SCHEME = "conexiunicluj"
    const val HOST = "bus.bmarian.online"
    // Opened by the phone when it starts following a bus or a journey.
    const val FOLLOWING = "following"
    const val JOURNEY = "journey"

    fun stop(stopId: Int) = "stop/$stopId"
    fun route(routeId: Int, direction: String) = "route/$routeId/$direction"

    fun app(path: String): Uri = "$SCHEME://$HOST/$path".toUri()
    fun web(path: String): Uri = "${BuildConfig.SITE_ORIGIN}/$path".toUri()

    /** The screen a link opens here, as a navigation route; null if it isn't one of ours. */
    fun destination(uri: Uri): String? {
        if (uri.scheme != SCHEME || uri.host != HOST) return null
        val parts = uri.pathSegments
        return when {
            parts.size == 2 && parts[0] == "stop" -> parts[1].toIntOrNull()?.let(::stop)
            parts.size == 3 && parts[0] == "route" && (parts[2] == "0" || parts[2] == "1") ->
                parts[1].toIntOrNull()?.let { route(it, parts[2]) }
            else -> null
        }
    }
}
