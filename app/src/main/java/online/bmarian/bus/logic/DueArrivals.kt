package online.bmarian.bus.logic

/** Port of useDueArrivals. */
class DueArrivals {
    private var minutes = emptyMap<String, Int>()

    fun newlyDue(departures: List<StopDeparture>): List<StopDeparture> {
        val next = HashMap<String, Int>()
        val due = mutableListOf<StopDeparture>()
        for (departure in departures) {
            val first = departure.nextTimes.firstOrNull()?.takeIf { it.isLive } ?: continue
            val key = "${departure.tripId}|${first.vehicleId}"
            next[key] = first.minutes
            if (first.minutes == 0 && (minutes[key] ?: 0) > 0) due += departure
        }
        minutes = next
        return due
    }
}
