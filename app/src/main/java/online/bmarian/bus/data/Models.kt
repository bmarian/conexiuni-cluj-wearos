package online.bmarian.bus.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val OUTGOING_SUFFIX = "_0"
const val INCOMING_SUFFIX = "_1"

@Serializable
data class Route(
    @SerialName("route_id") val routeId: Int,
    @SerialName("route_short_name") val shortName: String,
    @SerialName("route_long_name") val longName: String = "",
    @SerialName("route_type") val type: Int = 3,
    @SerialName("route_color") val color: String = "#64748b",
)

@Serializable
data class Stop(
    @SerialName("stop_id") val stopId: Int,
    @SerialName("stop_name") val name: String,
    @SerialName("stop_lat") val lat: Double = 0.0,
    @SerialName("stop_lon") val lon: Double = 0.0,
)

@Serializable
data class StopTime(
    @SerialName("trip_id") val tripId: String,
    @SerialName("stop_id") val stopId: Int,
    // Seconds from the previous stop.
    @SerialName("offset_arrival_time") val offsetArrivalTime: Double = 0.0,
    @SerialName("stop_sequence") val stopSequence: Int = 0,
    @SerialName("stop_headsign") val headsign: String = "",
    @SerialName("stop_lat") val lat: Double = 0.0,
    @SerialName("stop_lon") val lon: Double = 0.0,
)

@Serializable
data class TimetableEntry(
    @SerialName("departure_in") val departureIn: String = "",
    @SerialName("departure_out") val departureOut: String = "",
)

@Serializable
data class Frequency(
    @SerialName("min_minutes") val minMinutes: Int = 0,
    @SerialName("max_minutes") val maxMinutes: Int = 0,
)

@Serializable
data class DaySchedule(
    val entries: List<TimetableEntry> = emptyList(),
    @SerialName("in_frequency") val inFrequency: Frequency? = null,
    @SerialName("out_frequency") val outFrequency: Frequency? = null,
)

@Serializable
data class Timetable(
    @SerialName("route_long_name") val routeLongName: String = "",
    @SerialName("in_stop_name") val inStopName: String = "",
    @SerialName("out_stop_name") val outStopName: String = "",
    val weekdays: DaySchedule = DaySchedule(),
    val saturday: DaySchedule = DaySchedule(),
    val sunday: DaySchedule = DaySchedule(),
)

@Serializable
data class ShapeInfo(
    @SerialName("route_short_name") val shortName: String,
    @SerialName("route_long_name") val longName: String = "",
    @SerialName("route_id") val routeId: Int,
    @SerialName("route_type") val type: Int = 3,
    @SerialName("route_color") val color: String = "#64748b",
    @SerialName("stop_times") private val stopTimesPlural: List<StopTime>? = null,
    @SerialName("stop_time") private val stopTimesSingular: List<StopTime>? = null,
    val timetable: Timetable = Timetable(),
    // A timetable run this many minutes late is still coming (learned on the server).
    @SerialName("late_grace_min") val lateGraceMin: Double = 1.0,
) {
    val stopTimes: List<StopTime> get() = stopTimesPlural ?: stopTimesSingular ?: emptyList()
}

@Serializable
data class StopInfo(
    @SerialName("stop_id") val stopId: Int,
    @SerialName("stop_name") val name: String = "",
    @SerialName("stop_lat") val lat: Double = 0.0,
    @SerialName("stop_lon") val lon: Double = 0.0,
    @SerialName("outgoing_trip_ids") val outgoingTripIds: List<String> = emptyList(),
    @SerialName("incoming_trip_ids") val incomingTripIds: List<String> = emptyList(),
    @SerialName("shapes_info") val shapesInfo: List<ShapeInfo> = emptyList(),
    @SerialName("late_grace_min") val lateGraceMin: Double = 1.0,
)

@Serializable
data class ShapePoint(
    @SerialName("shape_id") val shapeId: String,
    @SerialName("shape_pt_lat") val lat: Double,
    @SerialName("shape_pt_lon") val lon: Double,
    @SerialName("shape_pt_sequence") val sequence: Int = 0,
)

@Serializable
data class Vehicle(
    val id: Int = 0,
    @SerialName("trip_id") val tripId: String? = null,
    @SerialName("route_id") val routeId: Int? = null,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val heading: Double = 0.0,
    val speed: Double = 0.0,
    val timestamp: String = "",
    val label: String = "",
    @SerialName("vehicle_type") val vehicleType: Int = 0,
    @SerialName("bike_accessible") val bikeAccessible: String = "",
    @SerialName("wheelchair_accessible") val wheelchairAccessible: String = "",
)

@Serializable
data class TransferLink(val code: String, val secret: String, val expiresIn: Int = 600)

fun routeIdFromTripId(tripId: String): Int? = tripId.substringBefore('_').toIntOrNull()

fun directionFromTripId(tripId: String): String = if (tripId.endsWith(INCOMING_SUFFIX)) "1" else "0"

fun tripIdFor(routeId: Int, direction: String): String =
    "$routeId${if (direction == "1") INCOMING_SUFFIX else OUTGOING_SUFFIX}"

fun bothWays(routeLongName: String): String {
    val i = routeLongName.lastIndexOf(" - ")
    return if (i < 0) routeLongName else routeLongName.substring(0, i) + " ⇄ " + routeLongName.substring(i + 3)
}

fun terminalName(routeLongName: String, direction: String): String {
    val i = routeLongName.lastIndexOf(" - ")
    if (i < 0) return routeLongName
    return if (direction == "1") routeLongName.substring(0, i) else routeLongName.substring(i + 3)
}
