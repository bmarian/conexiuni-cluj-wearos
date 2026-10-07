package com.example.conexiuniclujwearos

import com.example.conexiuniclujwearos.data.Api
import com.example.conexiuniclujwearos.data.Favorites
import com.example.conexiuniclujwearos.data.Route
import com.example.conexiuniclujwearos.data.ShapePoint
import com.example.conexiuniclujwearos.data.Stop
import com.example.conexiuniclujwearos.data.StopInfo
import com.example.conexiuniclujwearos.data.StopTime
import com.example.conexiuniclujwearos.data.Timetable
import com.example.conexiuniclujwearos.data.Transfer
import com.example.conexiuniclujwearos.data.Vehicle
import com.example.conexiuniclujwearos.data.assembleShapeInfo
import com.example.conexiuniclujwearos.data.tripIdFor
import com.example.conexiuniclujwearos.logic.StopBrowse
import com.example.conexiuniclujwearos.logic.browseStops
import com.example.conexiuniclujwearos.logic.computeStopDepartures
import com.example.conexiuniclujwearos.logic.directionShape
import com.example.conexiuniclujwearos.logic.indexVehicles
import com.example.conexiuniclujwearos.logic.initialOf
import com.example.conexiuniclujwearos.logic.keyedStops
import com.example.conexiuniclujwearos.logic.minutesOfDay
import com.example.conexiuniclujwearos.logic.routeStopArrivals
import com.example.conexiuniclujwearos.logic.sortedRoutes
import com.example.conexiuniclujwearos.logic.stopsForDirection
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.GZIPInputStream

// Expected values come from the website's own TypeScript (arrivals.ts, time.ts,
// useVehicleTracking.ts) run on the same prod snapshot, so this fails if the port drifts.
class ParityTest {
    @Serializable
    private class FixtureRoute(val route: Route, val timetable: Timetable? = null, val stop_times: List<StopTime> = emptyList())

    @Serializable
    private class Fixture(
        val nowMs: Long,
        val stops: Map<String, StopInfo>,
        val routes: Map<String, FixtureRoute>,
        val shapes: Map<String, List<ShapePoint>>,
        val vehicles: List<Vehicle>,
    )

    private fun read(name: String): String =
        GZIPInputStream(javaClass.classLoader!!.getResourceAsStream("parity/$name.gz")).bufferedReader().readText()

    private val zone = ZoneId.of("Europe/Bucharest")

    @Test
    fun departuresAndRouteArrivalsMatchTheWebsite() {
        val fixture = Api.json.decodeFromString<Fixture>(read("parity_a.json"))
        val expected = Api.json.parseToJsonElement(read("parity_a_expected.json")).jsonArray
        val byTrip = fixture.vehicles.groupBy { it.tripId.orEmpty() }
        var compared = 0

        for (scenario in expected.map { it.jsonObject }) {
            val label = scenario["label"]!!.jsonPrimitive.content
            val nowMs = scenario["nowMs"]!!.jsonPrimitive.long
            val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
            assertEquals("$label clock", scenario["localMinutes"]!!.jsonPrimitive.int, minutesOfDay(now))
            val vehicles = if (scenario["live"]!!.jsonPrimitive.boolean) byTrip else emptyMap()

            for ((stopId, rows) in scenario["stops"]!!.jsonObject) {
                val actual = computeStopDepartures(fixture.stops.getValue(stopId), fixture.shapes, vehicles, now, nowMs)
                val want = rows.jsonArray.map { it.jsonObject }
                assertEquals("$label stop $stopId count", want.size, actual.size)
                for ((w, a) in want.zip(actual)) {
                    val at = "$label stop $stopId route ${a.shortName}"
                    assertEquals(at, w["route"]!!.jsonPrimitive.content, a.shortName)
                    assertEquals(at, w["trip"]!!.jsonPrimitive.content, a.tripId)
                    assertEquals(at, w["name"]!!.jsonPrimitive.content, a.longName)
                    assertEquals(at, w["left"]!!.jsonPrimitive.int, a.minutesLeft)
                    assertEquals(at, w["static"]!!.jsonPrimitive.boolean, a.isStatic)
                    assertEquals(at, pairs(w["times"]!!), a.nextTimes.map { listOf(it.minutes.toString(), it.isLive.toString()) })
                    compared++
                }
            }

            for ((routeId, want) in scenario["routes"]!!.jsonObject) {
                val r = fixture.routes.getValue(routeId)
                val info = assembleShapeInfo(r.route, r.timetable, r.stop_times)
                assertEquals("$label route $routeId in", want.jsonObject["in"]!!.jsonPrimitive.content, info.timetable.inStopName)
                assertEquals("$label route $routeId out", want.jsonObject["out"]!!.jsonPrimitive.content, info.timetable.outStopName)
                for (dir in listOf("0", "1")) {
                    val w = want.jsonObject[dir]!!.jsonObject
                    val tripId = tripIdFor(info.routeId, dir)
                    val stops = stopsForDirection(info, tripId)
                    val shape = directionShape(info, tripId, fixture.shapes[tripId].orEmpty())
                    val indexed = if (shape == null) emptyList()
                    else indexVehicles(info.shortName, info.color, shape.index, nowMs, vehicles[tripId].orEmpty())
                    val at = "$label route $routeId/$dir"
                    assertEquals(at, w["vehicles"]!!.jsonPrimitive.int, indexed.size)
                    assertEquals(at, w["stops"]!!.jsonArray.map { pair(it) }, stops.map { listOf("${it.stop.stopId}", "${it.offsetFromStart}") })
                    val rows = routeStopArrivals(info, stops, dir == "0", shape, indexed, now, nowMs)
                    assertEquals(
                        at,
                        w["rows"]!!.jsonArray.map { row -> row.jsonArray.map { a -> a.jsonArray.map { it.jsonPrimitive.content } } },
                        rows.map { row -> row.map { listOf("${it.minutes}", "${it.isLive}", "${it.soon}") } },
                    )
                    compared += rows.size
                }
            }
        }
        println("compared $compared rows across ${expected.size} scenarios")
    }

