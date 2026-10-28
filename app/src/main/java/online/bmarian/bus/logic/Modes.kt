package online.bmarian.bus.logic

/** Port of utils/modes.ts, biggest first. */
enum class Mode { METRO, RAIL, TRAM, TROLLEYBUS, BUS }

fun modeOf(routeType: Int): Mode = when (routeType) {
    1, 12 -> Mode.METRO
    2 -> Mode.RAIL
    0, 5 -> Mode.TRAM
    11 -> Mode.TROLLEYBUS
    else -> Mode.BUS
}

fun <T> byModeThenNumber(type: (T) -> Int, shortName: (T) -> String): Comparator<T> =
    compareBy<T> { modeOf(type(it)) }.thenComparator { a, b -> NaturalOrder.compare(shortName(a), shortName(b)) }

fun <T> groupByMode(lines: List<T>, type: (T) -> Int, shortName: (T) -> String): List<Pair<Mode, List<T>>> =
    lines.sortedWith(byModeThenNumber(type, shortName)).groupBy { modeOf(type(it)) }.toList()

/** Port of stopMatchRank. */
fun stopMatchRank(name: String, query: String): Int {
    val at = name.indexOf(query)
    if (at < 0) return -1
    if (at == 0) return 0
    val own = name.indexOf(" (")
    return if (own < 0 || at < own) 1 else 2
}
