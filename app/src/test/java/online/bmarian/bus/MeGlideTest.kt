package online.bmarian.bus

import online.bmarian.bus.data.Fix
import online.bmarian.bus.ui.map.fixBetween
import org.junit.Assert.assertEquals
import org.junit.Test

class MeGlideTest {
    @Test
    fun halfwayAcrossNorthTurnsTheShortWay() {
        val mid = fixBetween(Fix(46.0, 23.0, 5f, 350f), Fix(46.2, 23.4, 5f, 10f), 0.5)
        assertEquals(46.1, mid.lat, 1e-9)
        assertEquals(23.2, mid.lon, 1e-9)
        assertEquals(0f, mid.bearing!! % 360, 0.01f)
    }

    @Test
    fun withoutAHeadingToStartFromItTakesTheNewOne() {
        assertEquals(10f, fixBetween(Fix(46.0, 23.0, 5f), Fix(46.2, 23.4, 5f, 10f), 0.5).bearing)
    }
}
