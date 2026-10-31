package online.bmarian.bus.ui.map

import android.os.Handler
import android.os.SystemClock
import online.bmarian.bus.data.Fix

// Fixes arrive further apart than MapLibre animates.
private const val STEP_MS = 1_000L
// Twice JourneyService's TICK_MS.
private const val MAX_GAP_MS = 10_000L

internal fun fixBetween(a: Fix, b: Fix, t: Double): Fix {
    val from = a.bearing
    val to = b.bearing
    val bearing = if (from != null && to != null) (from + ((to - from + 540) % 360 - 180) * t.toFloat() + 360) % 360 else to
    return Fix(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t, b.accuracyMeters, bearing)
}

internal class MeGlide(private val handler: Handler, private val draw: (Fix?) -> Unit) {
    private var shown: Fix? = null
    private var seenAt = 0L
    private var running: Runnable? = null

    fun to(fix: Fix?, glide: Boolean) {
        running?.let(handler::removeCallbacks)
        val from = shown
        val now = SystemClock.elapsedRealtime()
        val gap = now - seenAt
        seenAt = now
        if (fix == null || from == null || !glide || gap > MAX_GAP_MS) return show(fix)
        val steps = (gap / STEP_MS).toInt().coerceAtLeast(1)
        var done = 0
        val step = object : Runnable {
            override fun run() {
                done++
                show(fixBetween(from, fix, done.toDouble() / steps))
                if (done < steps) handler.postDelayed(this, STEP_MS)
            }
        }
        running = step
        step.run()
    }

    private fun show(fix: Fix?) {
        shown = fix
        draw(fix)
    }
}
