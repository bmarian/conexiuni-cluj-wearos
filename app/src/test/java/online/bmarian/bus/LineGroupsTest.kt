package online.bmarian.bus

import online.bmarian.bus.data.NetworkBundle
import online.bmarian.bus.data.NetworkIndex
import online.bmarian.bus.data.NetworkRoute
import online.bmarian.bus.data.NetworkTrip
import online.bmarian.bus.data.Stop
import online.bmarian.bus.logic.GroupLine
import online.bmarian.bus.logic.boardingStop
import online.bmarian.bus.logic.groupKey
import online.bmarian.bus.logic.readKey
import org.junit.Assert.assertEquals
import org.junit.Test

/** Same cases as the phone's LineGroupsTest. */
class LineGroupsTest {
    private fun stop(id: Int) = Stop(id, "S$id", 46.77 + id / 1000.0, 23.6, stationId = id)

    private fun route(id: Int, slug: String, trips: Map<String, List<Int>>) = NetworkRoute(
        routeId = id,
        shortName = slug.uppercase(),
        longName = "$slug start - $slug end",
        trips = trips.map { (tripId, stops) -> NetworkTrip(tripId, stops.toIntArray(), IntArray(stops.size) { it }) },
    )

    private val index = NetworkIndex(
        NetworkBundle(
            stops = (1..6).map { stop(it) },
            routes = listOf(route(1, "a", mapOf("1_0" to listOf(6, 5, 4, 3, 2, 1), "1_1" to listOf(1, 2, 3, 4, 5, 6)))),
        ),
    )

    @Test
    fun `reads back the key it writes`() {
        val lines = listOf(GroupLine(1, "1"), GroupLine(2, "1"), GroupLine(4, "0"))
        assertEquals(lines, readKey(groupKey(lines)))
        assertEquals(listOf(GroupLine(1, "1")), readKey("1/1,2/x,zz"))
    }

    @Test
    fun `boards at the stop nearest you, never at the last one, and at the first without a location`() {
        val a = GroupLine(1, "1")
        assertEquals(3, index.boardingStop(a, 46.773, 23.6))
        assertEquals(5, index.boardingStop(a, 46.776, 23.6))
        assertEquals(1, index.boardingStop(a, null, null))
    }
}
