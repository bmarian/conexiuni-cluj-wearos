package online.bmarian.bus

import online.bmarian.bus.data.Api
import online.bmarian.bus.data.FavoriteRoute
import online.bmarian.bus.data.FavoritesSync
import online.bmarian.bus.follow.FollowSync
import online.bmarian.bus.journey.JourneySync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shared with the phone app. */
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
        assertNull(FavoritesSync.decode("""{"stops":[1],"routes":[]}"""))
        assertNull(FavoritesSync.decode("not json"))
    }

    @Test
    fun eachCityHasItsOwnFavoritesItem() {
        assertEquals("/sync/favorites", FavoritesSync.path("cluj"))
        assertEquals("/sync/favorites/iasi", FavoritesSync.path("iasi"))
        assertEquals("cluj", FavoritesSync.cityOf("/sync/favorites"))
        assertEquals("iasi", FavoritesSync.cityOf("/sync/favorites/iasi"))
        assertNull(FavoritesSync.cityOf("/sync/language"))
        assertNull(FavoritesSync.cityOf("/sync/favorites/Not a city"))
    }

    @Test
    fun aFollowKnowsItsCity() {
        val followed = """{"stopId":2,"stopName":"B","routeId":57,"tripId":"57_0","shortName":"57","color":"#ff0000","destination":"Somewhere""""
        val iasi = Api.json.decodeFromString<FollowSync>("""{"id":"f1","followed":$followed,"city":"iasi"}}""")
        assertEquals("iasi", iasi.followed.city)
        // From a phone that knows only Cluj.
        val older = Api.json.decodeFromString<FollowSync>("""{"id":"f1","followed":$followed}}""")
        assertEquals("cluj", older.followed.city)
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

    @Test
    fun aFollowFromThePhoneSaysWhenToGoBy() {
        val sync = Api.json.decodeFromString<FollowSync>(
            """{"id":"f1","followed":{"stopId":2,"stopName":"B","routeId":57,"tripId":"57_0","shortName":"57","color":"#ff0000",""" +
                """"destination":"Somewhere"},"minutes":20,"alert":true,"leaveAt":1791441990000,"walkMinutes":8,"leaveBy":1791441960000}""",
        )
        assertEquals(1791441990000, sync.leaveAt)
        assertEquals(8, sync.walkMinutes)
        assertEquals(1791441960000, sync.leaveBy)
        val older = Api.json.decodeFromString<FollowSync>(
            """{"id":"f1","followed":${Api.json.encodeToString(sync.followed)},"minutes":20,"leaveAt":1791441990000}""",
        )
        assertNull(older.leaveBy)
    }
}
