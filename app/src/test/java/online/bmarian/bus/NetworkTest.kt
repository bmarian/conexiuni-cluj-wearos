package online.bmarian.bus

import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import online.bmarian.bus.data.Api
import online.bmarian.bus.data.NetworkBundle
import online.bmarian.bus.data.NetworkIndex
import online.bmarian.bus.data.StopInfo
import online.bmarian.bus.data.LiveMerge
import online.bmarian.bus.data.clujClock
import online.bmarian.bus.data.routeIdFromTripId
import online.bmarian.bus.data.shapeHash
import online.bmarian.bus.data.decodePolyline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NetworkTest {
    private val fixture = Api.json.parseToJsonElement(
        javaClass.classLoader!!.getResourceAsStream("network/network_fixture.json").bufferedReader().readText(),
    ).jsonObject
    private val index = NetworkIndex(Api.json.decodeFromJsonElement<NetworkBundle>(fixture["bundle"]!!))
    private val stopId = fixture["stop_id"]!!.jsonPrimitive.int
    private val capturedAt = fixture["captured_at_ms"]!!.jsonPrimitive.long

    private fun comparable(info: StopInfo): StopInfo {
        val lines = info.shapesInfo.map { it.routeId }.toSet()
        return info.copy(
            outgoingTripIds = info.outgoingTripIds.filter { routeIdFromTripId(it) in lines }.sorted(),
            incomingTripIds = info.incomingTripIds.filter { routeIdFromTripId(it) in lines }.sorted(),
        )
    }

    @Test
    fun buildsTheStopExactlyAsStopInfoAnswered() {
        val server = Api.json.decodeFromJsonElement<StopInfo>(fixture["stop_info"]!!)
        val built = index.stopInfo(stopId, capturedAt)!!
        assertEquals(comparable(server), comparable(built))
    }

    @Test
    fun knowsNothingOfAStopItDoesNotHave() {
        assertNull(index.stopInfo(-1, capturedAt))
    }

    @Test
    fun decodesTheServersPolylineAndNamesItTheSameWay() {
        val shape = fixture["shape"]!!.jsonObject
        val polyline = shape["polyline"]!!.jsonPrimitive.content
        val expected = shape["points"]!!.jsonArray.map { p -> p.jsonArray.let { it[0].jsonPrimitive.double to it[1].jsonPrimitive.double } }
        val decoded = decodePolyline(polyline, precision = 6)
        assertEquals(expected.size, decoded.size)
        decoded.zip(expected).forEach { (a, b) ->
            assertTrue(Math.abs(a.first - b.first) < 6e-7 && Math.abs(a.second - b.second) < 6e-7)
        }
        assertEquals(index.shapeHash(shape["trip_id"]!!.jsonPrimitive.content), shapeHash(polyline))
    }

    @Test
    fun readsTheDayAndHourInCluj() {
        assertEquals("sunday" to 0, clujClock(Instant.parse("2026-10-10T21:30:00Z").toEpochMilli()))
        assertEquals("weekday" to 9, clujClock(Instant.parse("2026-10-09T06:00:00Z").toEpochMilli()))
    }

    @Test
    fun mergesTheLiveSnapshotAndDeltas() {
        val merge = LiveMerge()
        val bus = """{"id":%d,"trip_id":"%s","route_id":1,"latitude":%s,"longitude":23.5,"heading":90,"speed":20,"timestamp":"t","label":"A"}"""
        val first = merge.apply(
            "snapshot",
            """{"as_of":1000,"rows":[{"trip_id":"1_0","stop_id":5,"entries":[{"at":1240,"live":true,"vehicle_id":7},{"at":1720}]}],""" +
                """"vehicles":[${bus.format(7, "1_0", "46.1")},${bus.format(8, "2_0", "46.2")}]}""",
        )!!
        assertEquals(listOf(4, 12), first.entriesAt("1_0", 5, 1000_000)!!.map { it.minutes })
        assertEquals(listOf(2, 10), first.entriesAt("1_0", 5, 1000_000 + 130_000)!!.map { it.minutes })
        assertNull(first.entriesAt("1_0", 6, 1000_000))
        assertEquals(1, first.vehicles("2_0").size)

        val after = merge.apply(
            "delta",
            """{"as_of":1060,"rows":[{"trip_id":"1_0","stop_id":5,"entries":[{"at":1240,"live":true,"vehicle_id":7}]}],""" +
                """"vehicles":{"upsert":[${bus.format(7, "1_0", "46.15")}],"remove":[8]}}""",
        )!!
        assertEquals(listOf(3), after.entriesAt("1_0", 5, 1060_000)!!.map { it.minutes })
        assertEquals(46.15, after.vehicles("1_0")[0].latitude, 0.0)
        assertEquals("A", after.vehicles("1_0")[0].label)
        assertTrue(after.vehicles("2_0").isEmpty())
        assertNull(merge.apply("unknown", "{}"))
    }
}
