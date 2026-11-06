package online.bmarian.bus

import online.bmarian.bus.data.LiveMerge
import online.bmarian.bus.data.LiveTraffic
import online.bmarian.bus.data.Traffic
import online.bmarian.bus.data.TrafficStretch
import online.bmarian.bus.logic.railParts
import online.bmarian.bus.logic.trafficRuns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficTest {
    private val line = (0..10).map { 46.0 to 23.0 + it * 0.001 }

    // From 23.002 to 23.004, 23.006 to 23.008 and 23.001 to 23.003, all along latitude 46.
    private val slow = TrafficStretch("_wrvvA_x|zj@?_|B", 2)
    private val jammed = TrafficStretch("_wrvvA_rd{j@?_|B", 3)
    private val busy = TrafficStretch("_wrvvAoyzzj@?_|B", 1)

    @Test
    fun railsOnlyTheSlowAndJammedStretches() {
        val runs = trafficRuns(listOf(busy, slow, jammed), line)
        assertEquals(listOf(2, 3), runs.map { it.band })
        assertEquals(0.2f, runs[0].from, 1e-3f)
        assertEquals(0.4f, runs[0].to, 1e-3f)
        assertEquals(0.8f, runs[1].to, 1e-3f)
    }

    @Test
    fun railsGrowWithTheLine() {
        val runs = trafficRuns(listOf(slow, jammed), line)
        assertTrue(railParts(line, runs, 0.1f).isEmpty())
        val half = railParts(line, runs, 0.3f)
        assertEquals(listOf(2), half.map { it.second })
        assertEquals(23.003, half[0].first.last().second, 1e-6)
        assertEquals(listOf(2, 3), railParts(line, runs).map { it.second })
    }

    @Test
    fun deltasReplaceTheTrafficOnlyWhenTheyCarryIt() {
        val merge = LiveMerge()
        val stretch = """{"stretches":[{"path":"${slow.path}","band":2}],"jams":[{"from_stop_id":1,"to_stop_id":2,"band":2,"delay_sec":130}]}"""
        merge.apply("snapshot", """{"as_of":1000,"traffic":{"2_0":$stretch}}""")
        assertEquals(listOf(slow), merge.traffic["2_0"]?.stretches)
        merge.apply("delta", """{"as_of":1010}""")
        assertEquals(listOf(slow), merge.traffic["2_0"]?.stretches)
        merge.apply("delta", """{"as_of":1020,"traffic":{}}""")
        assertTrue(merge.traffic.isEmpty())
        merge.apply("snapshot", """{"as_of":1030}""")
        assertTrue(merge.traffic.isEmpty())
    }

    @Test
    fun mergesTheTrafficOfOpenStreams() {
        val stop = Any()
        val route = Any()
        LiveTraffic.publish(stop, mapOf("1_0" to Traffic(listOf(slow))))
        LiveTraffic.publish(route, mapOf("2_0" to Traffic(listOf(jammed))))
        assertEquals(setOf("1_0", "2_0"), LiveTraffic.byTrip.value.keys)
        LiveTraffic.publish(stop, null)
        assertEquals(setOf("2_0"), LiveTraffic.byTrip.value.keys)
        LiveTraffic.publish(route, null)
        assertTrue(LiveTraffic.byTrip.value.isEmpty())
    }
}
