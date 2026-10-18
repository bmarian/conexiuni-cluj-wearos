package online.bmarian.bus.logic

import kotlin.math.ceil

// The phone's walking rules, so both time the same walk.
const val WALK_SPEED = 80.0 // m/min
private const val WALK_DETOUR = 1.25
// Within this the user is at the stop: told when the bus comes, not when to leave.
private const val AT_STOP_METERS = 60.0
private const val LEAVE_BUFFER_MIN = 5
// How early to get ready when the walk can't be timed.
private const val NO_LOCATION_LEAD_MIN = 20
// The heads-up comes this long before it's time to leave.
const val HEADS_UP_MIN = 10

fun walkMinutes(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Int {
    val meters = haversineMeters(fromLat, fromLon, toLat, toLon)
    if (meters < AT_STOP_METERS) return 0
    return ceil(meters * WALK_DETOUR / WALK_SPEED).toInt()
}

/** How long before the bus to set off: the walk and a few minutes, or a fixed lead when there's no fix. */
fun leadMinutes(walk: Int?) = walk?.plus(LEAVE_BUFFER_MIN) ?: NO_LOCATION_LEAD_MIN

/** At the stop, or too late to walk there for a bus [minutes] away: just told when it comes, not when to leave. */
fun justNotify(walk: Int?, minutes: Int) = walk == 0 || minutes <= leadMinutes(walk)

/** When a reminder first speaks for the bus at [atMs]: the heads-up before the walk, or the flat lead without a fresh fix or at the stop. */
fun tellAt(atMs: Long, walk: Int?): Long =
    if (walk == null || walk == 0) atMs - NO_LOCATION_LEAD_MIN * 60_000L
    else atMs - (leadMinutes(walk) + HEADS_UP_MIN) * 60_000L
