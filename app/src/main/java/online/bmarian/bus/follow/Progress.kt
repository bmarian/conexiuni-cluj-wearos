package online.bmarian.bus.follow

internal const val SOON_MINUTES = 2

/** What a reading buzzes for: the bus close, or here. */
internal enum class Cue { Soon, Now }

/** A follow's readings so far. */
internal class Progress {
    private var lastMinutes: Int? = null
    private var buzzedSoon = false
    private var arrived = false

    /** A jump after the bus was close means it passed, even if no reading caught it at 0. */
    fun passed(minutes: Int) = buzzedSoon && minutes > (lastMinutes ?: 0) + 2

    /** The bus [minutes] away. */
    fun read(minutes: Int): Cue? {
        // Nothing buzzes on the first reading: the user just asked.
        val opening = lastMinutes == null
        var cue: Cue? = null
        if (minutes <= SOON_MINUTES && !buzzedSoon) {
            buzzedSoon = true
            if (minutes > 0) cue = Cue.Soon
        }
        if (minutes == 0 && !arrived) {
            arrived = true
            cue = Cue.Now
        }
        lastMinutes = minutes
        return cue.takeIf { !opening }
    }
}
