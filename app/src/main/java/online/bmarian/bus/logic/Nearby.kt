package online.bmarian.bus.logic

import online.bmarian.bus.data.NetworkIndex
import online.bmarian.bus.data.Stop

// Same as the website's nearby list.
const val NEARBY_RADIUS_METERS = 700.0

fun nearestStation(index: NetworkIndex, lat: Double, lon: Double): Pair<Stop, Double>? =
    index.stops.asSequence()
        .filter { index.look(it.stopId) != null }
        .map { it to haversineMeters(lat, lon, it.lat, it.lon) }
        .filter { it.second <= NEARBY_RADIUS_METERS }
        .minByOrNull { it.second }
