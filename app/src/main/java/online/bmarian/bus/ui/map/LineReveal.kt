package online.bmarian.bus.ui.map

import android.content.Context
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import online.bmarian.bus.logic.RoutePath
import online.bmarian.bus.ui.Bouncy
import online.bmarian.bus.ui.animatorDurationScale

private val POP = Bouncy.pop.vectorize(Float.VectorConverter)
private val ZERO = AnimationVector1D(0f)
private val ONE = AnimationVector1D(1f)
private val POP_NANOS = POP.getDurationNanos(ZERO, ONE, ZERO)

/**
 * Port of utils/lineReveal.ts, on the sweep's own progress: stops on the growing lines pop in as its front passes them,
 * their buses once they are drawn. Until [draw] is first called, they stay hidden.
 */
internal class LineReveal(private val context: Context) {
    private var scale = 1f
    private var paths: List<RoutePath> = emptyList()
    private var drawn: Float? = null
    private var growing = false
    private var ended: Long? = null
    private val shares = HashMap<Pair<Double, Double>, Float?>()
    private val pops = HashMap<Pair<Double, Double>, Long>()

    fun grow(lines: List<RoutePath>) {
        scale = animatorDurationScale(context)
        paths = lines
        drawn = null
        growing = true
        ended = null
        shares.clear()
        pops.clear()
    }

    fun draw(done: Float) {
        if (growing) drawn = done
    }

    fun end(now: Long) {
        if (!growing) return
        growing = false
        drawn = 1f
        ended = now
    }

    /** Bouncy.pop from nothing, on the animator duration scale the sweep runs on. */
    private fun popScale(start: Long, now: Long): Float {
        val played = ((now - start) / scale).toLong()
        return if (played >= POP_NANOS) 1f else POP.getValueFromNanos(played, ZERO, ONE, ZERO).value
    }

    private fun share(at: Pair<Double, Double>): Float? {
        if (at in shares) return shares[at]
        return paths.mapNotNull { path -> path.locate(at.first, at.second)?.let { (it.s / path.length).toFloat() } }.minOrNull().also { shares[at] = it }
    }

    fun stop(at: Pair<Double, Double>, now: Long): Float {
        if (paths.isEmpty()) return 1f
        val share = share(at) ?: return 1f
        val start = pops[at] ?: run {
            val done = drawn ?: return 0f
            if (share > done) return 0f
            now.also { pops[at] = it }
        }
        return popScale(start, now)
    }

    fun vehicle(now: Long): Float = if (growing) 0f else ended?.let { popScale(it, now) } ?: 1f

    /** Whether this frame still changes a scale; once nothing does, every marker is back to full size for good. */
    fun moving(now: Long): Boolean {
        val playing = (pops.values + listOfNotNull(ended)).any { (now - it) / scale < POP_NANOS }
        if (growing && drawn != null || playing) return true
        if (!growing) {
            paths = emptyList()
            pops.clear()
            ended = null
        }
        return false
    }
}
