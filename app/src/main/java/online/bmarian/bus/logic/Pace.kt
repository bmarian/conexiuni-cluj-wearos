package online.bmarian.bus.logic

import kotlin.math.roundToInt

// Share of its full length each kind of animation runs for.
const val LONG_PACE = 0.5
const val DELAY_PACE = 0.4
const val SPRING_PACE = 0.65f

fun paced(ms: Int): Int = (ms * LONG_PACE).roundToInt()

fun paced(ms: Long): Long = (ms * LONG_PACE).toLong()

fun delayed(ms: Long): Long = (ms * DELAY_PACE).toLong()

fun pacedStiffness(full: Float): Float = full / (SPRING_PACE * SPRING_PACE)
