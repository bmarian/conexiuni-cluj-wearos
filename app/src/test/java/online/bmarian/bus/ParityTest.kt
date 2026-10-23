package online.bmarian.bus

import online.bmarian.bus.data.Api
import online.bmarian.bus.data.Favorites
import online.bmarian.bus.data.Route
import online.bmarian.bus.data.Stop
import online.bmarian.bus.logic.StopBrowse
import online.bmarian.bus.logic.browseStops
import online.bmarian.bus.logic.initialOf
import online.bmarian.bus.logic.keyedStops
import online.bmarian.bus.logic.sortedRoutes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.zip.GZIPInputStream

// Expected values from the website's TypeScript on the same snapshot.
class ParityTest {
    private fun read(name: String): String =
        GZIPInputStream(javaClass.classLoader!!.getResourceAsStream("parity/$name.gz")).bufferedReader().readText()

    private fun pair(element: JsonElement) = element.jsonArray.map { it.jsonPrimitive.content }

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
}
