package online.bmarian.bus.presentation.map

import android.animation.ValueAnimator
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import online.bmarian.bus.logic.RoutePath
import online.bmarian.bus.presentation.Bouncy

private val POP = Bouncy.pop.vectorize(Float.VectorConverter)
private val ZERO = AnimationVector1D(0f)
private val ONE = AnimationVector1D(1f)
private val POP_NANOS = POP.getDurationNanos(ZERO, ONE, ZERO)

private fun popScale(start: Long, now: Long): Float {
    val played = ((now - start) / ValueAnimator.getDurationScale()).toLong()
    return if (played >= POP_NANOS) 1f else POP.getValueFromNanos(played, ZERO, ONE, ZERO).value
}

// Port of LineReveal.
internal class LineReveal {
    private var paths: List<RoutePath> = emptyList()
    private var drawn: Float? = null
    private var growing = false
    private var ended: Long? = null
    private val shares = HashMap<Pair<Double, Double>, Float?>()
    private val pops = HashMap<Pair<Double, Double>, Long>()

    fun grow(lines: List<RoutePath>) {
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

    fun moving(now: Long): Boolean {
        val playing = (pops.values + listOfNotNull(ended)).any { (now - it) / ValueAnimator.getDurationScale() < POP_NANOS }
        if (growing && drawn != null || playing) return true
        if (!growing) {
            paths = emptyList()
            pops.clear()
            ended = null
        }
        return false
    }
}
