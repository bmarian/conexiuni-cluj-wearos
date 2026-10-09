package online.bmarian.bus.follow

import kotlinx.serialization.Serializable

// The phone's journey as it sends it at the start: the same JSON the phone app writes. Only
// what the watch draws is read; the rest is skipped.

@Serializable
data class JourneyStop(val id: Int, val name: String, val lat: Double, val lon: Double)

@Serializable
data class JourneyPoint(val lat: Double, val lon: Double)

/** One bus ride: its lines (alternatives like 45 / 44), colours, stops and the path it takes. */
@Serializable
data class JourneyLeg(
    val routeIds: List<Int>,
    val tripIds: List<String>,
    val lines: List<String>,
    val colors: List<String>,
    val stops: List<JourneyStop>,
    val path: List<JourneyPoint> = emptyList(),
) {
    val boarding get() = stops.first()
    val alighting get() = stops.last()
}

@Serializable
data class Journey(
    val id: String,
    val destination: JourneyStop,
    val legs: List<JourneyLeg>,
    val walks: List<List<JourneyPoint>> = emptyList(),
)

/** What a step is, as the phone marks it. */
object StepType {
    const val WALK = "walk"
    const val WAIT = "wait"
    const val RIDE = "ride"
    const val WALK_END = "walkEnd"
    const val ARRIVED = "arrived"
}
