package online.bmarian.bus

import online.bmarian.bus.follow.Cue
import online.bmarian.bus.follow.Progress
import online.bmarian.bus.logic.justNotify
import online.bmarian.bus.logic.leadMinutes
import online.bmarian.bus.logic.tellAt
import online.bmarian.bus.logic.walkMinutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

// Expected values from the phone's ReminderTest, so both time the same walk.
class FollowTest {
    private fun ms(text: String) = Instant.parse(text).toEpochMilli()

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
    fun atTheStopOrTooLateItsJustToldWhenItComes() {
        assertTrue(justNotify(0, 30))
        assertTrue(justNotify(12, 17))
        assertFalse(justNotify(12, 18))
        assertTrue(justNotify(null, 20))
        assertFalse(justNotify(null, 21))
    }

    @Test
    fun theHeadsUpComesTenMinutesBeforeItsTimeToLeaveOrTwentyBeforeTheBusWithoutAFix() {
        val bus = ms("2026-10-19T03:00:00Z")
        // A five minute walk: leave by 05:50, so told at 05:40.
        assertEquals(ms("2026-10-19T02:40:00Z"), tellAt(bus, 5))
        assertEquals(ms("2026-10-19T02:28:00Z"), tellAt(bus, 17))
        assertEquals(ms("2026-10-19T02:40:00Z"), tellAt(bus, null))
        assertEquals(ms("2026-10-19T02:40:00Z"), tellAt(bus, 0))
    }

    @Test
    fun aFollowBuzzesWhenTheBusIsCloseAndWhenItsHere() {
        val progress = Progress()
        assertNull(progress.read(12))
        assertNull(progress.read(5))
        assertEquals(Cue.Soon, progress.read(2))
        assertNull(progress.read(1))
        assertEquals(Cue.Now, progress.read(0))
        assertTrue(progress.passed(5))
    }

    @Test
    fun theFirstReadingNeverBuzzes() {
        val progress = Progress()
        assertNull(progress.read(0))
        assertNull(progress.read(0))
    }
}
