package online.bmarian.bus.follow

import online.bmarian.bus.logic.HEADS_UP_MIN
import online.bmarian.bus.logic.leadMinutes

internal const val SOON_MINUTES = 2

/** What a reading buzzes for: time to leave (the heads-up too), the bus close, or here. */
internal enum class Cue { Leave, Soon, Now }

/** A follow's readings so far, timed from where the user is by the phone's reminder rules. */
internal class Progress {
    private var lastMinutes: Int? = null
    private var buzzedSoon = false
    private var arrived = false
    private var warned = false
    private var toldToLeave = false
    private var late = false
    private var earliestLeave: Long? = null

    /** Epoch ms to set off for the stop, while the walk is timed and the bus isn't close. */
    var leaveAt: Long? = null
        private set
    var walk: Int? = null
        private set

    /** The time to go by, from the heads-up until it's time to leave. */
    val leaveBy get() = earliestLeave.takeIf { leaveAt != null && !toldToLeave }

    /** A jump after the bus was close means it passed, even if no reading caught it at 0. */
    fun passed(minutes: Int) = buzzedSoon && minutes > (lastMinutes ?: 0) + 2

    /** The bus [minutes] away and the stop [walk] minutes on foot, null without a fix. */
    fun read(minutes: Int, walk: Int?, nowMs: Long): Cue? {
        // Nothing buzzes on the first reading: the user just asked.
        val opening = lastMinutes == null
        var cue: Cue? = null
        val leaveIn = minutes - leadMinutes(walk)
        // Already time to go when it starts: too late to time the walk.
        if (opening && leaveIn <= 0) late = true
        // At the stop or too late, it's told when the bus comes.
        val timed = walk != 0 && !late
        if (timed && leaveIn <= 0 && !toldToLeave) {
            toldToLeave = true
            warned = true
            cue = Cue.Leave
        } else if (timed && leaveIn <= HEADS_UP_MIN && !warned) {
            warned = true
            cue = Cue.Leave
        }
        // Until the bus is close.
        val at = if (timed && minutes > SOON_MINUTES) nowMs + leaveIn * 60_000L else null
        leaveAt = at
        this.walk = walk.takeIf { at != null }
        // The time to go by only moves earlier, so it doesn't flicker as the minutes round.
        if (at != null && warned) earliestLeave = minOf(earliestLeave ?: Long.MAX_VALUE, at / 60_000 * 60_000)
        if (minutes <= SOON_MINUTES && !buzzedSoon) {
            buzzedSoon = true
            if (minutes > 0) cue = cue ?: Cue.Soon
        }
        if (minutes == 0 && !arrived) {
            arrived = true
            cue = Cue.Now
        }
        lastMinutes = minutes
        return cue.takeIf { !opening }
    }
}
