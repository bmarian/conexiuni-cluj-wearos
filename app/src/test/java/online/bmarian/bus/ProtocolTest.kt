package online.bmarian.bus

import online.bmarian.bus.data.Api
import online.bmarian.bus.data.FavoriteRoute
import online.bmarian.bus.data.FavoritesSync
import online.bmarian.bus.follow.JourneySync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The JSON the phone app writes and reads too: a change here breaks the other side. */
class ProtocolTest {
    @Test
    fun favoritesAreWrittenAsThePhoneReadsThem() {
        val snapshot = FavoritesSync.Snapshot(listOf(391, 4), listOf(FavoriteRoute(25, "0")), 1791441990000)
        val json = FavoritesSync.encode(snapshot)
        assertEquals("""{"stops":[391,4],"routes":[{"routeId":25,"direction":"0"}],"updatedAt":1791441990000}""", json)
        assertEquals(snapshot, FavoritesSync.decode(json))
    }

    @Test
    fun favoritesFromThePhoneKeepTheirOrder() {
        val snapshot = FavoritesSync.decode(
            """{"stops":[4,391],"routes":[{"routeId":7,"direction":"1"},{"routeId":25,"direction":"0"}],"updatedAt":5,"later":true}""",
        )
        assertEquals(listOf(4, 391), snapshot?.stops)
        assertEquals(listOf(FavoriteRoute(7, "1"), FavoriteRoute(25, "0")), snapshot?.routes)
        assertEquals(5L, snapshot?.updatedAt)
        // Without a time there is no telling whether it is newer.
        assertNull(FavoritesSync.decode("""{"stops":[1],"routes":[]}"""))
        assertNull(FavoritesSync.decode("not json"))
    }

    @Test
    fun journeyFromThePhone() {
        val step = Api.json.decodeFromString<JourneySync>(
            """{"id":"j1","title":"Iulius Mall","step":"Walk to Tribunal","detail":"45 or 44 in 6 min","line":"45",""" +
                """"color":"#E91E63","arrivalAt":1791441990000,"stepIndex":2,"alert":true,"later":1}""",
        )
        assertEquals(
            JourneySync("j1", "Iulius Mall", "Walk to Tribunal", "45 or 44 in 6 min", "45", "#E91E63", 1791441990000, 2, alert = true),
            step,
        )
        val end = Api.json.decodeFromString<JourneySync>("""{"id":"j1","title":"Iulius Mall","step":"You're there","end":true}""")
        assertTrue(end.end)
        assertFalse(end.alert)
        assertEquals(0, end.stepIndex)
        assertNull(end.arrivalAt)
    }
}
