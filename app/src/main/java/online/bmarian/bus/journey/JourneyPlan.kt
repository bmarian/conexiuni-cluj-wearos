package online.bmarian.bus.journey

import kotlinx.serialization.Serializable
import online.bmarian.bus.data.Cities

// The phone's JSON; only what the watch draws is read.

@Serializable
data class JourneyStop(val id: Int, val name: String, val lat: Double, val lon: Double)

@Serializable
data class JourneyPoint(val lat: Double, val lon: Double)

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
    // Planned before cities: Cluj.
    val city: String = Cities.DEFAULT,
)

object StepType {
    const val WALK = "walk"
    const val WAIT = "wait"
    const val RIDE = "ride"
    const val WALK_END = "walkEnd"
    const val ARRIVED = "arrived"
}
