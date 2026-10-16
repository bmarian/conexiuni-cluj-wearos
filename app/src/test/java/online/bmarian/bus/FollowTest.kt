package online.bmarian.bus

import online.bmarian.bus.follow.Cue
import online.bmarian.bus.follow.Progress
import online.bmarian.bus.logic.leadMinutes
import online.bmarian.bus.logic.walkMinutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

// Expected values from the phone's ReminderTest, so both time the same walk.
class FollowTest {
    private val start = Instant.parse("2026-10-15T05:00:00Z").toEpochMilli()

    private fun at(minutes: Int, seconds: Int = 0) = start + minutes * 60_000L + seconds * 1_000L

    @Test
    fun theWalkIsTimedLikeThePhones() {
        assertEquals(0, walkMinutes(46.77, 23.59, 46.7702, 23.5901))
        // At the stop within 60 m, a walk just past it.
        assertEquals(0, walkMinutes(46.77, 23.59, 46.77053, 23.59))
        assertEquals(1, walkMinutes(46.77, 23.59, 46.77055, 23.59))
        assertEquals(16, walkMinutes(46.77, 23.59, 46.779, 23.59))
        assertEquals(17, leadMinutes(12))
        assertEquals(5, leadMinutes(0))
        assertEquals(20, leadMinutes(null))
    }

    @Test
    fun aHeadsUpThenLeaveNowThenTheBus() {
        val progress = Progress()
        // An 8 min walk: time to go 13 min before the bus.
        assertNull(progress.read(30, 8, at(0)))
        assertEquals(at(17), progress.leaveAt)
        assertNull(progress.leaveBy)
        assertEquals(Cue.Leave, progress.read(23, 8, at(7, 20)))
        assertEquals(at(17), progress.leaveBy)
        assertNull(progress.read(22, 8, at(8, 40)))
        // A late bus doesn't move the time to go by later.
        assertNull(progress.read(24, 8, at(9)))
        assertEquals(at(17), progress.leaveBy)
        assertEquals(Cue.Leave, progress.read(13, 8, at(19)))
        assertNull(progress.leaveBy)
        assertEquals(at(19), progress.leaveAt)
        assertEquals(Cue.Soon, progress.read(2, 8, at(30)))
        assertNull(progress.leaveAt)
        assertEquals(Cue.Now, progress.read(0, 8, at(32)))
        assertTrue(progress.passed(5))
    }

    @Test
    fun withoutAFixItsTwentyMinutesEarly() {
        val progress = Progress()
        assertNull(progress.read(35, null, at(0)))
        assertEquals(Cue.Leave, progress.read(30, null, at(5)))
        assertEquals(Cue.Leave, progress.read(20, null, at(15)))
    }

    @Test
    fun startedInsideTheHeadsUpItSaysWhenToGoWithoutABuzz() {
        val progress = Progress()
        assertNull(progress.read(20, 8, at(0)))
        assertEquals(at(7), progress.leaveBy)
        assertNull(progress.read(19, 8, at(1)))
        assertEquals(Cue.Leave, progress.read(13, 8, at(7)))
    }

    @Test
    fun tooLateToWalkItsJustTheBus() {
        val progress = Progress()
        assertNull(progress.read(10, 8, at(0)))
        assertNull(progress.leaveAt)
        assertNull(progress.read(9, 8, at(1)))
        assertEquals(Cue.Soon, progress.read(2, 8, at(8)))
        assertEquals(Cue.Now, progress.read(0, 8, at(10)))
    }

    @Test
    fun atTheStopItsJustTheBus() {
        val progress = Progress()
        assertNull(progress.read(30, 0, at(0)))
        assertNull(progress.leaveAt)
        assertNull(progress.read(10, 0, at(20)))
        assertEquals(Cue.Soon, progress.read(2, 0, at(28)))
        assertFalse(progress.passed(3))
    }

    @Test
    fun theFirstReadingNeverBuzzes() {
        val progress = Progress()
        assertNull(progress.read(0, 8, at(0)))
        assertNull(progress.read(0, 8, at(0, 15)))
    }
}
