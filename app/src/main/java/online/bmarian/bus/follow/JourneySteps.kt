package online.bmarian.bus.follow

sealed interface TripStep {
    class Board(val leg: JourneyLeg) : TripStep
    class Ride(val leg: JourneyLeg) : TripStep
    class WalkEnd(val destination: JourneyStop) : TripStep
}

// Walking to a stop counts as boarding there.
fun upcomingSteps(plan: Journey, stepType: String?, legIndex: Int): List<TripStep> {
    if (plan.legs.isEmpty()) return emptyList()
    val steps = plan.legs.flatMap { listOf(TripStep.Board(it), TripStep.Ride(it)) } + TripStep.WalkEnd(plan.destination)
    val leg = legIndex.coerceIn(0, plan.legs.lastIndex)
    val now = when (stepType) {
        StepType.RIDE -> leg * 2 + 1
        StepType.WALK_END -> steps.lastIndex
        StepType.ARRIVED -> return emptyList()
        else -> leg * 2
    }
    return steps.drop(now + 1)
}
