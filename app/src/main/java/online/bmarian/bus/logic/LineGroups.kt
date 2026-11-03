package online.bmarian.bus.logic

import kotlinx.serialization.Serializable
import online.bmarian.bus.data.NetworkIndex
import online.bmarian.bus.data.NetworkRoute
import online.bmarian.bus.data.tripIdFor

// Port of the phone's logic/LineGroups.kt, the part the watch shows.

@Serializable
data class GroupLine(val routeId: Int, val direction: String)

fun tripOf(line: GroupLine) = tripIdFor(line.routeId, line.direction)

fun groupKey(lines: List<GroupLine>) = lines.joinToString(",") { "${it.routeId}/${it.direction}" }

fun readKey(key: String): List<GroupLine> = key.split(',').mapNotNull { part ->
    val (id, direction) = part.split('/').takeIf { it.size == 2 } ?: return@mapNotNull null
    id.toIntOrNull()?.let { GroupLine(it, direction) }?.takeIf { direction == "0" || direction == "1" }
}

fun NetworkIndex.groupRoutes(lines: List<GroupLine>): List<NetworkRoute> = lines.mapNotNull { route(it.routeId) }

fun groupLabel(routes: List<NetworkRoute>) = routes.joinToString(", ") { it.shortName }

fun NetworkIndex.stopsOf(line: GroupLine): List<Int> =
    route(line.routeId)?.trips?.firstOrNull { it.tripId == tripOf(line) }?.stopIds?.toList().orEmpty()

/** The line's stop nearest you, never its last; its first without a location. */
fun NetworkIndex.boardingStop(line: GroupLine, lat: Double?, lon: Double?): Int? {
    val boardable = stopsOf(line).dropLast(1)
    if (lat == null || lon == null) return boardable.firstOrNull()
    return boardable.minByOrNull { id -> stop(id)?.let { haversineMeters(lat, lon, it.lat, it.lon) } ?: Double.MAX_VALUE }
}
