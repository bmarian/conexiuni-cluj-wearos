package online.bmarian.bus.logic

// Port of DueArrivals, keyed by trip.
class DueArrivals {
    private var minutes = emptyMap<String, Int>()

    fun newlyDue(departures: List<StopDeparture>): List<StopDeparture> {
        val next = HashMap<String, Int>()
        val due = mutableListOf<StopDeparture>()
        for (departure in departures) {
            val first = departure.nextTimes.firstOrNull()?.takeIf { it.isLive } ?: continue
            next[departure.tripId] = first.minutes
            if (first.minutes == 0 && (minutes[departure.tripId] ?: 0) > 0) due += departure
        }
        minutes = next
        return due
    }
}
