package online.bmarian.bus.data

import kotlinx.serialization.Serializable

@Serializable
class TrafficStretch(val path: String, val band: Int)

@Serializable
class TrafficNow(val lines: Map<String, List<TrafficStretch>> = emptyMap())

const val TRAFFIC_REFRESH_MS = 60_000L
