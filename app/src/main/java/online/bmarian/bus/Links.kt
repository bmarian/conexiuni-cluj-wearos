package online.bmarian.bus

import android.net.Uri
import androidx.core.net.toUri

object Links {
    const val SCHEME = "conexiunicluj"
    const val HOST = "bus.bmarian.online"
    const val FOLLOWING = "following"
    const val JOURNEY = "journey"

    fun stop(stopId: Int) = "stop/$stopId"
    fun route(routeId: Int, direction: String) = "route/$routeId/$direction"

    fun app(path: String): Uri = "$SCHEME://$HOST/$path".toUri()
    fun web(path: String): Uri = "${BuildConfig.SITE_ORIGIN}/$path".toUri()

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