    private fun pair(element: JsonElement) = element.jsonArray.map { it.jsonPrimitive.content }
    private fun pairs(element: JsonElement) = element.jsonArray.map(::pair)

    @Test
    fun browsingMatchesTheWebsite() {
        val stops = Api.json.decodeFromString<List<Stop>>(read("stops_all.json"))
        val routes = Api.json.decodeFromString<List<Route>>(read("routes_all.json"))
        val expected = Api.json.parseToJsonElement(read("browse_expected.json")).jsonObject
        val keyed = keyedStops(stops)

        assertEquals(expected["keys"]!!.jsonArray.map { pair(it) }, keyed.map { listOf("${it.stop.stopId}", it.key) })
        assertEquals(expected["routeOrder"]!!.jsonArray.map { it.jsonPrimitive.content }, sortedRoutes(routes).map { it.shortName })

        val letters = (browseStops(keyed, null, null) as StopBrowse.Tiles).tiles.map { it.label }
        assertEquals(expected["letters"]!!.jsonArray.map { it.jsonPrimitive.content }, letters)
        for ((letter, want) in expected["byLetter"]!!.jsonObject) {
            val screen = browseStops(keyed, letter, null)
            val w = want.jsonObject
            if ("stops" in w) {
                assertEquals(letter, w["stops"]!!.jsonArray.map { it.jsonPrimitive.int }, (screen as StopBrowse.Stops).stops.map { it.stopId })
            } else {
                val tiles = (screen as StopBrowse.Tiles).tiles
                assertEquals(letter, w["tiles"]!!.jsonArray.map { it.jsonPrimitive.content }, tiles.map { it.label })
                for ((prefix, ids) in w["prefixes"]!!.jsonObject) {
                    val list = browseStops(keyed, letter, prefix) as StopBrowse.Stops
                    assertEquals(prefix, ids.jsonArray.map { it.jsonPrimitive.int }, list.stops.map { it.stopId })
                }
            }
        }
        assertEquals("#", initialOf("1 DECEMBRIE"))
    }

    @Test
    fun opensWhatTheWebsiteSeals() {
        val sample = Api.json.parseToJsonElement(read("sealed.json")).jsonObject
        val opened = Transfer.open(sample["sealed"]!!, sample["key"]!!.jsonPrimitive.content)
        assertEquals(sample["plain"], opened)

        val summary = Transfer.describe(opened)!!
        // Bad entries are dropped the same way parseFavoriteRoutes and parseStopIds drop them.
        assertEquals(Transfer.Summary(stops = 2, lines = 2), summary)
        assertEquals("ro", Transfer.localeOf(opened))
        val favorites = (opened as JsonObject)["favorites"]!!.jsonObject
        assertEquals(listOf(391, 4), Favorites.parseStopIds(favorites["stops"]))
        assertEquals(
            listOf(com.example.conexiuniclujwearos.data.FavoriteRoute(49, "1"), com.example.conexiuniclujwearos.data.FavoriteRoute(13, "0")),
            Favorites.parseFavoriteRoutes(favorites["routes"]),
        )
        // A plain payload (a typed code) passes through untouched.
        assertEquals(sample["plain"], Transfer.open(sample["plain"]!!, sample["key"]!!.jsonPrimitive.content))
        assertEquals(null, Transfer.describe(JsonArray(emptyList())))
    }
}
