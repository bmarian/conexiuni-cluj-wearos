package online.bmarian.bus

import online.bmarian.bus.data.ShapePoint
import online.bmarian.bus.logic.VehicleTrack
import online.bmarian.bus.logic.haversineMeters
import online.bmarian.bus.logic.pathOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Port of utils/__tests__/vehicleMotion.spec.ts.
class VehicleMotionTest {
    private val mPerLon = haversineMeters(46.77, 0.0, 46.77, 1.0)
    private val mPerLat = haversineMeters(0.0, 0.0, 1.0, 0.0)
    private fun east(m: Double, north: Double = 0.0) = 46.77 + north / mPerLat to 23.55 + m / mPerLon
    private fun shape(vararg pts: Pair<Double, Double>) = pts.mapIndexed { i, (lat, lon) -> ShapePoint("t", lat, lon, i) }
    private val line = pathOf(shape(*listOf(0.0, 1000.0, 2000.0, 4000.0, 8000.0).map { east(it) }.toTypedArray()))

    private class Frame(val s: Double, val v: Double)

    private fun drive(track: VehicleTrack, from: Long, ms: Long): List<Frame> {
        val frames = ArrayList<Frame>()
        var now = from + 16
        while (now <= from + ms) {
            track.step(now)
            frames += Frame(track.s, track.v)
            now += 16
        }
        return frames
    }

    private fun VehicleTrack.fix(at: Pair<Double, Double>, fixMs: Long, speedKmh: Double, nowMs: Long) =
        fix(at.first, at.second, fixMs, speedKmh, 90.0, nowMs)

    @Test
    fun measuresAlongTheLineAndClampsPastItsEnds() {
        assertEquals(8000.0, line.length, 5.0)
        assertEquals(east(3000.0).second, line.pointAt(3000.0).second, 1e-6)
        assertEquals(east(0.0), line.pointAt(-5.0))
        assertEquals(east(8000.0).second, line.pointAt(1e6).second, 1e-9)
        assertEquals(90.0, line.headingAt(500.0), 0.5)
    }

    @Test
    fun projectsAFixOntoTheLineAndDropsOneTooFarOff() {
        val (lat, lon) = east(2500.0, 20.0)
        assertEquals(2500.0, line.locate(lat, lon)!!.s, 5.0)
        val (farLat, farLon) = east(2500.0, 400.0)
        assertNull(line.locate(farLat, farLon))
    }

    @Test
    fun takesTheLegTheBusFacesWhereTheRouteDoublesBack() {
        val loop = pathOf(shape(east(0.0), east(1000.0), east(1000.0, 15.0), east(0.0, 15.0)))
        val (lat, lon) = east(400.0, 8.0)
        assertTrue(loop.locate(lat, lon, heading = 90.0)!!.s < 1000)
        assertTrue(loop.locate(lat, lon, heading = 270.0)!!.s > 1000)
    }

    @Test
    fun neverBacksUpWhenAFixLandsBehindTheDrawnBus() {
        val track = VehicleTrack(line)
        var now = 1_000_000_000L
        track.fix(east(1000.0), now - 30_000, 36.0, now)
        val before = drive(track, now, 30_000)
        now += 30_000
        track.fix(east(1050.0), now - 30_000, 0.0, now)
        val after = drive(track, now, 60_000)
        val all = before + after
        assertTrue(all.zipWithNext().all { (a, b) -> b.s >= a.s })
        assertTrue(after.last().v < before.last().v)
    }

    @Test
    fun catchesUpOnAFixAheadWithoutLeaping() {
        val track = VehicleTrack(line)
        var now = 1_000_000_000L
        track.fix(east(1000.0), now - 30_000, 0.0, now)
        drive(track, now, 30_000)
        now += 30_000
        track.fix(east(1300.0), now - 30_000, 30.0, now)
        val frames = drive(track, now, 120_000)
        assertTrue(frames.zipWithNext().maxOf { (a, b) -> b.s - a.s } < 0.4)
        assertTrue(frames.maxOf { it.v } <= 20.0)
        assertEquals(track.target(now + 120_000).first, track.s, 25.0)
    }

    @Test
    fun coastsToAStopWhenFixesStopComing() {
        val track = VehicleTrack(line)
        val now = 1_000_000_000L
        track.fix(east(1000.0), now - 30_000, 36.0, now)
        val frames = drive(track, now, 300_000)
        assertTrue(frames.last().v < 0.05)
        assertTrue(frames.last().s - frames[frames.size - 3000].s < 1)
        assertTrue(frames.last().s < 2000)
    }

    @Test
    fun givesUpOnABusRunningBackwardsAlongItsPath() {
        val track = VehicleTrack(line)
        val now = 1_000_000_000L
        assertTrue(track.fix(east(3000.0), now - 60_000, 30.0, now))
        assertFalse(track.fix(east(2700.0), now - 30_000, 30.0, now))
    }

    @Test
    fun relocatesInsteadOfDrivingACorrectionItCannotMakeUp() {
        val track = VehicleTrack(line)
        var now = 1_000_000_000L
        track.fix(east(1000.0), now - 30_000, 0.0, now)
        drive(track, now, 5000)
        now += 5000
        track.fix(east(3000.0), now - 30_000, 0.0, now)
        assertTrue(track.step(now + 16))
        assertEquals(track.target(now + 16).first, track.s, 0.0)
        assertTrue(track.s > 2990)
    }
}
